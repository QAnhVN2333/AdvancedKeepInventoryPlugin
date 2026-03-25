package com.lyrinth.model;

/**
 * Record chứa cấu hình kinh tế (tiền) khi chết cho 1 thế giới.
 *
 * @param activate     Bật/tắt tính năng trừ tiền
 * @param type         Loại economy: "Vault" hoặc "PlayerPoints"
 * @param percent      Phần trăm tài sản bị trừ (0.1 = 10%)
 * @param maxLoss      Số tiền tối đa bị trừ
 * @param giveToKiller Chuyển tiền cho sát thủ (chỉ áp dụng PvP)
 */
public record EconomyRule(
        boolean activate,
        String type,
        double percent,
        double maxLoss,
        boolean giveToKiller
) {
}
