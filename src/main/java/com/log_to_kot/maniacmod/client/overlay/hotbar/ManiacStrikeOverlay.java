package com.log_to_kot.maniacmod.client.overlay.hotbar;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.UiFx;
import com.mojang.blaze3d.systems.RenderSystem;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.AbilityCooldownPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Locale;

/**
 * Круглий слот удару маньяка — правий нижній кут, з ВІЗУАЛЬНОЮ
 * перезарядкою.
 *
 * ── Чому окремий оверлей, а не частина {@link ManiacHotbarOverlay} ───
 * Хотбар-рендерер малює лише слоти, які lib видає з
 * {@code InventorySlotAllocation}, а у маньяка їх 0 (див. «Слоти
 * інвентаря: 4 у виживого, 0 у маньяка» в {@code ManiacMod}). Тому
 * {@code ManiacHotbarOverlay} для маньяка не малював НІЧОГО — слот удару
 * там був мертвим кодом, і маньяк його ніколи не бачив. Тут слот
 * малюється незалежно від інвентаря — так само, як {@link TrapPanelOverlay}
 * для пасток.
 *
 * ── Вигляд ───────────────────────────────────────────────────────────
 *   • перезарядка — коло ЗАПОВНЮЄТЬСЯ знизу догори червоним; верхня межа
 *     заливки світиться; кігті тьмяні, у центрі — секунди, що лишились;
 *   • готово      — коло світле, яскраві кігті, біле кільце.
 *
 * Джерело даних — той самий {@code AbilityCooldownPacket.ATTACK_ID}, що
 * вже приходить від сервера при кожному ударі.
 */
@OnlyIn(Dist.CLIENT)
public final class ManiacStrikeOverlay {

    private static final int SIZE = 60;
    private static final int MARGIN_RIGHT = 14;
    private static final int MARGIN_BOTTOM = 14;

    private static final int SLOT_FILL      = GeneratorFuelUiTheme.PANEL_BG;
    private static final int RELOAD_FILL    = 0xA0C2C9D6;
    private static final int RELOAD_EDGE    = 0xFFF2F4F8;
    private static final int READY_FILL     = 0x22FFFFFF;
    private static final int RING_RELOAD    = GeneratorFuelUiTheme.PANEL_BORDER;
    private static final int RING_READY     = GeneratorFuelUiTheme.HIGHLIGHT;
    private static final int GLYPH_READY    = GeneratorFuelUiTheme.FILL;
    private static final int GLYPH_RELOAD   = 0x70C2C9D6;

    private ManiacStrikeOverlay() {}

    // ── Анімаційний стан ─────────────────────────────────────────────
    private static final long[] lastFrameNanos = new long[1];
    private static float entrance = 0f;
    private static float readyFlash = 0f;
    private static boolean wasReloading = false;
    private static float hitKick = 0f;
    private static float lastCooldown = 0f;
    private static final UiFx.Sparks SPARKS = new UiFx.Sparks(40);

    private static void resetAnims() {
        lastFrameNanos[0] = 0L;
        entrance = 0f;
        readyFlash = 0f;
        wasReloading = false;
        hitKick = 0f;
        lastCooldown = 0f;
        SPARKS.clear();
    }

    public static void render(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.player.isSpectator()) { resetAnims(); return; }
        if (!ClientMatchState.isManiac()) { resetAnims(); return; }
        if (!ClientMatchState.allows(PhaseRule.HUD)) { resetAnims(); return; }

        float dt = UiAnim.frameDt(lastFrameNanos);
        entrance = UiAnim.approach(entrance, 1f, 7f, dt);
        float pop = UiAnim.easeOutBack(UiAnim.clamp01(entrance), 1.1f);
        readyFlash = UiAnim.approach(readyFlash, 0f, 4.2f, dt);
        hitKick = UiAnim.approach(hitKick, 0f, 10f, dt);

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int radius = SIZE / 2;
        int cx = screenW - MARGIN_RIGHT - radius;
        int cy = screenH - MARGIN_BOTTOM - radius;

        long now = mc.level.getGameTime();
        float cooldown = clamp01(ClientMatchState.abilityCooldownFraction(AbilityCooldownPacket.ATTACK_ID, now));
        long ticksLeft = ClientMatchState.abilityCooldownTicksLeft(AbilityCooldownPacket.ATTACK_ID, now);
        boolean reloading = cooldown > 0f;

        // Удар щойно стався (кулдаун стрибнув угору) — «віддача» слота і уламки-подряпини.
        if (cooldown > lastCooldown + 0.5f) {
            hitKick = 1f;
            SPARKS.burst(cx, cy, 16, 90f, -0.7f, -0.7f, 0.55f, 0xE4EAF6);
        }
        lastCooldown = cooldown;
        // Перезарядка закінчилась — спалах, розбіжне кільце та іскри.
        if (wasReloading && !reloading) {
            readyFlash = 1f;
            SPARKS.burst(cx, cy, 20, 70f, 0f, -1f, 0.7f, 0xFFFFFF);
        }
        wasReloading = reloading;

        long ms = System.currentTimeMillis();
        float breathe = 0.5f + 0.5f * (float) Math.sin(ms / 380.0);

        var pose = graphics.pose();
        pose.pushPose();
        float sc = (0.72f + 0.28f * pop) * (1f - 0.10f * hitKick);
        pose.translate(cx, cy, 0f);
        pose.scale(sc, sc, 1f);
        pose.translate(-cx, -cy, 0f);
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, UiAnim.clamp01(entrance * 1.4f));

        // Світіння: готовий — м'яке срібне «дихання», перезарядка — тьмяне.
        if (!reloading) {
            for (int i = 1; i <= 5; i++) {
                float k = 1f - (i - 1) / 5f;
                strokeCircle(graphics, cx, cy, radius + i, GeneratorFuelUiTheme.alpha(0xFFC2C9D6,
                    (0.10f + 0.20f * breathe + readyFlash * 0.4f) * k * k), 1);
            }
        }

        fillCircle(graphics, cx, cy, radius, SLOT_FILL);
        fillCircle(graphics, cx, cy, radius - 1, 0x66000000);

        if (reloading) {
            // Заливка росте знизу догори: 0 → щойно вдарив, 1 → готово.
            int fillH = Math.round(SIZE * (1f - cooldown));
            fillCircleFromY(graphics, cx, cy, radius, cy + radius - fillH, RELOAD_FILL, RELOAD_EDGE);
        } else {
            fillCircle(graphics, cx, cy, radius, READY_FILL);
        }

        // Кільце-«тік-марки» навколо слота: дрібні поділки, у готовому стані по ним
        // ковзає яскрава хвиля (як блик по бару генератора).
        int ticks = 36;
        for (int i = 0; i < ticks; i++) {
            double ang = -Math.PI / 2 + 2 * Math.PI * i / ticks;
            float wave = reloading ? 0f
                : Math.max(0f, 1f - Math.abs(((ms / 1400f + 1f) % 1f) - (i / (float) ticks)) * 9f);
            boolean lit = reloading && (i / (float) ticks) <= (1f - cooldown);
            int a = lit ? 0xFF : Math.round(0x40 + 0xBF * wave);
            int rr = radius + 3;
            int px = cx + (int) Math.round(rr * Math.cos(ang));
            int py = cy + (int) Math.round(rr * Math.sin(ang));
            int col = lit ? GeneratorFuelUiTheme.FILL : 0x8E97A5;
            graphics.fill(px, py, px + 1, py + 1, GeneratorFuelUiTheme.alpha(0xFF000000 | (col & 0xFFFFFF), a / 255f));
        }

        strokeCircle(graphics, cx, cy, radius, reloading ? RING_RELOAD : RING_READY, reloading ? 1 : 2);
        if (reloading) {
            // Яскравий прогрес-дуга по колу, з «голівкою».
            strokeProgressCircle(graphics, cx, cy, radius, GeneratorFuelUiTheme.FILL, 1f - cooldown, 2);
        } else {
            // Готово: по кільцю бігає світла дуга (сканер).
            float head = (ms % 2200L) / 2200f;
            for (int i = 0; i < 26; i++) {
                double ang = -Math.PI / 2 + 2 * Math.PI * (head - i / 90f);
                int px = cx + (int) Math.round((radius - 1) * Math.cos(ang));
                int py = cy + (int) Math.round((radius - 1) * Math.sin(ang));
                float k = 1f - i / 26f;
                graphics.fill(px, py, px + 2, py + 2, GeneratorFuelUiTheme.alpha(0xFFFFFFFF, 0.85f * k * k));
            }
        }

        float glyphPulse = reloading ? 0f : 0.15f * breathe;
        drawClaws(graphics, cx, cy, 32, reloading ? GLYPH_RELOAD
            : GeneratorFuelUiTheme.mix(GLYPH_READY, 0xFFFFFFFF, glyphPulse + readyFlash * 0.6f));

        if (reloading) {
            var font = mc.font;
            String label = String.format(Locale.ROOT, "%.1f", ticksLeft / 20f);
            pose.pushPose();
            pose.translate(cx, cy, 0f);
            pose.scale(1.5f, 1.5f, 1f);
            graphics.drawString(font, label, -font.width(label) / 2, -font.lineHeight / 2, 0xFFFFFFFF, true);
            pose.popPose();
        }
        if (readyFlash > 0.02f) {
            fillCircle(graphics, cx, cy, radius - 1, GeneratorFuelUiTheme.alpha(0xFFFFFFFF, readyFlash * 0.45f));
            strokeCircle(graphics, cx, cy, radius + Math.round((1f - readyFlash) * 16f),
                GeneratorFuelUiTheme.alpha(0xFFF2F4F8, readyFlash), 2);
        }

        SPARKS.tickAndRender(graphics, dt, 40f);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        pose.popPose();
    }

    /** Дуга-прогрес по колу від 12 години за годинниковою (0..1). */
    private static void strokeProgressCircle(GuiGraphics g, int cx, int cy, int radius, int color,
                                             float fraction, int thickness) {
        float frac = clamp01(fraction);
        int steps = Math.max(48, radius * 4);
        int lit = Math.round(steps * frac);
        for (int t = 0; t < thickness; t++) {
            int r = radius - t;
            if (r <= 0) continue;
            for (int i = 0; i < lit; i++) {
                double ang = -Math.PI / 2 + 2 * Math.PI * i / steps;
                int px = cx + (int) Math.round(r * Math.cos(ang));
                int py = cy + (int) Math.round(r * Math.sin(ang));
                g.fill(px, py, px + 1, py + 1, color);
            }
        }
        if (lit > 0) { // голівка
            double ang = -Math.PI / 2 + 2 * Math.PI * (lit - 1) / steps;
            int px = cx + (int) Math.round(radius * Math.cos(ang));
            int py = cy + (int) Math.round(radius * Math.sin(ang));
            g.fill(px - 1, py - 1, px + 2, py + 2, 0xFFFFFFFF);
        }
    }

    // ── Геометрія ────────────────────────────────────────────────────

    private static void fillCircle(GuiGraphics g, int cx, int cy, int radius, int color) {
        for (int dy = -radius; dy <= radius; dy++) {
            int dx = (int) Math.round(Math.sqrt((double) radius * radius - (double) dy * dy));
            g.fill(cx - dx, cy + dy, cx + dx, cy + dy + 1, color);
        }
    }

    /** Заливає лише ту частину кола, що нижче {@code topY}; верхній рядок — яскрава смужка. */
    private static void fillCircleFromY(GuiGraphics g, int cx, int cy, int radius, int topY,
                                        int color, int edgeColor) {
        for (int dy = -radius; dy <= radius; dy++) {
            int y = cy + dy;
            if (y < topY) continue;
            int dx = (int) Math.round(Math.sqrt((double) radius * radius - (double) dy * dy));
            boolean edge = y < topY + 2;
            g.fill(cx - dx, y, cx + dx, y + 1, edge ? edgeColor : color);
        }
    }

    private static void strokeCircle(GuiGraphics g, int cx, int cy, int radius, int color, int thickness) {
        int steps = Math.max(48, radius * 4);
        for (int t = 0; t < thickness; t++) {
            int r = radius - t;
            if (r <= 0) continue;
            for (int i = 0; i < steps; i++) {
                double angle = 2 * Math.PI * i / steps;
                int px = cx + (int) Math.round(r * Math.cos(angle));
                int py = cy + (int) Math.round(r * Math.sin(angle));
                g.fill(px, py, px + 1, py + 1, color);
            }
        }
    }

    /** Три подряпини під 45° — значок удару. */
    private static void drawClaws(GuiGraphics g, int cx, int cy, int iconSize, int color) {
        float half = iconSize / 2f;
        double angle = Math.toRadians(45);
        double perp = angle + Math.PI / 2;
        float[] offsets = { -0.90f * half * 0.33f, 0f, 0.90f * half * 0.33f };

        for (float offset : offsets) {
            float x0 = cx + (float) (Math.cos(perp) * offset) - (float) (Math.cos(angle) * half * 0.85f);
            float y0 = cy + (float) (Math.sin(perp) * offset) - (float) (Math.sin(angle) * half * 0.85f);
            float x1 = cx + (float) (Math.cos(perp) * offset) + (float) (Math.cos(angle) * half * 0.85f);
            float y1 = cy + (float) (Math.sin(perp) * offset) + (float) (Math.sin(angle) * half * 0.85f);
            taperedLine(g, x0, y0, x1, y1, half * 0.16f, color);
        }
    }

    private static void taperedLine(GuiGraphics g, float x0, float y0, float x1, float y1,
                                    float baseHalfWidth, int color) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        int steps = Math.max(8, Math.round(len));
        for (int i = 0; i < steps; i++) {
            float t = i / (float) steps;
            float halfW = Math.max(0.5f, baseHalfWidth * (1f - t));
            int px = Math.round(x0 + dx * t);
            int py = Math.round(y0 + dy * t);
            int w = Math.max(1, Math.round(halfW));
            g.fill(px - w / 2, py - w / 2, px - w / 2 + w, py - w / 2 + w, color);
        }
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
