package com.log_to_kot.maniacmod.vr;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;

/**
 * Спільна (клієнт + сервер) частина сумісності з Vivecraft. Як і
 * {@link com.log_to_kot.maniacmod.client.vr.VrCompat}, не торкається
 * класів Vivecraft напряму — лише через {@link VrServerCompatImpl}, який
 * вантажиться тільки коли мод є.
 *
 * ── Що це дає ────────────────────────────────────────────────────────
 *  • {@link #isVrPlayer} — «цей гравець у VR» (працює для ЧУЖИХ гравців
 *    на клієнті й для будь-якого на сервері, якщо Vivecraft стоїть там).
 *  • {@link #aimOrigin}/{@link #aimDir} — промінь прицілювання. У VR
 *    клієнт цілиться КОНТРОЛЕРОМ (перехрестя = рука), а ваніль на сервері
 *    бере напрямок ГОЛОВИ — без цього підняття предметів, пастки й удари
 *    «промахувались» би повз те, на що гравець показує.
 *
 * ВАЖЛИВО: серверні перевірки бачать VR-гравців лише якщо Vivecraft
 * встановлений і на сервері. Без нього сервер вважає всіх не-VR, і
 * поведінка лишається ванільною (голова) — не падає, але прицілювання
 * рукою не діє.
 */
public final class VrServerCompat {

    private static final boolean LOADED = ModList.get() != null && ModList.get().isLoaded("vivecraft");

    private VrServerCompat() {}

    public static boolean isVrPlayer(Player player) {
        return LOADED && player != null && VrServerCompatImpl.isVrPlayer(player);
    }

    /** Початок променя прицілювання: рука для VR, око для решти. */
    public static Vec3 aimOrigin(Entity e) {
        if (LOADED && e instanceof Player p) {
            Vec3 v = VrServerCompatImpl.handPos(p);
            if (v != null) return v;
        }
        return e.getEyePosition();
    }

    /**
     * Напрямок променя прицілювання: рука для VR, {@code getLookAngle()} для решти
     * (БЕЗ змін відносно коду, який це замінило).
     */
    public static Vec3 aimDir(Entity e) {
        if (LOADED && e instanceof Player p) {
            Vec3 v = VrServerCompatImpl.handDir(p);
            if (v != null) return v;
        }
        return e.getLookAngle();
    }

    /**
     * Те саме, але для місць, де оригінал брав {@code getViewVector(1.0f)} (пастки):
     * у LivingEntity він іде від yHeadRot, а не від yRot, тож це не завжди
     * тотожно {@link #aimDir}. Для не-VR лишаємо рівно те, що було.
     */
    public static Vec3 aimDirView(Entity e) {
        if (LOADED && e instanceof Player p) {
            Vec3 v = VrServerCompatImpl.handDir(p);
            if (v != null) return v;
        }
        return e.getViewVector(1.0f);
    }
}
