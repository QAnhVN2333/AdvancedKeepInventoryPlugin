package com.lyrinth.model;

/**
 * Record chứa cấu hình blanket-keep (giữ đồ toàn bộ) cho 1 thế giới.
 *
 * @param onPlayerKilled   Giữ toàn bộ đồ khi bị người chơi giết (PvP)
 * @param onMobKilled      Giữ toàn bộ đồ khi bị mob giết (PvE)
 * @param onNaturalDeath   Giữ toàn bộ đồ khi chết tự nhiên
 * @param keepEquippedArmor Luôn giữ giáp đang mặc (độc lập với inventory)
 * @param keepMainHand     Luôn giữ vật phẩm đang cầm trên tay chính
 */
public record BlanketKeepRule(
        boolean onPlayerKilled,
        boolean onMobKilled,
        boolean onNaturalDeath,
        boolean keepEquippedArmor,
        boolean keepMainHand
) {

    /**
     * Kiểm tra xem có nên giữ toàn bộ đồ dựa trên nguyên nhân chết không.
     * Giống như 1 công tắc tổng: nếu bật -> giữ hết, không cần kiểm tra item rules.
     */
    public boolean shouldKeepAll(DeathCause cause) {
        return switch (cause) {
            case PLAYER -> onPlayerKilled;
            case MOB -> onMobKilled;
            case NATURAL -> onNaturalDeath;
        };
    }
}

