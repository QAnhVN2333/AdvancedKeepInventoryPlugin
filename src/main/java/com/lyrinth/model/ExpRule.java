package com.lyrinth.model;

import java.util.Set;

/**
 * Record chứa cấu hình kinh nghiệm (EXP) khi chết cho 1 thế giới.
 *
 * @param activate    Bật/tắt tính năng mất EXP
 * @param percentLoss Phần trăm level bị trừ (0.5 = 50%)
 * @param applyOn     Tập hợp nguyên nhân chết mà EXP loss áp dụng (PLAYER, MOB, NATURAL)
 */
public record ExpRule(
        boolean activate,
        double percentLoss,
        Set<DeathCause> applyOn
) {

    /**
     * Kiểm tra xem có nên trừ EXP cho nguyên nhân chết này không.
     * Nếu applyOn rỗng -> không trừ cho bất kỳ nguyên nhân nào.
     */
    public boolean shouldApply(DeathCause cause) {
        return applyOn.contains(cause);
    }
}
