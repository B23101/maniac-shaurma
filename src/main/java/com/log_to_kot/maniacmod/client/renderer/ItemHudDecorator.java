package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.AbilityCooldownPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.IItemDecorator;

import java.util.Locale;
import java.util.function.ToIntFunction;

/**
 * ЄДИНИЙ декоратор іконки предмета виживого: відсотки (міцність / заряд) і
 * власна перезарядка — в одному місці, за одним розкладом.
 *
 * ── Чому один клас (БАГ: усе малювалось одне поверх одного) ──────────
 * Раніше відсотки ({@code CrowbarDurabilityDecorator}, {@code FuelCanisterChargeDecorator})
 * і бар перезарядки ({@code ItemCooldownBarDecorator}) були НЕЗАЛЕЖНИМИ
 * декораторами, що не знали одне про одного: число «67%» у правому нижньому
 * куті лягало просто на бар, секунди — на жалюзі, а у лома ще й ванільна
 * біла «шторка» {@code ItemCooldowns}. Тепер на іконці завжди ОДНЕ з двох:
 * <ul>
 *   <li><b>спокій</b> — число відсотків у правому нижньому куті;</li>
 *   <li><b>перезарядка</b> — горизонтальний бар на всю ширину іконки внизу +
 *       секунди НАД баром (там, де інакше було б число). Відсотки на цей час
 *       ховаються й плавно повертаються після «готово».</li>
 * </ul>
 * Зони не перетинаються: бар — y 12..16, текст — над ним (перезарядка) або
 * в самому куті (спокій). Бар малюється заливкою, що скорочується справа
 * наліво (дробовий кінець згладжений), колір червоний → бурштин → холодно-білий.
 *
 * Усе — чиста функція від часу (декоратор викликається по разу на слот на
 * кадр і стану не має).
 */
@OnlyIn(Dist.CLIENT)
public final class ItemHudDecorator implements IItemDecorator {

    // ── Геометрія (піксельна сітка іконки 16×16) ─────────────────────────
    private static final int BAR_X = 1;
    private static final int BAR_Y = 12;
    private static final int BAR_W = 14;              // зовнішня ширина з рамкою
    private static final int BAR_H = 4;               // зовнішня висота з рамкою
    private static final int INNER_W = BAR_W - 2;

    private static final float START_FX_TICKS = 10f;
    private static final float READY_FX_TICKS = 16f;
    private static final float FINALE_TICKS = 30f;

    private static final int WARN_ABOVE_PERCENT = 50;
    private static final int DANGER_BELOW_PERCENT = 20;
    private static final float TEXT_SCALE = 0.65f;
    private static final float Z = 200f;

    private static final int COL_START = 0xFFE0303A;
    private static final int COL_MID = 0xFFF0A030;
    private static final int COL_END = 0xFFDDF3FF;
    private static final int COL_TRACK = 0xFF0C0E13;
    private static final int COL_BORDER = 0xFF2B303B;
    private static final int COL_CURTAIN = 0x66080A10;

    private final ToIntFunction<ItemStack> percent;
    private final String cooldownId; // null — предмет без власної перезарядки

    /**
     * @param percent     відсотки для числа на іконці (міцність / заряд), {@code null} — без числа
     * @param cooldownKey ключ у {@code OwnCooldowns} (напр. {@code "bat"}), {@code null} — без бару
     */
    public ItemHudDecorator(ToIntFunction<ItemStack> percent, String cooldownKey) {
        this.percent = percent;
        this.cooldownId = cooldownKey == null ? null : AbilityCooldownPacket.itemId(cooldownKey);
    }

    @Override
    public boolean render(GuiGraphics g, Font font, ItemStack stack, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        float percentAlpha = 1f;

        g.pose().pushPose();
        g.pose().translate(0f, 0f, Z);

        if (cooldownId != null && mc.level != null) {
            long readyAt = ClientMatchState.abilityReadyAtTick(cooldownId);
            int total = ClientMatchState.abilityTotalTicks(cooldownId);
            if (readyAt >= 0 && total > 0) {
                float now = mc.level.getGameTime() + mc.getFrameTime();
                float left = readyAt - now;
                long ms = System.currentTimeMillis();
                if (left > 0f) {
                    renderActive(g, font, x, y, Math.min(1f, left / total), left,
                        now - (readyAt - total), ms);
                    percentAlpha = 0f; // по черзі: відсотки ховаємо на час перезарядки
                } else if (now - readyAt < READY_FX_TICKS) {
                    float r = (now - readyAt) / READY_FX_TICKS;
                    renderReady(g, x, y, r);
                    percentAlpha = UiAnim.clamp01((r - 0.45f) / 0.55f); // повертаються, коли бар розчинився
                }
            }
        }

        if (percent != null && percentAlpha > 0.1f) {
            drawPercent(g, font, x, y, percent.applyAsInt(stack), percentAlpha);
        }
        g.pose().popPose();
        return false;
    }

    // ── Відсотки ─────────────────────────────────────────────────────────

    private static void drawPercent(GuiGraphics g, Font font, int x, int y, int value, float alpha) {
        String text = value + "%";
        int color = value < DANGER_BELOW_PERCENT ? ManiacUiTheme.BAR_FILL_DANGER
                  : value <= WARN_ABOVE_PERCENT ? ManiacUiTheme.BAR_FILL_WARN
                  : ManiacUiTheme.BAR_FILL_NEUTRAL;
        drawCornerText(g, font, text, x, y + 16f, GeneratorFuelUiTheme.alpha(color, alpha));
    }

    /** Текст, вирівняний вправо; {@code bottom} — нижня межа тексту в пікселях іконки. */
    private static void drawCornerText(GuiGraphics g, Font font, String text, float x, float bottom, int argb) {
        var pose = g.pose();
        pose.pushPose();
        pose.translate(x + 16f, bottom, 0f);
        pose.scale(TEXT_SCALE, TEXT_SCALE, 1f);
        g.drawString(font, text, -font.width(text) - 1, -font.lineHeight, argb, true);
        pose.popPose();
    }

    // ── Перезарядка триває ───────────────────────────────────────────────

    private static void renderActive(GuiGraphics g, Font font, int x, int y, float frac,
                                     float leftTicks, float sinceStart, long ms) {
        float progress = 1f - frac;
        boolean slam = sinceStart < START_FX_TICKS;
        float st = UiAnim.clamp01(sinceStart / START_FX_TICKS);

        // 1) Світла «жалюзі» на іконці над баром (не залазить у зону бару).
        int curtainH = Math.min(BAR_Y, Math.round(16f * frac));
        if (curtainH > 0) {
            g.fill(x, y, x + 16, y + curtainH, COL_CURTAIN);
            int edge = GeneratorFuelUiTheme.mix(COL_MID, COL_END, progress);
            g.fill(x, y + curtainH - 1, x + 16, y + curtainH, GeneratorFuelUiTheme.alpha(edge, 0.85f));
        }

        // 2) Бар: рамка → трек → заливка.
        float shown = slam ? frac * UiAnim.easeOutBack(UiAnim.clamp01(sinceStart / 4f), 0.6f) : frac;
        shown = UiAnim.clamp01(shown);
        boolean finale = leftTicks < FINALE_TICKS;
        float blink = finale ? 0.5f + 0.5f * (float) Math.sin(ms / 65.0) : 0f;

        int bx = x + BAR_X, by = y + BAR_Y;
        int fillRgb = barColor(progress);
        int base = 0xFF000000 | fillRgb;
        int top = GeneratorFuelUiTheme.mix(base, 0xFFFFFFFF, 0.38f);

        g.fill(bx, by, bx + BAR_W, by + BAR_H,
            GeneratorFuelUiTheme.mix(COL_BORDER, 0xFFFFFFFF, blink * 0.85f));
        g.fill(bx + 1, by + 1, bx + BAR_W - 1, by + BAR_H - 1, COL_TRACK);

        int ix = bx + 1, iy = by + 1, ih = BAR_H - 2;
        float filledF = INNER_W * shown;
        int filledW = (int) filledF;
        float partial = filledF - filledW;
        if (filledW > 0) {
            g.fill(ix, iy, ix + filledW, iy + 1, top);
            if (ih > 1) g.fill(ix, iy + 1, ix + filledW, iy + ih, base);
        }
        if (partial > 0.08f && filledW < INNER_W) {
            g.fill(ix + filledW, iy, ix + filledW + 1, iy + ih, GeneratorFuelUiTheme.alpha(base, partial));
        }

        // 3) Старт: короткий спалах і кільце навколо слота.
        if (slam) {
            float inv = 1f - st;
            g.fill(x, y, x + 16, y + BAR_Y, GeneratorFuelUiTheme.alpha(0xFFFFFFFF, 0.45f * inv * inv));
            ring(g, x, y, 1 + Math.round(st * 5f), GeneratorFuelUiTheme.alpha(COL_START, inv * inv));
        }

        // 4) Секунди — НАД баром, там, де в спокої стоїть число відсотків.
        float secs = leftTicks / 20f;
        String text = secs >= 10f ? String.valueOf((int) Math.ceil(secs))
                                  : String.format(Locale.ROOT, "%.1f", secs);
        int color = GeneratorFuelUiTheme.mix(0xFFFFFFFF, COL_MID, progress > 0.75f ? (progress - 0.75f) * 4f : 0f);
        drawCornerText(g, font, text, x, y + BAR_Y - 0.5f, color);
    }

    // ── Готово ───────────────────────────────────────────────────────────

    private static void renderReady(GuiGraphics g, int x, int y, float r) {
        float inv = 1f - r;

        // Діагональний блик по іконці (тільки над зоною бару).
        float sweepT = UiAnim.clamp01(r * 1.7f);
        if (sweepT < 1f) {
            float bc = -6f + 28f * UiAnim.easeOutCubic(sweepT);
            for (int row = 0; row < BAR_Y; row++) {
                float c = bc - row * 0.55f;
                int a0 = Math.max(0, Math.round(c - 2f));
                int a1 = Math.min(16, Math.round(c + 2f));
                if (a1 > a0) g.fill(x + a0, y + row, x + a1, y + row + 1,
                    GeneratorFuelUiTheme.alpha(0xFFFFFFFF, 0.5f * (1f - sweepT)));
            }
        }
        g.fill(x, y, x + 16, y + BAR_Y, GeneratorFuelUiTheme.alpha(0xFFFFFFFF, 0.30f * inv * inv * inv));

        // Повний бар холодно-білого кольору, що розчиняється НА МІСЦІ (без росту вгору).
        float a = inv * inv;
        int bx = x + BAR_X, by = y + BAR_Y;
        g.fill(bx, by, bx + BAR_W, by + BAR_H, GeneratorFuelUiTheme.alpha(COL_END, 0.85f * a));
        g.fill(bx + 1, by + 1, bx + BAR_W - 1, by + 2, GeneratorFuelUiTheme.alpha(0xFFFFFFFF, a));

        ring(g, x, y, 1 + Math.round(UiAnim.easeOutCubic(r) * 7f), GeneratorFuelUiTheme.alpha(COL_END, a));
    }

    // ── Примітиви ────────────────────────────────────────────────────────

    private static int barColor(float progress) {
        int c = progress < 0.55f
            ? GeneratorFuelUiTheme.mix(COL_START, COL_MID, progress / 0.55f)
            : GeneratorFuelUiTheme.mix(COL_MID, COL_END, (progress - 0.55f) / 0.45f);
        return c & 0xFFFFFF;
    }

    private static void ring(GuiGraphics g, int x, int y, int grow, int argb) {
        if (((argb >>> 24) & 0xFF) < 4) return;
        int x0 = x - grow, y0 = y - grow, x1 = x + 16 + grow, y1 = y + 16 + grow;
        g.fill(x0, y0, x1, y0 + 1, argb);
        g.fill(x0, y1 - 1, x1, y1, argb);
        g.fill(x0, y0 + 1, x0 + 1, y1 - 1, argb);
        g.fill(x1 - 1, y0 + 1, x1, y1 - 1, argb);
    }
}
