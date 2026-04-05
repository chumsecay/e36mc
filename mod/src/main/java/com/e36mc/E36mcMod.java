package com.e36mc;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * e36mc - Self-hosted Minecraft tunnel mod.
 * 
 * When the user opens their world to LAN, this mod connects to your
 * relay server and creates a public tunnel so external players can join
 * via a subdomain like "username.mc.yourdomain.com".
 */
public class E36mcMod implements ClientModInitializer {
    public static final String MOD_ID = "e36mc";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Config values
    public static String relayHost = "mc.sigmaskibidi.click";
    public static int relayPort = 25500;
    public static String userId = "";
    public static String token = "";
    public static boolean trustAllCerts = true; // Set to true to allow self-signed certs

    // Active tunnel client
    private static TunnelClient activeTunnel = null;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[e36mc] Initializing e36mc tunnel mod");

        // Load config
        loadConfig();

        // Register cleanup on client stop
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            stopTunnel();
        });

        LOGGER.info("[e36mc] e36mc initialized. Relay: {}:{}, User: {}", relayHost, relayPort, userId);
    }

    /**
     * Called by the mixin when LAN server is opened.
     */
    public static void onLanOpened(int lanPort) {
        LOGGER.info("[e36mc] LAN opened on port {}", lanPort);

        if (userId.isEmpty() || token.isEmpty()) {
            LOGGER.error("[e36mc] No user_id or token configured! Edit config/e36mc.json");
            LanEventHandler.sendChatMessage("§c[e36mc] Error: No user_id or token configured. Edit config/e36mc.json");
            return;
        }

        // TEST
        LanEventHandler.displayWhitelistInfo("test", "test");


        // Stop any existing tunnel
        stopTunnel();

        // Start new tunnel
        activeTunnel = new TunnelClient(lanPort, relayHost, relayPort, userId, token, trustAllCerts);
        activeTunnel.start();
    }

    /**
     * Called when LAN server is stopped or client is shutting down.
     */
    public static void stopTunnel() {
        if (activeTunnel != null) {
            LOGGER.info("[e36mc] Stopping tunnel");
            activeTunnel.stop();
            activeTunnel = null;
        }
    }

    /**
     * Loads configuration from config/e36mc.json.
     * Auto-generates userId and token if missing.
     */
    private void loadConfig() {
        Path configDir = Path.of("config");
        Path configFile = configDir.resolve("e36mc.json");

        boolean saveNeeded = false;
        JsonObject config = new JsonObject();

        try {
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }

            if (Files.exists(configFile)) {
                try {
                    String json = Files.readString(configFile);
                    config = new Gson().fromJson(json, JsonObject.class);
                } catch (Exception e) {
                    LOGGER.error("[e36mc] Failed to parse config JSON, resetting to defaults.");
                }
            }
            if (config == null) {
                config = new JsonObject();
                saveNeeded = true;
            }

            // Relay Host
            if (config.has("relay_host") && !config.get("relay_host").isJsonNull()) {
                relayHost = config.get("relay_host").getAsString();
                if ("localhost".equals(relayHost) || "127.0.0.1".equals(relayHost)) {
                    relayHost = "mc.sigmaskibidi.click";
                    config.addProperty("relay_host", relayHost);
                    saveNeeded = true;
                }
            } else { 
                config.addProperty("relay_host", "mc.sigmaskibidi.click"); 
                saveNeeded = true; 
            }

            // Relay Port
            if (config.has("relay_port") && !config.get("relay_port").isJsonNull()) {
                relayPort = config.get("relay_port").getAsInt();
            } else { 
                config.addProperty("relay_port", 25500); 
                saveNeeded = true; 
            }

            // User ID
            if (config.has("user_id") && !config.get("user_id").isJsonNull()) {
                userId = config.get("user_id").getAsString();
            }
            if (userId == null || userId.isEmpty()) {
                userId = "user-" + java.util.UUID.randomUUID().toString().substring(0, 8);
                config.addProperty("user_id", userId);
                saveNeeded = true;
            }

            // Token
            if (config.has("token") && !config.get("token").isJsonNull()) {
                token = config.get("token").getAsString();
            }
            if (token == null || token.isEmpty()) {
                token = "e36mc-" + java.util.UUID.randomUUID().toString().replace("-", "");
                config.addProperty("token", token);
                saveNeeded = true;
            }

            // Trust All Certs
            if (config.has("trust_all_certs") && !config.get("trust_all_certs").isJsonNull()) {
                trustAllCerts = config.get("trust_all_certs").getAsBoolean();
                if (!trustAllCerts) {
                    trustAllCerts = true;
                    config.addProperty("trust_all_certs", trustAllCerts);
                    saveNeeded = true;
                }
            } else { 
                trustAllCerts = true;
                config.addProperty("trust_all_certs", true); 
                saveNeeded = true; 
            }

            if (saveNeeded) {
                Files.writeString(configFile, new Gson().toJson(config));
                LOGGER.info("[e36mc] Updated config at {} with auto-generated values", configFile);
            } else {
                LOGGER.info("[e36mc] Config loaded from {}", configFile);
            }
        } catch (IOException e) {
            LOGGER.error("[e36mc] Failed to load/save config: {}", e.getMessage());
        }
    }
}
