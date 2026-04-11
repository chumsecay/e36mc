package com.e36mc;

import javax.net.ssl.*;
import java.io.*;
import java.net.Socket;
import java.security.cert.X509Certificate;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the tunnel connection to the relay server.
 * 
 * Architecture:
 * - Control channel: TLS connection for auth, heartbeat, and new_conn notifications
 * - Data channels: One TLS connection per external player, bridged to localhost:lanPort
 * 
 * All network I/O runs on background threads to avoid blocking the game.
 */
public class TunnelClient {

    private final int lanPort;
    private final String relayHost;
    private final int relayPort;
    private final String token;
    private final boolean trustAllCerts;

    private SSLSocket controlSocket;
    private OutputStream controlOut;
    private InputStream controlIn;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "e36mc-tunnel");
        t.setDaemon(true);
        return t;
    });

    private ScheduledExecutorService heartbeatExecutor;
    private String assignedDomain;

    // Reconnect settings
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long RECONNECT_DELAY_MS = 5000;

    public TunnelClient(int lanPort, String relayHost, int relayPort,
                        String token, boolean trustAllCerts) {
        this.lanPort = lanPort;
        this.relayHost = relayHost;
        this.relayPort = relayPort;
        this.token = token;
        this.trustAllCerts = trustAllCerts;
    }

    /**
     * Start the tunnel connection on a background thread.
     */
    public void start() {
        if (running.getAndSet(true)) {
            E36mcMod.LOGGER.warn("[e36mc] Tunnel already running");
            return;
        }
        executor.submit(this::connect);
    }

    /**
     * Stop the tunnel and clean up all connections.
     */
    public void stop() {
        stop(false);
    }

    /**
     * Stop the tunnel. If silent=true, don't display "Tunnel closed" message.
     * Used when restarting the tunnel (close old → open new) to avoid confusing output.
     */
    public void stop(boolean silent) {
        if (!running.getAndSet(false)) return;

        E36mcMod.LOGGER.info("[e36mc] Stopping tunnel client (silent={})", silent);

        // Shutdown heartbeat
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
        }

        // Close control connection
        closeQuietly(controlSocket);

        // Shutdown all data channel threads
        executor.shutdownNow();

        if (!silent) {
            LanEventHandler.displayTunnelClosed("Tunnel stopped");
        }
    }

    /**
     * Connect to the relay server, authenticate, and start listening for commands.
     */
    private void connect() {
        for (int attempt = 1; attempt <= MAX_RECONNECT_ATTEMPTS && running.get(); attempt++) {
            try {
                E36mcMod.LOGGER.info("[e36mc] Connecting to relay {}:{} (attempt {})", relayHost, relayPort, attempt);

                if (attempt > 1) {
                    LanEventHandler.displayReconnecting(attempt);
                    Thread.sleep(RECONNECT_DELAY_MS);
                }

                // Create TLS connection
                SSLSocketFactory factory = createSSLSocketFactory();
                controlSocket = (SSLSocket) factory.createSocket(relayHost, relayPort);
                configureSocket(controlSocket);
                controlSocket.startHandshake();
                controlOut = controlSocket.getOutputStream();
                controlIn = controlSocket.getInputStream();

                E36mcMod.LOGGER.info("[e36mc] TLS connected to relay with SNI");

                // Send AUTH
                TunnelProtocol.sendAuth(controlOut, token);
                E36mcMod.LOGGER.info("[e36mc] Auth sent with token");

                // Read response
                TunnelProtocol.Envelope response = TunnelProtocol.readMessage(controlIn);

                if (TunnelProtocol.MSG_AUTH_OK.equals(response.type)) {
                    if (response.payload.has("domain") && !response.payload.get("domain").isJsonNull()) {
                        assignedDomain = response.payload.get("domain").getAsString();
                    } else {
                        assignedDomain = "unknown-domain";
                    }
                    E36mcMod.LOGGER.info("[e36mc] Auth OK! Domain: {}", assignedDomain);
                    LanEventHandler.displayTunnelAddress(assignedDomain);

                    // Start heartbeat
                    startHeartbeat();

                    // Listen for commands (this blocks until disconnect)
                    listenForCommands();

                    // If we get here, connection was lost
                    E36mcMod.LOGGER.warn("[e36mc] Control channel disconnected");

                } else if (TunnelProtocol.MSG_AUTH_ERR.equals(response.type)) {
                    String reason = response.payload.get("reason").getAsString();
                    E36mcMod.LOGGER.error("[e36mc] Auth failed: {}", reason);
                    // Pass the reason to the event handler so it can show MAINTENANCE or NOT_WHITELISTED
                    LanEventHandler.displayWhitelistInfo(token, reason);
                    running.set(false);
                    return;
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.net.ConnectException e) {
                E36mcMod.LOGGER.error("[e36mc] Connection refused: {}:{}", relayHost, relayPort);
                LanEventHandler.displayConnectionError("Connection Refused (Port 25500 closed or unreachable)");
            } catch (javax.net.ssl.SSLHandshakeException e) {
                E36mcMod.LOGGER.error("[e36mc] TLS Handshake failed: {}", e.getMessage());
                LanEventHandler.displayConnectionError("TLS Error (Check server certificate/domain)");
            } catch (Exception e) {
                E36mcMod.LOGGER.error("[e36mc] Connection error ({}): {}", e.getClass().getSimpleName(), e.getMessage());
                LanEventHandler.displayConnectionError(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        if (running.get()) {
            E36mcMod.LOGGER.error("[e36mc] Max reconnect attempts reached");
            LanEventHandler.displayTunnelClosed("Max reconnect attempts reached");
            running.set(false);
        }
    }

    /**
     * Listen for messages on the control channel (NEW_CONN, PING).
     */
    private void listenForCommands() {
        try {
            while (running.get()) {
                TunnelProtocol.Envelope env = TunnelProtocol.readMessage(controlIn);

                switch (env.type) {
                    case TunnelProtocol.MSG_NEW_CONN:
                        String connId = env.payload.get("conn_id").getAsString();
                        E36mcMod.LOGGER.info("[e36mc] New player connection: {}", connId);
                        // Handle in a new thread
                        executor.submit(() -> handleNewConnection(connId));
                        break;

                    case TunnelProtocol.MSG_PING:
                        TunnelProtocol.sendPong(controlOut);
                        break;

                    default:
                        E36mcMod.LOGGER.warn("[e36mc] Unexpected message: {}", env.type);
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                E36mcMod.LOGGER.error("[e36mc] Control channel error: {}", e.getMessage());
            }
        }
    }

    /**
     * Handle a NEW_CONN from the relay: open a data channel and bridge it
     * to the local LAN Minecraft server.
     */
    private void handleNewConnection(String connId) {
        SSLSocket dataSocket = null;
        Socket localSocket = null;

        try {
            // 1. Open new TLS connection to relay (data channel)
            SSLSocketFactory factory = createSSLSocketFactory();
            dataSocket = (SSLSocket) factory.createSocket(relayHost, relayPort);
            configureSocket(dataSocket);
            dataSocket.startHandshake();

            // 2. Send CONN_READY
            OutputStream dataOut = dataSocket.getOutputStream();
            TunnelProtocol.sendConnReady(dataOut, connId);

            E36mcMod.LOGGER.info("[e36mc] Data channel established for {}", connId);

            // 3. Connect to local Minecraft LAN server
            localSocket = new Socket("127.0.0.1", lanPort);

            E36mcMod.LOGGER.info("[e36mc] Bridging conn {} ↔ localhost:{}", connId, lanPort);

            // 4. Bridge the two sockets bidirectionally
            bridgeSockets(dataSocket, localSocket, connId);

        } catch (Exception e) {
            E36mcMod.LOGGER.error("[e36mc] Failed to handle conn {}: {}", connId, e.getMessage());
            closeQuietly(dataSocket);
            closeQuietly(localSocket);
        }
    }

    /**
     * Bridge two sockets: forward data bidirectionally until one side closes.
     */
    private void bridgeSockets(Socket socket1, Socket socket2, String connId) {
        try {
            InputStream in1 = socket1.getInputStream();
            OutputStream out1 = socket1.getOutputStream();
            InputStream in2 = socket2.getInputStream();
            OutputStream out2 = socket2.getOutputStream();

            CountDownLatch latch = new CountDownLatch(1);

            // socket1 → socket2
            Thread t1 = new Thread(() -> {
                try {
                    copyStream(in1, out2);
                } catch (IOException ignored) {
                } finally {
                    latch.countDown();
                }
            }, "e36mc-bridge-" + connId + "-relay→local");
            t1.setDaemon(true);

            // socket2 → socket1
            Thread t2 = new Thread(() -> {
                try {
                    copyStream(in2, out1);
                } catch (IOException ignored) {
                } finally {
                    latch.countDown();
                }
            }, "e36mc-bridge-" + connId + "-local→relay");
            t2.setDaemon(true);

            t1.start();
            t2.start();

            // Wait for one direction to finish
            latch.await();

            E36mcMod.LOGGER.debug("[e36mc] Bridge closed for conn {}", connId);

        } catch (Exception e) {
            E36mcMod.LOGGER.error("[e36mc] Bridge error for conn {}: {}", connId, e.getMessage());
        } finally {
            closeQuietly(socket1);
            closeQuietly(socket2);
        }
    }

    /**
     * Copy bytes from input to output until EOF or error.
     */
    private void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            out.flush();
        }
    }

    /**
     * Start periodic heartbeat on the control channel.
     */
    private void startHeartbeat() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "e36mc-heartbeat");
            t.setDaemon(true);
            return t;
        });

        heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (!running.get()) return;
            try {
                // We respond to PING from server with PONG (handled in listenForCommands).
                // We can also send our own PING to detect dead connections.
                // For now, the server sends PINGs and we respond.
            } catch (Exception e) {
                E36mcMod.LOGGER.error("[e36mc] Heartbeat error: {}", e.getMessage());
            }
        }, 15, 15, TimeUnit.SECONDS);
    }

    /**
     * Create an SSLSocketFactory, optionally trusting all certs for development.
     * Also configures SNI for the socket if possible.
     */
    private SSLSocketFactory createSSLSocketFactory() throws Exception {
        SSLSocketFactory factory;
        if (trustAllCerts) {
            // WARNING: Only for development! Trusts all certificates.
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            }, new java.security.SecureRandom());
            factory = ctx.getSocketFactory();
        } else {
            // Use default trust store (includes Let's Encrypt)
            factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
        return factory;
    }

    private void configureSocket(SSLSocket socket) {
        try {
            // Enable SNI
            SSLParameters params = socket.getSSLParameters();
            params.setServerNames(java.util.Collections.singletonList(new SNIHostName(relayHost)));
            socket.setSSLParameters(params);
        } catch (Exception e) {
            E36mcMod.LOGGER.warn("[e36mc] Failed to set SNI: {}", e.getMessage());
        }
    }

    // --- Utility ---
    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }
}
