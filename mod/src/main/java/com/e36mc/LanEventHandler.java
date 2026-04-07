package com.e36mc;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.text.MutableText;
import net.minecraft.text.ClickEvent;
// import net.minecraft.text.HoverEvent;

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

            // Robust ClickEvent creation using reflection to handle Record vs Class mismatch
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
                    .withHoverEvent(createHoverEvent(net.minecraft.text.HoverEvent.Action.SHOW_TEXT, Text.literal("§bNhấn để copy địa chỉ")))));
            client.player.sendMessage(domainText, false);

            MutableText tokenText = Text.literal("§eToken: §6" + maskedToken + " ")
                .append(Text.literal("§c§n[Bấm vào đây để Copy]")
                .styled(style -> style
                    .withClickEvent(tokenClick)
                    .withHoverEvent(createHoverEvent(net.minecraft.text.HoverEvent.Action.SHOW_TEXT, Text.literal("§cNhấn để copy Token")))));
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

            // Revert to standard constructor and add Hover
            ClickEvent tokenClick = createClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, safeToken);

            MutableText tokenText = Text.literal("§eGửi Token này cho Admin để được cấp quyền: ")
                .append(Text.literal("§b§n[Bấm vào đây để Copy Token]")
                .styled(style -> style
                    .withClickEvent(tokenClick)
                    .withHoverEvent(createHoverEvent(net.minecraft.text.HoverEvent.Action.SHOW_TEXT, Text.literal("§bNhấn để copy Token")))));
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
     * More robust helper to create ClickEvent via reflection to bypass Record/Class binary mismatch.
     */
    private static ClickEvent createClickEvent(ClickEvent.Action action, String value) {
        try {
            // Find any constructor that matches (ClickEvent.Action, String)
            for (java.lang.reflect.Constructor<?> constructor : ClickEvent.class.getDeclaredConstructors()) {
                Class<?>[] params = constructor.getParameterTypes();
                if (params.length == 2 && 
                    (params[0].isAssignableFrom(ClickEvent.Action.class) || ClickEvent.Action.class.isAssignableFrom(params[0])) && 
                    params[1] == String.class) {
                    
                    constructor.setAccessible(true);
                    return (ClickEvent) constructor.newInstance(action, value);
                }
            }
        } catch (Exception e) {
            E36mcMod.LOGGER.error("[e36mc] Critical failure creating ClickEvent via reflection: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Helper to create HoverEvent via reflection to bypass Record/Class binary mismatch.
     */
    private static net.minecraft.text.HoverEvent createHoverEvent(net.minecraft.text.HoverEvent.Action<?> action, Object value) {
        try {
            // Find constructor with (HoverEvent.Action, Object) signature
            for (java.lang.reflect.Constructor<?> constructor : net.minecraft.text.HoverEvent.class.getDeclaredConstructors()) {
                Class<?>[] params = constructor.getParameterTypes();
                if (params.length == 2 && 
                    (params[0].isAssignableFrom(net.minecraft.text.HoverEvent.Action.class) || net.minecraft.text.HoverEvent.Action.class.isAssignableFrom(params[0]))) {
                    
                    constructor.setAccessible(true);
                    return (net.minecraft.text.HoverEvent) constructor.newInstance(action, value);
                }
            }
        } catch (Exception e) {
            E36mcMod.LOGGER.error("[e36mc] Critical failure creating HoverEvent via reflection: {}", e.getMessage());
        }
        return null;
    }
}
