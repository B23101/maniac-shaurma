package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.AbilityCooldownPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.IItemDecorator;

/**
 * ВЛАСНА перезарядка предмета — бар під іконкою, що зменшується, замість
 * ванільної білої «шторки» ({@code OwnCooldowns} на сервері,
 * {@code AbilityCooldownPacket.itemId} як канал даних).
 *
 * <p>Малюється в КОЖНОМУ слоті з предметом цього типу — і в хотбарі, і в
 * інвентарі, — бо це декоратор ({@code RegisterItemDecorationsEvent}), той самий
 * механізм, що {@link CrowbarDurabilityDecorator}.</p>
 *
 * <h3>Анімації</h3>
 * Декоратор викликається по разу на слот на кадр і не має свого стану, тому
 * ВСЕ — чиста функція від часу: ігрові тіки (з partialTick, тож пауза не ламає
 * відлік) для логіки бару й {@code currentTimeMillis} для косметики.
 * <ul>
 *   <li><b>Старт («слем»)</b>: бар «заряджається» з нуля з перельотом, товщає й
 *       спалахує білим; іконка блимає; від слота розходиться кільце ударної
 *       хвилі; з бару вилітає віяло іскор.</li>
 *   <li><b>Хід</b>: заливка стискається справа наліво (кінець — дробовий,
 *       з антиаліасингом); колір іде від червоного через бурштин до
 *       холодно-білого; по заливці біжить блик; на голівці пульсує світіння й
 *       сиплються іскри; іконку затемнює «жалюзі», що піднімається вгору із
 *       світлою лінією-скан по краю; у куті — секунди до готовності.</li>
 *   <li><b>Фінал (~1.5 с)</b>: рамка бару моргає, іскри густішають.</li>
 *   <li><b>Готово</b>: бар спалахує на всю довжину й розчиняється, що росте
 *       вгору; діагональний блик пролітає по іконці; кільце й іскри б'ють
 *       назовні. Звук «готово» — у {@code OwnCooldownClientHooks}.</li>
 * </ul>
 * Іскри — детерміновані (псевдовипадкові від «відра» часу): жодного стану
 * між кадрами й між слотами.
 */
@OnlyIn(Dist.CLIENT)
public final class ItemCooldownBarDecorator implements IItemDecorator {

    // ── Геометрія (у пікселях іконки 16×16) ──────────────────────────────
    private static final int BAR_X = 1;
    private static final int BAR_Y = 12;
    private static final int BAR_W = 14;        // зовнішня ширина, з рамкою
    private static final int BAR_H = 4;         // зовнішня висота, з рамкою
    private static final int INNER_W = BAR_W - 2;

    // ── Тривалості ефектів (ігрові тіки) ─────────────────────────────────
    private static final float START_FX_TICKS = 14f;
    private static final float READY_FX_TICKS = 18f;
    private static final float FINALE_TICKS = 30f;

    // ── Палітра ──────────────────────────────────────────────────────────
    private static final int COL_START = 0xFFE0303A;   // гаряче: щойно вдарив
    private static final int COL_MID = 0xFFF0A030;     // бурштин
    private static final int COL_END = 0xFFDDF3FF;     // холодно-біле: майже готово
    private static final int COL_TRACK = 0xFF0C0E13;
    private static final int COL_BORDER = 0xFF2B303B;
    private static final int COL_CURTAIN = 0x8C0A0C12;
    private static final float Z = 200f;

    private final String cooldownId;

    /** @param itemKey ключ предмета в {@code OwnCooldowns} (напр. {@code "bat"}) */
    public ItemCooldownBarDecorator(String itemKey) {
        this.cooldownId = AbilityCooldownPacket.itemId(itemKey);
    }

    @Override
    public boolean render(GuiGraphics g, Font font, ItemStack stack, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;

        long readyAt = ClientMatchState.abilityReadyAtTick(cooldownId);
        int total = ClientMatchState.abilityTotalTicks(cooldownId);
        if (readyAt < 0 || total <= 0) return false;

        float now = mc.level.getGameTime() + mc.getFrameTime();
        float left = readyAt - now;
        float sinceStart = now - (readyAt - total);
        float sinceReady = now - readyAt;
        long ms = System.currentTimeMillis();

        g.pose().pushPose();
        g.pose().translate(0f, 0f, Z);
        if (left > 0f) {
            renderActive(g, font, x, y, Math.min(1f, left / total), left, sinceStart, ms);
        } else if (sinceReady < READY_FX_TICKS) {
            renderReady(g, x, y, sinceReady / READY_FX_TICKS, ms);
        }
        g.pose().popPose();
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Перезарядка триває
    // ─────────────────────────────────────────────────────────────────────

    private static void renderActive(GuiGraphics g, Font font, int x, int y,
                                     float frac, float leftTicks, float sinceStart, long ms) {
        float progress = 1f - frac; // 0 = щойно, 1 = майже готово
        boolean slam = sinceStart < START_FX_TICKS;
        float st = UiAnim.clamp01(sinceStart / START_FX_TICKS);

        // 1) «Жалюзі» на іконці: темна завіса зверху, що з часом піднімається; по краю — лінія-скан.
        int curtainH = Math.round(16f * frac);
        if (curtainH > 0) {
            g.fill(x, y, x + 16, y + curtainH, COL_CURTAIN);
            int lineY = y + curtainH;
            int edge = GeneratorFuelUiTheme.mix(COL_MID, COL_END, progress);
            g.fill(x, lineY - 1, x + 16, lineY, GeneratorFuelUiTheme.alpha(edge, 0.95f));
            g.fill(x, lineY, x + 16, lineY + 1, GeneratorFuelUiTheme.alpha(edge, 0.32f));
            // Іскринка біжить по лінії-скану.
            int sx = x + (int) ((ms / 55L) % 16L);
            g.fill(sx, lineY - 1, sx + 2, lineY, 0xFFFFFFFF);
        }

        // 2) Бар. При старті — «зарядка» з перельотом; далі — чесна частка, з дробовим кінцем.
        float shown = slam ? frac * UiAnim.easeOutBack(UiAnim.clamp01(sinceStart / 4f), 0.8f) : frac;
        shown = UiAnim.clamp01(shown);
        int pop = slam ? Math.round(2.4f * (1f - UiAnim.easeOutCubic(UiAnim.clamp01(st * 1.4f)))) : 0;

        int fillRgb = barColor(progress);
        boolean finale = leftTicks < FINALE_TICKS;
        float blink = finale ? 0.5f + 0.5f * (float) Math.sin(ms / 65.0) : 0f;

        int bx = x + BAR_X;
        int by = y + BAR_Y - pop;
        int bh = BAR_H + pop;

        // Світіння за баром — сильніше на старті й у фіналі.
        float glowA = 0.10f + 0.25f * (1f - st) * (slam ? 1f : 0f) + 0.22f * blink;
        if (glowA > 0.02f) {
            g.fill(bx - 1, by - 1, bx + BAR_W + 1, by + bh + 1, GeneratorFuelUiTheme.alpha(fillRgb | 0xFF000000, glowA));
        }
        int border = GeneratorFuelUiTheme.mix(COL_BORDER, 0xFFFFFFFF, blink * 0.85f);
        g.fill(bx, by, bx + BAR_W, by + bh, border);
        g.fill(bx + 1, by + 1, bx + BAR_W - 1, by + bh - 1, COL_TRACK);

        int ix = bx + 1, iy = by + 1, ih = bh - 2;
        float filledF = INNER_W * shown;
        int filledW = (int) filledF;
        float partial = filledF - filledW;
        int top = 0xFF000000 | GeneratorFuelUiTheme.mix(fillRgb | 0xFF000000, 0xFFFFFFFF, 0.38f);
        int base = 0xFF000000 | fillRgb;
        if (filledW > 0) {
            g.fill(ix, iy, ix + filledW, iy + 1, top);
            if (ih > 1) g.fill(ix, iy + 1, ix + filledW, iy + ih, base);
        }
        if (partial > 0.08f && filledW < INNER_W) {
            g.fill(ix + filledW, iy, ix + filledW + 1, iy + ih, GeneratorFuelUiTheme.alpha(base, partial));
        }

        // Блик, що біжить по заливці.
        if (filledW > 2) {
            float sweep = ((ms % 950L) / 950f) * (filledW + 8) - 4f;
            for (int c = 0; c < filledW; c++) {
                float d = Math.abs(c - sweep);
                if (d > 3f) continue;
                float k = 1f - d / 3f;
                g.fill(ix + c, iy, ix + c + 1, iy + ih, GeneratorFuelUiTheme.alpha(0xFFFFFFFF, 0.5f * k * k));
            }
        }

        // Голівка заливки: біла, що пульсує, + світіння вперед + іскри.
        if (filledF > 0.5f) {
            int hx = ix + Math.max(0, Math.min(filledW, INNER_W) - 1);
            float hp = 0.65f + 0.35f * (float) Math.sin(ms / 90.0);
            g.fill(hx, iy, hx + 1, iy + ih, GeneratorFuelUiTheme.alpha(0xFFFFFFFF, hp));
            g.fill(hx + 1, iy, hx + 2, iy + ih, GeneratorFuelUiTheme.alpha(base, 0.55f * hp));
            g.fill(hx - 1, iy, hx, iy + ih, GeneratorFuelUiTheme.alpha(0xFFFFFFFF, 0.25f * hp));
            headSparks(g, hx + 1, iy, ms, fillRgb, finale ? 1f : 0.7f);
        }

        // 3) Старт: спалах іконки, кільце, віяло іскор.
        if (slam) {
            float inv = 1f - st;
            g.fill(x, y, x + 16, y + 16, GeneratorFuelUiTheme.alpha(0xFFFFFFFF, 0.55f * inv * inv));
            // Білий спалах по бару.
            g.fill(bx + 1, by + 1, bx + BAR_W - 1, by + bh - 1, GeneratorFuelUiTheme.alpha(0xFFFFFFFF, 0.8f * inv * inv * inv));
            ring(g, x, y, 1 + Math.round(st * 6f), GeneratorFuelUiTheme.alpha(COL_START, inv * inv));
            burst(g, x + 8f, y + BAR_Y + 2f, sinceStart, START_FX_TICKS, 12, 26f, fillRgb, 0.5f, 0x9E37);
        }

        // 4) Секунди до готовності — маленьким текстом у лівому верхньому куті.
        drawSeconds(g, font, x, y, leftTicks, progress);
    }

    /** Псевдовипадкові іскри з голівки: стан не потрібен — усе від «відра» часу. */
    private static void headSparks(GuiGraphics g, int hx, int hy, long ms, int rgb, float intensity) {
        final long period = 300L;
        for (int i = 0; i < 4; i++) {
            long t0 = ms + i * 75L;
            long bucket = t0 / period;
            float life = (t0 % period) / (float) period;
            int h = hash((int) bucket * 31 + i * 7919);
            float ang = (float) (-Math.PI / 2 + (((h & 255) / 255f) - 0.5f) * 2.6f);
            float speed = 4f + ((h >> 8) & 7);
            float px = hx + (float) Math.cos(ang) * speed * life;
            float py = hy + (float) Math.sin(ang) * speed * life + 5f * life * life;
            float a = (1f - life) * intensity;
            if (a < 0.05f) continue;
            int c = (i & 1) == 0 ? 0xFFFFFFFF : (0xFF000000 | rgb);
            int ix = Math.round(px), iy = Math.round(py);
            g.fill(ix, iy, ix + 1, iy + 1, GeneratorFuelUiTheme.alpha(c, a));
        }
    }

    private static void drawSeconds(GuiGraphics g, Font font, int x, int y, float leftTicks, float progress) {
        float secs = leftTicks / 20f;
        String text = secs >= 10f ? String.valueOf((int) Math.ceil(secs)) : String.format(java.util.Locale.ROOT, "%.1f", secs);
        int color = GeneratorFuelUiTheme.mix(0xFFFFFFFF, COL_MID, progress > 0.75f ? (progress - 0.75f) * 4f : 0f);
        g.pose().pushPose();
        g.pose().translate(x + 1f, y + 1f, 0f);
        g.pose().scale(0.62f, 0.62f, 1f);
        g.drawString(font, text, 0, 0, color, true);
        g.pose().popPose();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Перезарядка завершилась — «готово»
    // ─────────────────────────────────────────────────────────────────────

    private static void renderReady(GuiGraphics g, int x, int y, float r, long ms) {
        float inv = 1f - r;

        // Діагональний блик пролітає по іконці зліва направо.
        float sweepT = UiAnim.clamp01(r * 1.7f);
        if (sweepT < 1f) {
            float bc = -6f + 28f * UiAnim.easeOutCubic(sweepT);
            for (int row = 0; row < 16; row++) {
                float c = bc - row * 0.55f;
                int a0 = Math.max(0, Math.round(c - 3f));
                int a1 = Math.min(16, Math.round(c + 3f));
                if (a1 > a0) g.fill(x + a0, y + row, x + a1, y + row + 1, GeneratorFuelUiTheme.alpha(0xFFFFFFFF, 0.28f * (1f - sweepT)));
                int b0 = Math.max(0, Math.round(c - 1f));
                int b1 = Math.min(16, Math.round(c + 1f));
                if (b1 > b0) g.fill(x + b0, y + row, x + b1, y + row + 1, GeneratorFuelUiTheme.alpha(0xFFFFFFFF, 0.65f * (1f - sweepT)));
            }
        }
        // Коротка біла вспишка всієї іконки.
        g.fill(x, y, x + 16, y + 16, GeneratorFuelUiTheme.alpha(0xFFFFFFFF, 0.38f * inv * inv * inv));

        // Бар: повний, холодно-білий, розчиняється й росте вгору.
        int grow = Math.round(3.5f * UiAnim.easeOutCubic(r));
        float a = inv * inv;
        int bx = x + BAR_X, by = y + BAR_Y - grow, bh = BAR_H + grow;
        g.fill(bx - grow / 2, by, bx + BAR_W + grow / 2, by + bh, GeneratorFuelUiTheme.alpha(COL_END, 0.85f * a));
        g.fill(bx + 1, by + 1, bx + BAR_W - 1, by + 2, GeneratorFuelUiTheme.alpha(0xFFFFFFFF, a));

        // Кільце готовності — світле, шире за стартове.
        ring(g, x, y, 1 + Math.round(UiAnim.easeOutCubic(r) * 8f), GeneratorFuelUiTheme.alpha(COL_END, a));

        // Іскри вгору й убік.
        burst(g, x + 8f, y + 9f, r * READY_FX_TICKS, READY_FX_TICKS, 14, 30f, 0xFFDDF3FF, -0.9f, 0x51ED);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Спільні примітиви
    // ─────────────────────────────────────────────────────────────────────

    /** Колір заливки за прогресом: червоний → бурштин → холодно-білий. */
    private static int barColor(float progress) {
        int c = progress < 0.55f
            ? GeneratorFuelUiTheme.mix(COL_START, COL_MID, progress / 0.55f)
            : GeneratorFuelUiTheme.mix(COL_MID, COL_END, (progress - 0.55f) / 0.45f);
        return c & 0xFFFFFF;
    }

    /** Контур-кільце навколо слота 16×16, розсунуте на {@code grow} px. */
    private static void ring(GuiGraphics g, int x, int y, int grow, int argb) {
        if (((argb >>> 24) & 0xFF) < 4) return;
        int x0 = x - grow, y0 = y - grow, x1 = x + 16 + grow, y1 = y + 16 + grow;
        g.fill(x0, y0, x1, y0 + 1, argb);
        g.fill(x0, y1 - 1, x1, y1, argb);
        g.fill(x0, y0 + 1, x0 + 1, y1 - 1, argb);
        g.fill(x1 - 1, y0 + 1, x1, y1 - 1, argb);
    }

    /**
     * Віяло іскор з точки {@code (cx, cy)}: {@code t}/{@code duration} — прогрес,
     * {@code gravity} > 0 тягне вниз, < 0 — вгору. Кожна іскра має свій кут,
     * швидкість і розмір від {@code seed}.
     */
    private static void burst(GuiGraphics g, float cx, float cy, float t, float duration, int count,
                              float speedPx, int rgb, float gravity, int seed) {
        float k = UiAnim.clamp01(t / duration);
        if (k >= 1f) return;
        float tau = t / 20f; // секунди
        for (int i = 0; i < count; i++) {
            int h = hash(seed + i * 2654435);
            float ang = (float) ((i / (float) count) * Math.PI * 2 + ((h & 255) / 255f - 0.5f) * 0.9f);
            float sp = speedPx * (0.55f + 0.45f * (((h >> 8) & 255) / 255f));
            float px = cx + (float) Math.cos(ang) * sp * tau;
            float py = cy + (float) Math.sin(ang) * sp * tau + gravity * 40f * tau * tau;
            float a = (1f - k) * (1f - k);
            int size = ((h >> 16) & 3) == 0 ? 2 : 1;
            int c = ((h >> 18) & 1) == 0 ? 0xFFFFFFFF : (0xFF000000 | (rgb & 0xFFFFFF));
            int ix = Math.round(px), iy = Math.round(py);
            g.fill(ix, iy, ix + size, iy + size, GeneratorFuelUiTheme.alpha(c, a));
        }
    }

    private static int hash(int v) {
        int h = v * 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return h & 0x7FFFFFFF;
    }
}
