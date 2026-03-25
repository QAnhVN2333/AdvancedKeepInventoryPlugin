package com.lyrinth.manager;

import com.lyrinth.model.NotificationConfig;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * MessageManager handles cross-platform message delivery.
 * Paper: MiniMessage + Adventure via reflection.
 * Spigot/Bukkit: legacy color fallback.
 */
public class MessageManager {

    private static final Pattern MINI_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private final JavaPlugin plugin;

    private final boolean paperAdventureAvailable;
    private final Object miniMessageInstance;
    private final Class<?> componentClass;

    public MessageManager(JavaPlugin plugin) {
        this.plugin = plugin;

        Object mini = null;
        Class<?> component = null;
        boolean available = false;

        try {
            Class<?> miniMessageClass = Class.forName("net.kyori.adventure.text.minimessage.MiniMessage");
            component = Class.forName("net.kyori.adventure.text.Component");
            mini = miniMessageClass.getMethod("miniMessage").invoke(null);
            Player.class.getMethod("sendMessage", component);
            available = true;
        } catch (Throwable ignored) {
            available = false;
        }

        this.paperAdventureAvailable = available;
        this.miniMessageInstance = mini;
        this.componentClass = component;
    }

    // ==================== PUBLIC API ====================

    /**
     * Gửi message theo cấu hình hiển thị (NotificationConfig).
     * Đây là method chính - các method khác đều delegate vào đây.
     *
     * @param player       Người chơi nhận message
     * @param rawMessage   Chuỗi message gốc (MiniMessage format)
     * @param placeholders Map placeholder cần thay thế (VD: %killer% -> "Steve")
     * @param config       Cấu hình cách hiển thị (chat/actionbar/title/bossbar)
     */
    public void send(Player player, String rawMessage,
                     Map<String, String> placeholders, NotificationConfig config) {
        if (player == null || rawMessage == null || rawMessage.isEmpty()) return;

        // Bước 1: Thay thế tất cả placeholder
        String formatted = applyPlaceholders(rawMessage, placeholders);

        // Bước 2: Gửi theo kiểu hiển thị tương ứng
        switch (config.type()) {
            case CHAT      -> sendChat(player, formatted);
            case ACTIONBAR -> sendActionBar(player, formatted);
            case TITLE     -> sendTitle(player, formatted, config);
            case BOSSBAR   -> sendBossBar(player, formatted, config);
        }
    }

    /**
     * Overload: gửi với placeholder nhưng dùng config mặc định (CHAT).
     * Giữ backward-compatible với code cũ không cần config.
     */
    public void send(Player player, String rawMessage, Map<String, String> placeholders) {
        send(player, rawMessage, placeholders, NotificationConfig.defaultChat());
    }

    /**
     * Overload: gửi đơn giản (không placeholder, không config).
     */
    public void send(Player player, String rawMessage) {
        send(player, rawMessage, Map.of(), NotificationConfig.defaultChat());
    }

    // ==================== PRIVATE SENDERS ====================

    /**
     * Gửi message vào chat thông thường.
     */
    private void sendChat(Player player, String formatted) {
        if (paperAdventureAvailable && sendAdventureChat(player, formatted)) {
            return;
        }
        player.sendMessage(toLegacy(formatted));
    }

    /**
     * Gửi message lên action bar (thanh nhỏ phía trên hotbar).
     * Thích hợp cho thông báo thoáng qua không làm phiền chat.
     */
    private void sendActionBar(Player player, String formatted) {
        if (paperAdventureAvailable && sendAdventureActionBar(player, formatted)) {
            return;
        }

        try {
            player.spigot().sendMessage(
                    net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                    net.md_5.bungee.api.chat.TextComponent.fromLegacyText(toLegacy(formatted))
            );
        } catch (Throwable ignored) {
            player.sendMessage(toLegacy(formatted));
        }
    }

    /**
     * Hiển thị message dạng Title (chữ lớn giữa màn hình).
     * - Title chính: nội dung rawMessage
     * - Subtitle: chuỗi phụ từ config (có thể rỗng)
     * - Thời gian: fadeIn / stay / fadeOut (ticks)
     */
    private void sendTitle(Player player, String formatted, NotificationConfig config) {
        if (paperAdventureAvailable && sendAdventureTitle(player, formatted, config)) {
            return;
        }

        String title = toLegacy(formatted);
        String subtitle = toLegacy(config.subtitle() == null ? "" : config.subtitle());
        player.sendTitle(title, subtitle, config.titleFadeIn(), config.titleStay(), config.titleFadeOut());
    }

    /**
     * Hiển thị message dạng Boss Bar phía trên màn hình.
     * Boss Bar sẽ tự động ẩn sau N giây được cấu hình.
     *
     * Luồng: tạo BossBar -> hiển thị cho player -> lên lịch task xóa sau N giây.
     */
    private void sendBossBar(Player player, String formatted, NotificationConfig config) {
        if (paperAdventureAvailable && sendAdventureBossBar(player, formatted, config)) {
            return;
        }

        BarColor color = parseBarColor(config.bossBarColor());
        BarStyle style = parseBarStyle(config.bossBarOverlay());
        BossBar bossBar = Bukkit.createBossBar(toLegacy(formatted), color, style);
        bossBar.addPlayer(player);
        bossBar.setProgress(1.0);

        int seconds = config.bossBarSeconds();
        if (seconds > 0) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    bossBar.removePlayer(player);
                    bossBar.setVisible(false);
                }
            }.runTaskLater(plugin, seconds * 20L);
        }
    }

    // ==================== HELPERS ====================

    private boolean sendAdventureChat(Player player, String formatted) {
        try {
            Object component = deserializeMiniMessage(formatted);
            Method sendMethod = player.getClass().getMethod("sendMessage", componentClass);
            sendMethod.invoke(player, component);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean sendAdventureActionBar(Player player, String formatted) {
        try {
            Object component = deserializeMiniMessage(formatted);
            Method sendActionBarMethod = player.getClass().getMethod("sendActionBar", componentClass);
            sendActionBarMethod.invoke(player, component);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean sendAdventureTitle(Player player, String formatted, NotificationConfig config) {
        try {
            Object titleComponent = deserializeMiniMessage(formatted);
            String subtitleRaw = config.subtitle() == null ? "" : config.subtitle();
            Object subtitleComponent = deserializeMiniMessage(subtitleRaw);

            Class<?> titleClass = Class.forName("net.kyori.adventure.title.Title");
            Class<?> titleTimesClass = Class.forName("net.kyori.adventure.title.Title$Times");

            Object times = titleTimesClass.getMethod("times", Duration.class, Duration.class, Duration.class)
                    .invoke(null,
                            Duration.ofMillis(config.titleFadeIn() * 50L),
                            Duration.ofMillis(config.titleStay() * 50L),
                            Duration.ofMillis(config.titleFadeOut() * 50L)
                    );

            Object title = titleClass.getMethod("title", componentClass, componentClass, titleTimesClass)
                    .invoke(null, titleComponent, subtitleComponent, times);

            Method showTitle = player.getClass().getMethod("showTitle", titleClass);
            showTitle.invoke(player, title);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean sendAdventureBossBar(Player player, String formatted, NotificationConfig config) {
        try {
            Object component = deserializeMiniMessage(formatted);

            Class<?> bossBarClass = Class.forName("net.kyori.adventure.bossbar.BossBar");
            Class<?> colorClass = Class.forName("net.kyori.adventure.bossbar.BossBar$Color");
            Class<?> overlayClass = Class.forName("net.kyori.adventure.bossbar.BossBar$Overlay");

            Object color = parseAdventureEnum(colorClass, config.bossBarColor(), "RED");
            String overlayName = normalizeAdventureOverlay(config.bossBarOverlay());
            Object overlay = parseAdventureEnum(overlayClass, overlayName, "PROGRESS");

            Object bossBar = bossBarClass
                    .getMethod("bossBar", componentClass, float.class, colorClass, overlayClass)
                    .invoke(null, component, 1.0f, color, overlay);

            Method showBossBar = player.getClass().getMethod("showBossBar", bossBarClass);
            Method hideBossBar = player.getClass().getMethod("hideBossBar", bossBarClass);
            showBossBar.invoke(player, bossBar);

            int seconds = config.bossBarSeconds();
            if (seconds > 0) {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        Player online = Bukkit.getPlayer(player.getUniqueId());
                        if (online != null) {
                            try {
                                hideBossBar.invoke(online, bossBar);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }.runTaskLater(plugin, seconds * 20L);
            }

            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Object deserializeMiniMessage(String input) throws Exception {
        Class<?> miniMessageClass = miniMessageInstance.getClass();
        return miniMessageClass.getMethod("deserialize", String.class)
                .invoke(miniMessageInstance, input);
    }

    private Object parseAdventureEnum(Class<?> enumClass, String name, String fallback) {
        try {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object value = Enum.valueOf((Class<? extends Enum>) enumClass, safeUpper(name));
            return value;
        } catch (Throwable ignored) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object value = Enum.valueOf((Class<? extends Enum>) enumClass, fallback);
            return value;
        }
    }

    private String normalizeAdventureOverlay(String raw) {
        String value = safeUpper(raw);
        if ("SOLID".equals(value)) {
            return "PROGRESS";
        }
        return value;
    }

    private BarColor parseBarColor(String raw) {
        try {
            return BarColor.valueOf(safeUpper(raw));
        } catch (IllegalArgumentException e) {
            return BarColor.RED;
        }
    }

    private BarStyle parseBarStyle(String raw) {
        String normalized = safeUpper(raw);
        if ("PROGRESS".equals(normalized)) {
            normalized = "SOLID";
        }

        try {
            return BarStyle.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return BarStyle.SOLID;
        }
    }

    private String applyPlaceholders(String message, Map<String, String> placeholders) {
        String result = message;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private String toLegacy(String message) {
        String translated = translateMiniTags(message);
        translated = MINI_TAG_PATTERN.matcher(translated).replaceAll("");
        return ChatColor.translateAlternateColorCodes('&', translated);
    }

    private String translateMiniTags(String message) {
        if (message == null || message.isEmpty()) return "";

        String converted = message;
        converted = converted.replace("<black>", "&0");
        converted = converted.replace("<dark_blue>", "&1");
        converted = converted.replace("<dark_green>", "&2");
        converted = converted.replace("<dark_aqua>", "&3");
        converted = converted.replace("<dark_red>", "&4");
        converted = converted.replace("<dark_purple>", "&5");
        converted = converted.replace("<gold>", "&6");
        converted = converted.replace("<gray>", "&7");
        converted = converted.replace("<dark_gray>", "&8");
        converted = converted.replace("<blue>", "&9");
        converted = converted.replace("<green>", "&a");
        converted = converted.replace("<aqua>", "&b");
        converted = converted.replace("<red>", "&c");
        converted = converted.replace("<light_purple>", "&d");
        converted = converted.replace("<yellow>", "&e");
        converted = converted.replace("<white>", "&f");

        converted = converted.replace("<bold>", "&l");
        converted = converted.replace("<italic>", "&o");
        converted = converted.replace("<underlined>", "&n");
        converted = converted.replace("<strikethrough>", "&m");
        converted = converted.replace("<obfuscated>", "&k");
        converted = converted.replace("<reset>", "&r");

        converted = converted.replace("</black>", "");
        converted = converted.replace("</dark_blue>", "");
        converted = converted.replace("</dark_green>", "");
        converted = converted.replace("</dark_aqua>", "");
        converted = converted.replace("</dark_red>", "");
        converted = converted.replace("</dark_purple>", "");
        converted = converted.replace("</gold>", "");
        converted = converted.replace("</gray>", "");
        converted = converted.replace("</dark_gray>", "");
        converted = converted.replace("</blue>", "");
        converted = converted.replace("</green>", "");
        converted = converted.replace("</aqua>", "");
        converted = converted.replace("</red>", "");
        converted = converted.replace("</light_purple>", "");
        converted = converted.replace("</yellow>", "");
        converted = converted.replace("</white>", "");

        converted = converted.replace("</bold>", "");
        converted = converted.replace("</italic>", "");
        converted = converted.replace("</underlined>", "");
        converted = converted.replace("</strikethrough>", "");
        converted = converted.replace("</obfuscated>", "");

        return converted;
    }

    private String safeUpper(String value) {
        if (value == null) return "";
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
