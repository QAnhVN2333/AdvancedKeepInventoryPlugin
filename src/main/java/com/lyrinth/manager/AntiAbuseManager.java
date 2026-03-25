package com.lyrinth.manager;

import com.lyrinth.AdvancedKeepInventory;
import com.lyrinth.model.AntiAbuseRule;
import org.bukkit.Bukkit;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AntiAbuseManager - Hệ thống chống lạm dụng.
 *
 * Bao gồm 4 cơ chế:
 * 1. Cooldown PvP: Giới hạn thời gian nhận tiền từ cùng 1 người chơi.
 * 2. IP Check: Chặn nếu sát thủ và nạn nhân cùng IP.
 * 3. Minimum Balance: Nạn nhân phải có đủ tiền tối thiểu.
 * 4. Rapid Death Protection: Nếu player chết liên tục trong cooldown window
 *    (mọi loại chết) -> không trừ tiền/EXP lần tiếp theo.
 *
 * Dùng ConcurrentHashMap để thread-safe (vì có thể truy cập từ async).
 *
 * [DESIGN-03] Có task dọn dẹp định kỳ mỗi 5 phút để tránh memory leak.
 * [DESIGN-04] Nhận UUID + IP string thay vì Player object để an toàn trong async context.
 */
public class AntiAbuseManager {

    private final AdvancedKeepInventory plugin;

    /**
     * Cache cooldown giữa các cặp người chơi (PvP).
     * Key: "killerUUID:victimUUID", Value: timestamp (ms) lần cuối nhận tiền.
     */
    private final Map<String, Long> cooldownMap = new ConcurrentHashMap<>();

    /**
     * Cache lần chết gần nhất của từng player (mọi loại chết: PvP, Mob, Natural).
     * Dùng để kiểm tra Rapid Death Protection.
     * Key: playerUUID, Value: timestamp (ms) lần chết gần nhất.
     *
     * Logic: Nếu player chết lại trong vòng cooldownSeconds kể từ lần chết trước
     * -> coi là "rapid death" -> bỏ qua trừ tiền/EXP cho lần chết này.
     */
    private final Map<UUID, Long> rapidDeathMap = new ConcurrentHashMap<>();

    public AntiAbuseManager(AdvancedKeepInventory plugin) {
        this.plugin = plugin;

        // [DESIGN-03] Chạy task dọn dẹp entry hết hạn mỗi 5 phút (6000 ticks)
        // Tránh 2 map phình to vô hạn khi server chạy liên tục nhiều ngày
        startCleanupTask();
    }

    /**
     * [DESIGN-03] Task dọn dẹp entry cooldown đã hết hạn.
     * Chạy async mỗi 5 phút để không ảnh hưởng main thread.
     */
    private void startCleanupTask() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            AntiAbuseRule rule = plugin.getConfigManager().getAntiAbuseRule();
            long now = System.currentTimeMillis();
            long cooldownMs = rule.cooldownSeconds() * 1000L;

            // Dọn PvP cooldown map
            int before = cooldownMap.size();
            cooldownMap.entrySet().removeIf(entry -> now - entry.getValue() > cooldownMs);
            int removedPvp = before - cooldownMap.size();

            // Dọn rapid death map (dùng chung cooldownMs)
            int beforeRapid = rapidDeathMap.size();
            rapidDeathMap.entrySet().removeIf(entry -> now - entry.getValue() > cooldownMs);
            int removedRapid = beforeRapid - rapidDeathMap.size();

            if ((removedPvp > 0 || removedRapid > 0) && plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[AntiAbuse] Dọn dẹp " + removedPvp
                        + " PvP cooldown + " + removedRapid + " rapid death entry hết hạn.");
            }
        }, 20L * 60 * 5, 20L * 60 * 5); // Delay 5 phút, lặp lại mỗi 5 phút
    }

    /**
     * Kiểm tra xem sát thủ có được phép nhận tiền từ nạn nhân không.
     * Kiểm tra tất cả 3 điều kiện anti-abuse PvP.
     *
     * [DESIGN-04] Nhận UUID + IP string thay vì Player object.
     *
     * @param killerUUID    UUID của sát thủ
     * @param victimUUID    UUID của nạn nhân
     * @param killerIp      IP address của sát thủ
     * @param victimIp      IP address của nạn nhân
     * @param victimBalance Số dư hiện tại của nạn nhân
     * @return true nếu được phép nhận tiền, false nếu bị chặn
     */
    public boolean canReceiveMoney(UUID killerUUID, UUID victimUUID,
                                   String killerIp, String victimIp,
                                   double victimBalance) {
        AntiAbuseRule rule = plugin.getConfigManager().getAntiAbuseRule();

        // Nếu anti-abuse bị tắt -> luôn cho phép
        if (!rule.enabled()) return true;

        // Kiểm tra 1: IP Check - Cùng IP thì chặn
        if (rule.checkIp() && hasSameIp(killerIp, victimIp)) {
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[AntiAbuse] Chặn PvP: " + killerUUID
                        + " và " + victimUUID + " cùng IP (" + killerIp + ").");
            }
            return false;
        }

        // Kiểm tra 2: Minimum Balance - Nạn nhân phải có đủ tiền tối thiểu
        if (victimBalance < rule.minVictimBalance()) {
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[AntiAbuse] Chặn PvP: nạn nhân " + victimUUID
                        + " không đủ tiền tối thiểu (" + victimBalance + " < " + rule.minVictimBalance() + ").");
            }
            return false;
        }

        // Kiểm tra 3: Cooldown - Kiểm tra thời gian giữa 2 lần giết cùng người
        String cooldownKey = killerUUID + ":" + victimUUID;
        Long lastKillTime = cooldownMap.get(cooldownKey);

        if (lastKillTime != null) {
            long elapsed = (System.currentTimeMillis() - lastKillTime) / 1000;
            if (elapsed < rule.cooldownSeconds()) {
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info("[AntiAbuse] Chặn PvP Cooldown: " + killerUUID
                            + " -> " + victimUUID + " (còn " + (rule.cooldownSeconds() - elapsed) + "s).");
                }
                return false;
            }
        }

        // Tất cả đều pass -> cho phép và cập nhật PvP cooldown
        cooldownMap.put(cooldownKey, System.currentTimeMillis());
        return true;
    }

    /**
     * Kiểm tra xem có nên trừ tiền/EXP cho player này không (Rapid Death Protection).
     *
     * Cơ chế hoạt động như "death cooldown cá nhân":
     * - Lần chết đầu tiên -> LUÔN bị trừ, và ghi lại timestamp.
     * - Các lần chết tiếp theo trong vòng cooldownSeconds -> bỏ qua, KHÔNG trừ.
     * - Sau khi hết cooldown, lần chết kế tiếp lại bị trừ bình thường.
     *
     * Ví dụ (cooldown = 60s):
     *   T=0s  → Chết lần 1 → TRỪ (ghi timestamp)
     *   T=10s → Chết lần 2 → BỎ QUA (trong 60s cooldown)
     *   T=30s → Chết lần 3 → BỎ QUA (trong 60s cooldown)
     *   T=61s → Chết lần 4 → TRỪ (đã hết cooldown, ghi lại timestamp mới)
     *
     * [DESIGN-04] An toàn để gọi từ async thread vì chỉ dùng UUID (không phải Player).
     *
     * @param playerUUID UUID của player vừa chết
     * @return true nếu nên trừ tiền/EXP, false nếu đang trong rapid death cooldown
     */
    public boolean canChargePlayer(UUID playerUUID) {
        AntiAbuseRule rule = plugin.getConfigManager().getAntiAbuseRule();

        // Nếu anti-abuse tắt hoặc rapid death protection tắt -> luôn trừ
        if (!rule.enabled() || !rule.rapidDeathProtection()) return true;

        long now = System.currentTimeMillis();
        long cooldownMs = rule.cooldownSeconds() * 1000L;

        Long lastDeathTime = rapidDeathMap.get(playerUUID);

        if (lastDeathTime != null) {
            long elapsed = now - lastDeathTime;
            if (elapsed < cooldownMs) {
                // Đang trong rapid death window -> KHÔNG trừ
                long remainingSec = (cooldownMs - elapsed) / 1000;
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info("[AntiAbuse] Rapid Death Protection: chặn trừ tiền/EXP cho "
                            + playerUUID + " (còn " + remainingSec + "s cooldown).");
                }
                return false;
            }
        }

        // Lần chết này được phép trừ -> ghi lại timestamp để bắt đầu đếm cooldown
        rapidDeathMap.put(playerUUID, now);
        return true;
    }

    /**
     * Kiểm tra 2 IP string có giống nhau không.
     * [DESIGN-04] Nhận String thay vì Player — an toàn cho async context.
     * Null-safe: nếu không lấy được IP -> coi như khác IP (cho phép giao dịch).
     */
    private boolean hasSameIp(String ipA, String ipB) {
        if (ipA == null || ipB == null) return false;
        return ipA.equals(ipB);
    }

    /**
     * Xóa toàn bộ cooldown cache (dùng khi reload).
     */
    public void clearCooldowns() {
        cooldownMap.clear();
        rapidDeathMap.clear();
    }
}
