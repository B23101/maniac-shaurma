package com.log_to_kot.maniacmod.client.screen.voice;

import com.log_to_kot.maniacmod.client.screen.voice.VoiceWidgets.VButton;
import com.log_to_kot.maniacmod.client.screen.voice.VoiceWidgets.VWidget;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.HorrorBackdrop;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Спільна основа голосових екранів мода: фон-«жах» ({@link HorrorBackdrop}, той самий, що в ESC-меню),
 * панель у стилі мода, анімація появи/закриття, каскад рядків, плавна прокрутка й футер із кнопкою «Назад».
 *
 * Підклас лише описує рядки ({@link #build()} → {@link #addRow}) і, за потреби, власну верхню зону
 * ({@link #topExtra()} + {@link #renderTop}). Позиціювання, ножиці прокрутки, затримки появи — тут.
 *
 * Екран НЕ ставить гру на паузу (як і ESC-меню мода). Перехід у під-екран — без анімації закриття, а
 * закриття/«Назад» — з короткою анімацією згасання.
 */
public abstract class VoiceScreenBase extends Screen {

    protected static final int HEADER_H = 41;
    protected static final int FOOTER_H = 38;
    protected static final int PAD = 16;
    protected static final int GAP = 4;
    private static final float OPEN_MS = 340f;
    private static final float CLOSE_MS = 170f;

    protected final Screen parent;
    private final HorrorBackdrop backdrop = new HorrorBackdrop();
    private final long openedAt = Util.getMillis();
    private long closeStart = -1L;
    private boolean introDone;
    private boolean cascade;
    private long lastNanos;

    protected float life = 1f;
    protected int px, py, pw, ph;

    // ── рядки в прокручуваній зоні ──
    protected static final class Row {
        final VWidget widget;
        final BooleanSupplier visible;
        float show;

        Row(VWidget widget, BooleanSupplier visible) {
            this.widget = widget;
            this.visible = visible;
            this.show = visible.getAsBoolean() ? 1f : 0f;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final List<VWidget> fixed = new ArrayList<>();
    protected int viewTop, viewBottom;
    protected float scroll, scrollTarget, maxScroll;
    private VButton backButton;

    protected VoiceScreenBase(Component title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    // ── що задає підклас ─────────────────────────────────────────────────

    protected abstract int panelWidth();

    /** Створює рядки через {@link #addRow}; викликається з {@link #init()} після очищення. */
    protected abstract void build();

    /** Висота зони між заголовком і списком (статус, пошук…). */
    protected int topExtra() { return 0; }

    protected Component backLabel() { return Component.translatable("maniacvoice.back"); }

    /** Малювання верхньої зони; координати вже в системі панелі. {@code a} — загальна прозорість. */
    protected void renderTop(GuiGraphics g, int mx, int my, float pt, float a, int topY) {}

    /** Малювання поверх усього (підказки) — вже без трансформації появи. */
    protected void renderOverlay(GuiGraphics g, int mx, int my, float pt) {}

    protected void onRemoved() {}

    protected void onTick() {}

    /** Висота «пустого» повідомлення, якщо список порожній; null — не показувати. */
    protected Component emptyText() { return null; }

    // ── будівництво ──────────────────────────────────────────────────────

    protected final int rowWidth() {
        return pw - PAD * 2 - 6;
    }

    protected final <T extends VWidget> T addRow(T w) {
        return addRow(w, () -> true);
    }

    protected final <T extends VWidget> T addRow(T w, BooleanSupplier visible) {
        if (cascade) {
            w.delay(10 + rows.size() * 28);
        } else if (introDone) {
            w.noIntro();
        } else {
            w.delay(110 + rows.size() * 45);
        }
        rows.add(new Row(w, visible));
        addWidget(w);
        return w;
    }

    protected final <T extends VWidget> T addFixed(T w) {
        if (introDone) {
            w.noIntro();
        } else {
            w.delay(110 + (rows.size() + fixed.size()) * 45);
        }
        fixed.add(w);
        addWidget(w);
        return w;
    }

    /** true — панель завжди на всю висоту екрана (списки, що міняють довжину при пошуку). */
    protected boolean fillHeight() { return false; }

    @Override
    protected void init() {
        rows.clear();
        fixed.clear();
        clearWidgets();

        pw = panelWidth();
        px = (width - pw) / 2;
        ph = height - 24;
        py = 12;

        build();

        backButton = addFixed(new VButton(0, 0, pw - PAD * 2, 22, () -> backLabel(), this::onClose));
        relayout();
        introDone = true;
    }

    /**
     * Замінює ЛИШЕ прокручувані рядки (пошук, оновлення списку гравців), не чіпаючи решту віджетів —
     * тож поле пошуку не втрачає фокус. Нові рядки з'являються каскадом.
     */
    protected final void replaceRows(Runnable fill) {
        for (Row r : rows) {
            removeWidget(r.widget);
        }
        rows.clear();
        cascade = true;
        try {
            fill.run();
        } finally {
            cascade = false;
        }
        relayout();
        scrollTarget = Mth.clamp(scrollTarget, 0f, maxScroll);
    }

    private void relayout() {
        int total = 0;
        for (Row r : rows) {
            total += r.widget.getHeight() + GAP;
        }
        int want = HEADER_H + topExtra() + total + 6 + FOOTER_H;
        ph = fillHeight() ? Math.max(160, height - 24) : Mth.clamp(want, 160, Math.max(160, height - 24));
        py = (height - ph) / 2;
        viewTop = py + HEADER_H + topExtra();
        viewBottom = py + ph - FOOTER_H;
        maxScroll = Math.max(0, total + 6 - (viewBottom - viewTop));
        scrollTarget = Mth.clamp(scrollTarget, 0f, maxScroll);
        scroll = Mth.clamp(scroll, 0f, maxScroll);

        if (backButton != null) {
            backButton.setPosition(px + PAD, py + ph - FOOTER_H + 10);
        }
        for (Row r : rows) {
            r.widget.setClip(viewTop, viewBottom);
        }
        layoutRows(0f);
    }

    private void layoutRows(float dt) {
        float cursor = viewTop + 4 - scroll;
        for (Row r : rows) {
            boolean vis = r.visible.getAsBoolean();
            r.show = UiAnim.approach(r.show, vis ? 1f : 0f, 14f, dt);
            if (Math.abs(r.show - (vis ? 1f : 0f)) < 0.01f) r.show = vis ? 1f : 0f;
            r.widget.show = r.show;
            r.widget.visible = r.show > 0.02f;
            r.widget.setPosition(px + PAD, Math.round(cursor));
            cursor += (r.widget.getHeight() + GAP) * r.show;
        }
        float total = cursor + scroll - viewTop;
        maxScroll = Math.max(0f, total + 2 - (viewBottom - viewTop));
    }

    // ── життєвий цикл ────────────────────────────────────────────────────

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (closeStart < 0L) {
            closeStart = Util.getMillis();
        }
    }

    private boolean closing() {
        return closeStart >= 0L;
    }

    @Override
    public void removed() {
        onRemoved();
        super.removed();
    }

    @Override
    public void tick() {
        super.tick();
        onTick();
    }

    // ── ввід ─────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        return !closing() && super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (maxScroll > 0f && my >= viewTop && my < viewBottom) {
            scrollTarget = Mth.clamp(scrollTarget - (float) delta * 28f, 0f, maxScroll);
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    // ── малювання ────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        long now = Util.getMillis();
        long nn = System.nanoTime();
        float dt = lastNanos == 0L ? 0f : Math.min(0.1f, (nn - lastNanos) / 1_000_000_000f);
        lastNanos = nn;

        float openT = UiAnim.easeOutCubic((now - openedAt) / OPEN_MS);
        if (closing()) {
            float ct = Mth.clamp((now - closeStart) / CLOSE_MS, 0f, 1f);
            life = 1f - UiAnim.easeInOutCubic(ct);
            if (ct >= 1f) {
                Minecraft.getInstance().setScreen(parent);
                return;
            }
        } else {
            life = 1f;
        }
        float ease = openT * life;
        VoiceWidgets.master = ease;

        backdrop.render(g, width, height, life);

        scroll = UiAnim.approach(scroll, scrollTarget, 18f, dt);
        layoutRows(dt);
        scrollTarget = Mth.clamp(scrollTarget, 0f, maxScroll);

        GeneratorFuelUiTheme.beginAppear(g, px + pw / 2f, py + ph / 2f, ease, 20f);

        ManiacUiTheme.drawPanel(g, px, py, pw, ph, true);
        ManiacUiTheme.drawTitle(g, font, title, px, py, pw);
        ManiacUiTheme.divider(g, px + PAD, py + ph - FOOTER_H + 2, pw - PAD * 2);

        renderTop(g, mx, my, pt, ease, py + HEADER_H);

        g.enableScissor(px + 1, viewTop, px + pw - 1, viewBottom);
        for (Row r : rows) {
            if (r.widget.visible) {
                r.widget.render(g, mx, my, pt);
            }
        }
        g.disableScissor();
        VoiceWidgets.drawScrollbar(g, px + pw - 9, viewTop + 2, viewBottom - 2, scroll, maxScroll, ease);

        Component empty = emptyText();
        if (empty != null && rows.isEmpty()) {
            g.drawCenteredString(font, empty, px + pw / 2, (viewTop + viewBottom) / 2 - 4,
                    GeneratorFuelUiTheme.alpha(ManiacUiTheme.TEXT_DIM, ease));
        }

        for (VWidget w : fixed) {
            w.render(g, mx, my, pt);
        }

        GeneratorFuelUiTheme.endAppear(g);
        VoiceWidgets.master = 1f;

        if (!closing()) {
            renderOverlay(g, mx, my, pt);
        }
    }

    // ── для підкласів ────────────────────────────────────────────────────

    protected final boolean ingame() {
        return Minecraft.getInstance().level != null;
    }

    /** Усі рядки зараз (для перебудови списків без повного init). */
    protected final List<Row> rows() {
        return rows;
    }
}
