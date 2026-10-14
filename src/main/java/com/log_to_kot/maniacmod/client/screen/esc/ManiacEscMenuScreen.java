package com.log_to_kot.maniacmod.client.screen.esc;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.screen.rating.ClientManiacRatingState;
import com.log_to_kot.maniacmod.client.screen.rating.ManiacRatingScreen;
import com.log_to_kot.maniacmod.client.style.BloodFx;
import com.log_to_kot.maniacmod.client.style.CircleFx;
import com.log_to_kot.maniacmod.client.style.HorrorBackdrop;
import com.log_to_kot.maniacmod.client.style.ManiacTabTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.UiFx;
import com.log_to_kot.maniacmod.core.match.MatchObjectives;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.map.zones.GeneratorPoi;
import com.log_to_kot.maniacmod.maniacs.ManiacRegistry;
import com.log_to_kot.maniacmod.net.s2c.matchstate.ObjectiveSyncPacket;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Кастомне ESC-меню маньяка — замість ванільного {@code PauseScreen}
 * (підміну робить {@link ManiacEscInterceptor}).
 *
 * ── Що на екрані ─────────────────────────────────────────────────────
 *   • фон {@link HorrorBackdrop}: кров стікає з верху екрана, затемнення
 *     і віньєтка б'ються з серцем, розгортка, зерно, глітчі;
 *   • ліворуч — бренд: назва режиму ВЕЛИКИМИ літерами, що падають
 *     зверху й б'ються об «підлогу», з них стікає кров; під назвою —
 *     «хто створив» і чіп стану (фаза · роль);
 *   • під брендом — кнопки-«леза»: похилі смуги, по яких при наведенні
 *     вмикається червоне лезо; клавіатура (↑ ↓ Enter) працює так само;
 *   • праворуч — велике Око, що стежить за курсором і кліпає, та
 *     картка «ЗАВДАННЯ» — те саме, що показує Tab: що робити за ролі й
 *     фази, «N / M» генераторів і лише важливі для ролі віхи;
 *   • внизу — друкований опис кнопки під курсором і соцмережі.
 *
 * ── Відмінності від снайперів ────────────────────────────────────────
 * Там — центрована коробка з сіткою кнопок. Тут — ліва колонка, кожна
 * кнопка має свій стан, а «Відключитися» вимагає ПОДВІЙНОГО натискання
 * (перше — озброює кнопку на 2.5 с), щоб випадковий клік не викинув
 * гравця посеред матчу.
 *
 * ── Вихід і оцінки ───────────────────────────────────────────────────
 * Логіка оцінок — як у снайперів: якщо гравець ще не оцінював і не
 * натискав «пізніше», вихід спершу відкриває {@link ManiacRatingScreen}.
 * Див. {@link ClientManiacRatingState#shouldShowRatingOnExit()}.
 *
 * ── Esc ──────────────────────────────────────────────────────────────
 * Закриває меню з анімацією (кров втягується вгору, ~0.2 с), без звуку.
 * Повернення з підекранів (оцінки, «Скоро», адмін-відгуки) будує меню з
 * {@code instant = true} — вступна анімація вдруге не програється, а
 * кнопки відразу активні. Сам екран
 * НЕ ставить гру на паузу ({@link #isPauseScreen()} = false) — матч
 * іде далі, як і у снайперів.
 */
@OnlyIn(Dist.CLIENT)
public final class ManiacEscMenuScreen extends Screen {

    // ── Палітра ─────────────────────────────────────────────────────────
    private static final int SILVER = 0xFFF2F4F8;
    private static final int BODY = 0xFFB9B9B9;
    private static final int DIM = 0xFF7A7A7A;
    private static final int PANEL = 0xFF0B0D0E;
    private static final int BORDER = 0xFF292D2E;
    private static final int DANGER_TEXT = 0xFFE05A60;

    private static final int YT = 0xFFFF2A2A;
    private static final int DC = 0xFF5865F2;
    private static final int TG = 0xFF229ED9;

    // ── Посилання (змінюються тут) ───────────────────────────────────────
    private static final String URL_YOUTUBE = "https://www.youtube.com/@shaurm_official";
    private static final String URL_DISCORD = "https://discord.gg/fzwpnG8Nw9";
    private static final String URL_TELEGRAM = "https://t.me/shaurma_official22";

    private static final long CLOSE_MS = 220L;
    private static final long LEAVE_ARM_MS = 2500L;
    /** Скільки мс триває вся вступна хореографія — на стільки «зістарюємо» меню при instant. */
    private static final long INTRO_MS = 4500L;

    /** Кнопка меню. {@code action} виконується на клік/Enter. */
    private record Btn(String id, String labelKey, String descKey, boolean primary, boolean danger,
                       Runnable action) {}

    private final HorrorBackdrop backdrop = new HorrorBackdrop();
    private long openedAt = Util.getMillis();
    private final long[] frameClock = new long[1];
    private final List<Btn> buttons = new ArrayList<>();
    private final float[] hover = new float[16];
    private final float[] socialHover = new float[3];

    private long closeStart = -1L;
    private int selected = -1;
    private int lastMx = Integer.MIN_VALUE;
    private int lastMy = Integer.MIN_VALUE;
    private long leaveArmedUntil;
    private long lastHoverSoundMs;

    private String typedKey = "";
    private long typedSince;

    // ── Розкладка (рахується в layout()) ─────────────────────────────────
    private boolean wide;
    private boolean compact;
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

    public ManiacEscMenuScreen() {
        this(false);
    }

    /** @param instant true — без вступної анімації (повернення з підекрана). */
    public ManiacEscMenuScreen(boolean instant) {
        super(Component.translatable("maniacmod.esc.brand.title"));
        if (instant) openedAt -= INTRO_MS;
    }

    // ── Життєвий цикл ───────────────────────────────────────────────────

    @Override
    protected void init() {
        buildButtons();
        layout();
    }

    private void buildButtons() {
        buttons.clear();
        boolean gameplay = ClientMatchState.isGameplay();
        // «Полювання» — це про маньяка; вижилий повертається до втечі.
        String returnKey = !gameplay ? "maniacmod.esc.return.lobby"
            : ClientMatchState.isSurvivor() ? "maniacmod.esc.return.game.survivor"
            : "maniacmod.esc.return.game";
        buttons.add(new Btn("return", returnKey,
            "maniacmod.esc.return.desc", true, false, this::beginClose));
        buttons.add(new Btn("settings", "maniacmod.esc.settings", "maniacmod.esc.settings.desc",
            false, false, () -> minecraft.setScreen(new OptionsScreen(this, minecraft.options))));
        buttons.add(new Btn("profile", "maniacmod.esc.profile", "maniacmod.esc.profile.desc",
            false, false, () -> minecraft.setScreen(
                new com.log_to_kot.maniacmod.client.screen.profile.ProfileScreen(new ManiacEscMenuScreen(true)))));
        // Архів ВСІХ матчів — лише адмін. Клієнт лише ховає кнопку: сервер перевіряє права на кожен запит.
        if (minecraft != null && minecraft.player != null && minecraft.player.hasPermissions(2)) {
            buttons.add(new Btn("history", "maniacmod.esc.history", "maniacmod.esc.history.desc",
                false, false, () -> minecraft.setScreen(
                    new com.log_to_kot.maniacmod.client.screen.history.MatchHistoryScreen(new ManiacEscMenuScreen(true)))));
        }
        buttons.add(new Btn("leaderboard", "maniacmod.esc.leaderboard", "maniacmod.esc.leaderboard.desc",
            false, false, () -> minecraft.setScreen(
                new com.log_to_kot.maniacmod.client.screen.leaderboard.LeaderboardScreen(new ManiacEscMenuScreen(true)))));
        if (ClientManiacRatingState.get().showRateButtonInEsc()) {
            buttons.add(new Btn("rate", "maniacmod.esc.rate", "maniacmod.esc.rate.desc",
                false, false, () -> minecraft.setScreen(new ManiacRatingScreen(false))));
        }
        buttons.add(new Btn("leave", "maniacmod.esc.leave", "maniacmod.esc.leave.desc",
            false, true, this::onLeavePressed));
    }

    private void layout() {
        wide = width >= 700 && height >= 300;
        compact = height < 340;
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

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    @Override
    public void onClose() { beginClose(); }

    private void beginClose() {
        if (closeStart >= 0L) return;
        closeStart = Util.getMillis();
    }

    // ── Рендер ──────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        long now = Util.getMillis();
        long age = now - openedAt;

        float life = 1f;
        if (closeStart >= 0L) {
            float p = UiAnim.clamp01((now - closeStart) / (float) CLOSE_MS);
            life = 1f - p * p * p;
            if (p >= 1f) {
                minecraft.setScreen(null);
                return;
            }
        }

        float dt = UiAnim.frameDt(frameClock);
        layout();
        updateInput(mx, my, age, dt);

        backdrop.render(g, width, height, life);
        if (wide) {
            renderEye(g, mx, my, now, age, life);
            renderInfoCard(g, now, age, life);
        }
        renderBrand(g, now, age, life);
        renderButtons(g, now, age, life);
        renderHintAndSocials(g, now, age, life, mx, my);

        // підказка внизу ліворуч
        text(g, I18n.get("maniacmod.esc.hint.close"), lm, height - 16, 1f,
            BloodFx.a(DIM, Math.min(1f, age / 900f) * life), false);
    }

    /** Наведення мишею, клавіатурний вибір, плавні значення hover. */
    private void updateInput(int mx, int my, long age, float dt) {
        if (mx != lastMx || my != lastMy) {
            lastMx = mx;
            lastMy = my;
            lastMoveMs = Util.getMillis();
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
        if (leaveArmedUntil != 0L && Util.getMillis() > leaveArmedUntil) leaveArmedUntil = 0L;
    }

    private void hoverSound() {
        long now = Util.getMillis();
        if (now - lastHoverSoundMs < 60L) return;
        lastHoverSoundMs = now;
        UiAnim.playMenuHover();
    }

    // ── Бренд ───────────────────────────────────────────────────────────

    private void renderBrand(GuiGraphics g, long now, long age, float life) {
        float beat = backdrop.beat(now);
        float appear = UiAnim.clamp01(age / 500f) * life;

        // надрядок: червона цятка + «ПАУЗА»
        BloodFx.blob(g, lm + 2.5f, overlineY + 4f, 2.2f + beat * 0.8f, BloodFx.a(BloodFx.BRIGHT, appear));
        text(g, I18n.get("maniacmod.esc.overline"), lm + 9, overlineY, 0.85f, BloodFx.a(BODY, appear), false);

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
            float alpha = UiAnim.clamp01(t * 3f) * life;
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
                BloodFx.drip(g, x, y0, 2 + (k % 2), len, now, s, life);
            }
        }

        // «хто створив»
        text(g, I18n.get("maniacmod.esc.brand.sub"), lm, subY, 0.9f,
            BloodFx.a(BODY, UiAnim.clamp01((age - 700L) / 500f) * life), false);

        // чіп стану: фаза · роль
        float chipA = UiAnim.clamp01((age - 850L) / 500f) * life;
        String chip = phaseName() + "  ·  " + roleName();
        int chipW = Math.round(this.font.width(chip) * 0.9f) + 22;
        g.fill(lm, statusY, lm + chipW, statusY + 15, BloodFx.a(PANEL, 0.85f * chipA));
        BloodFx.border1(g, lm, statusY, chipW, 15, BloodFx.a(BORDER, chipA));
        g.fill(lm, statusY, lm + 2, statusY + 15, BloodFx.a(BloodFx.BRIGHT, chipA));
        BloodFx.blob(g, lm + 9f, statusY + 7.5f, 1.8f + beat, BloodFx.a(BloodFx.HOT, chipA));
        text(g, chip, lm + 16, statusY + 4, 0.9f, BloodFx.a(SILVER, chipA), false);
    }

    private String phaseName() {
        return I18n.get("maniacmod.phase." + ClientMatchState.phase().name().toLowerCase(Locale.ROOT));
    }

    private String roleName() {
        if (ClientMatchState.isManiac()) {
            String id = ClientMatchState.archetypeId();
            if (id != null && !id.isEmpty() && ManiacRegistry.exists(id)) {
                return I18n.get("maniacmod.esc.role.maniac") + " «" + ManiacRegistry.get(id).displayName() + "»";
            }
            return I18n.get("maniacmod.esc.role.maniac");
        }
        if (ClientMatchState.isSurvivor()) return I18n.get("maniacmod.esc.role.survivor");
        return I18n.get("maniacmod.esc.role.spectator");
    }

    // ── Кнопки ──────────────────────────────────────────────────────────

    private void renderButtons(GuiGraphics g, long now, long age, float life) {
        for (int i = 0; i < buttons.size() && i < btnY.length; i++) {
            Btn b = buttons.get(i);
            int h = b.primary() ? primH : normH;
            float delay = 420f + i * 75f;
            float t = UiAnim.clamp01((age - delay) / 420f);
            if (t <= 0f) continue;

            float e = UiAnim.easeOutBack(t, 0.7f);
            float xOff = -(1f - e) * (bw + 60f) - (1f - life) * 80f;
            float alpha = UiAnim.clamp01(t * 2f) * life;
            float hv = hover[i];
            boolean armed = b.danger() && leaveArmedUntil != 0L;

            int x = lm + Math.round(xOff);
            int y = btnY[i];

            int bodyCol = b.danger() ? 0xD8140A0C : 0xD80B0D0E;
            slantFill(g, x, y, bw, h, BloodFx.a(bodyCol, alpha));

            // лезо: червона смуга, що виїжджає зліва при наведенні
            int bladeBase = b.primary() ? BloodFx.BRIGHT : (b.danger() ? 0xFF9C1018 : BloodFx.MID);
            float blade = UiAnim.easeOutCubic(armed ? 1f : hv);
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
            int borderCol = BloodFx.mix(BORDER, b.danger() ? 0xFFE0303A : BloodFx.HOT, Math.max(hv, armed ? 1f : 0f) * 0.9f);
            slantBorder(g, x, y, bw, h, BloodFx.a(borderCol, alpha));

            // ліва акцентна смужка
            int accent = b.primary() || hv > 0.5f ? BloodFx.BRIGHT : (b.danger() ? 0xFF6E0C12 : 0xFF3A3F47);
            slantFill(g, x, y, 3, h, BloodFx.a(accent, alpha));

            // номер
            String idx = String.format(Locale.ROOT, "%02d", i + 1);
            text(g, idx, x + slant + 8, y + (h - 7) / 2f, 0.75f,
                BloodFx.a(hv > 0.4f ? SILVER : DIM, alpha), false);

            // підпис
            String label = I18n.get(armed ? "maniacmod.esc.leave.confirm" : b.labelKey());
            float ls = b.primary() ? 1.25f : 1f;
            int labelCol = armed
                ? 0xFFFFFFFF
                : (hv > 0.4f ? 0xFFFFFFFF : (b.danger() ? DANGER_TEXT : (b.primary() ? SILVER : BODY)));
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

    private void renderHintAndSocials(GuiGraphics g, long now, long age, float life, int mx, int my) {
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
            text(g, "▸ ", lm, hintY, 0.9f, BloodFx.a(BloodFx.BRIGHT, life), false);
            text(g, full.substring(0, shown) + cursor, lm + 10, hintY, 0.9f, BloodFx.a(BODY, life), false);
        }

        // соцмережі
        int[] colors = {YT, DC, TG};
        String[] glyphs = {"YT", "DC", "TG"};
        for (int i = 0; i < 3; i++) {
            float t = UiAnim.clamp01((age - 900L - i * 90L) / 350f);
            if (t <= 0f) continue;
            float a = t * life;
            int x = lm + i * 40;
            int y = socialY + Math.round((1f - UiAnim.easeOutCubic(t)) * 10f);
            float h = socialHover[i];
            g.fill(x, y, x + 34, y + 22, BloodFx.a(PANEL, 0.88f * a));
            g.fill(x, y, x + 34, y + 22, BloodFx.a((0xFF << 24) | (colors[i] & 0xFFFFFF), (0.10f + 0.40f * h) * a));
            BloodFx.border1(g, x, y, 34, 22, BloodFx.a((0xFF << 24) | (colors[i] & 0xFFFFFF), (0.55f + 0.45f * h) * a));
            g.fill(x, y + 21, x + Math.round(34 * h), y + 22, BloodFx.a(0xFFFFFFFF, 0.8f * a));
            int gw = this.font.width(glyphs[i]);
            text(g, glyphs[i], x + (34 - gw) / 2f, y + 7, 1f, BloodFx.a(0xFFFFFFFF, (0.75f + 0.25f * h) * a), true);
        }
    }

    // ── Око ─────────────────────────────────────────────────────────────
    //
    // Малюється ПОСТОВПЧИКОВО: для кожного пікселя по ширині рахуємо, де
    // верхня й нижня повіка, і заливаємо вертикальні градієнти. Звідси
    // гладкий мигдалеподібний контур без «сходинок».
    //
    // Що є (порівняно з першою версією):
    //   • форма: внутрішній (лівий) кут нижче, верхня повіка товща й має
    //     складку, кути повік плавно згасають (без «обрубаних» країв);
    //   • сльозний горбок у внутрішньому куті, вологий червоний край нижньої
    //     повіки;
    //   • райдужка з радіальними волокнами, лімбальним кільцем і двома
    //     відблисками; зіниця-щілина «дихає» в такт серцю;
    //   • верхня повіка опускається, коли око дивиться вниз;
    //   • «живий» погляд: слідкує за курсором, а коли мишу не чіпають
    //     ~3 с — повільно блукає сам, з мікросаккадами;
    //   • кліпання: раз на ~7 с, інколи подвійне, і швидке — на кожен клік;
    //   • реакція на меню: над кнопкою око трохи примружується, а коли
    //     «Відключитися» озброєно — розплющується, зіниця звужується, склера
    //     і судини червоніють.

    private float eyeDx;
    private float eyeDy;
    private long eyeLast;
    private float eyeAlert;
    private float eyeFocus;
    private long eyeBlinkKick = -100000L;
    private long lastMoveMs = Util.getMillis();

    // геометрія кадру (оновлюється на початку renderEye, читається lidTop/lidBot)
    private float eCx, eCy, eRx, eRy, eTopS, eBotS, eTilt;

    /** Висота мигдалю над центром (0..1) на нормованому зміщенні u = dx/rx. */
    private static float lens(float u) {
        float k = 1f - u * u;
        return k <= 0f ? 0f : k;
    }

    private float lidK(float x) { return lens((x - eCx) / eRx); }

    private float lidTop(float x) {
        return eCy - eTilt * ((x - eCx) / eRx) - eRy * lidK(x) * eTopS;
    }

    private float lidBot(float x) {
        return eCy - eTilt * ((x - eCx) / eRx) + eRy * 0.8f * lidK(x) * eBotS;
    }

    /** Плавне згасання біля кутів повік: 0 у самому куті, 1 трохи далі. */
    private static float cornerFade(float k) { return Mth.clamp(k * 9f, 0f, 1f); }

    private static float bell(float t) {
        return (float) Math.sin(Mth.clamp(t, 0f, 1f) * Math.PI);
    }

    /** 0 — око відкрите, 1 — заплющене. */
    private float blinkAmount(long now) {
        float b = 0f;
        long shifted = now + 2500L;
        long cyc = shifted % 7300L;
        if (cyc < 240L) b = Math.max(b, bell(cyc / 240f));
        // кожен третій цикл — ще одне, швидке кліпання услід
        if ((shifted / 7300L) % 3L == 2L && cyc >= 360L && cyc < 560L) {
            b = Math.max(b, bell((cyc - 360L) / 200f));
        }
        long since = now - eyeBlinkKick;
        if (since >= 0L && since < 170L) b = Math.max(b, bell(since / 170f));
        return b;
    }

    private void renderEye(GuiGraphics g, int mx, int my, long now, long age, float life) {
        float beat = backdrop.beat(now);
        float sc = Math.min(width, height) / 540f * 1.15f;
        float freeX0 = lm + bw + slant + 20f;
        float cx = freeX0 + (width - freeX0 - lm) * 0.47f;
        float cy = height * 0.40f;
        float rx = 118f * sc;
        float ryFull = 58f * sc;

        float open = UiAnim.easeInOutCubic((age - 500f) / 1400f);
        if (open <= 0.01f) return;
        float al = life * open;

        if (eyeLast == 0L) eyeLast = now;
        float dtS = Math.min(0.05f, (now - eyeLast) / 1000f);
        eyeLast = now;

        // настрій: тривога («Відключитися» озброєно) і цікавість (курсор над кнопкою)
        eyeAlert = UiAnim.approach(eyeAlert, leaveArmedUntil != 0L ? 1f : 0f, 5f, dtS);
        eyeFocus = UiAnim.approach(eyeFocus, selected >= 0 ? 1f : 0f, 7f, dtS);
        float alert = eyeAlert;
        float focus = eyeFocus;

        float blink = blinkAmount(now);
        float openness = 1f - blink;

        // погляд: курсор або, коли мишу не чіпають, повільне блукання
        float tx;
        float ty;
        if (now - lastMoveMs > 2800L) {
            double w = now / 1000.0;
            float wx = (float) (Math.sin(w * 0.55) * 0.6 + Math.sin(w * 1.31 + 1.7) * 0.4);
            float wy = (float) (Math.sin(w * 0.43 + 0.8) * 0.5 + Math.sin(w * 1.1) * 0.2);
            tx = wx * rx * 0.22f;
            ty = wy * ryFull * 0.22f;
        } else {
            tx = Mth.clamp((mx - cx) / (width * 0.5f), -1f, 1f) * rx * 0.26f;
            ty = Mth.clamp((my - cy) / (height * 0.5f), -1f, 1f) * ryFull * 0.30f;
        }
        // мікросаккади: крихітні стрибки погляду кілька разів на секунду
        tx += (BloodFx.hash01(now / 260L) - 0.5f) * rx * 0.012f;
        ty += (BloodFx.hash01(now / 310L + 77L) - 0.5f) * ryFull * 0.012f;
        eyeDx = UiAnim.approach(eyeDx, tx, 7f, dtS);
        eyeDy = UiAnim.approach(eyeDy, ty, 7f, dtS);
        float dyN = Mth.clamp(eyeDy / (ryFull * 0.30f), -1f, 1f);

        // геометрія повік
        eCx = cx;
        eCy = cy;
        eRx = rx;
        eRy = ryFull * open * (0.04f + 0.96f * openness)
            * (1f + 0.14f * alert - 0.08f * focus) * (1f + 0.012f * beat);
        eTopS = 1f - 0.12f * dyN;   // дивиться вниз — верхня повіка опускається
        eBotS = 1f + 0.06f * dyN;
        eTilt = ryFull * 0.10f;     // внутрішній (лівий) кут нижче зовнішнього

        // м'який ореол — червоніє й росте від тривоги
        CircleFx.disc(g, cx, cy, rx * (1.35f + 0.10f * alert),
            BloodFx.a(BloodFx.BRIGHT, (0.07f + 0.04f * beat + 0.07f * alert) * al), 0x00C8161F);

        final int cream = BloodFx.mix(0xFFE6DAC8, 0xFFE9B7A6, alert * 0.55f);
        final int redEdge = BloodFx.mix(0xFF4A1218, 0xFF8A1620, alert * 0.7f);
        final int dark = 0xFF0A0203;

        float ir = ryFull * 0.90f;
        float ix = cx + eyeDx;
        float iy = cy + eyeDy;
        float pw = Math.max(1.8f, ir * 0.16f) * (1f + 0.22f * beat) * (1f - 0.38f * alert) * (1f + 0.14f * focus);

        int x0 = Math.round(cx - rx);
        int x1 = Math.round(cx + rx);

        // 1) склера
        for (int x = x0; x < x1; x++) {
            float xc = x + 0.5f;
            float k = lidK(xc);
            if (k <= 0f) continue;
            int top = Math.round(lidTop(xc));
            int bot = Math.round(lidBot(xc));
            if (bot - top < 1) continue;
            int mid = (top + bot) / 2;
            float ea = al * cornerFade(k);
            int base = BloodFx.mix(redEdge, cream, (float) Math.pow(k, 0.7));
            g.fillGradient(x, top, x + 1, mid, BloodFx.a(BloodFx.mix(dark, base, 0.35f), ea), BloodFx.a(base, ea));
            g.fillGradient(x, mid, x + 1, bot, BloodFx.a(base, ea), BloodFx.a(BloodFx.mix(dark, base, 0.55f), ea));
        }

        // 2) райдужка (радіальне затемнення + лімб) і зіниця
        int ia = (int) Math.floor(ix - ir);
        int ib = (int) Math.ceil(ix + ir);
        for (int x = ia; x < ib; x++) {
            float d = x + 0.5f - ix;
            float f = Math.abs(d) / ir;
            if (f >= 1f) continue;
            float xc = x + 0.5f;
            if (lidK(xc) <= 0f) continue;
            float lidTopY = lidTop(xc);
            float lidBotY = lidBot(xc);
            float h = (float) Math.sqrt(ir * ir - d * d);
            float y0 = Math.max(iy - h, lidTopY);
            float y1 = Math.min(iy + h, lidBotY);
            if (y1 - y0 < 1f) continue;

            float ff = f * f;
            int midCol = BloodFx.mix(0xFFFF6A2C, 0xFF8A0F18, (float) Math.pow(f, 1.4));
            if (f > 0.84f) midCol = BloodFx.mix(midCol, 0xFF1A0306, (f - 0.84f) / 0.16f);
            int edgeCol = BloodFx.mix(midCol, 0xFF1A0306, 0.62f);
            int ym = Math.round(Mth.clamp(iy, y0, y1));
            g.fillGradient(x, Math.round(y0), x + 1, ym, BloodFx.a(edgeCol, al), BloodFx.a(midCol, al));
            g.fillGradient(x, ym, x + 1, Math.round(y1),
                BloodFx.a(midCol, al), BloodFx.a(BloodFx.mix(midCol, 0xFF300508, 0.35f + 0.2f * ff), al));

            if (Math.abs(d) < pw) {
                float e = ir * 0.88f * (float) Math.sqrt(1f - (d * d) / (pw * pw));
                float p0 = Math.max(iy - e, lidTopY);
                float p1 = Math.min(iy + e, lidBotY);
                if (p1 - p0 >= 1f) g.fill(x, Math.round(p0), x + 1, Math.round(p1), BloodFx.a(0xFF050102, al));
            }
        }

        // 2б) радіальні волокна райдужки: темні й світлі промені від зіниці до лімба
        final int fibers = 36;
        for (int f = 0; f < fibers; f++) {
            float ang = f * (float) (Math.PI * 2.0 / fibers) + (BloodFx.hash01(900L + f) - 0.5f) * 0.10f;
            float cs = (float) Math.cos(ang);
            float sn = (float) Math.sin(ang);
            boolean light = (f & 1) == 0;
            int col = light ? 0xFFFFB04A : 0xFF2A0508;
            float fa = (light ? 0.20f : 0.34f) * al;
            float rStart = ir * (0.30f + 0.08f * BloodFx.hash01(950L + f));
            float rEnd = ir * (0.80f + 0.12f * BloodFx.hash01(990L + f));
            for (float r = rStart; r < rEnd; r += 2f) {
                float fx = ix + cs * r;
                float fy = iy + sn * r;
                if (Math.abs(fx - ix) < pw * 1.2f && Math.abs(fy - iy) < ir * 0.88f) continue;
                if (fy < lidTop(fx) + 1f || fy > lidBot(fx) - 1f) continue;
                int px0 = Math.round(fx);
                int py0 = Math.round(fy);
                g.fill(px0, py0, px0 + 1, py0 + 1, BloodFx.a(col, fa * (1f - 0.5f * (r - rStart) / (rEnd - rStart))));
            }
        }

        // 3) блики (лише якщо потрапляють у відкрите око)
        float hx = ix - ir * 0.36f;
        float hy = iy - ir * 0.40f;
        if (hy > lidTop(hx) + 3f && hy < lidBot(hx) - 3f) {
            BloodFx.blob(g, hx, hy, 3.2f * sc + 0.8f, BloodFx.a(0xFFFFFFFF, 0.85f * al));
            BloodFx.blob(g, hx + 5f * sc, hy + 4f * sc, 1.4f * sc + 0.4f, BloodFx.a(0xFFFFFFFF, 0.45f * al));
        }
        float gx = ix + ir * 0.42f;
        float gy = iy + ir * 0.46f;
        if (gy > lidTop(gx) + 3f && gy < lidBot(gx) - 3f) {
            BloodFx.blob(g, gx, gy, 1.7f * sc + 0.4f, BloodFx.a(0xFFFFE2C8, 0.28f * al));
        }

        // 4) судини: детерміновані, виростають від кутів до райдужки; пульсують із серцем,
        //    а в тривозі їх стає більше й вони яскравіші
        int veins = 8 + Math.round(6f * alert);
        float veinA = 0.55f + 0.20f * beat + 0.30f * alert;
        for (int v = 0; v < veins; v++) {
            boolean left = (v % 2 == 0);
            float px0 = left ? cx - rx * 0.93f : cx + rx * 0.93f;
            float py0 = cy + (BloodFx.hash01(300L + v) - 0.5f) * eRy * 0.6f;
            float tgx = ix + (left ? -ir : ir) * 0.96f;
            float tgy = iy + (BloodFx.hash01(400L + v) - 0.5f) * ir * 0.5f;
            float reach = UiAnim.easeOutCubic((age - 1500f - v * 90f) / 1100f);
            int steps = (int) (30 * reach);
            float vx = px0;
            float vy = py0;
            for (int st = 0; st < steps; st++) {
                float t = st / 30f;
                vx += (tgx - vx) * 0.085f + (BloodFx.hash01(500L + v * 50L + st) - 0.5f) * 1.5f;
                vy += (tgy - vy) * 0.085f + (BloodFx.hash01(700L + v * 50L + st) - 0.5f) * 1.1f;
                if (vy < lidTop(vx) + 2f || vy > lidBot(vx) - 2f) continue;
                if (Math.abs(vx - ix) < ir && Math.abs(vy - iy) < ir) continue;
                g.fill(Math.round(vx), Math.round(vy), Math.round(vx) + 1, Math.round(vy) + 1,
                    BloodFx.a(0xFFB0141C, Math.min(1f, veinA - 0.30f * t) * al));
            }
        }

        // 5) сльозний горбок у внутрішньому (лівому) куті
        float cux = cx - rx * 0.90f;
        float cuy = (lidTop(cux) + lidBot(cux)) * 0.5f + ryFull * 0.04f;
        if (openness > 0.15f) {
            float ca = al * openness;
            BloodFx.blob(g, cux, cuy, 3.6f * sc + 1f, BloodFx.a(0xFFB3434C, 0.80f * ca));
            BloodFx.blob(g, cux - 0.5f * sc, cuy - 0.6f * sc, 1.5f * sc + 0.5f, BloodFx.a(0xFFE88A90, 0.55f * ca));
        }

        // 6) повіки: тінь зовні, складка зверху, вологий червоний край знизу, темний край
        for (int x = x0; x < x1; x++) {
            float xc = x + 0.5f;
            float k = lidK(xc);
            if (k <= 0f) continue;
            int top = Math.round(lidTop(xc));
            int bot = Math.round(lidBot(xc));
            float ea = al * cornerFade(k);
            g.fillGradient(x, top - 11, x + 1, top, BloodFx.a(dark, 0f), BloodFx.a(dark, 0.92f * ea));
            int crease = top - 8 - Math.round(ryFull * 0.10f * k);
            g.fill(x, crease, x + 1, crease + 1, BloodFx.a(0xFF3A1216, 0.55f * ea));
            g.fill(x, top - 2, x + 1, top + 1, BloodFx.a(dark, ea));
            g.fillGradient(x, bot, x + 1, bot + 8, BloodFx.a(dark, 0.85f * ea), BloodFx.a(dark, 0f));
            g.fill(x, bot - 3, x + 1, bot - 1, BloodFx.a(0xFFB0303A, (0.45f + 0.25f * alert) * ea * openness));
            g.fill(x, bot - 1, x + 1, bot + 2, BloodFx.a(dark, ea));
        }

        // 7) кривава сльоза з нижньої повіки (сильніша в тривозі)
        float tearGrow = UiAnim.easeOutCubic((age - 2200f) / 1800f);
        if (tearGrow > 0f && openness > 0.5f) {
            float tu = -0.30f;
            int tex = Math.round(cx + rx * tu);
            int ty0 = Math.round(lidBot(tex + 0.5f));
            float len = (30f * sc * (1f + 0.5f * alert) + 6f * (float) Math.sin(now / 1500.0)) * tearGrow;
            BloodFx.drip(g, tex, ty0, 3, len, now, 0.3f, al);
        }
    }

    // ── Картка «ЗАВДАННЯ» (те саме, що верхня картка Tab) ───────────────
    //
    // Показуємо лише те, що відповідає на «що мені зараз робити»: завдання
    // від ролі й фази (ті самі ключі, що в Tab), «N / M» і слоти генераторів
    // (лише коли вони вже мають сенс) та віхи, важливі ролі гравця. Фаза й
    // роль уже є в чіпі під назвою, тож тут не дублюються.

    private record Milestone(MatchObjectives.Objective o, String key, int color) {}

    private static String objectiveKey() {
        String role = ClientMatchState.isManiac() ? "maniac"
            : ClientMatchState.isSurvivor() ? "survivor" : "spectator";
        return switch (ClientMatchState.phase()) {
            case HUNT    -> "maniacmod.tab.objective." + role + ".hunt";
            case POWERED -> "maniacmod.tab.objective." + role + ".powered";
            case HELI_WAIT -> "maniacmod.tab.objective." + role + ".heli_wait";
            case FINALE  -> "maniacmod.tab.objective." + role + ".finale";
            case ENDING  -> "maniacmod.tab.objective.ending";
            default      -> "maniacmod.tab.objective.wait";
        };
    }

    /** Фази, у яких генератори й віхи вже мають сенс. */
    private static boolean objectivePhase() {
        return switch (ClientMatchState.phase()) {
            case HUNT, POWERED, HELI_WAIT, FINALE -> true;
            default -> false;
        };
    }

    /** Віхи ПО РОЛІ — та сама логіка, що у {@code TabObjectiveCard.milestoneRelevant}. */
    private static List<Milestone> relevantMilestones() {
        List<Milestone> out = new ArrayList<>();
        if (!objectivePhase()) return out;
        boolean escape = ClientMatchState.allows(PhaseRule.ESCAPE);
        boolean maniac = ClientMatchState.isManiac();
        boolean survivor = ClientMatchState.isSurvivor();
        if (maniac || (!survivor)) {
            out.add(new Milestone(MatchObjectives.Objective.FIRST_BLOOD, "maniacmod.tab.milestone.blood", ManiacTabTheme.BLOOD_HOT));
        }
        out.add(new Milestone(MatchObjectives.Objective.POWER_RESTORED, "maniacmod.tab.milestone.power", ManiacTabTheme.WARN));
        out.add(new Milestone(MatchObjectives.Objective.EXIT_OPENED, "maniacmod.tab.milestone.exit", ManiacTabTheme.SILVER_HI));
        if (!survivor && (!maniac || escape)) {
            out.add(new Milestone(MatchObjectives.Objective.SOMEONE_ESCAPED, "maniacmod.tab.milestone.escape", ManiacTabTheme.OK));
        }
        return out;
    }

    private void renderInfoCard(GuiGraphics g, long now, long age, float life) {
        float t = UiAnim.clamp01((age - 1100f) / 600f);
        if (t <= 0f) return;
        float a = t * life;
        int w = 232;
        int pad = 12;

        ObjectiveSyncPacket obj = ClientMatchState.objectives();
        String objKey = objectiveKey();
        int required = Math.max(0, Math.min(12, obj.generatorsRequired()));
        boolean showGens = objectivePhase() && required > 0;
        boolean complete = showGens && obj.generatorsDone() >= required;
        List<Milestone> ms = relevantMilestones();

        // Підказка «як саме» — до трьох рядків.
        float subScale = 0.8f;
        var subLines = this.font.split(Component.literal(I18n.get(objKey + ".sub")),
            (int) ((w - pad * 2) / subScale));
        int subCount = Math.min(3, subLines.size());

        // Висота рахується з вмісту: порожніх «Ціль зʼявиться…» рядків більше нема.
        int cy = 26 + 16;
        int slotsY = cy;
        if (showGens) cy += 12;
        int subY0 = cy;
        cy += subCount * 10;
        int msY = cy + 3;
        if (!ms.isEmpty()) cy += 3 + 10;
        int h = cy + 8;

        int x = width - lm - w;
        int y = height - 36 - h + Math.round((1f - UiAnim.easeOutCubic(t)) * 16f);

        g.fill(x, y, x + w, y + h, BloodFx.a(PANEL, 0.88f * a));
        BloodFx.border1(g, x, y, w, h, BloodFx.a(BORDER, a));
        int accent = complete ? ManiacTabTheme.OK : BloodFx.BRIGHT;
        g.fill(x, y, x + 3, y + h, BloodFx.a(accent, a));
        UiFx.brackets(g, x, y, w, h, BloodFx.a(0xFFF2F4F8, 0.55f * a), 8,
            0.5f + 0.5f * (float) Math.sin(now / 700.0), 3);

        // підпис + роздільник
        text(g, I18n.get("maniacmod.tab.card.objective").toUpperCase(Locale.ROOT), x + pad, y + 8, 0.75f,
            BloodFx.a(DIM, a), false);
        g.fill(x + pad, y + 19, x + w - pad, y + 20, BloodFx.a(BORDER, a));

        // назва завдання + лічильник «N / M»
        String title = I18n.get(objKey).toUpperCase(Locale.ROOT);
        int counterW = showGens ? 44 : 0;
        int maxTitle = (int) ((w - pad * 2 - counterW) / 1.15f);
        if (this.font.width(title) > maxTitle) title = this.font.plainSubstrByWidth(title, maxTitle);
        text(g, title, x + pad, y + 26, 1.15f, BloodFx.a(0xFFF2F4F8, a), true);

        if (showGens) {
            int done = Math.min(obj.generatorsDone(), required);
            String big = Integer.toString(done);
            String small = "/" + required;
            int smallW = Math.round(this.font.width(small) * 0.9f);
            int bigW = Math.round(this.font.width(big) * 1.6f);
            int right = x + w - pad;
            int col = complete ? ManiacTabTheme.OK : 0xFFF2F4F8;
            text(g, small, right - smallW, y + 30, 0.9f, BloodFx.a(DIM, a), false);
            text(g, big, right - smallW - bigW - 1, y + 24, 1.6f, BloodFx.a(col, a), true);

            // слоти: світиться лише повністю готовий генератор (як у Tab)
            int gap = 3;
            int slotsW = w - pad * 2;
            int slotW = Math.max(8, Math.min(30, (slotsW - gap * (required - 1)) / required));
            var list = obj.generators();
            for (int i = 0; i < required; i++) {
                int sx = x + pad + i * (slotW + gap);
                int sy = y + slotsY;
                boolean ok = i < list.size() && list.get(i).stage() == GeneratorPoi.Stage.DONE;
                g.fill(sx, sy, sx + slotW, sy + 6, BloodFx.a(0xFF05070A, a));
                BloodFx.border1(g, sx, sy, slotW, 6, BloodFx.a(ok ? ManiacTabTheme.OK : BORDER, a));
                if (ok) g.fill(sx + 1, sy + 1, sx + slotW - 1, sy + 5, BloodFx.a(ManiacTabTheme.OK, 0.85f * a));
            }
        }

        // підказка «як саме»
        int sa = BloodFx.a(DIM, a);
        if ((sa >>> 24) >= 4) {
            for (int i = 0; i < subCount; i++) {
                g.pose().pushPose();
                g.pose().translate(x + pad, y + subY0 + i * 10, 0f);
                g.pose().scale(subScale, subScale, 1f);
                g.drawString(this.font, subLines.get(i), 0, 0, sa, false);
                g.pose().popPose();
            }
        }

        // віхи, важливі ролі
        if (!ms.isEmpty()) {
            int mx = x + pad;
            for (Milestone m : ms) {
                boolean lit = obj.has(m.o());
                int col = lit ? m.color() : ManiacTabTheme.DIM;
                float al = (lit ? 1f : 0.45f) * a;
                BloodFx.blob(g, mx + 2f, y + msY + 3.5f, 1.8f, BloodFx.a(col, al));
                String label = I18n.get(m.key()).toUpperCase(Locale.ROOT);
                text(g, label, mx + 8, y + msY, 0.65f, BloodFx.a(col, al), false);
                mx += 8 + Math.round(this.font.width(label) * 0.65f) + 10;
            }
        }
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
        if (closeStart >= 0L) return true;
        if (button != 0) return super.mouseClicked(mx, my, button);

        int idx = buttonAt((int) mx, (int) my);
        if (idx >= 0) {
            activate(idx);
            return true;
        }
        int s = socialAt((int) mx, (int) my);
        if (s >= 0) {
            UiAnim.playClick();
            eyeBlinkKick = Util.getMillis();
            openUrl(s == 0 ? URL_YOUTUBE : s == 1 ? URL_DISCORD : URL_TELEGRAM);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (closeStart >= 0L) return true;
        int n = buttons.size();
        switch (keyCode) {
            case GLFW.GLFW_KEY_ESCAPE -> {
                beginClose();
                return true;
            }
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
        eyeBlinkKick = Util.getMillis();
        if (!b.danger()) UiAnim.playClick();
        b.action().run();
    }

    // ── Дії ─────────────────────────────────────────────────────────────

    /** Перше натискання озброює кнопку, друге — виходить. */
    private void onLeavePressed() {
        long now = Util.getMillis();
        if (leaveArmedUntil == 0L || now > leaveArmedUntil) {
            leaveArmedUntil = now + LEAVE_ARM_MS;
            UiAnim.playMenuWarn();
            return;
        }
        leaveArmedUntil = 0L;
        UiAnim.playClick();
        if (ClientManiacRatingState.get().shouldShowRatingOnExit()) {
            minecraft.setScreen(new ManiacRatingScreen(true));
        } else {
            leaveGame();
        }
    }

    /**
     * Вихід зі світу тим самим шляхом, що ванільний {@code PauseScreen}:
     * одиночна гра — зі збереженням і на головне меню, мережева — на
     * список серверів. Публічний, бо потрібен і екрану оцінок.
     */
    public static void leaveGame() {
        Minecraft mc = Minecraft.getInstance();
        boolean local = mc.isLocalServer();
        if (mc.level != null) mc.level.disconnect();
        if (local) {
            mc.clearLevel(new GenericDirtMessageScreen(Component.translatable("menu.savingLevel")));
        } else {
            mc.clearLevel();
        }
        TitleScreen title = new TitleScreen();
        if (local) mc.setScreen(title);
        else mc.setScreen(new JoinMultiplayerScreen(title));
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
