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
     * Displays the public tunnel address to the player (without token).
     * Token is now retrieved via /e36mc token command.
     */
    public static void displayTunnelAddress(String domain) {
        final String safeDomain = (domain != null) ? domain : "Unknown";

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;

        client.execute(() -> {
            if (client.player == null) return;

            ClickEvent domainClick = createClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, safeDomain);

            client.player.sendMessage(Text.literal("§a§l[e36mc] §r§aHầm Server Đã Mở!"), false);

            MutableText domainText = Text.literal("§eĐịa chỉ: §f" + safeDomain + " ")
                .append(Text.literal("§b§n[Bấm vào đây để Copy]")
                .styled(style -> style
                    .withClickEvent(domainClick)
                    .withHoverEvent(createHoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("§bNhấn để copy địa chỉ")))));
            client.player.sendMessage(domainText, false);

            // Hint about /e36mc token
            client.player.sendMessage(Text.literal("§7Gõ §f/e36mc token §7để lấy token kết nối."), false);
        });
    }

    /**
     * Displays the token with a copy button (called by /e36mc token command).
     */
    public static void displayToken(String token) {
        final String safeToken = (token != null) ? token : "Unknown";

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;

        client.execute(() -> {
            if (client.player == null) return;

            ClickEvent tokenClick = createClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, safeToken);

            // Mask the token for visual display
            String maskedToken = "Unknown";
            if (!"Unknown".equals(safeToken)) {
                int dashIndex = safeToken.indexOf('-');
                if (dashIndex != -1 && dashIndex + 1 < safeToken.length()) {
                    maskedToken = safeToken.substring(0, dashIndex + 1) + "••••••••";
                } else {
                    maskedToken = "••••••••••••";
                }
            }

            client.player.sendMessage(Text.literal("§e§l[e36mc] §r§eToken của bạn:"), false);

            MutableText tokenText = Text.literal("§6" + maskedToken + " ")
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
     * Creates a ClickEvent compatible across MC versions.
     * Strategy: try direct constructor, then brute-force all inner classes.
     */
    private static ClickEvent createClickEvent(ClickEvent.Action action, String value) {

        // Strategy 1: Direct constructor (1.21.1 where ClickEvent is a record/class)
        try {
            Constructor<ClickEvent> ctor = ClickEvent.class.getDeclaredConstructor(ClickEvent.Action.class, String.class);
            ctor.setAccessible(true);
            return ctor.newInstance(action, value);
        } catch (Throwable ignored) {}

        // Strategy 2: Brute-force all inner classes that implement ClickEvent
        // In 1.21.11+ ClickEvent may be an interface; inner records implement it.
        // Runtime uses intermediary names so we can't match by name — try every inner class.
        for (Class<?> inner : ClickEvent.class.getDeclaredClasses()) {
            if (!ClickEvent.class.isAssignableFrom(inner)) continue;
            for (Constructor<?> c : inner.getDeclaredConstructors()) {
                try {
                    c.setAccessible(true);
                    Class<?>[] params = c.getParameterTypes();
                    // Try (String) constructor
                    if (params.length == 1 && params[0] == String.class) {
                        ClickEvent result = (ClickEvent) c.newInstance(value);
                        E36mcMod.LOGGER.info("[e36mc] Created ClickEvent via inner class {} (String)", inner.getName());
                        return result;
                    }
                } catch (Throwable ignored) {}
            }
        }

        // Strategy 3: Try inner classes with (Action, String) constructor
        for (Class<?> inner : ClickEvent.class.getDeclaredClasses()) {
            if (!ClickEvent.class.isAssignableFrom(inner)) continue;
            for (Constructor<?> c : inner.getDeclaredConstructors()) {
                try {
                    c.setAccessible(true);
                    Class<?>[] params = c.getParameterTypes();
                    if (params.length == 2 && params[1] == String.class) {
                        ClickEvent result = (ClickEvent) c.newInstance(action, value);
                        E36mcMod.LOGGER.info("[e36mc] Created ClickEvent via inner class {} (Action, String)", inner.getName());
                        return result;
                    }
                } catch (Throwable ignored) {}
            }
        }

        E36mcMod.LOGGER.error("[e36mc] FAILED to create ClickEvent for action: {}", action.name());
        return null;
    }

    /**
     * Creates a HoverEvent compatible across MC versions.
     */
    @SuppressWarnings("unchecked")
    private static <T> HoverEvent createHoverEvent(HoverEvent.Action<T> action, T value) {

        // Strategy 1: Direct constructor (Action, T)
        try {
            for (Constructor<?> ctor : HoverEvent.class.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length == 2 && params[0] == HoverEvent.Action.class) {
                    ctor.setAccessible(true);
                    return (HoverEvent) ctor.newInstance(action, value);
                }
            }
        } catch (Throwable ignored) {}

        // Strategy 2: Brute-force inner classes implementing HoverEvent
        for (Class<?> inner : HoverEvent.class.getDeclaredClasses()) {
            if (!HoverEvent.class.isAssignableFrom(inner)) continue;
            for (Constructor<?> c : inner.getDeclaredConstructors()) {
                try {
                    c.setAccessible(true);
                    Class<?>[] params = c.getParameterTypes();
                    // Try (Text) or (value type) constructor
                    if (params.length == 1 && params[0].isAssignableFrom(value.getClass())) {
                        HoverEvent result = (HoverEvent) c.newInstance(value);
                        E36mcMod.LOGGER.info("[e36mc] Created HoverEvent via inner class {} (value)", inner.getName());
                        return result;
                    }
                } catch (Throwable ignored) {}
            }
        }

        // Strategy 3: Inner classes with (Action, value) constructor
        for (Class<?> inner : HoverEvent.class.getDeclaredClasses()) {
            if (!HoverEvent.class.isAssignableFrom(inner)) continue;
            for (Constructor<?> c : inner.getDeclaredConstructors()) {
                try {
                    c.setAccessible(true);
                    Class<?>[] params = c.getParameterTypes();
                    if (params.length == 2 && params[1].isAssignableFrom(value.getClass())) {
                        HoverEvent result = (HoverEvent) c.newInstance(action, value);
                        E36mcMod.LOGGER.info("[e36mc] Created HoverEvent via inner class {} (Action, value)", inner.getName());
                        return result;
                    }
                } catch (Throwable ignored) {}
            }
        }

        E36mcMod.LOGGER.error("[e36mc] FAILED to create HoverEvent");
        return null;
    }
}
