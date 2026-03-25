package com.lyrinth.manager;

import com.lyrinth.AdvancedKeepInventory;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * DeathCacheManager — "Sổ cái" an toàn cho items khi chết.
 *
 * Ý tưởng: Giống ngân hàng ghi sổ cái trước giao dịch.
 * Nếu server crash giữa chừng, vẫn khôi phục được từ file.
 *
 * Flow:
 *   Player chết → serialize items → ghi file YAML (flush to disk) → lưu RAM (như cũ)
 *   Player respawn → lấy từ RAM → xóa file
 *   Server restart → scan thư mục → trả items cho player khi join lại
 *
 * Dùng Bukkit YamlConfiguration + ItemStack.serialize()/deserialize()
 * thay vì BukkitObjectOutputStream (deprecated since 1.21).
 * YAML format dễ đọc, dễ debug, hỗ trợ đầy đủ NBT/enchant/meta.
 */
public class DeathCacheManager {

    private final AdvancedKeepInventory plugin;
    private final Logger logger;

    // Thư mục chứa file cache: plugins/AdvancedKeepInventory/death-cache/
    private final Path cacheDir;

    // Danh sách UUID cần recovery khi player join lại (đọc từ file lúc server start)
    private final Set<UUID> pendingRecovery = Collections.synchronizedSet(new HashSet<>());

    public DeathCacheManager(AdvancedKeepInventory plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.cacheDir = plugin.getDataFolder().toPath().resolve("death-cache");

        // Tạo thư mục nếu chưa tồn tại
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            logger.severe("[DeathCache] Không thể tạo thư mục death-cache: " + e.getMessage());
        }
    }

    // ==================== GHI FILE (Khi player chết) ====================

    /**
     * Lưu items xuống file YAML ngay lập tức.
     * Dùng Bukkit YamlConfiguration — hỗ trợ đầy đủ NBT/enchant/meta, không deprecated.
     *
     * File name: <uuid>.yml
     * Cấu trúc file:
     *   player-name: "Steve"
     *   death-time: 1708512000000
     *   items:
     *     '0':
     *       ==: org.bukkit.inventory.ItemStack
     *       type: DIAMOND_SWORD
     *       ...
     *
     * @param playerUUID UUID người chơi
     * @param items      Danh sách ItemStack cần lưu
     */
    public void saveToDisk(UUID playerUUID, List<ItemStack> items) {
        if (items == null || items.isEmpty()) return;

        Path filePath = cacheDir.resolve(playerUUID.toString() + ".yml");

        try {
            YamlConfiguration yaml = new YamlConfiguration();

            // Ghi metadata để debug (không bắt buộc cho recovery, nhưng hữu ích)
            Player player = Bukkit.getPlayer(playerUUID);
            if (player != null) {
                yaml.set("player-name", player.getName());
            }
            yaml.set("death-time", System.currentTimeMillis());

            // Ghi từng ItemStack vào section "items" (Bukkit tự serialize đầy đủ)
            for (int i = 0; i < items.size(); i++) {
                yaml.set("items." + i, items.get(i));
            }
            yaml.set("item-count", items.size());

            // Ghi file xuống disk
            yaml.save(filePath.toFile());

            if (plugin.getConfigManager().isDebug()) {
                logger.info("[DeathCache] Đã lưu " + items.size() + " item(s) cho "
                        + playerUUID + " xuống disk.");
            }

        } catch (IOException e) {
            logger.severe("[DeathCache] Lỗi ghi file cho " + playerUUID + ": " + e.getMessage());
        }
    }

    // ==================== ĐỌC FILE (Recovery khi crash) ====================

    /**
     * Đọc items từ file YAML.
     * Trả về danh sách ItemStack, hoặc null nếu file không tồn tại/lỗi.
     *
     * @param playerUUID UUID người chơi
     * @return List<ItemStack> hoặc null
     */
    public List<ItemStack> loadFromDisk(UUID playerUUID) {
        Path filePath = cacheDir.resolve(playerUUID.toString() + ".yml");

        // Không có file = không có gì cần recovery
        if (!Files.exists(filePath)) return null;

        List<ItemStack> items = new ArrayList<>();

        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(filePath.toFile());

            int count = yaml.getInt("item-count", 0);

            // Đọc từng ItemStack từ section "items"
            for (int i = 0; i < count; i++) {
                ItemStack item = yaml.getItemStack("items." + i);
                if (item != null) {
                    items.add(item);
                }
            }

            if (plugin.getConfigManager().isDebug()) {
                logger.info("[DeathCache] Đọc được " + items.size() + " item(s) cho "
                        + playerUUID + " từ disk.");
            }

        } catch (Exception e) {
            // File bị corrupt → log error, xóa file hỏng
            logger.severe("[DeathCache] File bị lỗi cho " + playerUUID + ": " + e.getMessage());
            deleteFromDisk(playerUUID);
            return null;
        }

        return items.isEmpty() ? null : items;
    }

    // ==================== XÓA FILE (Sau khi trả items thành công) ====================

    /**
     * Xóa file cache sau khi đã trả items cho player thành công.
     *
     * @param playerUUID UUID người chơi
     */
    public void deleteFromDisk(UUID playerUUID) {
        Path filePath = cacheDir.resolve(playerUUID.toString() + ".yml");

        try {
            boolean deleted = Files.deleteIfExists(filePath);
            if (deleted && plugin.getConfigManager().isDebug()) {
                logger.info("[DeathCache] Đã xóa file cache cho " + playerUUID);
            }
        } catch (IOException e) {
            logger.warning("[DeathCache] Không thể xóa file cho " + playerUUID + ": " + e.getMessage());
        }

        // Xóa khỏi danh sách pending recovery
        pendingRecovery.remove(playerUUID);
    }

    // ==================== RECOVERY (Khi server restart) ====================

    /**
     * Quét thư mục death-cache/ để tìm file chưa được xóa (= items chưa trả).
     * Gọi trong onEnable() của plugin.
     *
     * Logic:
     *   - Player đang online? → Trả items ngay
     *   - Player offline? → Thêm vào pendingRecovery, trả khi họ join
     */
    public void recoverOnStartup() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDir, "*.yml")) {
            int recovered = 0;
            int pending = 0;

            for (Path file : stream) {
                // Lấy UUID từ tên file (bỏ đuôi .yml)
                String fileName = file.getFileName().toString();
                String uuidStr = fileName.replace(".yml", "");

                UUID playerUUID;
                try {
                    playerUUID = UUID.fromString(uuidStr);
                } catch (IllegalArgumentException e) {
                    // File có tên không hợp lệ → bỏ qua
                    logger.warning("[DeathCache] File không hợp lệ, bỏ qua: " + fileName);
                    continue;
                }

                // Kiểm tra player có đang online không
                Player player = Bukkit.getPlayer(playerUUID);
                if (player != null && player.isOnline()) {
                    // Player online → trả items ngay
                    restoreItems(player);
                    recovered++;
                } else {
                    // Player offline → đánh dấu chờ recovery khi join
                    pendingRecovery.add(playerUUID);
                    pending++;
                }
            }

            if (recovered > 0 || pending > 0) {
                logger.info("[DeathCache] Recovery: trả ngay " + recovered
                        + " player(s), chờ " + pending + " player(s) join lại.");
            }

        } catch (IOException e) {
            logger.severe("[DeathCache] Lỗi quét thư mục recovery: " + e.getMessage());
        }
    }

    /**
     * Kiểm tra và trả items cho player khi join.
     * Gọi từ PlayerJoinEvent listener.
     *
     * @param player Player vừa join
     * @return true nếu có items được trả, false nếu không
     */
    public boolean tryRecoverOnJoin(Player player) {
        UUID uuid = player.getUniqueId();

        // Không nằm trong danh sách recovery → bỏ qua
        if (!pendingRecovery.contains(uuid)) return false;

        return restoreItems(player);
    }

    /**
     * Đọc items từ file và trả lại cho player.
     * Nếu inventory đầy → drop item ra đất.
     *
     * @param player Player cần trả items
     * @return true nếu trả thành công
     */
    private boolean restoreItems(Player player) {
        UUID uuid = player.getUniqueId();
        List<ItemStack> items = loadFromDisk(uuid);

        if (items == null || items.isEmpty()) {
            // File rỗng hoặc lỗi → xóa khỏi pending
            pendingRecovery.remove(uuid);
            return false;
        }

        // Trả items vào inventory, nếu đầy thì drop ra đất
        for (ItemStack item : items) {
            var leftover = player.getInventory().addItem(item);
            leftover.values().forEach(remaining ->
                    player.getWorld().dropItemNaturally(player.getLocation(), remaining)
            );
        }

        // Trả xong → xóa file + xóa khỏi pending
        deleteFromDisk(uuid);

        logger.info("[DeathCache] Đã recovery " + items.size() + " item(s) cho "
                + player.getName() + " từ file backup.");

        return true;
    }

    /**
     * Kiểm tra player có đang chờ recovery không.
     */
    public boolean hasPendingRecovery(UUID playerUUID) {
        return pendingRecovery.contains(playerUUID);
    }

    /**
     * Lấy số lượng player đang chờ recovery.
     */
    public int getPendingCount() {
        return pendingRecovery.size();
    }
}

