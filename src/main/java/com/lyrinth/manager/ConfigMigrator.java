package com.lyrinth.manager;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * ConfigMigrator - Quản lý việc cập nhật (migration) config.yml và messages.yml
 * khi dev release version mới có thêm field.
 *
 * Cách hoạt động (tương tự "trộn bột bánh"):
 *   1. Đọc file DEFAULT trong JAR (bản mới nhất dev viết)
 *   2. Đọc file NGOÀI data folder (bản user đang dùng)
 *   3. So sánh version number:
 *      - Nếu bằng nhau → không làm gì
 *      - Nếu file ngoài cũ hơn → chạy migration
 *   4. Migration = đọc file default TỪNG DÒNG (giữ comment + format)
 *      → thay thế giá trị bằng giá trị cũ của user nếu key tồn tại
 *   5. Backup file cũ trước khi ghi đè
 *
 * [FIX-01] Version key luôn lấy từ default (không giữ giá trị cũ)
 * [FIX-02] Giữ lại các section user tự thêm (VD: world_nether, custom_world)
 *          bằng cách append vào cuối file sau khi merge template default
 *
 * Ưu điểm so với Bukkit copyDefaults():
 *   - Giữ nguyên comment trong file YAML
 *   - Giữ nguyên thứ tự các section
 *   - Giữ nguyên các section user tự thêm (extra worlds, custom sections)
 *   - User thấy file sạch đẹp, không bị loạn
 */
public class ConfigMigrator {

    private final JavaPlugin plugin;
    private final Logger logger;

    // Key chứa version number - luôn lấy từ default, KHÔNG giữ giá trị cũ
    private static final String VERSION_KEY = "version";

    // Các section "repeatable" - user có thể tự thêm child mới (VD: worlds.world_nether)
    // Khi merge: child có trong default → merge bình thường
    //            child CHỈ có trong user → append nguyên vẹn vào cuối section
    private static final Set<String> REPEATABLE_SECTIONS = Set.of("worlds");

    public ConfigMigrator(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /**
     * Kiểm tra và migrate config.yml nếu cần.
     *
     * @return true nếu đã chạy migration (file bị thay đổi), false nếu không cần
     */
    public boolean migrateConfig() {
        return migrateFile("config.yml");
    }

    /**
     * Kiểm tra và migrate messages.yml nếu cần.
     *
     * @return true nếu đã chạy migration, false nếu không cần
     */
    public boolean migrateMessages() {
        return migrateFile("messages.yml");
    }

    // ==================== CORE MIGRATION LOGIC ====================

    /**
     * Logic migration chung cho bất kỳ file YAML nào.
     *
     * Luồng xử lý:
     *   1. Load file ngoài (user) và file default (JAR)
     *   2. So sánh version
     *   3. Nếu cần migrate → backup → merge → ghi file mới
     *
     * @param fileName Tên file (VD: "config.yml", "messages.yml")
     * @return true nếu đã migrate
     */
    private boolean migrateFile(String fileName) {
        File externalFile = new File(plugin.getDataFolder(), fileName);

        // Nếu file chưa tồn tại → plugin sẽ tự saveDefaultConfig(), không cần migrate
        if (!externalFile.exists()) {
            return false;
        }

        // Bước 1: Load cả 2 bản YAML
        YamlConfiguration userConfig = YamlConfiguration.loadConfiguration(externalFile);
        YamlConfiguration defaultConfig = loadDefaultFromJar(fileName);

        if (defaultConfig == null) {
            logger.warning("[Migration] Không tìm thấy " + fileName + " mặc định trong JAR!");
            return false;
        }

        // Bước 2: So sánh version
        int userVersion = userConfig.getInt(VERSION_KEY, 0);
        int defaultVersion = defaultConfig.getInt(VERSION_KEY, 0);

        if (userVersion >= defaultVersion) {
            // Version bằng hoặc mới hơn → không cần migrate
            return false;
        }

        logger.info("[Migration] Phát hiện " + fileName + " phiên bản cũ (v" + userVersion
                + " → v" + defaultVersion + "). Bắt đầu cập nhật...");

        // Bước 3: Backup file cũ trước khi thay đổi
        backupFile(externalFile, userVersion);

        // Bước 4: Merge - đọc default từng dòng, thay giá trị user nếu có
        List<String> mergedLines = mergeWithComments(fileName, userConfig, defaultConfig);

        // Bước 5: Xử lý list items (categories, items, custom-items, apply-on...)
        mergedLines = postProcessLists(mergedLines, userConfig, defaultConfig);

        // Bước 6: [FIX-02] Append các section user tự thêm (extra worlds, custom sections)
        mergedLines = appendExtraUserSections(mergedLines, userConfig, defaultConfig);

        // Bước 7: Ghi file mới ra ngoài
        try {
            Files.write(externalFile.toPath(), mergedLines, StandardCharsets.UTF_8);
            logger.info("[Migration] Cập nhật " + fileName + " thành công! (v" + userVersion
                    + " → v" + defaultVersion + ")");
            logger.info("[Migration] File cũ đã backup tại: " + fileName + ".v" + userVersion + ".bak");
            return true;
        } catch (IOException e) {
            logger.severe("[Migration] Lỗi ghi file " + fileName + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Load file YAML mặc định từ bên trong JAR.
     */
    private YamlConfiguration loadDefaultFromJar(String fileName) {
        InputStream stream = plugin.getResource(fileName);
        if (stream == null) return null;
        return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }

    /**
     * Tạo bản backup của file trước khi migration.
     * Format: config.yml.v1.bak, messages.yml.v2.bak, ...
     */
    private void backupFile(File file, int userVersion) {
        try {
            File backupFile = new File(file.getParent(), file.getName() + ".v" + userVersion + ".bak");

            // Nếu backup đã tồn tại → thêm timestamp tránh ghi đè
            if (backupFile.exists()) {
                backupFile = new File(file.getParent(),
                        file.getName() + ".v" + userVersion + "." + System.currentTimeMillis() + ".bak");
            }

            Files.copy(file.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.warning("[Migration] Không thể backup " + file.getName() + ": " + e.getMessage());
        }
    }

    // ==================== MERGE WITH COMMENTS ====================

    /**
     * Merge giữ comment - PHẦN QUAN TRỌNG NHẤT.
     *
     * Tưởng tượng: photocopy file default, rồi "bôi trắng" và viết lại giá trị
     * mà user đã tùy chỉnh. Những ô mới (field mới) giữ nguyên giá trị default.
     *
     * [FIX-01] Key "version" LUÔN giữ giá trị default (không lấy từ user)
     *          → Sau migrate, file sẽ có version mới nhất
     *
     * @param fileName      Tên file để đọc template từ JAR
     * @param userConfig    Config hiện tại của user (giá trị cũ cần giữ)
     * @param defaultConfig Config mặc định (biết kiểu dữ liệu, section structure)
     * @return Danh sách dòng đã merge, sẵn sàng ghi ra file
     */
    private List<String> mergeWithComments(String fileName, YamlConfiguration userConfig,
                                           YamlConfiguration defaultConfig) {
        List<String> result = new ArrayList<>();

        // Đọc file default từ JAR theo từng dòng (giữ comment, format, khoảng trắng)
        List<String> defaultLines = readLinesFromJar(fileName);
        if (defaultLines.isEmpty()) return result;

        // Stack theo dõi indent → tính full YAML path
        // Tương tự "breadcrumb": indent 0 = "worlds", indent 2 = "worlds.world"
        Deque<PathEntry> pathStack = new ArrayDeque<>();

        for (String line : defaultLines) {
            String trimmed = line.trim();

            // Dòng trống hoặc comment → giữ nguyên 100%
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                result.add(line);
                continue;
            }

            int indent = getIndentLevel(line);

            // List item "- value" → giữ nguyên (xử lý ở postProcessLists)
            if (trimmed.startsWith("- ")) {
                result.add(line);
                continue;
            }

            // Parse key từ "  key: value"
            int colonIndex = trimmed.indexOf(':');
            if (colonIndex <= 0) {
                result.add(line);
                continue;
            }

            String yamlKey = trimmed.substring(0, colonIndex).trim();

            // Cập nhật path stack: pop entry cùng level hoặc sâu hơn
            while (!pathStack.isEmpty() && pathStack.peek().indent >= indent) {
                pathStack.pop();
            }

            // Tính full YAML path (VD: "worlds.world.enabled")
            String fullPath = pathStack.isEmpty()
                    ? yamlKey
                    : pathStack.peek().path + "." + yamlKey;

            pathStack.push(new PathEntry(indent, fullPath));

            // Kiểm tra section header vs leaf value
            String afterColon = trimmed.substring(colonIndex + 1).trim();

            if (afterColon.isEmpty()) {
                // Section header hoặc list header → giữ nguyên dòng
                result.add(line);
                continue;
            }

            // ═══════════════════════════════════════════════════
            // LEAF VALUE - Đây là nơi merge xảy ra!
            // ═══════════════════════════════════════════════════

            // [FIX-01] Key "version" → LUÔN giữ giá trị default (version mới)
            // Vì mục đích migration là upgrade version, nếu giữ version cũ
            // thì lần sau plugin load sẽ lại trigger migration → vòng lặp vô hạn
            if (fullPath.equals(VERSION_KEY)) {
                result.add(line); // Giữ nguyên dòng default (version mới)
                continue;
            }

            // Lấy inline comment từ dòng default (nếu có)
            String inlineComment = extractInlineComment(line);

            if (userConfig.contains(fullPath) && !userConfig.isConfigurationSection(fullPath)) {
                // User ĐÃ CÓ key này → giữ giá trị cũ của user
                Object userValue = userConfig.get(fullPath);
                String formattedValue = formatYamlValue(userValue);

                // Ghép: indent + key: value [+ inline comment]
                String indentStr = " ".repeat(indent);
                String newLine = indentStr + yamlKey + ": " + formattedValue;
                if (inlineComment != null) {
                    newLine = padWithComment(newLine, inlineComment);
                }
                result.add(newLine);
            } else {
                // User KHÔNG CÓ key → field MỚI, giữ nguyên default
                result.add(line);
            }
        }

        return result;
    }

    // ==================== POST-PROCESS LISTS ====================

    /**
     * Xử lý hậu kỳ: thay thế list items default bằng list items của user.
     *
     * Vấn đề: mergeWithComments() giữ nguyên dòng "- value" từ default.
     * Nhưng user có thể đã sửa list (thêm/xóa item) → cần dùng list của user.
     *
     * Cách giải quyết: Quét lại output, tìm list header ("categories:"),
     * skip các "- ..." default, ghi "- ..." từ user config thay thế.
     */
    private List<String> postProcessLists(List<String> lines,
                                           YamlConfiguration userConfig,
                                           YamlConfiguration defaultConfig) {
        List<String> result = new ArrayList<>();
        Deque<PathEntry> pathStack = new ArrayDeque<>();

        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            String trimmed = line.trim();

            // Dòng trống, comment, list item → giữ nguyên
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                result.add(line);
                i++;
                continue;
            }
            if (trimmed.startsWith("- ")) {
                result.add(line);
                i++;
                continue;
            }

            int indent = getIndentLevel(line);
            int colonIndex = trimmed.indexOf(':');
            if (colonIndex <= 0) {
                result.add(line);
                i++;
                continue;
            }

            String yamlKey = trimmed.substring(0, colonIndex).trim();

            // Cập nhật path stack
            while (!pathStack.isEmpty() && pathStack.peek().indent >= indent) {
                pathStack.pop();
            }
            String fullPath = pathStack.isEmpty()
                    ? yamlKey
                    : pathStack.peek().path + "." + yamlKey;
            pathStack.push(new PathEntry(indent, fullPath));

            String afterColon = trimmed.substring(colonIndex + 1).trim();

            // Kiểm tra: đây có phải list header không?
            // List header = "categories:" (afterColon rỗng) + path là list trong config
            if (afterColon.isEmpty() && (userConfig.isList(fullPath) || defaultConfig.isList(fullPath))) {
                result.add(line); // Ghi header "categories:"
                i++;

                // Skip tất cả dòng "- ..." default phía sau header
                int listItemIndent = indent + 2;
                while (i < lines.size()) {
                    String nextTrimmed = lines.get(i).trim();
                    int nextIndent = getIndentLevel(lines.get(i));
                    if (nextTrimmed.startsWith("- ") && nextIndent >= listItemIndent) {
                        i++; // Skip dòng list default
                    } else {
                        break;
                    }
                }

                // Ghi list items từ USER config (ưu tiên), fallback về default
                List<?> listToWrite = userConfig.isList(fullPath)
                        ? userConfig.getList(fullPath)
                        : defaultConfig.getList(fullPath);

                if (listToWrite != null) {
                    String listIndentStr = " ".repeat(listItemIndent);
                    for (Object item : listToWrite) {
                        result.add(listIndentStr + "- " + formatYamlValue(item));
                    }
                }
                continue;
            }

            // Không phải list → giữ nguyên
            result.add(line);
            i++;
        }

        return result;
    }

    // ==================== APPEND EXTRA USER SECTIONS ====================

    /**
     * [FIX-02] Tìm và append các section mà user tự thêm nhưng default không có.
     *
     * Ví dụ thực tế:
     *   - Default chỉ có: worlds.world
     *   - User đã thêm: worlds.world_nether, worlds.world_the_end, worlds.custom_pvp
     *   → Sau merge, 3 world này bị MẤT vì không có trong template default
     *   → Fix: tìm các child "thừa" trong REPEATABLE_SECTIONS, serialize và append
     *
     * Cách hoạt động (tương tự "kiểm kê kho"):
     *   1. Với mỗi REPEATABLE_SECTION (VD: "worlds")
     *   2. Lấy danh sách child trong user config (VD: world, world_nether, world_the_end)
     *   3. Lấy danh sách child trong default config (VD: world)
     *   4. Tìm hiệu: user - default = extra children (VD: world_nether, world_the_end)
     *   5. Serialize mỗi extra child thành YAML text và append vào cuối section
     *
     * @param lines         Danh sách dòng đã merge (chưa có extra sections)
     * @param userConfig    Config user (có thể có section extra)
     * @param defaultConfig Config default (template gốc)
     * @return Danh sách dòng đã bổ sung extra sections
     */
    private List<String> appendExtraUserSections(List<String> lines,
                                                  YamlConfiguration userConfig,
                                                  YamlConfiguration defaultConfig) {
        // Thu thập tất cả extra sections cần append, nhóm theo parent section
        // Key: parent section name (VD: "worlds")
        // Value: Map<childName, ConfigurationSection> (VD: "world_nether" → section data)
        Map<String, Map<String, ConfigurationSection>> extrasMap = new LinkedHashMap<>();

        for (String parentPath : REPEATABLE_SECTIONS) {
            ConfigurationSection userParent = userConfig.getConfigurationSection(parentPath);
            ConfigurationSection defaultParent = defaultConfig.getConfigurationSection(parentPath);

            if (userParent == null) continue; // User không có section này → skip

            // Lấy danh sách child key trong default (có thể null nếu default không có section)
            Set<String> defaultChildren = (defaultParent != null)
                    ? defaultParent.getKeys(false)
                    : Collections.emptySet();

            // Tìm child mà user có nhưng default KHÔNG có
            Map<String, ConfigurationSection> extraChildren = new LinkedHashMap<>();
            for (String childKey : userParent.getKeys(false)) {
                if (!defaultChildren.contains(childKey)) {
                    ConfigurationSection childSection = userParent.getConfigurationSection(childKey);
                    if (childSection != null) {
                        extraChildren.put(childKey, childSection);
                    }
                }
            }

            if (!extraChildren.isEmpty()) {
                extrasMap.put(parentPath, extraChildren);
            }
        }

        // Nếu không có extra section nào → trả về nguyên lines
        if (extrasMap.isEmpty()) {
            return lines;
        }

        // Tìm vị trí cuối cùng của mỗi parent section trong output,
        // rồi insert extra children vào đó
        List<String> result = new ArrayList<>(lines);

        for (Map.Entry<String, Map<String, ConfigurationSection>> entry : extrasMap.entrySet()) {
            String parentPath = entry.getKey();
            Map<String, ConfigurationSection> extraChildren = entry.getValue();

            // Tìm vị trí cuối cùng thuộc parent section trong output
            int insertPos = findEndOfSection(result, parentPath);

            if (insertPos < 0) {
                // Không tìm thấy section → append cuối file
                insertPos = result.size();
            }

            // Xác định indent level của child (= indent của parent + 2)
            int parentIndent = findSectionIndent(result, parentPath);
            int childIndent = parentIndent + 2;

            // Serialize và insert từng extra child
            List<String> extraLines = new ArrayList<>();
            for (Map.Entry<String, ConfigurationSection> child : extraChildren.entrySet()) {
                // Dòng trống phân cách cho dễ đọc
                extraLines.add("");
                // Header: "  world_nether:"
                extraLines.add(" ".repeat(childIndent) + child.getKey() + ":");
                // Serialize toàn bộ nội dung section con
                serializeSection(child.getValue(), childIndent + 2, extraLines);

                logger.info("[Migration] Giữ lại section do user tự thêm: "
                        + parentPath + "." + child.getKey());
            }

            // Insert extra lines vào vị trí đúng
            result.addAll(insertPos, extraLines);
        }

        return result;
    }

    /**
     * Tìm dòng cuối cùng thuộc 1 section trong output.
     *
     * Cách xác định: Tìm dòng header "parentKey:" (indent = 0 cho top-level),
     * rồi duyệt xuống dưới cho đến khi gặp dòng có indent <= parent indent
     * (tức là đã ra khỏi section đó).
     *
     * @param lines      Danh sách dòng output
     * @param sectionPath Path section cần tìm (VD: "worlds")
     * @return Index sau dòng cuối cùng của section, hoặc -1 nếu không tìm thấy
     */
    private int findEndOfSection(List<String> lines, String sectionPath) {
        // Tìm dòng header "sectionPath:" (chỉ hỗ trợ top-level section cho đơn giản)
        String headerPattern = sectionPath + ":";
        int headerIndex = -1;
        int headerIndent = -1;

        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.equals(headerPattern) || trimmed.startsWith(headerPattern + " ")) {
                headerIndex = i;
                headerIndent = getIndentLevel(lines.get(i));
                break;
            }
        }

        if (headerIndex < 0) return -1;

        // Duyệt xuống tìm dòng cuối cùng thuộc section này
        int lastLine = headerIndex;
        for (int i = headerIndex + 1; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();

            // Bỏ qua dòng trống và comment
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                lastLine = i;
                continue;
            }

            int currentIndent = getIndentLevel(lines.get(i));

            // Nếu indent > header → vẫn thuộc section này
            if (currentIndent > headerIndent) {
                lastLine = i;
            } else {
                // Đã ra khỏi section → dừng
                break;
            }
        }

        return lastLine + 1; // Trả về vị trí SAU dòng cuối
    }

    /**
     * Tìm indent level của 1 section header trong output.
     *
     * @param lines       Danh sách dòng output
     * @param sectionPath Path cần tìm (VD: "worlds")
     * @return Indent level, hoặc 0 nếu không tìm thấy
     */
    private int findSectionIndent(List<String> lines, String sectionPath) {
        String headerPattern = sectionPath + ":";
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.equals(headerPattern) || trimmed.startsWith(headerPattern + " ")) {
                return getIndentLevel(line);
            }
        }
        return 0;
    }

    /**
     * Serialize 1 ConfigurationSection thành danh sách dòng YAML.
     *
     * Đệ quy duyệt toàn bộ section tree:
     *   - Leaf value → "indent + key: value"
     *   - Section → "indent + key:" rồi đệ quy vào trong
     *   - List → "indent + key:" rồi ghi từng "- item"
     *
     * @param section Section cần serialize
     * @param indent  Số space đầu dòng
     * @param output  List để append kết quả
     */
    private void serializeSection(ConfigurationSection section, int indent, List<String> output) {
        String indentStr = " ".repeat(indent);

        for (String key : section.getKeys(false)) {
            if (section.isConfigurationSection(key)) {
                // Sub-section → ghi header rồi đệ quy
                ConfigurationSection child = section.getConfigurationSection(key);
                output.add(indentStr + key + ":");
                if (child != null) {
                    serializeSection(child, indent + 2, output);
                }

            } else if (section.isList(key)) {
                // List → ghi header rồi ghi từng item
                output.add(indentStr + key + ":");
                List<?> list = section.getList(key);
                if (list != null) {
                    String listIndentStr = " ".repeat(indent + 2);
                    for (Object item : list) {
                        output.add(listIndentStr + "- " + formatYamlValue(item));
                    }
                }

            } else {
                // Leaf value → ghi "key: value"
                Object value = section.get(key);
                output.add(indentStr + key + ": " + formatYamlValue(value));
            }
        }
    }

    // ==================== HELPER METHODS ====================

    /**
     * Đọc tất cả dòng từ file resource trong JAR.
     */
    private List<String> readLinesFromJar(String fileName) {
        List<String> lines = new ArrayList<>();
        InputStream stream = plugin.getResource(fileName);
        if (stream == null) return lines;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException e) {
            logger.warning("[Migration] Lỗi đọc " + fileName + " từ JAR: " + e.getMessage());
        }
        return lines;
    }

    /**
     * Tính số khoảng trắng đầu dòng (indent level).
     * VD: "    enabled: true" → indent = 4
     */
    private int getIndentLevel(String line) {
        int count = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') count++;
            else break;
        }
        return count;
    }

    /**
     * Trích xuất inline comment từ dòng YAML.
     * VD: "cooldown-seconds: 60   # Thời gian cooldown" → "# Thời gian cooldown"
     *
     * Cẩn thận: '#' bên trong quote KHÔNG phải comment.
     * VD: "text: 'Hello #world'" → null (không có comment)
     */
    private String extractInlineComment(String line) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        // Bỏ qua phần key trước ':'
        int colonIdx = line.indexOf(':');
        if (colonIdx < 0) return null;

        String afterColon = line.substring(colonIdx + 1);
        for (int i = 0; i < afterColon.length(); i++) {
            char c = afterColon.charAt(i);
            if (c == '\'' && !inDoubleQuote) inSingleQuote = !inSingleQuote;
            if (c == '"' && !inSingleQuote) inDoubleQuote = !inDoubleQuote;
            if (c == '#' && !inSingleQuote && !inDoubleQuote) {
                // YAML spec: '#' phải có space trước mới là comment
                if (i > 0 && afterColon.charAt(i - 1) == ' ') {
                    return afterColon.substring(i).trim();
                }
            }
        }
        return null;
    }

    /**
     * Format giá trị Java thành chuỗi YAML hợp lệ.
     * Boolean/Number → giữ nguyên. String đặc biệt → bọc quote.
     */
    private String formatYamlValue(Object value) {
        if (value == null) return "";

        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }

        if (value instanceof String str) {
            if (needsQuoting(str)) {
                return "\"" + str.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            }
            return str;
        }

        return value.toString();
    }

    /**
     * Kiểm tra string có cần quote trong YAML không.
     * Cần quote khi: rỗng, giống boolean/null/number, chứa ký tự đặc biệt,
     * hoặc có khoảng trắng đầu/cuối.
     */
    private boolean needsQuoting(String str) {
        if (str.isEmpty()) return true;

        // Giống boolean hoặc null
        String lower = str.toLowerCase();
        if (Set.of("true", "false", "null", "~", "yes", "no").contains(lower)) {
            return true;
        }

        // Giống number
        try {
            Double.parseDouble(str);
            return true;
        } catch (NumberFormatException ignored) {
        }

        // Chứa ký tự đặc biệt YAML
        for (char c : str.toCharArray()) {
            if (":#[]{}|>&*!%@,`".indexOf(c) >= 0) return true;
        }

        // Khoảng trắng đầu/cuối
        return str.startsWith(" ") || str.endsWith(" ");
    }

    /**
     * Thêm inline comment vào cuối dòng, căn chỉnh cho đẹp.
     */
    private String padWithComment(String line, String comment) {
        int targetColumn = 40;
        int padding = Math.max(1, targetColumn - line.length());
        return line + " ".repeat(padding) + comment;
    }

    /**
     * Record theo dõi YAML path khi duyệt file theo dòng.
     * Tương tự "breadcrumb": biết đang ở đâu trong cây YAML.
     */
    private record PathEntry(int indent, String path) {
    }
}

