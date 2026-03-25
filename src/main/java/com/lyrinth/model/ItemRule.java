package com.lyrinth.model;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * Record chứa luật drop/keep vật phẩm cho 1 thế giới.
 *
 * @param dropCategories   Danh sách category sẽ bị rơi
 * @param dropItems        Danh sách Material cụ thể sẽ bị rơi
 * @param dropCustomItems  Danh sách custom item (Material:CustomModelData) sẽ bị rơi
 * @param keepCategories   Danh sách category sẽ được giữ (ưu tiên cao hơn drop)
 * @param keepItems        Danh sách Material cụ thể sẽ được giữ
 * @param keepCustomItems  Danh sách custom item (Material:CustomModelData) sẽ được giữ
 */
public record ItemRule(
        Set<ItemCategory> dropCategories,
        Set<Material> dropItems,
        Set<String> dropCustomItems,
        Set<ItemCategory> keepCategories,
        Set<Material> keepItems,
        Set<String> keepCustomItems
) {

    /**
     * Kiểm tra xem 1 ItemStack có nên bị DROP hay không.
     * Logic ưu tiên: KEEP luôn thắng DROP.
     * Nếu item khớp keep -> giữ lại (return false).
     * Nếu item khớp drop -> rơi ra (return true).
     * Nếu không khớp gì cả -> giữ lại (return false) - mặc định keep.
     */
    public boolean shouldDrop(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;

        // Bước 1: Kiểm tra KEEP trước (ưu tiên cao hơn)
        if (matchesKeep(item)) return false;

        // Bước 2: Kiểm tra DROP
        return matchesDrop(item);
    }

    /**
     * Kiểm tra item có khớp với danh sách KEEP không.
     */
    private boolean matchesKeep(ItemStack item) {
        Material mat = item.getType();

        // Kiểm tra custom item trước (chính xác nhất)
        String customKey = getCustomItemKey(item);
        if (customKey != null && keepCustomItems.contains(customKey)) return true;

        // Kiểm tra Material cụ thể
        if (keepItems.contains(mat)) return true;

        // Kiểm tra Category
        ItemCategory category = categorize(mat);
        return keepCategories.contains(category);
    }

    /**
     * Kiểm tra item có khớp với danh sách DROP không.
     */
    private boolean matchesDrop(ItemStack item) {
        Material mat = item.getType();

        // Kiểm tra custom item trước
        String customKey = getCustomItemKey(item);
        if (customKey != null && dropCustomItems.contains(customKey)) return true;

        // Kiểm tra Material cụ thể
        if (dropItems.contains(mat)) return true;

        // Kiểm tra Category
        ItemCategory category = categorize(mat);
        return dropCategories.contains(category);
    }

    /**
     * Tạo key dạng "MATERIAL:CustomModelData" cho custom item.
     * Trả về null nếu item không có CustomModelData.
     */
    @SuppressWarnings("deprecation")
    private String getCustomItemKey(ItemStack item) {
        if (!item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasCustomModelData()) return null;
        return item.getType().name() + ":" + meta.getCustomModelData();
    }

    /**
     * Phân loại Material vào Category tương ứng.
     * Dùng tên Material để xác định nhóm (VD: DIAMOND_SWORD -> SWORD).
     */
    public static ItemCategory categorize(Material mat) {
        String name = mat.name();

        if (name.endsWith("_SWORD")) return ItemCategory.SWORD;

        // Armor: bao gồm helmet, chestplate, leggings, boots, shield, elytra
        if (name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS")
                || mat == Material.SHIELD || mat == Material.ELYTRA
                || name.contains("TURTLE_HELMET")) {
            return ItemCategory.ARMOR;
        }

        if (name.endsWith("_PICKAXE")) return ItemCategory.PICKAXE;
        if (name.endsWith("_AXE") && !name.endsWith("_PICKAXE")) return ItemCategory.AXE;
        if (name.endsWith("_SHOVEL")) return ItemCategory.SHOVEL;
        if (name.endsWith("_HOE")) return ItemCategory.HOE;

        if (mat == Material.BOW) return ItemCategory.BOW;
        if (mat == Material.CROSSBOW) return ItemCategory.CROSSBOW;
        if (mat == Material.TRIDENT) return ItemCategory.TRIDENT;

        // Food: kiểm tra isEdible()
        if (mat.isEdible()) return ItemCategory.FOOD;

        // Potion
        if (name.contains("POTION") || mat == Material.SPLASH_POTION
                || mat == Material.LINGERING_POTION) {
            return ItemCategory.POTION;
        }

        // Mặc định: MISC
        return ItemCategory.MISC;
    }
}

