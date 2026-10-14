package com.log_to_kot.maniacmod.client.screen.menu;

import com.log_to_kot.maniacmod.client.screen.mainmenu.ManiacMainMenuBackground;
import com.log_to_kot.maniacmod.client.style.BloodFx;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.EditServerScreen;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Єдина «тема» для екранів списків (сервери / світи) і форм (додати-редагувати
 * сервер, пряме підключення). Стиль — той самий, що в {@code ManiacTitleScreen}:
 * фон-картинка з головного меню, кнопки-«леза», бренд-заголовок.
 *
 * ── Принцип ──────────────────────────────────────────────────────────
 * Ванільні екрани НЕ підміняються. Ванільна логіка (пінг, відкриття світу,
 * колбеки форм) працює як є; міксини лише:
 *  • малюють наш фон замість «ґрунту» ({@code MixinScreenManiacBackdrop});
 *  • малюють наші кнопки замість ванільних ({@code MixinAbstractButtonManiac});
 *  • перемальовують картки рядків ПІСЛЯ ванільного {@code render} рядка
 *    ({@link ManiacMenuCards}) — тому пінг, що стартує всередині цього
 *    {@code render}, не ламається;
 *  • перекладають віджети й малюють заголовки/підписи на екранах.
 *
 * Усе тут активне лише на екранах, які повертає {@link #modeOf(Screen)}.
 */
@OnlyIn(Dist.CLIENT)
public final class ManiacMenuTheme {

    /** LIST — центрований список; FORM — ліва колонка з формою, як у головному меню. */
    public enum Mode { LIST, FORM }

    // ── Палітра (та сама, що в ManiacTitleScreen) ───────────────────────
    public static final int SILVER = 0xFFF2F4F8;
    public static final int BODY = 0xFFB9B9B9;
    public static final int DIM = 0xFF7A7A7A;
    public static final int PANEL = 0xFF0B0D0E;
    public static final int BORDER = 0xFF292D2E;
    public static final int DANGER_TEXT = 0xFFE05A60;
    public static final int SHADE = 0xFF050607;
    public static final int GOOD = 0xFF58D26A;
    public static final int WARN = 0xFFE0B040;

    /** Y надрядка заголовка на екранах-списках. */
    public static final int HEADER_TOP = 10;
    /** Верх списку під заголовком. */
    public static final int LIST_TOP = 56;
    /** Низ списку: над двома рядами ванільних кнопок. */
    public static final int LIST_BOTTOM_MARGIN = 64;

    private ManiacMenuTheme() {}

    // ── Які екрани оформлюємо ───────────────────────────────────────────

    @Nullable
    public static Mode modeOf(@Nullable Screen s) {
        if (s instanceof JoinMultiplayerScreen || s instanceof SelectWorldScreen) return Mode.LIST;
        if (s instanceof EditServerScreen || s instanceof DirectJoinServerScreen) return Mode.FORM;
        return null;
    }

    /** Чи зараз відкритий екран-список (для карток рядків). */
    public static boolean listActive() {
        return modeOf(Minecraft.getInstance().screen) == Mode.LIST;
    }

    // ── Доступ до віджетів без приватних полів ──────────────────────────

    public static <T> List<T> children(Screen s, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (GuiEventListener c : s.children()) {
            if (type.isInstance(c)) out.add(type.cast(c));
        }
        return out;
    }

    @Nullable
    public static <T> T firstChild(Screen s, Class<T> type) {
        for (GuiEventListener c : s.children()) {
            if (type.isInstance(c)) return type.cast(c);
        }
        return null;
    }

    /** Малює всі віджети екрана (замість приватного списку {@code renderables}). */
    public static void renderChildren(Screen s, GuiGraphics g, int mx, int my, float pt) {
        for (GuiEventListener c : s.children()) {
            if (c instanceof Renderable r) r.render(g, mx, my, pt);
        }
    }

    /** Чи цей рядок зараз вибраний у списку відкритого екрана. */
    public static boolean isSelected(Object entry) {
        Screen s = Minecraft.getInstance().screen;
        if (s == null) return false;
        for (GuiEventListener c : s.children()) {
            if (c instanceof ServerSelectionList l) return l.getSelected() == entry;
            if (c instanceof WorldSelectionList l) return l.getSelected() == entry;
        }
        return false;
    }

    /** Чи це список, якому прибираємо ванільні ґрунтові смуги й рамку вибору. */
    public static boolean isThemedList(Object list) {
        return list instanceof ServerSelectionList || list instanceof WorldSelectionList;
    }

    public static void place(AbstractWidget w, int x, int y, int width) {
        w.setPosition(x, y);
        w.setWidth(width);
    }

    // ── Фон ─────────────────────────────────────────────────────────────

    /**
     * Той самий фон, що в головному меню (картинка, що їде; годинник спільний,
     * тож перехід між екранами без стрибка), плюс затемнення під контент.
     */
    public static void renderBackdrop(GuiGraphics g, int w, int h, Mode mode) {
        if (!ManiacMainMenuBackground.render(g, w, h, ManiacMainMenuBackground.sharedElapsedNs())) {
            g.fillGradient(0, 0, w, h, 0xFF0B0809, 0xFF030304);
        }
        g.fill(0, 0, w, h, BloodFx.a(SHADE, mode == Mode.LIST ? 0.50f : 0.30f));

        final int step = 3;
        if (mode == Mode.FORM) {
            // ліва колонка, як у головному меню: щільно біля краю, до нуля на ~62% ширини
            int span = (int) (w * 0.62f);
            for (int x = 0; x < span; x += step) {
                float k = 1f - x / (float) span;
                g.fill(x, 0, Math.min(span, x + step), h, BloodFx.a(SHADE, 0.82f * k * k));
            }
        } else {
            // віньєтка з боків — центр (список) читається, краї картинки живі
            int span = Math.max(48, (int) (w * 0.22f));
            for (int x = 0; x < span; x += step) {
                float k = 1f - x / (float) span;
                int a = BloodFx.a(SHADE, 0.62f * k * k);
                g.fill(x, 0, Math.min(span, x + step), h, a);
                g.fill(w - Math.min(span, x + step), 0, w - x, h, a);
            }
        }

        int vh = (int) (h * 0.24f);
        g.fillGradient(0, 0, w, vh, BloodFx.a(0xFF020203, 0.60f), 0x00020203);
        g.fillGradient(0, h - vh, w, h, 0x00020203, BloodFx.a(0xFF020203, 0.70f));
        int fogH = (int) (h * 0.30f);
        g.fillGradient(0, h - fogH, w, h, 0x00500810, BloodFx.a(0xFF500810, 0.18f));

        // рамка списку — та сама панель, що в голосових екранах (рамка, акцент, скоби, промінь)
        if (mode == Mode.LIST) {
            int pw = Math.min(336, w - 16);
            ManiacUiTheme.drawPanel(g, (w - pw) / 2, 4, pw, h - 8, true);
        }
    }

    // ── Заголовок ───────────────────────────────────────────────────────

    /** Надрядок із червоною цяткою, назва-«бренд» з кривавою тінню й коротка червона риска. */
    public static void renderHeader(GuiGraphics g, Font font, int left, int y,
                                    String overlineKey, String titleKey, float scale) {
        float beat = beat(Util.getMillis());
        BloodFx.blob(g, left + 2.5f, y + 4f, 2.2f + beat * 0.8f, BloodFx.BRIGHT);
        text(g, font, I18n.get(overlineKey), left + 9, y, 0.85f, BODY, false);

        String title = I18n.get(titleKey);
        int ty = y + 12;
        g.pose().pushPose();
        g.pose().translate(left, ty, 0f);
        g.pose().scale(scale, scale, 1f);
        g.drawString(font, title, 2, 2, BloodFx.MID, false);
        g.drawString(font, title, 0, 0, SILVER, false);
        g.pose().popPose();

        int barY = ty + (int) (9 * scale) + 4;
        g.fill(left, barY, left + 28, barY + 2, BloodFx.BRIGHT);
    }

    /** Повільне серцебиття (~0.8 Гц), 0..1 — як у головному меню. */
    public static float beat(long now) {
        float phase = (now / 1000f * 0.8f) % 1f;
        float b1 = (float) Math.exp(-Math.pow((phase - 0.08f) / 0.10f, 2));
        float b2 = 0.55f * (float) Math.exp(-Math.pow((phase - 0.34f) / 0.11f, 2));
        return Math.min(1f, b1 + b2);
    }

    // ── Кнопки ──────────────────────────────────────────────────────────

    private static final class State { float hover; long lastNs; }

    private static final Map<AbstractWidget, State> STATES = new WeakHashMap<>();

    private static String keyOf(AbstractButton b) {
        return b.getMessage().getContents() instanceof TranslatableContents tc ? tc.getKey() : "";
    }

    public static boolean isPrimary(AbstractButton b) {
        String k = keyOf(b);
        return k.equals("selectServer.select") || k.equals("selectWorld.select") || k.equals("addServer.add");
    }

    private static boolean isDanger(AbstractButton b) {
        String k = keyOf(b);
        return k.equals("selectServer.delete") || k.equals("selectWorld.delete");
    }

    /**
     * Кнопка-«лезо» замість ванільної. Стан ({@code active}, фокус, колбек) лишається
     * ванільним — міняється лише малювання.
     *
     * @return {@code false}, якщо поточний екран не наш — тоді малює ваніль
     */
    public static boolean drawButton(GuiGraphics g, AbstractButton b) {
        if (modeOf(Minecraft.getInstance().screen) == null) return false;
        Font font = Minecraft.getInstance().font;

        State st = STATES.computeIfAbsent(b, k -> new State());
        long nowNs = System.nanoTime();
        float dt = st.lastNs == 0L ? 0.016f : Math.min(0.1f, (nowNs - st.lastNs) / 1_000_000_000f);
        st.lastNs = nowNs;
        boolean active = b.active;
        st.hover = UiAnim.approach(st.hover, active && b.isHoveredOrFocused() ? 1f : 0f, 16f, dt);
        float hv = st.hover;

        boolean danger = isDanger(b);
        boolean primary = isPrimary(b);
        int x = b.getX(), y = b.getY(), w = b.getWidth(), h = b.getHeight();
        int slant = h >= 24 ? 6 : 4;
        int sw = w - slant; // паралелограм вміщається в рамку віджета: сусідні кнопки не злипаються

        int body = !active ? 0xA00B0D0E : (danger ? 0xD8140A0C : 0xD80B0D0E);
        slantFill(g, x, y, sw, h, slant, body);

        int bladeBase = primary ? BloodFx.BRIGHT : (danger ? 0xFF9C1018 : BloodFx.MID);
        float blade = UiAnim.easeOutCubic(hv);
        if (blade > 0.01f) {
            int bladeW = Math.round(sw * blade);
            slantFill(g, x, y, bladeW, h, slant, BloodFx.a(bladeBase, primary ? 0.92f : 0.80f));
            slantFill(g, x + Math.max(0, bladeW - 3), y, Math.min(3, bladeW), h, slant,
                BloodFx.a(BloodFx.HOT, 0.75f * blade));
        }

        int borderCol = BloodFx.mix(BORDER, danger ? 0xFFE0303A : BloodFx.HOT, hv * 0.9f);
        if (b.isFocused() && active) borderCol = BloodFx.BRIGHT;
        slantBorder(g, x, y, sw, h, slant, borderCol);

        int accent = !active ? 0xFF2A2F31
            : (primary || hv > 0.5f ? BloodFx.BRIGHT : (danger ? 0xFF6E0C12 : 0xFF3A3F47));
        slantFill(g, x, y, 3, h, slant, accent);

        String label = b.getMessage().getString().toUpperCase(Locale.ROOT);
        float maxW = sw - 14f;
        float tw = font.width(label);
        float scale = tw > maxW ? Math.max(0.65f, maxW / tw) : 1f;
        if (tw * scale > maxW) {
            label = font.plainSubstrByWidth(label, (int) (maxW / scale));
            tw = font.width(label);
        }
        int col = !active ? 0xFF555A60
            : (hv > 0.4f ? 0xFFFFFFFF : (danger ? DANGER_TEXT : (primary ? SILVER : BODY)));
        float tx = x + slant / 2f + (sw - tw * scale) / 2f;
        float ty = y + (h - 8 * scale) / 2f + 0.5f;
        text(g, font, label, tx, ty, scale, col, active);
        return true;
    }

    /** Паралелограм «/»: верх зсунутий праворуч на {@code slant}. */
    public static void slantFill(GuiGraphics g, int x, int y, int w, int h, int slant, int argb) {
        if (w <= 0 || (argb >>> 24) == 0) return;
        for (int row = 0; row < h; row++) {
            int off = Math.round(slant * (h - 1 - row) / (float) Math.max(1, h - 1));
            g.fill(x + off, y + row, x + off + w, y + row + 1, argb);
        }
    }

    public static void slantBorder(GuiGraphics g, int x, int y, int w, int h, int slant, int argb) {
        if ((argb >>> 24) == 0) return;
        for (int row = 0; row < h; row++) {
            int off = Math.round(slant * (h - 1 - row) / (float) Math.max(1, h - 1));
            g.fill(x + off, y + row, x + off + 1, y + row + 1, argb);
            g.fill(x + off + w - 1, y + row, x + off + w, y + row + 1, argb);
        }
        g.fill(x + slant, y, x + slant + w, y + 1, argb);
        g.fill(x, y + h - 1, x + w, y + h, argb);
    }

    // ── Форми (додати/редагувати сервер, пряме підключення) ─────────────

    /** Координати форми в лівій колонці. Нульові поля — блока в цій формі нема. */
    public record Form(int left, int width, int top, int nameLabelY, int nameY, int addrLabelY,
                       int addrY, int hintY, int cycleY, int doneY, int cancelY) {}

    public static Form layout(int w, int h, boolean hasName, boolean hasCycle) {
        int left = Math.max(26, (int) (w * 0.07f));
        int fw = Mth.clamp(w / 3, 230, 300);
        int total = 62 + (hasName ? 43 : 0) + 58 + (hasCycle ? 32 : 0) + 30 + 20;
        int top = Math.max(12, (h - total) / 2);

        int y = top + 62;
        int nameLabelY = 0;
        int nameY = 0;
        if (hasName) {
            nameLabelY = y;
            nameY = y + 11;
            y += 43;
        }
        int addrLabelY = y;
        int addrY = y + 11;
        int hintY = addrY + 24;
        y += 58;
        int cycleY = 0;
        if (hasCycle) {
            cycleY = y;
            y += 32;
        }
        return new Form(left, fw, top, nameLabelY, nameY, addrLabelY, addrY, hintY, cycleY, y, y + 30);
    }

    /**
     * Повний рендер форми замість ванільного: фон, заголовок, підписи полів, віджети,
     * рамки полів у стилі мода й рядок перевірки адреси.
     */
    public static void renderForm(Screen s, GuiGraphics g, int mx, int my, float pt, Form f,
                                  String overlineKey, String titleKey,
                                  @Nullable EditBox nameBox, EditBox addrBox) {
        Font font = Minecraft.getInstance().font;
        s.renderBackground(g);

        // рамка форми — панель як у голосових екранах
        int panelY = f.top() - 14;
        ManiacUiTheme.drawPanel(g, f.left() - 14, panelY, f.width() + 28,
            (f.cancelY() + 20 + 14) - panelY, true);

        // довгий заголовок («РЕДАГУВАННЯ СЕРВЕРА») стискаємо, щоб не вилазив із рамки
        float titleScale = Math.min(3f, f.width() / (float) Math.max(1, font.width(I18n.get(titleKey))));
        renderHeader(g, font, f.left(), f.top(), overlineKey, titleKey, titleScale);

        if (nameBox != null) {
            text(g, font, I18n.get("maniacmod.menu.field.name"), f.left(), f.nameLabelY(), 0.85f, BODY, false);
        }
        text(g, font, I18n.get("maniacmod.menu.field.address"), f.left(), f.addrLabelY(), 0.85f, BODY, false);

        renderChildren(s, g, mx, my, pt);

        if (nameBox != null) frameEditBox(g, nameBox);
        frameEditBox(g, addrBox);

        String addr = addrBox.getValue().trim();
        String key;
        int dot;
        if (addr.isEmpty()) {
            key = "maniacmod.menu.addr.empty";
            dot = DIM;
        } else if (ServerAddress.isValidAddress(addr)) {
            key = "maniacmod.menu.addr.ok";
            dot = GOOD;
        } else {
            key = "maniacmod.menu.addr.bad";
            dot = BloodFx.BRIGHT;
        }
        BloodFx.blob(g, f.left() + 2.5f, f.hintY() + 4f, 2f, dot);
        text(g, font, I18n.get(key), f.left() + 9, f.hintY(), 0.85f, dot == DIM ? DIM : BODY, false);
    }

    /** Рамка поля вводу в палітрі мода поверх ванільної (текст і курсор — ванільні). */
    public static void frameEditBox(GuiGraphics g, EditBox e) {
        int x = e.getX(), y = e.getY(), w = e.getWidth(), h = e.getHeight();
        boolean focus = e.isFocused();
        BloodFx.border1(g, x - 1, y - 1, w + 2, h + 2, focus ? BloodFx.BRIGHT : 0xFF3A3F47);
        if (focus) g.fill(x - 1, y - 1, x + 1, y + h + 1, BloodFx.BRIGHT);
    }

    // ── Текст ───────────────────────────────────────────────────────────

    /** Текст із масштабом; майже прозорий пропускає — інакше vanilla робить його непрозорим. */
    public static void text(GuiGraphics g, Font font, String s, float x, float y, float scale,
                            int argb, boolean shadow) {
        if ((argb >>> 24) < 4 || s.isEmpty()) return;
        if (scale == 1f) {
            g.drawString(font, s, Math.round(x), Math.round(y), argb, shadow);
            return;
        }
        g.pose().pushPose();
        g.pose().translate(x, y, 0f);
        g.pose().scale(scale, scale, 1f);
        g.drawString(font, s, 0, 0, argb, shadow);
        g.pose().popPose();
    }
}
