package com.lyrinth.model;

/**
 * Record chứa cấu hình chống lạm dụng (Anti-Abuse).
 *
 * @param enabled                Bật/tắt hệ thống anti-abuse
 * @param cooldownSeconds        Thời gian cooldown (giây) giữa 2 lần nhận tiền từ cùng 1 người
 * @param checkIp                Kiểm tra IP trùng giữa sát thủ và nạn nhân
 * @param minVictimBalance       Số dư tối thiểu nạn nhân phải có để sát thủ nhận tiền
 * @param rapidDeathProtection   Bật bảo vệ khi player bị chết liên tục trong cooldown window
 *                               (dùng chung cooldownSeconds) — áp dụng cho mọi loại chết
 */
public record AntiAbuseRule(
        boolean enabled,
        int cooldownSeconds,
        boolean checkIp,
        double minVictimBalance,
        boolean rapidDeathProtection
) {
}
