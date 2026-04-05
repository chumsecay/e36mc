package com.e36mc;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.text.MutableText;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;

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

            // Bấm để Copy Địa Chỉ (Tạm thời bỏ HoverEvent để tránh lỗi InstantiationError trên 1.21.1)
            MutableText domainText = Text.literal("§eĐịa chỉ của bạn: §f" + safeDomain + " ")
                .append(Text.literal("§b§n[Bấm để Copy Địa Chỉ]")
                .styled(style -> style
                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, safeDomain))
                ));
            client.player.sendMessage(domainText, false);

            // Bấm để Copy Token (Tạm thời bỏ HoverEvent để tránh lỗi InstantiationError trên 1.21.1)
            MutableText tokenText = Text.literal("§eBảo mật: ")
                .append(Text.literal("§c§n[Bấm để Copy Token]")
                .styled(style -> style
                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, safeToken))
                ));
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

            MutableText tokenText = Text.literal("§eGửi Token này cho Admin để được cấp quyền: ")
                .append(Text.literal("§b§n[Bấm vào đây để Copy Token]")
                .styled(style -> style
                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, safeToken))
                ));
            client.player.sendMessage(tokenText, false);
        });
    }

    /**
     * Displays a reconnection attempt message.
     */
    public static void displayReconnecting(int attempt) {
        sendChatMessage("§e[e36mc] Reconnecting... (attempt " + attempt + ")");
    }
}
