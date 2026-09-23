package com.log_to_kot.maniacmod.client.overlay.notify;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 5 зірочок, що обертаються по колу, — власний HUD оглушеного маньяка
 * (на відміну від {@link ManiacStunWorldMarker}, який малює те саме
 * НАД ГОЛОВОЮ для сторонніх глядачів). Класичний "зірочки перед очима"
 * ефект: коло навколо центру екрана, трохи вище прицілу, щоб не
 * заважати бачити, куди дивишся (хоча в цьому стані дивитись все одно
 * нікуди не можна — {@code MixinMouseHandlerManiacStunned} — але
 * гравцю має бути видно світ під зірочками, а не лише самі зірочки).
 *
 * ── Джерело даних ────────────────────────────────────────────────────
 * {@link ClientMatchState#isManiacStunned()} (без аргументів — "я
 * сам"), той самий пакет, що заповнює й {@link ManiacStunWorldMarker}.
 * Перевірка {@code isManiac()} зайва: {@code isManiacStunned()} за
 * побудовою істинна лише коли {@code maniacId} у пакеті збігався з
 * власним UUID (див. {@code ClientMatchState#setManiacStun}), а це
 * можливо лише для того, хто справді маньяк (сервер шле це поле лише
 * реальному маньяку, що зазнав удару).
 */
@OnlyIn(Dist.CLIENT)
public final class ManiacStunOverlay {

    private static final float OUTER_RADIUS = 8f;
    private static final float RING_RADIUS = 34f;

    /** Один повний оберт за цей час, мс. Трохи швидше за world-версію — зірочки "перед очима", мають виглядати гарячково. */
    private static final long ROTATION_PERIOD_MS = 1400L;

    private static final int STAR_ARGB = 0xFFFFE066;

    private ManiacStunOverlay() {}

    public static void render(GuiGraphics graphics) {
        if (!ClientMatchState.isManiacStunned()) return;

        Minecraft mc = Minecraft.getInstance();
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();
        int cx = width / 2;
        int cy = height / 2 - 20; // трохи вище прицілу

        long now = System.currentTimeMillis();
        float ringRotation = (now % ROTATION_PERIOD_MS) / (float) ROTATION_PERIOD_MS * 360f;

        for (int i = 0; i < 5; i++) {
            double angle = Math.toRadians(ringRotation + i * 72.0);
            float sx = cx + (float) (RING_RADIUS * Math.cos(angle));
            float sy = cy + (float) (RING_RADIUS * 0.5 * Math.sin(angle));
            StarShape.draw(graphics, sx, sy, OUTER_RADIUS, ringRotation * 2f, STAR_ARGB);
        }
    }
}
