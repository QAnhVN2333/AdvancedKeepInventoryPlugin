package com.lyrinth.model;

/**
 * NotificationConfig - Lưu cấu hình hiển thị cho 1 loại thông báo.
 *
 * Tương tự một "bảng cài đặt" cho mỗi message:
 *   - Hiển thị kiểu gì? (chat / actionbar / title / bossbar)
 *   - Nếu là TITLE: hiển thị bao lâu? (fadeIn, stay, fadeOut)
 *   - Nếu là BOSSBAR: màu gì? kiểu thanh gì? tồn tại bao lâu?
 *   - Nếu là ACTIONBAR: tồn tại bao lâu? (ticks)
 *
 * Dùng record để immutable và gọn code.
 *
 * @param type        Cách hiển thị (CHAT / ACTIONBAR / TITLE / BOSSBAR)
 *
 * -- Tùy chọn cho TITLE --
 * @param titleFadeIn  Thời gian fade in (ticks, 20 ticks = 1 giây)
 * @param titleStay    Thời gian hiển thị chính (ticks)
 * @param titleFadeOut Thời gian fade out (ticks)
 * @param subtitle     Chuỗi phụ hiển thị dưới title (MiniMessage format, có thể rỗng)
 *
 * -- Tùy chọn cho BOSSBAR --
 * @param bossBarColor  Màu của boss bar (từ BossBar.Color enum của Adventure)
 * @param bossBarOverlay Kiểu thanh (PROGRESS, NOTCHED_6, v.v.)
 * @param bossBarSeconds Thời gian tự ẩn boss bar (giây), 0 = không tự ẩn
 */
public record NotificationConfig(
        DisplayType type,

        // TITLE options
        int titleFadeIn,
        int titleStay,
        int titleFadeOut,
        String subtitle,

        // BOSSBAR options
        String bossBarColor,
        String bossBarOverlay,
        int bossBarSeconds
) {

    /**
     * Tạo config mặc định: gửi vào CHAT, không có cấu hình phụ.
     * Đây là "factory method" - giống như nút "Reset về mặc định".
     */
    public static NotificationConfig defaultChat() {
        return new NotificationConfig(
                DisplayType.CHAT,
                10, 70, 20,       // title defaults (không dùng)
                "",               // subtitle (không dùng)
                "RED",            // bossbar color (không dùng)
                "SOLID",          // bossbar overlay (không dùng)
                5                 // bossbar seconds (không dùng)
        );
    }
}
