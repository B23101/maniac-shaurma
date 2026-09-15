package com.log_to_kot.maniacmod.game;

import java.util.UUID;

/**
 * Per-survivor runtime state.
 * Lives: 1–3 (no regen except Medkit or Adrenaline revival)
 * Bound: frozen in place (rope / electric wire / bear trap)
 */
public class SurvivorData {

    public static final int MAX_LIVES = 3;

    private final UUID uuid;
    private int lives = MAX_LIVES;
    private boolean bound = false;
    private int boundTicksRemaining = 0;

    public SurvivorData(UUID uuid) {
        this.uuid = uuid;
    }

    // ── Lives ────────────────────────────────────────────────────────────────

    public int getLives() { return lives; }

    /** Set lives directly (used when reviving at 1 HP). */
    public void setLives(int lives) {
        this.lives = Math.max(0, Math.min(MAX_LIVES, lives));
    }

    /** Returns true if the survivor is now dead (lives == 0). */
    public boolean takeDamage() {
        if (lives > 0) lives--;
        return lives == 0;
    }

    /** Returns true if healed (lives < MAX). */
    public boolean heal() {
        if (lives < MAX_LIVES) {
            lives++;
            return true;
        }
        return false;
    }

    public boolean isDead() { return lives <= 0; }

    // ── Bound state ──────────────────────────────────────────────────────────

    public boolean isBound() { return bound; }

    public void setBound(boolean bound) {
        this.bound = bound;
        if (!bound) boundTicksRemaining = 0;
    }

    public void bindFor(int ticks) {
        this.bound = true;
        this.boundTicksRemaining = ticks;
    }

    public void tick() {
        if (bound && boundTicksRemaining > 0) {
            boundTicksRemaining--;
            if (boundTicksRemaining == 0) bound = false;
        }
    }

    public int getBoundTicksRemaining() { return boundTicksRemaining; }

    public UUID getUUID() { return uuid; }

    // ── Display ──────────────────────────────────────────────────────────────

    public String livesBar() {
        StringBuilder sb = new StringBuilder("§cЖиття: ");
        for (int i = 0; i < MAX_LIVES; i++) {
            sb.append(i < lives ? "§4❤ " : "§8❤ ");
        }
        return sb.toString().trim();
    }
}
