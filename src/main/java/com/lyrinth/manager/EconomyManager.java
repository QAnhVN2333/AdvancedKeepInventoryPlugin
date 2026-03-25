package com.lyrinth.manager;

import com.lyrinth.AdvancedKeepInventory;
import net.milkbowl.vault.economy.Economy;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.PlayerPointsAPI;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * EconomyManager - Quản lý tương tác với Vault API và PlayerPoints.
 * Nếu không có Vault/PlayerPoints, log warning và tắt tính năng tiền.
 * Không throw Exception -> plugin vẫn chạy bình thường.
 */
public class EconomyManager {

    private final AdvancedKeepInventory plugin;
    private final Logger logger;

    // Vault economy instance (có thể null nếu Vault không có)
    private Economy vaultEconomy;

    // PlayerPoints API instance (có thể null nếu PlayerPoints không có)
    private PlayerPointsAPI playerPointsAPI;

    public EconomyManager(AdvancedKeepInventory plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /**
     * Khởi tạo kết nối với Vault và PlayerPoints.
     * Gọi khi plugin enable (sau khi server load xong các plugin khác).
     */
    public void setup() {
        setupVault();
        setupPlayerPoints();
    }

    /**
     * Thiết lập Vault Economy thông qua RegisteredServiceProvider.
     * Nếu Vault không có -> log warning, vaultEconomy = null.
     */
    private void setupVault() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            logger.warning(getMsg("economy.vault-not-found",
                    "Vault không được tìm thấy! Tính năng tiền (Vault) sẽ bị tắt."));
            vaultEconomy = null;
            return;
        }

        RegisteredServiceProvider<Economy> rsp =
                Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            logger.warning(getMsg("economy.vault-no-provider",
                    "Không tìm thấy Economy provider từ Vault! Tính năng tiền (Vault) sẽ bị tắt."));
            vaultEconomy = null;
            return;
        }

        vaultEconomy = rsp.getProvider();
        // Placeholder %type% = tên economy provider (VD: "EssentialsX")
        String connectedMsg = getMsg("economy.vault-connected", "Đã kết nối với Vault Economy: %type%");
        logger.info(connectedMsg.replace("%type%", vaultEconomy.getName()));
    }

    /**
     * Thiết lập PlayerPoints API.
     * Nếu PlayerPoints không có -> log warning, playerPointsAPI = null.
     */
    private void setupPlayerPoints() {
        if (Bukkit.getPluginManager().getPlugin("PlayerPoints") == null) {
            logger.warning(getMsg("economy.playerpoints-not-found",
                    "PlayerPoints không được tìm thấy! Tính năng điểm (PlayerPoints) sẽ bị tắt."));
            playerPointsAPI = null;
            return;
        }

        PlayerPoints playerPoints = (PlayerPoints) Bukkit.getPluginManager().getPlugin("PlayerPoints");
        if (playerPoints != null) {
            playerPointsAPI = playerPoints.getAPI();
            logger.info(getMsg("economy.playerpoints-connected", "Đã kết nối với PlayerPoints API."));
        }
    }

    /**
     * Helper: lấy message từ messages.yml, fallback về chuỗi mặc định nếu key rỗng.
     * Tránh lặp code kiểm tra isEmpty() ở mỗi nơi.
     *
     * @param key      Key trong messages.yml (VD: "economy.vault-not-found")
     * @param fallback Chuỗi dự phòng nếu key không tìm thấy
     */
    private String getMsg(String key, String fallback) {
        String msg = plugin.getConfigManager().getMessage(key);
        return msg.isEmpty() ? fallback : msg;
    }

    // ==================== VAULT METHODS ====================

    /**
     * Kiểm tra Vault có sẵn không.
     */
    public boolean isVaultAvailable() {
        return vaultEconomy != null;
    }

    /**
     * Lấy số dư Vault của người chơi.
     * [DESIGN-05] Đã bỏ parameter playerName thừa — không được dùng ở đâu cả.
     */
    public double getVaultBalance(UUID playerUUID) {
        if (vaultEconomy == null) return 0.0;
        return vaultEconomy.getBalance(Bukkit.getOfflinePlayer(playerUUID));
    }

    /**
     * Trừ tiền Vault của người chơi.
     * PHẢI được gọi trên ASYNC thread (BukkitRunnable async).
     */
    public boolean vaultWithdraw(UUID playerUUID, double amount) {
        if (vaultEconomy == null || amount <= 0) return false;
        return vaultEconomy.withdrawPlayer(Bukkit.getOfflinePlayer(playerUUID), amount).transactionSuccess();
    }

    /**
     * Cộng tiền Vault cho người chơi.
     * PHẢI được gọi trên ASYNC thread (BukkitRunnable async).
     */
    public boolean vaultDeposit(UUID playerUUID, double amount) {
        if (vaultEconomy == null || amount <= 0) return false;
        return vaultEconomy.depositPlayer(Bukkit.getOfflinePlayer(playerUUID), amount).transactionSuccess();
    }

    // ==================== PLAYERPOINTS METHODS ====================

    /**
     * Kiểm tra PlayerPoints có sẵn không.
     */
    public boolean isPlayerPointsAvailable() {
        return playerPointsAPI != null;
    }

    /**
     * Lấy số điểm PlayerPoints của người chơi.
     */
    public int getPlayerPoints(UUID playerUUID) {
        if (playerPointsAPI == null) return 0;
        return playerPointsAPI.look(playerUUID);
    }

    /**
     * Trừ điểm PlayerPoints.
     */
    public boolean playerPointsTake(UUID playerUUID, int amount) {
        if (playerPointsAPI == null || amount <= 0) return false;
        return playerPointsAPI.take(playerUUID, amount);
    }

    /**
     * Cộng điểm PlayerPoints.
     */
    public boolean playerPointsGive(UUID playerUUID, int amount) {
        if (playerPointsAPI == null || amount <= 0) return false;
        return playerPointsAPI.give(playerUUID, amount);
    }

    // ==================== GENERIC METHODS ====================

    /**
     * Lấy số dư dựa trên loại economy ("Vault" hoặc "PlayerPoints").
     * [DESIGN-05] Đã bỏ parameter playerName thừa.
     */
    public double getBalance(UUID playerUUID, String type) {
        return switch (type.toLowerCase()) {
            case "vault" -> getVaultBalance(playerUUID);
            case "playerpoints" -> getPlayerPoints(playerUUID);
            default -> {
                logger.warning("Economy type không hợp lệ: " + type);
                yield 0.0;
            }
        };
    }

    /**
     * Trừ tiền/điểm dựa trên loại economy.
     */
    public boolean withdraw(UUID playerUUID, double amount, String type) {
        return switch (type.toLowerCase()) {
            case "vault" -> vaultWithdraw(playerUUID, amount);
            case "playerpoints" -> playerPointsTake(playerUUID, (int) amount);
            default -> false;
        };
    }

    /**
     * Cộng tiền/điểm dựa trên loại economy.
     */
    public boolean deposit(UUID playerUUID, double amount, String type) {
        return switch (type.toLowerCase()) {
            case "vault" -> vaultDeposit(playerUUID, amount);
            case "playerpoints" -> playerPointsGive(playerUUID, (int) amount);
            default -> false;
        };
    }

    /**
     * Kiểm tra economy type có sẵn không.
     */
    public boolean isAvailable(String type) {
        return switch (type.toLowerCase()) {
            case "vault" -> isVaultAvailable();
            case "playerpoints" -> isPlayerPointsAvailable();
            default -> false;
        };
    }
}
