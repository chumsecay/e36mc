package com.e36mc;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

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
    public static void displayTunnelAddress(String domain) {
        sendChatMessage("§a§l[e36mc] §r§aTunnel active!");
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            client.execute(() -> {
                if (client.player != null) {
                    Text prefix = Text.literal("§e§lPublic address: §f");
                    Text address = Text.literal(domain)
                            .styled(style -> style
                                    .withClickEvent(new net.minecraft.text.ClickEvent(net.minecraft.text.ClickEvent.Action.COPY_TO_CLIPBOARD, domain))
                                    .withHoverEvent(new net.minecraft.text.HoverEvent(net.minecraft.text.HoverEvent.Action.SHOW_TEXT, Text.literal("Click to copy to clipboard")))
                                    .withUnderline(true));
                    client.player.sendMessage(prefix.copy().append(address), false);
                }
            });
        }
        sendChatMessage("§7Share this address with friends to let them join.");
    }

    /**
     * Displays a tunnel disconnection message.
     */
    public static void displayTunnelClosed(String reason) {
        sendChatMessage("§c[e36mc] Tunnel closed: " + reason);
    }

    /**
     * Displays a reconnection attempt message.
     */
    public static void displayReconnecting(int attempt) {
        sendChatMessage("§e[e36mc] Reconnecting... (attempt " + attempt + ")");
    }
}
