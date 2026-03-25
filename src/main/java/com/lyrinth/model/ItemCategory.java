package com.lyrinth.model;

/**
 * Enum phân loại vật phẩm theo nhóm (Category).
 * Dùng để xác định vật phẩm thuộc nhóm nào khi xử lý drop/keep.
 */
public enum ItemCategory {
    SWORD,
    ARMOR,
    PICKAXE,
    AXE,
    SHOVEL,
    HOE,
    BOW,
    CROSSBOW,
    TRIDENT,
    FOOD,
    POTION,
    MISC; // Tất cả những gì không thuộc nhóm nào ở trên

    /**
     * Parse tên category từ String (không phân biệt hoa/thường).
     * Trả về null nếu không tìm thấy.
     */
    public static ItemCategory fromString(String name) {
        if (name == null) return null;
        try {
            return valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
