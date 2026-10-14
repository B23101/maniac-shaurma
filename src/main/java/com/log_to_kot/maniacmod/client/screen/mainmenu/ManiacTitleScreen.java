package com.log_to_kot.maniacmod.client.screen.mainmenu;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.style.BloodFx;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.gui.ModListScreen;
import net.minecraftforge.fml.ModList;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Головне меню мода — замість ванільного {@code TitleScreen}
 * (підміну робить {@link ManiacTitleInterceptor}).
 *
 * ── Що на екрані ─────────────────────────────────────────────────────
 * Те саме оформлення, що й у {@code ManiacEscMenuScreen}: ліворуч — бренд
 * (літери падають і б'ються об «підлогу», з них стікає кров), чіп із версією,
 * кнопки-«леза», друкований опис кнопки під курсором і соцмережі. Відмінність
 * одна — фон: замість {@code HorrorBackdrop} (кров зверху, віньєтка) тут
 * картинка, що їде вправо-вліво ({@link ManiacMainMenuBackground}); око й картку
 * «ЗАВДАННЯ» не малюємо — вони потрібні лише в матчі, і закривали б картинку.
 *
 * ── Кнопки ───────────────────────────────────────────────────────────
 * Список збирається в {@link #buildButtons()}: нова кнопка = один
 * {@code new Btn(...)} + два ключі в lang (мітка і опис). Розкладка
 * підлаштовується сама. Перша кнопка — «головна» (більша).
 *
 * ── Ввід ─────────────────────────────────────────────────────────────
 * Миша, а також ↑ ↓ / Tab і Enter / Пробіл. Esc нічого не робить: з
 * головного меню нікуди повертатись.
 */
@OnlyIn(Dist.CLIENT)
public final class ManiacTitleScreen extends Screen {

    // ── Палітра (та сама, що в ManiacEscMenuScreen) ─────────────────────
    private static final int SILVER = 0xFFF2F4F8;
    private static final int BODY = 0xFFB9B9B9;
    private static final int DIM = 0xFF7A7A7A;
    private static final int PANEL = 0xFF0B0D0E;
    private static final int BORDER = 0xFF292D2E;
    private static final int DANGER_TEXT = 0xFFE05A60;
    private static final int SHADE = 0xFF050607;

    private static final int YT = 0xFFFF2A2A;
    private static final int DC = 0xFF5865F2;
    private static final int TG = 0xFF229ED9;

    // ── Посилання (ті самі, що в ManiacEscMenuScreen) ────────────────────
    private static final String URL_YOUTUBE = "https://www.youtube.com/@shaurm_official";
    private static final String URL_DISCORD = "https://discord.gg/fzwpnG8Nw9";
    private static final String URL_TELEGRAM = "https://t.me/shaurma_official22";

    /** М'яке виринання з чорного при відкритті, мс. */
    private static final float FADE_IN_MS = 600f;

    /** Кнопка меню. {@code action} виконується на клік/Enter. */
    private record Btn(String labelKey, String descKey, boolean primary, boolean danger,
                       Runnable action) {}

    private static String versionCache;

    private final long openedAt = Util.getMillis();
    private final long[] frameClock = new long[1];
    private final List<Btn> buttons = new ArrayList<>();
    private final float[] hover = new float[16];
    private final float[] socialHover = new float[3];

    private int selected = -1;
    private int lastMx = Integer.MIN_VALUE;
    private int lastMy = Integer.MIN_VALUE;
    private long lastHoverSoundMs;

    private String typedKey = "";
    private long typedSince;

    // ── Розкладка (рахується в layout()) ─────────────────────────────────
    private int lm;
    private int bw;
    private int slant;
    private int primH;
    private int normH;
    private int gap;
    private float titleScale;
    private int overlineY;
    private int titleY;
    private int subY;
    private int statusY;
    private int hintY;
    private int socialY;
    private final int[] btnY = new int[16];

    public ManiacTitleScreen() {
        super(Component.translatable("maniacmod.esc.brand.title"));
    }

    // ── Життєвий цикл ───────────────────────────────────────────────────

    @Override
    protected void init() {
        buildButtons();
        layout();
    }

    private void buildButtons() {
        buttons.clear();
        buttons.add(new Btn("maniacmod.title.multiplayer", "maniacmod.title.multiplayer.desc",
            true, false, () -> minecraft.setScreen(new JoinMultiplayerScreen(this))));
        buttons.add(new Btn("maniacmod.title.singleplayer", "maniacmod.title.singleplayer.desc",
            false, false, () -> minecraft.setScreen(new SelectWorldScreen(this))));
        buttons.add(new Btn("maniacmod.esc.settings", "maniacmod.esc.settings.desc",
            false, false, () -> minecraft.setScreen(new OptionsScreen(this, minecraft.options))));
        buttons.add(new Btn("maniacmod.title.mods", "maniacmod.title.mods.desc",
            false, false, () -> minecraft.setScreen(new ModListScreen(this))));
        buttons.add(new Btn("maniacmod.title.quit", "maniacmod.title.quit.desc",
            false, true, () -> minecraft.stop()));
    }

    private void layout() {
        boolean compact = height < 340;
        lm = Math.max(26, (int) (width * 0.07f));
        slant = compact ? 5 : 7;
        primH = compact ? 28 : 34;
        normH = compact ? 20 : 25;
        gap = compact ? 4 : 6;
        bw = Mth.clamp(width / 3, 230, 300);
        titleScale = compact ? 3f : 4f;

        int n = buttons.size();
        int buttonsH = primH + Math.max(0, n - 1) * (normH + gap) + gap;
        int brandH = 12 + (int) (9 * titleScale) + 20 + 14 + 26;
        int total = brandH + buttonsH + 14 + 16 + 26 + 18;
        int top = Math.max(16, (height - total) / 2);

        overlineY = top;
        titleY = top + 12;
        subY = titleY + (int) (9 * titleScale) + 20;
        statusY = subY + 14;
        int y = statusY + 26;
        for (int i = 0; i < n && i < btnY.length; i++) {
            btnY[i] = y;
            y += (i == 0 ? primH : normH) + gap;
        }
        hintY = y + 6;
        socialY = hintY + 16;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    /** З головного меню нікуди повертатись — Esc нічого не закриває. */
    @Override
    public boolean shouldCloseOnEsc() { return false; }

    // ── Рендер ──────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        long now = Util.getMillis();
        long age = now - openedAt;

        float dt = UiAnim.frameDt(frameClock);
        layout();
        updateInput(mx, my, dt);

        renderScene(g, now);
        renderDecor(g, now, age);
        renderBrand(g, now, age);
        renderButtons(g, now, age);
        renderHintAndSocials(g, now, age, mx, my);

        // підказка внизу ліворуч — клавіші-«кепи»
        renderKeyHints(g, Math.min(1f, age / 900f));

        // м'яке виринання з чорного: фон і меню з'являються разом
        float fade = UiAnim.easeOutCubic(age / FADE_IN_MS);
        if (fade < 1f) g.fill(0, 0, width, height, BloodFx.a(0xFF000000, 1f - fade));
    }

    /** Картинка, що їде, + затемнення для читабельності лівої колонки. */
    private void renderScene(GuiGraphics g, long now) {
        if (!ManiacMainMenuBackground.renderShared(g, width, height)) {
            // запасний фон, якщо картинки нема: темний градієнт у палітрі меню
            g.fillGradient(0, 0, width, height, 0xFF0B0809, 0xFF030304);
        }

        // загальне затемнення
        g.fill(0, 0, width, height, BloodFx.a(SHADE, 0.30f));

        // ліва колонка: щільно біля краю, до нуля на ~62% ширини — праворуч картинка лишається видно
        int span = (int) (width * 0.62f);
        final int step = 3;
        for (int x = 0; x < span; x += step) {
            float k = 1f - x / (float) span;
            g.fill(x, 0, Math.min(span, x + step), height, BloodFx.a(SHADE, 0.82f * k * k));
        }

        // верх і низ
        int vh = (int) (height * 0.24f);
        g.fillGradient(0, 0, width, vh, BloodFx.a(0xFF020203, 0.60f), 0x00020203);
        g.fillGradient(0, height - vh, width, height, 0x00020203, BloodFx.a(0xFF020203, 0.70f));

        // тепла імла знизу — те саме, що в HorrorBackdrop
        int fogH = (int) (height * 0.36f);
        g.fillGradient(0, height - fogH, width, height, 0x00500810,
            BloodFx.a(0xFF500810, 0.16f + 0.05f * beat(now)));
    }

    /** Наведення мишею, клавіатурний вибір, плавні значення hover. */
    private void updateInput(int mx, int my, float dt) {
        if (mx != lastMx || my != lastMy) {
            lastMx = mx;
            lastMy = my;
            int idx = buttonAt(mx, my);
            if (idx != selected) {
                selected = idx;
                if (idx >= 0) hoverSound();
            }
        }
        for (int i = 0; i < buttons.size() && i < hover.length; i++) {
            hover[i] = UiAnim.approach(hover[i], i == selected ? 1f : 0f, 16f, dt);
        }
        for (int i = 0; i < 3; i++) {
            socialHover[i] = UiAnim.approach(socialHover[i], socialAt(mx, my) == i ? 1f : 0f, 16f, dt);
        }
    }

    private void hoverSound() {
        long now = Util.getMillis();
        if (now - lastHoverSoundMs < 60L) return;
        lastHoverSoundMs = now;
        UiAnim.playMenuHover();
    }

    /** Повільне серцебиття «туп-ту-туп» (~0.8 Гц), гладка функція 0..1 — та сама, що в HorrorBackdrop. */
    private float beat(long now) {
        float phase = ((now - openedAt) / 1000f * 0.8f) % 1f;
        float b1 = (float) Math.exp(-Math.pow((phase - 0.08f) / 0.10f, 2));
        float b2 = 0.55f * (float) Math.exp(-Math.pow((phase - 0.34f) / 0.11f, 2));
        return Math.min(1f, b1 + b2);
    }

    // ── Бренд ───────────────────────────────────────────────────────────

    private void renderBrand(GuiGraphics g, long now, long age) {
        float beat = beat(now);
        float appear = UiAnim.clamp01(age / 500f);

        // надрядок: червона цятка + «ГОЛОВНЕ МЕНЮ»
        BloodFx.blob(g, lm + 2.5f, overlineY + 4f, 2.2f + beat * 0.8f, BloodFx.a(BloodFx.BRIGHT, appear));
        text(g, I18n.get("maniacmod.title.overline"), lm + 9, overlineY, 0.85f, BloodFx.a(BODY, appear), false);

        // назва режиму: літери падають зверху й відскакують
        String title = I18n.get("maniacmod.esc.brand.title");
        float totalW = 0f;
        for (int i = 0; i < title.length(); i++) {
            totalW += this.font.width(String.valueOf(title.charAt(i))) * titleScale;
            if (i < title.length() - 1) totalW += 3f;
        }

        float cx = lm;
        for (int i = 0; i < title.length(); i++) {
            String ch = String.valueOf(title.charAt(i));
            float cw = this.font.width(ch) * titleScale;
            float t = UiAnim.clamp01((age - 90L - i * 85L) / 520f);
            float yOff = -(1f - bounce(t)) * 90f;
            float alpha = UiAnim.clamp01(t * 3f);
            if (alpha > 0.02f) {
                g.pose().pushPose();
                g.pose().translate(cx, titleY + yOff, 60f);
                g.pose().scale(titleScale, titleScale, 1f);
                rawText(g, ch, 2, 2, BloodFx.a(BloodFx.MID, alpha));
                rawText(g, ch, 0, 0, BloodFx.a(SILVER, alpha));
                g.pose().popPose();
            }
            cx += cw + 3f;
        }

        // кров стікає з літер
        long dripStart = 90L + title.length() * 85L + 520L;
        if (age > dripStart) {
            int drips = Math.max(5, (int) (totalW / 22f));
            int y0 = titleY + (int) (7.4f * titleScale);
            for (int k = 0; k < drips; k++) {
                float s = BloodFx.hash01(1000L + k * 77L);
                float grow = UiAnim.easeOutCubic((age - dripStart - k * 140L) / 900f);
                if (grow <= 0f) continue;
                float maxLen = 8f + s * 26f;
                float len = maxLen * grow + 1.4f * (float) Math.sin(now / 800.0 + k) - 0.5f;
                int x = lm + 2 + Math.round((k + 0.25f + s * 0.5f) * (totalW - 6f) / drips);
                BloodFx.drip(g, x, y0, 2 + (k % 2), len, now, s, 1f);
            }
        }

        // «хто створив» + тонка лінія, що тягнеться до краю колонки
        float subA = UiAnim.clamp01((age - 700L) / 500f);
        String sub = I18n.get("maniacmod.esc.brand.sub");
        text(g, sub, lm, subY, 0.9f, BloodFx.a(BODY, subA), false);
        int lineX = lm + Math.round(this.font.width(sub) * 0.9f) + 8;
        int lineW = Math.max(0, (int) ((bw - (lineX - lm)) * 0.8f * UiAnim.easeOutCubic(subA)));
        g.fillGradient(lineX, subY + 4, lineX + lineW, subY + 5, BloodFx.a(BORDER, subA), 0x00292D2E);

        // чіп: версія мода
        float chipA = UiAnim.clamp01((age - 850L) / 500f);
        String chip = I18n.get("maniacmod.title.chip", modVersion());
        int chipW = Math.round(this.font.width(chip) * 0.9f) + 22;
        g.fill(lm, statusY, lm + chipW, statusY + 15, BloodFx.a(PANEL, 0.85f * chipA));
        BloodFx.border1(g, lm, statusY, chipW, 15, BloodFx.a(BORDER, chipA));
        g.fill(lm, statusY, lm + 2, statusY + 15, BloodFx.a(BloodFx.BRIGHT, chipA));
        BloodFx.blob(g, lm + 9f, statusY + 7.5f, 1.8f + beat, BloodFx.a(BloodFx.HOT, chipA));
        text(g, chip, lm + 16, statusY + 4, 0.9f, BloodFx.a(SILVER, chipA), false);
    }

    private static String modVersion() {
        if (versionCache == null) {
            String v = "";
            ModList list = ModList.get();
            if (list != null) {
                v = list.getModContainerById(ManiacMod.MOD_ID)
                    .map(c -> c.getModInfo().getVersion().toString())
                    .orElse("");
            }
            versionCache = v;
        }
        return versionCache;
    }

    // ── Кнопки ──────────────────────────────────────────────────────────

    private void renderButtons(GuiGraphics g, long now, long age) {
        for (int i = 0; i < buttons.size() && i < btnY.length; i++) {
            Btn b = buttons.get(i);
            int h = b.primary() ? primH : normH;
            float delay = 420f + i * 75f;
            float t = UiAnim.clamp01((age - delay) / 420f);
            if (t <= 0f) continue;

            float e = UiAnim.easeOutBack(t, 0.7f);
            float xOff = -(1f - e) * (bw + 60f);
            float alpha = UiAnim.clamp01(t * 2f);
            float hv = hover[i];

            int x = lm + Math.round(xOff);
            int y = btnY[i];

            int bodyCol = b.danger() ? 0xD8140A0C : 0xD80B0D0E;
            slantFill(g, x, y, bw, h, BloodFx.a(bodyCol, alpha));

            // лезо: червона смуга, що виїжджає зліва при наведенні
            int bladeBase = b.primary() ? BloodFx.BRIGHT : (b.danger() ? 0xFF9C1018 : BloodFx.MID);
            float blade = UiAnim.easeOutCubic(hv);
            if (blade > 0.01f) {
                int bladeW = Math.round(bw * blade);
                slantFill(g, x, y, bladeW, h, BloodFx.a(bladeBase, (b.primary() ? 0.92f : 0.80f) * alpha));
                slantFill(g, x + Math.max(0, bladeW - 3), y, Math.min(3, bladeW), h,
                    BloodFx.a(BloodFx.HOT, 0.75f * alpha * blade));
                // ковзний блиск по лезу
                float f = (now % 900L) / 900f;
                int sx = x + Math.round((bw - 14) * f);
                if (sx + 14 <= x + bladeW) slantFill(g, sx, y, 14, h, BloodFx.a(0x30FFFFFF, alpha * hv));
            }

            // рамка: паралелограм
            int borderCol = BloodFx.mix(BORDER, b.danger() ? 0xFFE0303A : BloodFx.HOT, hv * 0.9f);
            slantBorder(g, x, y, bw, h, BloodFx.a(borderCol, alpha));

            // ліва акцентна смужка
            int accent = b.primary() || hv > 0.5f ? BloodFx.BRIGHT : (b.danger() ? 0xFF6E0C12 : 0xFF3A3F47);
            slantFill(g, x, y, 3, h, BloodFx.a(accent, alpha));

            // номер
            String idx = String.format(Locale.ROOT, "%02d", i + 1);
            text(g, idx, x + slant + 8, y + (h - 7) / 2f, 0.75f,
                BloodFx.a(hv > 0.4f ? SILVER : DIM, alpha), false);

            // підпис
            String label = I18n.get(b.labelKey());
            float ls = b.primary() ? 1.25f : 1f;
            int labelCol = hv > 0.4f ? 0xFFFFFFFF : (b.danger() ? DANGER_TEXT : (b.primary() ? SILVER : BODY));
            text(g, label, x + slant + 30, y + (h - 9 * ls) / 2f + 0.5f, ls, BloodFx.a(labelCol, alpha), true);

            // стрілка
            if (hv > 0.02f) {
                text(g, "›", x + bw - 18 + hv * 4f, y + (h - 9 * ls) / 2f, ls,
                    BloodFx.a(0xFFFFFFFF, alpha * hv), false);
            }
        }
    }

    /** Паралелограм «/»: верх зсунутий праворуч на {@code slant}. Кожен рядок — окремий fill. */
    private void slantFill(GuiGraphics g, int x, int y, int w, int h, int argb) {
        if (w <= 0 || (argb >>> 24) == 0) return;
        for (int row = 0; row < h; row++) {
            int off = Math.round(slant * (h - 1 - row) / (float) Math.max(1, h - 1));
            g.fill(x + off, y + row, x + off + w, y + row + 1, argb);
        }
    }

    private void slantBorder(GuiGraphics g, int x, int y, int w, int h, int argb) {
        if ((argb >>> 24) == 0) return;
        for (int row = 0; row < h; row++) {
            int off = Math.round(slant * (h - 1 - row) / (float) Math.max(1, h - 1));
            g.fill(x + off, y + row, x + off + 1, y + row + 1, argb);
            g.fill(x + off + w - 1, y + row, x + off + w, y + row + 1, argb);
        }
        g.fill(x + slant, y, x + slant + w, y + 1, argb);
        g.fill(x, y + h - 1, x + w, y + h, argb);
    }

    // ── Опис під кнопками і соцмережі ───────────────────────────────────

    private void renderHintAndSocials(GuiGraphics g, long now, long age, int mx, int my) {
        // друкований опис кнопки під курсором
        String key = selected >= 0 && selected < buttons.size() ? buttons.get(selected).descKey() : "";
        int social = socialAt(mx, my);
        if (social >= 0) key = "maniacmod.esc.social." + (social == 0 ? "youtube" : social == 1 ? "discord" : "telegram") + ".tip";
        if (!key.equals(typedKey)) {
            typedKey = key;
            typedSince = now;
        }
        if (!key.isEmpty()) {
            String full = I18n.get(key);
            int shown = Math.min(full.length(), (int) ((now - typedSince) / 14L));
            String cursor = (shown < full.length() || (now / 450L) % 2L == 0L) ? "_" : " ";
            text(g, "▸ ", lm, hintY, 0.9f, BloodFx.a(BloodFx.BRIGHT, 1f), false);
            text(g, full.substring(0, shown) + cursor, lm + 10, hintY, 0.9f, BloodFx.a(BODY, 1f), false);
        }

        // соцмережі
        int[] colors = {YT, DC, TG};
        for (int i = 0; i < 3; i++) {
            float t = UiAnim.clamp01((age - 900L - i * 90L) / 350f);
            if (t <= 0f) continue;
            int x = lm + i * 40;
            int y = socialY + Math.round((1f - UiAnim.easeOutCubic(t)) * 10f);
            float h = socialHover[i];
            g.fill(x, y, x + 34, y + 22, BloodFx.a(PANEL, 0.88f * t));
            g.fill(x, y, x + 34, y + 22, BloodFx.a((0xFF << 24) | (colors[i] & 0xFFFFFF), (0.10f + 0.40f * h) * t));
            BloodFx.border1(g, x, y, 34, 22, BloodFx.a((0xFF << 24) | (colors[i] & 0xFFFFFF), (0.55f + 0.45f * h) * t));
            g.fill(x, y + 21, x + Math.round(34 * h), y + 22, BloodFx.a(0xFFFFFFFF, 0.8f * t));
            sprite(g, SOCIAL_ICONS[i], x + 10, y + (22 - SOCIAL_ICONS[i].length) / 2,
                BloodFx.a(0xFFFFFFFF, (0.78f + 0.22f * h) * t));
        }
    }

    // ── Оздоблення ──────────────────────────────────────────────────────

    /** Піксельні значки соцмереж 14 px завширшки: YouTube, Discord, Telegram. '#' — зафарбований піксель. */
    private static final String[][] SOCIAL_ICONS = {
        {
            "..##########..",
            ".############.",
            "#####.########",
            "#####..#######",
            "#####...######",
            "#####...######",
            "#####..#######",
            "#####.########",
            ".############.",
            "..##########.."
        },
        {
            "..##########..",
            ".############.",
            "##############",
            "###..####..###",
            "###..####..###",
            "###..####..###",
            "##############",
            "##############",
            ".####....####.",
            "..##......##.."
        },
        {
            "............##",
            "..........####",
            "........######",
            "......########",
            "....##########",
            "..############",
            "##############",
            "....####.#####",
            ".......###.###",
            ".......##..###",
            "........#..##."
        }
    };

    /** Малює піксельний спрайт: послідовності '#' в рядку зливаються в один fill. */
    private static void sprite(GuiGraphics g, String[] rows, int x, int y, int argb) {
        for (int r = 0; r < rows.length; r++) {
            String row = rows[r];
            int c = 0;
            while (c < row.length()) {
                if (row.charAt(c) != '#') { c++; continue; }
                int start = c;
                while (c < row.length() && row.charAt(c) == '#') c++;
                g.fill(x + start, y + r, x + c, y + r + 1, argb);
            }
        }
    }

    /** Кутова скоба 2 px завтовшки; dx/dy = ±1 — у який бік від кута (x,y) росте. */
    private static void cornerMark(GuiGraphics g, int x, int y, int dx, int dy, int len, int argb) {
        g.fill(dx > 0 ? x : x - len, dy > 0 ? y : y - 2, dx > 0 ? x + len : x, dy > 0 ? y + 2 : y, argb);
        g.fill(dx > 0 ? x : x - 2, dy > 0 ? y : y - len, dx > 0 ? x + 2 : x, dy > 0 ? y + len : y, argb);
    }

    /** Фонові деталі: скан-лайни, вертикальна лінія з рухомою цяткою, рамкові кути, підпис студії. */
    private void renderDecor(GuiGraphics g, long now, long age) {
        float a = UiAnim.clamp01((age - 300L) / 700f);

        // ледь помітні скан-лайни по всьому екрану
        for (int y = 0; y < height; y += 3) g.fill(0, y, width, y + 1, 0x0D000000);

        // вертикальна лінія по лівому краю колонки: згасає до країв
        int dx = lm - 14;
        int y0 = (int) (height * 0.08f);
        int ym = height / 2;
        int y1 = (int) (height * 0.92f);
        g.fillGradient(dx, y0, dx + 1, ym, 0x00C8161F, BloodFx.a(BloodFx.BRIGHT, 0.55f * a));
        g.fillGradient(dx, ym, dx + 1, y1, BloodFx.a(BloodFx.BRIGHT, 0.55f * a), 0x00C8161F);
        // цятка, що повільно їде вниз, лишаючи хвіст
        float f = (now % 7000L) / 7000f;
        int py = y0 + Math.round((y1 - y0) * f);
        g.fillGradient(dx, py - 14, dx + 1, py, 0x00FF4A52, BloodFx.a(BloodFx.HOT, 0.8f * a));
        g.fill(dx - 1, py, dx + 2, py + 3, BloodFx.a(BloodFx.HOT, 0.9f * a));

        // рамкові кути праворуч (ліворуч їх заміняє лінія)
        int m = 14;
        int c = BloodFx.a(BloodFx.BRIGHT, 0.45f * a);
        cornerMark(g, width - m, m, -1, 1, 16, c);
        cornerMark(g, width - m, height - m, -1, -1, 16, c);

        // підпис студії внизу праворуч
        String studio = "© SHAURMA";
        float sw = this.font.width(studio) * 0.85f;
        float sx = width - m - 8 - sw;
        float sy = height - m - 12;
        BloodFx.blob(g, sx - 8f, sy + 3.5f, 1.8f, BloodFx.a(BloodFx.BRIGHT, a));
        text(g, studio, sx, sy, 0.85f, BloodFx.a(DIM, a), false);
    }

    /** Клавіша-«кеп»: рамка, нижній «борт» і підпис. Повертає x для наступного елемента. */
    private int keycap(GuiGraphics g, String label, int x, int y, float a) {
        int w = Math.round(this.font.width(label) * 0.8f) + 9;
        int h = 13;
        g.fill(x, y + h, x + w, y + h + 1, BloodFx.a(0xFF1B1E20, a));
        g.fill(x, y, x + w, y + h, BloodFx.a(PANEL, 0.92f * a));
        BloodFx.border1(g, x, y, w, h, BloodFx.a(0xFF3A3F47, a));
        text(g, label, x + 4.5f, y + 3.5f, 0.8f, BloodFx.a(SILVER, a), false);
        return x + w + 4;
    }

    private void renderKeyHints(GuiGraphics g, float a) {
        if (a <= 0f) return;
        int y = height - 22;
        int x = keycap(g, "↑", lm, y, a);
        x = keycap(g, "↓", x, y, a);
        x = keycap(g, "ENTER", x, y, a);
        text(g, I18n.get("maniacmod.title.hint.choose"), x + 3, y + 3.5f, 0.85f, BloodFx.a(DIM, a), false);
    }

    // ── Введення ────────────────────────────────────────────────────────

    private boolean ready(long age, int i) {
        return age >= 420L + i * 75L + 200L;
    }

    private int buttonAt(int mx, int my) {
        long age = Util.getMillis() - openedAt;
        for (int i = 0; i < buttons.size() && i < btnY.length; i++) {
            int h = i == 0 ? primH : normH;
            if (ready(age, i) && mx >= lm && mx < lm + bw + slant && my >= btnY[i] && my < btnY[i] + h) return i;
        }
        return -1;
    }

    private int socialAt(int mx, int my) {
        for (int i = 0; i < 3; i++) {
            int x = lm + i * 40;
            if (mx >= x && mx < x + 34 && my >= socialY && my < socialY + 22) return i;
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);

        int idx = buttonAt((int) mx, (int) my);
        if (idx >= 0) {
            activate(idx);
            return true;
        }
        int s = socialAt((int) mx, (int) my);
        if (s >= 0) {
            UiAnim.playClick();
            openUrl(s == 0 ? URL_YOUTUBE : s == 1 ? URL_DISCORD : URL_TELEGRAM);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        int n = buttons.size();
        switch (keyCode) {
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_TAB -> {
                selected = selected < 0 ? 0 : (selected + 1) % n;
                hoverSound();
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                selected = selected < 0 ? n - 1 : (selected - 1 + n) % n;
                hoverSound();
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                if (selected >= 0 && selected < n) {
                    activate(selected);
                    return true;
                }
            }
            default -> { }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void activate(int idx) {
        Btn b = buttons.get(idx);
        if (!b.danger()) UiAnim.playClick();
        b.action().run();
    }

    private static void openUrl(String url) {
        try {
            Util.getPlatform().openUri(new URI(url));
        } catch (Exception ignored) {
            // немає браузера/некоректний URL — мовчки, меню лишається
        }
    }

    // ── Малювання тексту ────────────────────────────────────────────────

    /** Текст із масштабом; повністю прозорий (alpha &lt; 4) пропускає — інакше vanilla робить його непрозорим. */
    private void text(GuiGraphics g, String s, float x, float y, float scale, int argb, boolean shadow) {
        if ((argb >>> 24) < 4 || s.isEmpty()) return;
        if (scale == 1f) {
            g.drawString(this.font, s, Math.round(x), Math.round(y), argb, shadow);
            return;
        }
        g.pose().pushPose();
        g.pose().translate(x, y, 0f);
        g.pose().scale(scale, scale, 1f);
        g.drawString(this.font, s, 0, 0, argb, shadow);
        g.pose().popPose();
    }

    /** Текст у вже масштабованій матриці (без власного push/scale). */
    private void rawText(GuiGraphics g, String s, int x, int y, int argb) {
        if ((argb >>> 24) < 4) return;
        g.drawString(this.font, s, x, y, argb, false);
    }

    /** easeOutBounce: падіння літери з відскоком. */
    private static float bounce(float t) {
        float x = UiAnim.clamp01(t);
        final float n1 = 7.5625f;
        final float d1 = 2.75f;
        if (x < 1f / d1) return n1 * x * x;
        if (x < 2f / d1) { x -= 1.5f / d1; return n1 * x * x + 0.75f; }
        if (x < 2.5f / d1) { x -= 2.25f / d1; return n1 * x * x + 0.9375f; }
        x -= 2.625f / d1;
        return n1 * x * x + 0.984375f;
    }
}
