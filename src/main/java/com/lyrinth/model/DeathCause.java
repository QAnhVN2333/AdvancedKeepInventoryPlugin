package com.lyrinth.model;

/**
 * Enum phân loại nguyên nhân cái chết (Death Cause).
 * Chia thành 3 loại chính: PvP, PvE (mob), và tự nhiên (ngã, lava, chết đuối...).
 */
public enum DeathCause {
    PLAYER,   // Bị người chơi khác giết (PvP)
    MOB,      // Bị mob giết (PvE)
    NATURAL   // Chết do môi trường (lava, ngã, chết đuối, v.v.)
}

