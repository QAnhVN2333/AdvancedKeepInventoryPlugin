package com.lyrinth;

import com.lyrinth.command.MainCommand;
import com.lyrinth.listener.DeathListener;
import com.lyrinth.listener.RespawnListener;
import com.lyrinth.manager.*;
import org.bukkit.command.PluginCommand;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AdvancedKeepInventory - Plugin chính.
 * Quản lý hình phạt/phần thưởng khi người chơi chết.
 *
 * Kiến trúc: Main plugin class chỉ làm nhiệm vụ "điều phối" (Orchestrator).
 * Logic thực tế được chia vào các Manager và Listener riêng biệt (SOLID).
 */
public class AdvancedKeepInventory extends JavaPlugin {

    // ==================== MANAGERS ====================
    private ConfigManager configManager;
    private EconomyManager economyManager;
    private AntiAbuseManager antiAbuseManager;
    private MessageManager messageManager;
    private DeathCacheManager deathCacheManager;

    /**
     * Cache tạm chứa items cần trả lại khi respawn.
     * Key: UUID người chơi, Value: danh sách ItemStack đã giữ.
     * Dùng ConcurrentHashMap vì có thể access từ nhiều thread.
     */
    private final Map<UUID, List<ItemStack>> keptItemsCache = new ConcurrentHashMap<>();

    // ==================== LIFECYCLE ====================

    @Override
    public void onEnable() {
        // Bước 1: Khởi tạo ConfigManager và load config vào RAM
        configManager = new ConfigManager(this);
        configManager.loadConfig();

        // Bước 2: Khởi tạo MessageManager (cần plugin để schedule BossBar task)
        messageManager = new MessageManager(this);

        // Bước 3: Khởi tạo EconomyManager (kết nối Vault/PlayerPoints)
        economyManager = new EconomyManager(this);
        economyManager.setup();

        // Bước 4: Khởi tạo AntiAbuseManager
        antiAbuseManager = new AntiAbuseManager(this);

        // Bước 5: Khởi tạo DeathCacheManager + recovery items từ lần crash trước (nếu có)
        deathCacheManager = new DeathCacheManager(this);
        deathCacheManager.recoverOnStartup();

        // Bước 6: Đăng ký Event Listeners
        getServer().getPluginManager().registerEvents(new DeathListener(this), this);
        getServer().getPluginManager().registerEvents(new RespawnListener(this), this);

        // Bước 7: Đăng ký Commands
        registerCommands();

        // Dùng message từ messages.yml, fallback nếu chưa có
        String enabledMsg = configManager.getMessage("plugin.enabled");
        getLogger().info(enabledMsg.isEmpty() ? "AdvancedKeepInventory đã được bật thành công!" : enabledMsg);
    }

    @Override
    public void onDisable() {
        // Xóa cache khi plugin tắt
        keptItemsCache.clear();
        // Lưu ý: KHÔNG xóa file trong death-cache/ khi disable.
        // File sẽ được recovery khi server restart (recoverOnStartup).

        String disabledMsg = configManager != null ? configManager.getMessage("plugin.disabled") : "";
        getLogger().info(disabledMsg.isEmpty() ? "AdvancedKeepInventory đã được tắt." : disabledMsg);
    }

    /**
     * Đăng ký command /advancedkeepinventory (alias: /aki).
     */
    private void registerCommands() {
        PluginCommand cmd = getCommand("advancedkeepinventory");
        if (cmd != null) {
            MainCommand mainCommand = new MainCommand(this);
            cmd.setExecutor(mainCommand);
            cmd.setTabCompleter(mainCommand);
        } else {
            String failMsg = configManager.getMessage("plugin.command-register-fail");
            getLogger().severe(failMsg.isEmpty()
                    ? "Không thể đăng ký command 'advancedkeepinventory'! Kiểm tra plugin.yml."
                    : failMsg);
        }
    }

    // ==================== KEPT ITEMS CACHE ====================

    /**
     * Lưu danh sách items cần trả lại khi respawn.
     * Gọi bởi DeathListener khi xử lý death event.
     *
     * [CRASH-SAFE] Ghi file backup xuống disk TRƯỚC khi lưu RAM.
     * Nếu server crash trước respawn, items vẫn recovery được từ file.
     */
    public void storeKeptItems(UUID playerUUID, List<ItemStack> items) {
        // Bước 1: Ghi file backup xuống disk (safety net cho crash)
        deathCacheManager.saveToDisk(playerUUID, items);

        // Bước 2: Lưu vào RAM (để respawn nhanh, không cần đọc file)
        keptItemsCache.put(playerUUID, items);
    }

    /**
     * Lấy và xóa danh sách items đã giữ (dùng khi respawn).
     * Trả về null nếu không có items nào.
     *
     * [CRASH-SAFE] Nếu RAM trống (do crash/restart), fallback đọc từ file.
     * Sau khi trả xong → xóa file backup.
     */
    public List<ItemStack> retrieveKeptItems(UUID playerUUID) {
        // Bước 1: Ưu tiên lấy từ RAM (nhanh nhất)
        List<ItemStack> items = keptItemsCache.remove(playerUUID);

        // Bước 2: Nếu RAM trống → fallback đọc từ file (trường hợp sau crash)
        if (items == null) {
            items = deathCacheManager.loadFromDisk(playerUUID);
        }

        // Bước 3: Xóa file backup sau khi lấy thành công
        if (items != null) {
            deathCacheManager.deleteFromDisk(playerUUID);
        }

        return items;
    }

    // ==================== GETTERS ====================

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public EconomyManager getEconomyManager() {
        return economyManager;
    }

    public AntiAbuseManager getAntiAbuseManager() {
        return antiAbuseManager;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }

    public DeathCacheManager getDeathCacheManager() {
        return deathCacheManager;
    }
}
