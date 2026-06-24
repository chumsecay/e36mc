package com.e36mc;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Implements the e36mc wire protocol: length-prefixed JSON messages.
 * 
 * Wire format:
 *   [4 bytes: uint32 big-endian message length][JSON payload]
 * 
 * JSON envelope:
 *   {"type": "msg_type", "payload": {...}}
 */
public class TunnelProtocol {

    private static final int MAX_MESSAGE_SIZE = 65536; // 64 KB
    private static final Gson GSON = new Gson();

    // --- Message types ---
    public static final String MSG_AUTH = "auth";
    public static final String MSG_AUTH_OK = "auth_ok";
    public static final String MSG_AUTH_ERR = "auth_err";
    public static final String MSG_NEW_CONN = "new_conn";
    public static final String MSG_CONN_READY = "conn_ready";
    public static final String MSG_PING = "ping";
    public static final String MSG_PONG = "pong";

    // --- Envelope ---
    public static class Envelope {
        public String type;
        public JsonObject payload;

        public Envelope() {}

        public Envelope(String type, JsonObject payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    // --- Write a message ---
    public static void writeMessage(OutputStream out, String type, JsonObject payload) throws IOException {
        Envelope env = new Envelope(type, payload);
        byte[] data = GSON.toJson(env).getBytes(StandardCharsets.UTF_8);

        if (data.length > MAX_MESSAGE_SIZE) {
            throw new IOException("Message too large: " + data.length + " bytes");
        }

        // Write 4-byte big-endian length prefix
        byte[] lenBuf = ByteBuffer.allocate(4).putInt(data.length).array();

        synchronized (out) {
            out.write(lenBuf);
            out.write(data);
            out.flush();
        }
    }

    // --- Read a message ---
    public static Envelope readMessage(InputStream in) throws IOException {
        // Read 4-byte length prefix
        byte[] lenBuf = new byte[4];
        readFully(in, lenBuf);
        int msgLen = ByteBuffer.wrap(lenBuf).getInt();

        if (msgLen < 0 || msgLen > MAX_MESSAGE_SIZE) {
            throw new IOException("Invalid message length: " + msgLen);
        }

        // Read JSON body
        byte[] data = new byte[msgLen];
        readFully(in, data);

        String json = new String(data, StandardCharsets.UTF_8);
        return GSON.fromJson(json, Envelope.class);
    }

    // --- Convenience methods ---

    public static void sendAuth(OutputStream out, String token) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("token", token);
        writeMessage(out, MSG_AUTH, payload);
    }

    public static void sendConnReady(OutputStream out, String connId, String token) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("conn_id", connId);
        payload.addProperty("token", token);
        writeMessage(out, MSG_CONN_READY, payload);
    }

    public static void sendPong(OutputStream out) throws IOException {
        writeMessage(out, MSG_PONG, null);
    }

    public static void sendPing(OutputStream out) throws IOException {
        writeMessage(out, MSG_PING, null);
    }

    // --- Helper ---
    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int offset = 0;
        while (offset < buf.length) {
            int read = in.read(buf, offset, buf.length - offset);
            if (read < 0) {
                throw new EOFException("Unexpected end of stream");
            }
            offset += read;
        }
    }
}
