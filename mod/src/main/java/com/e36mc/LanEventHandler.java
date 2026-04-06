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

            client.player.sendMessage(Text.literal("§a§l[e36mc] §r§aHầm Server Đã Mở!"), false);
            
            // Use reflection for ClickEvent to avoid InstantiationError on 1.21.1+ (where it is a Record)
            ClickEvent domainClick = null;
            ClickEvent tokenClick = null;
            try {
                var constructor = ClickEvent.class.getConstructor(ClickEvent.Action.class, String.class);
                domainClick = constructor.newInstance(ClickEvent.Action.COPY_TO_CLIPBOARD, safeDomain);
                tokenClick = constructor.newInstance(ClickEvent.Action.COPY_TO_CLIPBOARD, safeToken);
            } catch (Exception e) {
                E36mcMod.LOGGER.error("[e36mc] Failed to create ClickEvent via reflection: {}", e.getMessage());
            }

            final ClickEvent finalDomainClick = domainClick;
            final ClickEvent finalTokenClick = tokenClick;

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
                .styled(style -> finalDomainClick != null ? style.withClickEvent(finalDomainClick) : style));
            client.player.sendMessage(domainText, false);

            MutableText tokenText = Text.literal("§eToken: §6" + maskedToken + " ")
                .append(Text.literal("§c§n[Bấm vào đây để Copy]")
                .styled(style -> finalTokenClick != null ? style.withClickEvent(finalTokenClick) : style));
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

            ClickEvent tokenClick = null;
            try {
                var constructor = ClickEvent.class.getConstructor(ClickEvent.Action.class, String.class);
                tokenClick = constructor.newInstance(ClickEvent.Action.COPY_TO_CLIPBOARD, safeToken);
            } catch (Exception e) {
                E36mcMod.LOGGER.error("[e36mc] Failed to create ClickEvent via reflection: {}", e.getMessage());
            }

            final ClickEvent finalTokenClick = tokenClick;

            MutableText tokenText = Text.literal("§eGửi Token này cho Admin để được cấp quyền: ")
                .append(Text.literal("§b§n[Bấm vào đây để Copy Token]")
                .styled(style -> finalTokenClick != null ? style.withClickEvent(finalTokenClick) : style));
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
}
