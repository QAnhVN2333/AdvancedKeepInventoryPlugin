package com.lyrinth.model;

/**
 * DisplayType - Enum định nghĩa các cách hiển thị thông báo cho người chơi.
 *
 * Tương tự như chọn "kênh phát sóng":
 *   CHAT      -> Tin nhắn trong khung chat (mặc định)
 *   ACTIONBAR -> Thanh nhỏ phía trên hotbar (không làm phiền chat)
 *   TITLE     -> Màn hình to chính giữa (title + subtitle)
 *   BOSSBAR   -> Thanh máu boss phía trên màn hình (có màu, có thể tự ẩn)
 */
public enum DisplayType {

    /** Gửi vào chat thông thường */
    CHAT,

    /** Hiển thị trên action bar (phía trên hotbar) */
    ACTIONBAR,

    /** Hiển thị dạng title + subtitle giữa màn hình */
    TITLE,

    /** Hiển thị dạng boss bar phía trên màn hình */
    BOSSBAR;

    /**
     * Parse từ string trong config.
     * Không phân biệt HOA/thường, fallback về CHAT nếu không hợp lệ.
     *
     * @param value Chuỗi từ config (VD: "actionbar", "TITLE")
     * @return DisplayType tương ứng, hoặc CHAT nếu không nhận ra
     */
    public static DisplayType fromString(String value) {
        if (value == null || value.isBlank()) return CHAT;
        try {
            return valueOf(value.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            return CHAT; // Fallback an toàn
        }
    }
}

