package com.lyrinth.model;

/**
 * Record tổng hợp toàn bộ cấu hình cho 1 thế giới (world).
 * Đây là "bản thiết kế" cho từng world, được cache vào RAM.
 *
 * @param enabled                  Bật/tắt plugin cho thế giới này
 * @param respectVanillaGamerule   Tôn trọng Vanilla gamerule keepInventory (nếu true, plugin sẽ bỏ qua khi gamerule bật)
 * @param blanketKeep              Luật giữ đồ toàn bộ (blanket rules)
 * @param itemRule                 Luật drop/keep vật phẩm chi tiết
 * @param economyRule              Luật trừ tiền khi chết
 * @param expRule                  Luật trừ kinh nghiệm khi chết
 */
public record WorldRule(
        boolean enabled,
        boolean respectVanillaGamerule,
        BlanketKeepRule blanketKeep,
        ItemRule itemRule,
        EconomyRule economyRule,
        ExpRule expRule
) {
}
