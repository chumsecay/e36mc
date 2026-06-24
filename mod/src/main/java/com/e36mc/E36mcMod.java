package com.e36mc;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import com.mojang.brigadier.Command;
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
    public static String relayHost = "mc.example.com";
    public static int relayPort = 25500;
    public static String token = "";
    public static boolean trustAllCerts = false; // Set to true only for development/self-signed certs if needed.

    // Active tunnel client
    private static TunnelClient activeTunnel = null;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[e36mc] Initializing e36mc tunnel mod v6.0 (Restore-Buttons)");

        // Load config
        loadConfig();

        // Register cleanup on client stop
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            stopTunnel();
        });

        // Register /e36mc slash commands
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("e36mc")
                .then(ClientCommandManager.literal("token")
                    .executes(context -> {
                        if (token != null && !token.isEmpty()) {
                            LanEventHandler.displayToken(token);
                        } else {
                            LanEventHandler.sendChatMessage("§c[e36mc] Chưa có token. Hãy mở file config/e36mc.json");
                        }
                        return Command.SINGLE_SUCCESS;
                    })
                )
                .then(ClientCommandManager.literal("status")
                    .executes(context -> {
                        if (activeTunnel != null) {
                            LanEventHandler.sendChatMessage("§a[e36mc] Tunnel đang hoạt động.");
                        } else {
                            LanEventHandler.sendChatMessage("§e[e36mc] Không có tunnel nào đang chạy.");
                        }
                        return Command.SINGLE_SUCCESS;
                    })
                )
            );
        });

        LOGGER.info("[e36mc] e36mc initialized. Relay: {}:{}", relayHost, relayPort);
    }

    /**
     * Called by the mixin when LAN server is opened.
     */
    public static void onLanOpened(int lanPort) {
        LOGGER.info("[e36mc] LAN opened on port {}", lanPort);

        if (token.isEmpty()) {
            LOGGER.error("[e36mc] No token configured! Edit config/e36mc.json");
            LanEventHandler.sendChatMessage("§c[e36mc] Error: No token configured. Edit config/e36mc.json");
            return;
        }



        // Stop any existing tunnel (silent = don't show "Tunnel closed" message)
        stopTunnel(true);

        // Start new tunnel
        activeTunnel = new TunnelClient(lanPort, relayHost, relayPort, token, trustAllCerts);
        activeTunnel.start();
    }

    /**
     * Called when LAN server is stopped or client is shutting down.
     */
    public static void stopTunnel() {
        stopTunnel(false);
    }

    /**
     * Stop active tunnel. If silent=true, no chat message is shown.
     */
    public static void stopTunnel(boolean silent) {
        if (activeTunnel != null) {
            LOGGER.info("[e36mc] Stopping tunnel (silent={})", silent);
            activeTunnel.stop(silent);
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
            } else { 
                config.addProperty("relay_host", "mc.example.com"); 
                saveNeeded = true; 
            }

            // Relay Port
            if (config.has("relay_port") && !config.get("relay_port").isJsonNull()) {
                relayPort = config.get("relay_port").getAsInt();
            } else { 
                config.addProperty("relay_port", 25500); 
                saveNeeded = true; 
            }

            // Removing user_id legacy support by omitting it here
            // If they had user_id, it will just be ignored in memory

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
            } else { 
                trustAllCerts = false;
                config.addProperty("trust_all_certs", false); 
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
