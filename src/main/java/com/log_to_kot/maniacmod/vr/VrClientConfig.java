package com.log_to_kot.maniacmod.vr;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Клієнтські налаштування VR (файл {@code config/maniacmod-vr-client.toml}).
 * Діють лише в VR і лише на клієнті; на сервері файл не створюється.
 *
 * Лише {@link ForgeConfigSpec} і примітиви — клас безпечно вантажиться й на
 * виділеному сервері (його реєструє конструктор {@code ManiacMod}).
 */
public final class VrClientConfig {

    public static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.BooleanValue HUD_HEAD_LOCK;
    private static final ForgeConfigSpec.BooleanValue EYE_EFFECTS;
    private static final ForgeConfigSpec.BooleanValue HAPTICS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("vr");
        HUD_HEAD_LOCK = b
            .comment("Під час матчу тримати HUD (слоти, панелі, оверлеї) перед обличчям, а не на зап'ясті.",
                     "Вимикає в Vivecraft вибір слота дотиком руки (слот міняють кнопками).")
            .define("hudHeadLock", true);
        EYE_EFFECTS = b
            .comment("Малювати удар, шипи, дим і віньєтку хп на кожне око, а не на площині HUD.")
            .define("eyeEffects", true);
        HAPTICS = b
            .comment("Вібрація контролерів (удари, шипи, дріт, серцебиття).")
            .define("haptics", true);
        b.pop();
        SPEC = b.build();
    }

    private VrClientConfig() {}

    public static boolean hudHeadLock() { return read(HUD_HEAD_LOCK); }
    public static boolean eyeEffects()  { return read(EYE_EFFECTS); }
    public static boolean haptics()     { return read(HAPTICS); }

    /** Значення можна читати лише після завантаження конфігу; до того — типове (true). */
    private static boolean read(ForgeConfigSpec.BooleanValue value) {
        try {
            return value.get();
        } catch (IllegalStateException notLoadedYet) {
            return true;
        }
    }
}
