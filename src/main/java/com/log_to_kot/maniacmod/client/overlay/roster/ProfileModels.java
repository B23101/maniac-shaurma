package com.log_to_kot.maniacmod.client.overlay.roster;

import com.log_to_kot.maniacmod.maniacs.ManiacArchetype;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Публічний міст до 3D-вʼюпортів Tab-екрана для екрана профілю: живий гравець
 * ({@link TabModel3d}) і geo-модель маніяка без сутності ({@link TabGeoView}). Та сама
 * відмовостійкість: перший збій рендера вимикає відповідний вʼюпорт до перезапуску гри.
 */
@OnlyIn(Dist.CLIENT)
public final class ProfileModels {

    private ProfileModels() {}

    public static boolean playerAvailable() { return TabModel3d.available(); }

    public static boolean maniacAvailable() { return TabGeoView.available(); }

    /** @see TabModel3d#render: {@code yawDeg} 180 — обличчям до глядача; {@code baseY} — Y ніг. */
    public static boolean drawPlayer(GuiGraphics g, Entity player, float cx, float baseY,
                                     float scale, float yawDeg, float pitchDeg) {
        return TabModel3d.render(g, player, cx, baseY, scale, yawDeg, pitchDeg);
    }

    /** Маніяк: анімація стояння з його набору; {@code yawDeg} 180 — обличчям до глядача. */
    public static boolean drawManiac(GuiGraphics g, ManiacArchetype archetype, float cx, float baseY,
                                     float scale, float yawDeg) {
        return TabGeoView.drawManiac(g, archetype, cx, baseY, scale, yawDeg);
    }
}
