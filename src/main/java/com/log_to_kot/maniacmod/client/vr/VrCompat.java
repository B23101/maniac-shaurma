package com.log_to_kot.maniacmod.client.vr;

import com.log_to_kot.maniacmod.vr.VrClientConfig;

import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.ModList;

/**
 * Єдина точка зв'язку мода з Vivecraft (VR). Vivecraft — НЕОБОВ'ЯЗКОВА
 * залежність (compileOnly, як і Simple Voice Chat): без нього мод працює
 * як раніше.
 *
 * ── Чому два класи ──────────────────────────────────────────────────
 * Будь-який клас, у коді якого є посилання на {@code org.vivecraft.*},
 * кине NoClassDefFoundError у гравця без Vivecraft, щойно JVM його
 * завантажить. Тому ВСІ звернення до Vivecraft лежать у {@link VrCompatImpl},
 * а цей клас лише перевіряє {@link ModList} і викликає Impl тільки якщо
 * мод справді є. Інші частини мода викликають ТІЛЬКИ цей клас.
 */
@OnlyIn(Dist.CLIENT)
public final class VrCompat {

    private static final boolean VIVECRAFT_LOADED = ModList.get().isLoaded("vivecraft");

    private VrCompat() {}

    /** Чи встановлено Vivecraft (не плутати з «гравець зараз у VR»). */
    public static boolean isInstalled() {
        return VIVECRAFT_LOADED;
    }

    /**
     * Чи гравець ЗАРАЗ у VR-режимі. Саме цим треба вимикати примусове
     * керування камерою: у VR камера = голова гравця, крутити її силоміць
     * викликає нудоту.
     */
    public static boolean isVrActive() {
        return VIVECRAFT_LOADED && VrCompatImpl.isVrActive();
    }

    /**
     * Чи повноекранні ефекти (удар, шипи, дим, віньєтка) малюються НА ОЧІ —
     * тоді в HUD-проході їх треба пропускати. Див. {@link VrEyeEffects}.
     */
    public static boolean eyeEffects() {
        return isVrActive() && VrClientConfig.eyeEffects();
    }

    /** Вібрація контролера; в не-VR нічого не робить. */
    public static void haptic(Hand hand, float seconds, float amplitude) {
        if (!VIVECRAFT_LOADED || !VrClientConfig.haptics()) return;
        VrCompatImpl.haptic(hand, seconds, amplitude);
    }

    /** Позиція головного контролера у світі або null (не VR / немає даних). */
    public static Vec3 mainHandPos() {
        return VIVECRAFT_LOADED ? VrCompatImpl.mainHandPos() : null;
    }

    /** Напрям головного контролера (для ліхтарика) або null. */
    public static Vec3 mainHandDir() {
        return VIVECRAFT_LOADED ? VrCompatImpl.mainHandDir() : null;
    }

    public enum Hand { MAIN, OFF }
}
