package com.e36mc;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.text.MutableText;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import java.lang.reflect.Constructor;

/**
 * Handles LAN open/close events and communicates status to the player via chat.
 */
public class LanEventHandler {

    /**
     * Sends a message to the player's chat.
     * Must be called from the render thread.
     */
    public static void sendChatMessage(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            // Schedule on the main thread to avoid threading issues
            client.execute(() -> {
                if (client.player != null) {
                    client.player.sendMessage(Text.literal(message), false);
                }
            });
        }
    }

    /**
     * Displays the public tunnel address to the player.
     */
    public static void displayTunnelAddress(String domain, String token) {
        final String safeDomain = (domain != null) ? domain : "Unknown";
        final String safeToken = (token != null) ? token : "Unknown";

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;

        client.execute(() -> {
            if (client.player == null) return;

            // SUPER REFLECTION for 1.21.1 Interfaces/Records
            ClickEvent domainClick = createClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, safeDomain);
            ClickEvent tokenClick = createClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, safeToken);

            client.player.sendMessage(Text.literal("§a§l[e36mc] §r§aHầm Server Đã Mở!"), false);
            
            // Mask the token for visual display (e.g. e36mc-1a2b... -> e36mc-••••••••)
            String maskedToken = "Unknown";
            if (!"Unknown".equals(safeToken)) {
                int dashIndex = safeToken.indexOf('-');
                if (dashIndex != -1 && dashIndex + 1 < safeToken.length()) {
                    maskedToken = safeToken.substring(0, dashIndex + 1) + "••••••••";
                } else {
                    maskedToken = "••••••••••••";
                }
            }

            MutableText domainText = Text.literal("§eĐịa chỉ: §f" + safeDomain + " ")
                .append(Text.literal("§b§n[Bấm vào đây để Copy]")
                .styled(style -> style
                    .withClickEvent(domainClick)
                    .withHoverEvent(createHoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("§bNhấn để copy địa chỉ")))));
            client.player.sendMessage(domainText, false);

            MutableText tokenText = Text.literal("§eToken: §6" + maskedToken + " ")
                .append(Text.literal("§c§n[Bấm vào đây để Copy]")
                .styled(style -> style
                    .withClickEvent(tokenClick)
                    .withHoverEvent(createHoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("§cNhấn để copy Token")))));
            client.player.sendMessage(tokenText, false);
        });
    }

    /**
     * Displays a tunnel disconnection message.
     */
    public static void displayTunnelClosed(String reason) {
        sendChatMessage("§c[e36mc] Tunnel closed: " + reason);
    }

    /**
     * Displays whitelist instructions and clickable user identity.
     */
    public static void displayWhitelistInfo(String token, String reason) {
        final String safeToken = (token != null) ? token : "Unknown";
        final String safeReason = (reason != null) ? reason : "Unknown";

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;

        client.execute(() -> {
            if (client.player == null) return;

            if ("MAINTENANCE".equals(safeReason)) {
                client.player.sendMessage(Text.literal("§c§l[e36mc] §r§cKết nối thất bại. Máy chủ đang Bảo Trì!"), false);
            } else {
                client.player.sendMessage(Text.literal("§c§l[e36mc] §r§cKết nối thất bại. Máy chủ đang là Khép Kín (Private)."), false);
            }

            ClickEvent tokenClick = createClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, safeToken);

            MutableText tokenText = Text.literal("§eGửi Token này cho Admin để được cấp quyền: ")
                .append(Text.literal("§b§n[Bấm vào đây để Copy Token]")
                .styled(style -> style
                    .withClickEvent(tokenClick)
                    .withHoverEvent(createHoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("§bNhấn để copy Token")))));
            client.player.sendMessage(tokenText, false);
        });
    }

    /**
     * Displays a connection error with specific details.
     */
    public static void displayConnectionError(String detail) {
        sendChatMessage("§c§l[e36mc] §r§cLỗi kết nối: " + detail);
    }

    /**
     * Displays a reconnection attempt message.
     */
    public static void displayReconnecting(int attempt) {
        sendChatMessage("§e[e36mc] Đang kết nối lại... (Lần " + attempt + ")");
    }

    /**
     * Super Reflection to find and instantiate ClickEvent.
     * In 1.21.1, ClickEvent is an interface, and the actual implementations are inner records.
     */
    private static ClickEvent createClickEvent(ClickEvent.Action action, String value) {
        try {
            // First, try the Action-based inner class (Standard 1.21.1 Yarn)
            String actionName = action.name();
            // Convert COPY_TO_CLIPBOARD -> CopyToClipboard
            StringBuilder sb = new StringBuilder();
            for (String part : actionName.split("_")) {
                if (part.length() > 0) {
                    sb.append(part.substring(0, 1).toUpperCase());
                    sb.append(part.substring(1).toLowerCase());
                }
            }
            String camelName = sb.toString();

            // Scan inner classes
            for (Class<?> inner : ClickEvent.class.getDeclaredClasses()) {
                if (inner.getSimpleName().equals(camelName) || inner.getSimpleName().contains(camelName)) {
                    for (Constructor<?> constructor : inner.getDeclaredConstructors()) {
                        if (constructor.getParameterCount() == 1 && constructor.getParameterTypes()[0] == String.class) {
                            constructor.setAccessible(true);
                            // Some versions might need new ClickEvent(InnerRecord)
                            Object recordInstance = constructor.newInstance(value);
                            
                            // Check if ClickEvent has a constructor taking this record
                            for (Constructor<?> outerConstructor : ClickEvent.class.getDeclaredConstructors()) {
                                if (outerConstructor.getParameterCount() == 1 && outerConstructor.getParameterTypes()[0].isAssignableFrom(inner)) {
                                    outerConstructor.setAccessible(true);
                                    return (ClickEvent) outerConstructor.newInstance(recordInstance);
                                }
                            }
                            
                            // If InnerRecord implements ClickEvent directly
                            if (ClickEvent.class.isAssignableFrom(inner)) {
                                return (ClickEvent) recordInstance;
                            }
                        }
                    }
                }
            }

            // Fallback: Just return null rather than crashing if we can't find it
            E36mcMod.LOGGER.warn("[e36mc] Could not find ClickEvent implementation for: {}", actionName);
        } catch (Exception e) {
            E36mcMod.LOGGER.error("[e36mc] Super Reflection ClickEvent failure: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Super Reflection to find and instantiate HoverEvent.
     */
    private static HoverEvent createHoverEvent(HoverEvent.Action<?> action, Object value) {
        try {
            // In 1.21.1, HoverEvent is often a record taking (Action, Object/Content)
            for (Constructor<?> constructor : HoverEvent.class.getDeclaredConstructors()) {
                Class<?>[] params = constructor.getParameterTypes();
                if (params.length == 2 && params[0].isAssignableFrom(HoverEvent.Action.class)) {
                    constructor.setAccessible(true);
                    return (HoverEvent) constructor.newInstance(action, value);
                }
            }
        } catch (Exception e) {
            E36mcMod.LOGGER.error("[e36mc] Super Reflection HoverEvent failure: {}", e.getMessage());
        }
        return null;
    }
}
