package com.log_to_kot.maniacmod.client.overlay.hint;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.ClientProfileState;
import com.log_to_kot.maniacmod.client.ManiacKeybinds;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.RankFx;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.progression.Ranks;
import com.log_to_kot.maniacmod.progression.XpCurve;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Locale;

/**
 * Лобі-підказка про профіль у стилі ігрових підказок «натисни кнопку»: без рамки й панелі —
 * клавіша-кепка (світла, об'ємна, з буквою) і поруч напис «ПРОФІЛЬ» та ранг із тонкою смужкою XP.
 * Фон — лише м'яке затемнення, що розчиняється до центру екрана, тож підказка не лізе в очі.
 *
 * <p>Раз на кілька секунд кепка «натискається» (опускається, від неї розходиться хвиля) — око
 * вловлює, що це клавіша. Клавіша — {@link ManiacKeybinds#PROFILE} (за замовчуванням E).
 * Стоїть у правому нижньому куті, над хотбаром. З'їжджає з краю при вході в лобі, ховається, коли відкрито будь-який екран або почався матч.
 * Дані профілю підтягуються самі ({@link ClientProfileState#requestIfStale}).</p>
 */
@OnlyIn(Dist.CLIENT)
public final class ProfileHintOverlay {

    private ProfileHintOverlay() {}

    private static final long[] LAST = new long[1];
    private static float appear = 0f;

    private static final long CYCLE_MS = 3200L;

    private static final int CAP_FACE = 0xFFE9ECF1;
    private static final int CAP_EDGE = 0xFF8F97A4;
    private static final int CAP_SIDE = 0xFF5B6270;
    private static final int CAP_TEXT = 0xFF14171B;

    /** Під прицілом, коли дивишся на іншого гравця: «ПКМ — профіль гравця». */
    private static void drawTargetHint(GuiGraphics g, Minecraft mc) {
        if (!(mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit)) return;
        if (!(hit.getEntity() instanceof net.minecraft.world.entity.player.Player other)
            || other == mc.player || other.isSpectator()) return;
        Component msg = Component.translatable("maniacmod.profile.hint.other");
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        int tw = mc.font.width(msg);
        int x = (sw - tw) / 2, y = sh / 2 + 14;
        g.fill(x - 4, y - 2, x + tw + 4, y + 10, 0x99000000);
        g.drawString(mc.font, msg, x, y, 0xFFE9ECF1, false);
    }

    public static void render(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        boolean visible = mc.player != null && mc.screen == null && !mc.options.hideGui
            && ClientMatchState.phase() == GamePhase.LOBBY
            && !mc.player.isSpectator() && !mc.player.isCreative();
        if (visible) drawTargetHint(g, mc);
        float dt = UiAnim.frameDt(LAST);
        appear = UiAnim.approach(appear, visible ? 1f : 0f, visible ? 6f : 12f, dt);
        if (!visible && appear < 0.01f) return;
        if (visible) ClientProfileState.requestIfStale(60_000L);

        float e = UiAnim.easeOutCubic(UiAnim.clamp01(appear));
        Font font = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        long now = System.currentTimeMillis();

        String key = ManiacKeybinds.PROFILE.getTranslatedKeyMessage().getString().toUpperCase(Locale.ROOT);
        Component title = Component.literal(
                Component.translatable("maniacmod.profile.hint.title").getString().toUpperCase(Locale.ROOT))
            .withStyle(st -> st.withBold(true));
        var profile = ClientProfileState.get();

        Component sub;
        int tierCol = 0xFFC2C9D6;
        float xpFrac = 0f;
        boolean hasRank = profile != null;
        if (hasRank) {
            int level = XpCurve.levelOf(profile.totalXp());
            Ranks.Rank rank = Ranks.ofLevel(level);
            tierCol = RankFx.tierColor(rank.role(), rank.tier());
            xpFrac = UiAnim.clamp01(XpCurve.intoLevel(profile.totalXp()) / (float) XpCurve.needed(level));
            String roman = rank.divisionRoman();
            sub = Component.translatable(rank.langKey())
                .append(roman.isEmpty() ? "" : " " + roman)
                .append("  ·  " + level + " " + Component.translatable("maniacmod.profile.hint.lvl").getString());
        } else {
            sub = Component.translatable("maniacmod.profile.hint.sub");
        }

        int capW = Math.max(20, font.width(key) + 12);
        int capH = 20;
        int textW = Math.max(font.width(title), font.width(sub));
        int fade = 34;                                   // розчинення фону зліва
        int w = fade + capW + 9 + textW + 12;
        int h = 34;
        int x = sw - w + Math.round((1f - e) * (w + 6));
        int y = sh - h - 44;                              // внизу екрана, над хотбаром

        // Фон: затемнення, що густішає до краю екрана (смужки по 2 px).
        for (int i = 0; i < w; i += 2) {
            float t = (i + 1f) / w;
            float a = 0.62f * t * t * e;
            g.fill(x + i, y, x + Math.min(w, i + 2), y + h, GeneratorFuelUiTheme.alpha(0xFF05070A, a));
        }
        // Тонкий акцент на краю — у кольорі ступеня.
        g.fill(x + w - 2, y, x + w, y + h, GeneratorFuelUiTheme.alpha(tierCol, 0.9f * e));

        // ── Клавіша ──
        float p = (now % CYCLE_MS) / (float) CYCLE_MS;
        int down = p < 0.07f ? 2 : 0;
        int kx = x + fade;
        int ky = y + (h - capH) / 2;
        // Хвиля після натискання.
        if (p < 0.22f) {
            float t = UiAnim.clamp01(p / 0.22f);
            int rr = 1 + Math.round(t * 6);
            int ring = GeneratorFuelUiTheme.alpha(CAP_FACE, (1f - t) * 0.55f * e);
            g.fill(kx - rr, ky - rr, kx + capW + rr, ky - rr + 1, ring);
            g.fill(kx - rr, ky + capH + rr - 1, kx + capW + rr, ky + capH + rr, ring);
            g.fill(kx - rr, ky - rr, kx - rr + 1, ky + capH + rr, ring);
            g.fill(kx + capW + rr - 1, ky - rr, kx + capW + rr, ky + capH + rr, ring);
        }
        // Низ кепки (товщина клавіші), потім лице, що опускається при натисканні.
        g.fill(kx, ky + 3, kx + capW, ky + capH, GeneratorFuelUiTheme.alpha(CAP_SIDE, e));
        g.fill(kx + 1, ky + capH, kx + capW - 1, ky + capH + 1, GeneratorFuelUiTheme.alpha(0xFF05070A, 0.7f * e));
        int fy = ky + down;
        int fh = capH - 3;
        g.fill(kx, fy, kx + capW, fy + fh, GeneratorFuelUiTheme.alpha(CAP_FACE, e));
        g.fill(kx, fy + fh - 2, kx + capW, fy + fh, GeneratorFuelUiTheme.alpha(CAP_EDGE, e));
        // Зрізані кути — кепка не виглядає цеглиною.
        int cut = GeneratorFuelUiTheme.alpha(CAP_SIDE, e);
        g.fill(kx, fy, kx + 1, fy + 1, cut); g.fill(kx + capW - 1, fy, kx + capW, fy + 1, cut);
        g.drawString(font, key, kx + (capW - font.width(key)) / 2, fy + (fh - 8) / 2 + 1,
            GeneratorFuelUiTheme.alpha(CAP_TEXT, e), false);

        // ── Текст ──
        int tx = kx + capW + 9;
        g.drawString(font, title, tx, y + 5, GeneratorFuelUiTheme.alpha(0xFFF2F4F8, e), true);
        g.drawString(font, sub, tx, y + 17, GeneratorFuelUiTheme.alpha(hasRank ? tierCol : 0xFF9AA1AD, e), false);

        if (hasRank) {
            int by = y + h - 7;
            g.fill(tx, by, tx + textW, by + 2, GeneratorFuelUiTheme.alpha(0xFFFFFFFF, 0.14f * e));
            g.fill(tx, by, tx + Math.max(1, Math.round(textW * xpFrac)), by + 2,
                GeneratorFuelUiTheme.alpha(tierCol, e));
        }
    }
}
