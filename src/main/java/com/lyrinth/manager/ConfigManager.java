package com.lyrinth.manager;

import com.lyrinth.AdvancedKeepInventory;
import com.lyrinth.model.*;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * ConfigManager - Quản lý toàn bộ cấu hình plugin.
 * Load config.yml vào RAM (Map<String, WorldRule>) khi khởi động/reload.
 * Load messages.yml riêng biệt để hỗ trợ dịch đa ngôn ngữ.
 * KHÔNG đọc file config bên trong event handler.
 */
public class ConfigManager {

    private final AdvancedKeepInventory plugin;
    private final Logger logger;
    private final ConfigMigrator migrator;

    // Cache toàn bộ config vào RAM - key là tên world (lowercase)
    private final Map<String, WorldRule> worldRules = new HashMap<>();

    // Cache settings chung
    private AntiAbuseRule antiAbuseRule;
    private boolean debug;

    // Cache messages - key dạng "section.key" (VD: "death.victim-pvp")
    private final Map<String, String> messages = new HashMap<>();

    /**
     * Cache cấu hình hiển thị cho từng message key.
     * Key dạng "section.key" (VD: "death.victim-pvp")
     * Value: NotificationConfig chứa kiểu hiển thị + tùy chọn phụ.
     *
     * Tách riêng khỏi messages map để dễ lookup và không làm phức tạp flatten logic.
     */
    private final Map<String, NotificationConfig> notificationConfigs = new HashMap<>();

    public ConfigManager(AdvancedKeepInventory plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.migrator = new ConfigMigrator(plugin);
    }

    /**
     * Load (hoặc reload) toàn bộ config từ file vào RAM.
     * Gọi khi plugin enable và khi dùng lệnh /aki reload.
     *
     * Luồng xử lý:
     *   1. Lưu file mặc định nếu chưa có
     *   2. Chạy migration (kiểm tra version, merge field mới, backup file cũ)
     *   3. Reload config từ file đã merge
     *   4. Parse config vào RAM (WorldRules, AntiAbuse, Messages...)
     */
    public void loadConfig() {
        // Bước 0: Lưu config mặc định nếu chưa có file
        plugin.saveDefaultConfig();

        // Bước 1: Chạy migration TRƯỚC khi reload config
        // Nếu config.yml phiên bản cũ → backup + merge field mới + ghi file
        boolean configMigrated = migrator.migrateConfig();

        // Bước 2: Reload config từ file (có thể đã được migration cập nhật)
        plugin.reloadConfig();

        FileConfiguration config = plugin.getConfig();

        // --- Load Settings chung ---
        debug = config.getBoolean("settings.debug", false);
        loadAntiAbuseSettings(config);

        // --- Load Messages từ messages.yml (tách riêng khỏi config.yml) ---
        loadMessages();

        // --- Load World Rules ---
        worldRules.clear();
        loadWorldRules(config);

        if (debug) {
            logger.info("[Debug] Loaded " + worldRules.size() + " world rule(s).");
            logger.info("[Debug] Loaded " + messages.size() + " message(s).");
            logger.info("[Debug] Loaded " + notificationConfigs.size() + " notification config(s).");
            if (configMigrated) {
                logger.info("[Debug] Config was migrated to latest version.");
            }
        }
    }

    /**
     * Load cấu hình Anti-Abuse từ config.
     */
    private void loadAntiAbuseSettings(FileConfiguration config) {
        boolean enabled = config.getBoolean("settings.anti-abuse.enabled", true);
        int cooldown = config.getInt("settings.anti-abuse.cooldown-seconds", 600);
        boolean checkIp = config.getBoolean("settings.anti-abuse.check-ip", true);
        double minBalance = config.getDouble("settings.anti-abuse.min-victim-balance", 100.0);
        // Rapid Death Protection: bật thì các lần chết liên tục trong cooldown window sẽ không bị trừ tiền/EXP
        boolean rapidDeathProtection = config.getBoolean("settings.anti-abuse.rapid-death-protection", true);

        antiAbuseRule = new AntiAbuseRule(enabled, cooldown, checkIp, minBalance, rapidDeathProtection);

        if (debug) {
            logger.info("[Debug] Anti-Abuse: enabled=" + enabled + ", cooldown=" + cooldown
                    + "s, checkIp=" + checkIp + ", minBalance=" + minBalance
                    + ", rapidDeathProtection=" + rapidDeathProtection);
        }
    }

    /**
     * Load tất cả messages từ messages.yml vào cache.
     *
     * Cách hoạt động (tương tự "bảng tra cứu"):
     *   messages.yml có cấu trúc nested:
     *     death:
     *       victim-pvp:
     *         text: "..."       <-- nội dung message
     *         display: chat     <-- kiểu hiển thị (chat/actionbar/title/bossbar)
     *   -> messages cache key = "death.victim-pvp"
     *   -> notificationConfigs cache key = "death.victim-pvp"
     *
     * Ưu tiên: file ngoài (plugin data folder) > file mặc định trong JAR.
     * Nếu file ngoài không có key nào đó -> fallback về default trong JAR.
     */
    private void loadMessages() {
        messages.clear();
        notificationConfigs.clear();

        // Bước 1: Lưu messages.yml mặc định ra ngoài CHỈ KHI file chưa tồn tại
        // Nếu gọi saveResource() khi file đã có → Bukkit log WARN không cần thiết
        File messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }

        // Bước 2: Chạy migration cho messages.yml (thêm key mới, giữ giá trị cũ)
        migrator.migrateMessages();

        // Bước 3: Load file ngoài (admin có thể chỉnh sửa)
        // (messagesFile đã được khai báo ở trên, dùng lại luôn)
        YamlConfiguration externalConfig = YamlConfiguration.loadConfiguration(messagesFile);

        // Bước 4: Load default từ JAR để fallback khi admin xóa key
        InputStream defaultStream = plugin.getResource("messages.yml");
        if (defaultStream != null) {
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8)
            );
            externalConfig.setDefaults(defaultConfig);
        }

        // Bước 5: Duyệt qua từng section trong messages.yml và parse
        // Mỗi message key có thể là:
        //   - String đơn giản: "text cũ" -> backward-compatible, dùng CHAT
        //   - ConfigurationSection với "text" và "display" block
        parseMessagesSection(externalConfig, "");
    }

    /**
     * Đệ quy duyệt toàn bộ YAML để parse messages và notification configs.
     *
     * Logic nhận diện "leaf message node" (node chứa message thực sự):
     *   - Nếu là String -> đây là message text thuần (backward-compatible)
     *   - Nếu là Section CÓ key "text" -> đây là message node mới
     *   - Nếu là Section KHÔNG CÓ "text" -> tiếp tục đệ quy vào trong
     *
     * @param section Section YAML đang duyệt
     * @param prefix  Tiền tố tích lũy (VD: "death." khi đang duyệt section death)
     */
    private void parseMessagesSection(ConfigurationSection section, String prefix) {
        for (String key : section.getKeys(false)) {
            String fullKey = prefix.isEmpty() ? key : prefix + key;

            if (section.isString(key)) {
                // Trường hợp 1: Giá trị là String thuần -> backward-compatible
                // VD: victim-pvp: "<red>Bạn đã bị giết..."
                messages.put(fullKey, section.getString(key, ""));
                notificationConfigs.put(fullKey, NotificationConfig.defaultChat());

            } else if (section.isConfigurationSection(key)) {
                ConfigurationSection child = section.getConfigurationSection(key);
                if (child == null) continue;

                if (child.contains("text")) {
                    // Trường hợp 2: Section CÓ "text" -> đây là message node có display config
                    // VD:
                    //   victim-pvp:
                    //     text: "<red>Bạn đã bị giết..."
                    //     display: actionbar
                    String text = child.getString("text", "");
                    messages.put(fullKey, text);
                    notificationConfigs.put(fullKey, parseNotificationConfig(child));

                } else {
                    // Trường hợp 3: Section thuần (không có "text") -> tiếp tục đệ quy
                    // VD: section "death:", "command:", "plugin:"...
                    parseMessagesSection(child, fullKey + ".");
                }
            }
        }
    }

    /**
     * Parse NotificationConfig từ 1 ConfigurationSection của message node.
     *
     * Cấu trúc YAML mẫu:
     *   victim-pvp:
     *     text: "<red>Bạn đã bị giết bởi %killer%!"
     *     display: title
     *     title-fade-in: 10
     *     title-stay: 60
     *     title-fade-out: 20
     *     subtitle: "<gray>Mất <red>%money%$</red>"
     *
     * @param section Section chứa "text" và các key cấu hình hiển thị
     * @return NotificationConfig đã được parse
     */
    private NotificationConfig parseNotificationConfig(ConfigurationSection section) {
        // Parse kiểu hiển thị (mặc định: CHAT)
        String displayStr = section.getString("display", "chat");
        DisplayType displayType = DisplayType.fromString(displayStr);

        // --- Parse TITLE options ---
        int titleFadeIn  = section.getInt("title-fade-in", 10);
        int titleStay    = section.getInt("title-stay", 70);
        int titleFadeOut = section.getInt("title-fade-out", 20);
        String subtitle  = section.getString("subtitle", "");

        // --- Parse BOSSBAR options (string-based for backend compatibility) ---
        String bossBarColor = parseBossBarColor(section.getString("bossbar-color", "RED"));
        String bossBarOverlay = parseBossBarOverlay(section.getString("bossbar-overlay", "PROGRESS"));
        int bossBarSeconds = section.getInt("bossbar-seconds", 5);

        return new NotificationConfig(
                displayType,
                titleFadeIn, titleStay, titleFadeOut, subtitle,
                bossBarColor, bossBarOverlay, bossBarSeconds
        );
    }

    /**
     * Parse bossbar color from config and normalize to enum-like uppercase value.
     */
    private String parseBossBarColor(String value) {
        if (value == null) return "RED";

        String normalized = value.toUpperCase().trim();
        Set<String> allowed = Set.of("PINK", "BLUE", "RED", "GREEN", "YELLOW", "PURPLE", "WHITE");
        if (allowed.contains(normalized)) {
            return normalized;
        }

        logger.warning("BossBar color không hợp lệ: '" + value + "'. Dùng RED.");
        return "RED";
    }

    /**
     * Parse bossbar overlay/style from config and normalize to shared values.
     */
    private String parseBossBarOverlay(String value) {
        if (value == null) return "PROGRESS";

        String normalized = value.toUpperCase().trim();
        Set<String> allowed = Set.of("PROGRESS", "SOLID", "NOTCHED_6", "NOTCHED_10", "NOTCHED_12", "NOTCHED_20");
        if (allowed.contains(normalized)) {
            return normalized;
        }

        logger.warning("BossBar overlay không hợp lệ: '" + value + "'. Dùng PROGRESS.");
        return "PROGRESS";
    }

    /**
     * Load cấu hình cho từng thế giới.
     */
    private void loadWorldRules(FileConfiguration config) {
        ConfigurationSection worldsSection = config.getConfigurationSection("worlds");
        if (worldsSection == null) {
            logger.warning("Không tìm thấy section 'worlds' trong config.yml!");
            return;
        }

        for (String worldName : worldsSection.getKeys(false)) {
            ConfigurationSection ws = worldsSection.getConfigurationSection(worldName);
            if (ws == null) continue;

            boolean enabled = ws.getBoolean("enabled", true);

            // [DESIGN-02] Parse respect-vanilla-gamerule (mặc định true để an toàn)
            boolean respectVanilla = ws.getBoolean("respect-vanilla-gamerule", true);

            // Parse blanket-keep
            BlanketKeepRule blanketKeep = parseBlanketKeep(ws.getConfigurationSection("blanket-keep"));

            // Parse item rules (drop & keep)
            ItemRule itemRule = parseItemRule(ws);

            // Parse economy rule
            EconomyRule economyRule = parseEconomyRule(ws.getConfigurationSection("money"));

            // Parse exp rule
            ExpRule expRule = parseExpRule(ws.getConfigurationSection("exp"));

            // Tạo WorldRule và cache vào map (lowercase key để tránh lỗi case-sensitive)
            WorldRule rule = new WorldRule(enabled, respectVanilla, blanketKeep, itemRule, economyRule, expRule);
            worldRules.put(worldName.toLowerCase(), rule);

            if (debug) {
                logger.info("[Debug] Loaded world rule: " + worldName);
            }
        }
    }

    /**
     * Parse section blanket-keep thành BlanketKeepRule record.
     */
    private BlanketKeepRule parseBlanketKeep(ConfigurationSection section) {
        if (section == null) {
            // Mặc định: không giữ gì cả
            return new BlanketKeepRule(false, false, false, false, false);
        }
        return new BlanketKeepRule(
                section.getBoolean("on-player-killed", false),
                section.getBoolean("on-mob-killed", false),
                section.getBoolean("on-natural-death", false),
                section.getBoolean("keep-equipped-armor", false),
                section.getBoolean("keep-main-hand", false)
        );
    }

    /**
     * Parse sections drop & keep thành ItemRule record.
     */
    private ItemRule parseItemRule(ConfigurationSection worldSection) {
        // Parse DROP section
        ConfigurationSection dropSection = worldSection.getConfigurationSection("drop");
        Set<ItemCategory> dropCategories = parseCategories(dropSection);
        Set<Material> dropItems = parseMaterials(dropSection);
        Set<String> dropCustomItems = parseCustomItems(dropSection);

        // Parse KEEP section
        ConfigurationSection keepSection = worldSection.getConfigurationSection("keep");
        Set<ItemCategory> keepCategories = parseCategories(keepSection);
        Set<Material> keepItems = parseMaterials(keepSection);
        Set<String> keepCustomItems = parseCustomItems(keepSection);

        return new ItemRule(dropCategories, dropItems, dropCustomItems,
                keepCategories, keepItems, keepCustomItems);
    }

    /**
     * Parse danh sách categories từ config section.
     */
    private Set<ItemCategory> parseCategories(ConfigurationSection section) {
        if (section == null) return Collections.emptySet();
        List<String> list = section.getStringList("categories");
        return list.stream()
                .map(ItemCategory::fromString)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * Parse danh sách Material từ config section.
     */
    private Set<Material> parseMaterials(ConfigurationSection section) {
        if (section == null) return Collections.emptySet();
        List<String> list = section.getStringList("items");
        Set<Material> result = new HashSet<>();
        for (String name : list) {
            Material mat = Material.matchMaterial(name);
            if (mat != null) {
                result.add(mat);
            } else {
                logger.warning("Material không hợp lệ trong config: " + name);
            }
        }
        return result;
    }

    /**
     * Parse danh sách custom items (MATERIAL:CustomModelData) từ config section.
     */
    private Set<String> parseCustomItems(ConfigurationSection section) {
        if (section == null) return Collections.emptySet();
        List<String> list = section.getStringList("custom-items");
        Set<String> result = new HashSet<>();
        for (String entry : list) {
            // Validate format: MATERIAL:NUMBER
            String[] parts = entry.split(":");
            if (parts.length == 2) {
                Material mat = Material.matchMaterial(parts[0]);
                try {
                    Integer.parseInt(parts[1]);
                    if (mat != null) {
                        // Chuẩn hóa key: MATERIAL_NAME:CMD
                        result.add(mat.name() + ":" + parts[1]);
                    } else {
                        logger.warning("Custom item Material không hợp lệ: " + parts[0]);
                    }
                } catch (NumberFormatException e) {
                    logger.warning("Custom item CustomModelData không hợp lệ: " + entry);
                }
            } else {
                logger.warning("Custom item format không đúng (cần MATERIAL:CMD): " + entry);
            }
        }
        return result;
    }

    /**
     * Parse section money thành EconomyRule record.
     */
    private EconomyRule parseEconomyRule(ConfigurationSection section) {
        if (section == null) {
            return new EconomyRule(false, "Vault", 0.0, 0.0, false);
        }
        return new EconomyRule(
                section.getBoolean("activate", false),
                section.getString("type", "Vault"),
                section.getDouble("percent", 0.1),
                section.getDouble("max-loss", 10000.0),
                section.getBoolean("give-to-killer", false)
        );
    }

    /**
     * Parse section exp thành ExpRule record.
     * [DESIGN-01] Thêm applyOn: cho phép admin chọn nguyên nhân chết nào áp dụng mất EXP.
     */
    private ExpRule parseExpRule(ConfigurationSection section) {
        if (section == null) {
            // Mặc định: tắt, áp dụng cho tất cả nguyên nhân chết
            return new ExpRule(false, 0.0, EnumSet.allOf(DeathCause.class));
        }

        boolean activate = section.getBoolean("activate", false);
        double percentLoss = section.getDouble("percent-loss", 0.5);

        // Parse apply-on: danh sách nguyên nhân chết (VD: [PLAYER, MOB, NATURAL])
        // Mặc định áp dụng cho tất cả nếu không cấu hình
        Set<DeathCause> applyOn = parseDeathCauses(section.getStringList("apply-on"));

        return new ExpRule(activate, percentLoss, applyOn);
    }

    /**
     * Parse danh sách nguyên nhân chết từ config.
     * Nếu list rỗng hoặc không hợp lệ -> mặc định áp dụng cho TẤT CẢ nguyên nhân.
     * Đảm bảo backward-compatible với config cũ không có field này.
     */
    private Set<DeathCause> parseDeathCauses(List<String> list) {
        if (list == null || list.isEmpty()) {
            // Backward-compatible: config cũ không có apply-on -> áp dụng cho tất cả
            return EnumSet.allOf(DeathCause.class);
        }

        Set<DeathCause> result = EnumSet.noneOf(DeathCause.class);
        for (String name : list) {
            try {
                result.add(DeathCause.valueOf(name.toUpperCase().trim()));
            } catch (IllegalArgumentException e) {
                logger.warning("DeathCause không hợp lệ trong config: '" + name
                        + "'. Giá trị hợp lệ: PLAYER, MOB, NATURAL");
            }
        }

        // Nếu parse xong mà rỗng (tất cả giá trị đều sai) -> fallback về tất cả
        return result.isEmpty() ? EnumSet.allOf(DeathCause.class) : result;
    }

    // ==================== GETTERS ====================

    /**
     * Lấy WorldRule cho 1 thế giới. Trả về null nếu world không có trong config.
     */
    public WorldRule getWorldRule(String worldName) {
        return worldRules.get(worldName.toLowerCase());
    }

    public AntiAbuseRule getAntiAbuseRule() {
        return antiAbuseRule;
    }

    public boolean isDebug() {
        return debug;
    }

    /**
     * Lấy message text theo key, trả về chuỗi rỗng nếu không tìm thấy.
     */
    public String getMessage(String key) {
        return messages.getOrDefault(key, "");
    }

    /**
     * Lấy NotificationConfig cho message key.
     * Trả về config mặc định (CHAT) nếu không tìm thấy.
     */
    public NotificationConfig getNotificationConfig(String key) {
        return notificationConfigs.getOrDefault(key, NotificationConfig.defaultChat());
    }
}
