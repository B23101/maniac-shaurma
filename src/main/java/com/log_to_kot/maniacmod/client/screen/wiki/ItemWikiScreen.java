package com.log_to_kot.maniacmod.client.screen.wiki;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.ManiacKeybinds;
import com.log_to_kot.maniacmod.client.style.CircleFx;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.UiFx;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Вікі предмета: гравець тримає предмет, тисне H — відкривається це меню.
 * Ліворуч — об'ємна модель предмета (та сама geo-модель з анімацією
 * {@code idle}) на «п'єдесталі» зі світлом, променями, іскрами й кільцем
 * під ногами; праворуч — стаття, що «друкується» літера за літерою.
 * Закривається Esc (або H, або клік по плашці ESC).
 *
 * ── Звідки текст ─────────────────────────────────────────────────────
 * Увесь текст — з lang-файлів, за id предмета (мова клієнта підхоплюється
 * сама, а відсутній переклад Minecraft замінює англійським):
 * <pre>
 *   item.maniacmod.&lt;id&gt;                    назва (вже є)
 *   maniacmod.wiki.item.&lt;id&gt;.role          коротка роль («Лікування»)
 *   maniacmod.wiki.item.&lt;id&gt;.desc          опис
 *   maniacmod.wiki.item.&lt;id&gt;.use           як користуватись
 *   maniacmod.wiki.item.&lt;id&gt;.fact.1..8     «Назва|Значення» (рядки характеристик)
 *   maniacmod.wiki.item.&lt;id&gt;.tip           порада
 * </pre>
 * Усе, крім {@code desc}, необов'язкове. Новий предмет у
 * {@code ItemRegistry} (група SURVIVOR) + ці ключі = стаття готова, у коді
 * нічого міняти не треба. Тексти читаються через {@link Language} напряму,
 * а не {@link I18n#get}: той проганяє рядок крізь {@code String.format}, і
 * будь-який «%» у тексті («−33% міцності») дав би «Format error».
 *
 * ── Анімація тексту ──────────────────────────────────────────────────
 * Не ступінчаста по кадрах, а від ЧАСУ: скільки символів уже надруковано,
 * рахується з реального часу від відкриття ({@link #CPS} символів/с) — тож
 * темп однаковий на 30 і 240 FPS, зміна розміру вікна посеред друку його
 * не скидає. Клік по тексту або Enter/Пробіл дорисовують усе одразу.
 *
 * ── Гра не на паузі ──────────────────────────────────────────────────
 * Як і {@code TrapChooseScreen}: в одиночному світі пауза зупинила б
 * сервер матчу. Тому вікі САМА закривається, щойно вижившого збили з ніг
 * (див. {@link #tick}), — читати статтю лежачи під ударами маньяка не
 * можна, а клавіші в меню все одно не працюють.
 */
@OnlyIn(Dist.CLIENT)
public final class ItemWikiScreen extends Screen {

    // ── Таймінги ─────────────────────────────────────────────────────
    private static final float OPEN_MS = 440f;
    private static final float CLOSE_MS = 230f;
    private static final float TYPE_DELAY_MS = 700f;   // коли починається друк статті
    private static final float CPS = 105f;             // символів статті за секунду
    private static final float KICKER_CPS = 70f;

    // ── Палітра (срібло генераторного стилю) ─────────────────────────
    private static final int ACCENT = ManiacUiTheme.MENU_ACCENT_GOLD;
    private static final int COL_BODY = ManiacUiTheme.TEXT_BODY;
    private static final int COL_DIM = ManiacUiTheme.TEXT_DIM;
    private static final int COL_BRIGHT = ManiacUiTheme.TEXT_TITLE;

    private static final int PAD = 12;

    private enum Kind { HEADER, BODY, FACT, TIP, NOTE }

    /** Один візуальний рядок статті. {@code len} — скільки символів він додає до «бюджету друку». */
    private record Row(Kind kind, String label, String text, int indent, int len) {}

    // ── Дані ─────────────────────────────────────────────────────────
    private final String itemId;
    private final ItemStack stack;
    private String title = "";
    private String kicker = "";

    // ── Стан анімації ────────────────────────────────────────────────
    private final long[] frameClock = new long[1];
    private final UiFx.Sparks sparks = new UiFx.Sparks(260);
    private long openedAt;
    private long closingAt = -1;
    private boolean readyToClose;
    private boolean skipTyping;
    private boolean typingDoneSounded;
    private float emitAcc;

    // Обертання моделі.
    private float yaw = -35f;
    private float yawVel;
    private float tilt = 14f;
    private float zoom = 1f;
    private float zoomTarget = 1f;
    private boolean dragging;
    private float viewHover;
    private float chipHover;

    // ── Геометрія (перераховується в init) ───────────────────────────
    private int panelX, panelY, panelW, panelH;
    private int viewX, viewY, viewW, viewH;
    private int textX, textW, textTop;
    private int kickerY, titleY, dividerY, bodyTop, bodyH;
    private int chipX, chipY, chipW, chipH;
    private float titleScale = 2f;
    private float textScale = 1f;

    private List<Row> rows = List.of();
    private int[] rowY = new int[0];
    private int contentH;

    public ItemWikiScreen(String itemId, ItemStack stack) {
        super(Component.translatable("item." + ManiacMod.MOD_ID + "." + itemId));
        this.itemId = itemId;
        this.stack = stack;
    }

    // ═════════════════════════════════════════════════════════════════
    //  Життєвий цикл
    // ═════════════════════════════════════════════════════════════════

    @Override
    protected void init() {
        if (openedAt == 0) {
            openedAt = Util.getMillis();
            UiAnim.playAppear();
        }
        title = I18n.get("item." + ManiacMod.MOD_ID + "." + itemId);
        String role = key("role");
        kicker = (tr("maniacmod.wiki.kind.survivor")
            + (has(role) ? "  //  " + tr(role) : "")).toUpperCase(Locale.ROOT);
        layout();
    }

    @Override
    public boolean isPauseScreen() {
        return false; // див. клас-докстрінг: пауза завісила б сервер матчу
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // Esc обробляємо самі — з анімацією закриття
    }

    @Override
    public void tick() {
        if (readyToClose) {
            onClose();
            return;
        }
        // Роль змінилась (матч скінчився) або гравця збили з ніг — читати нічого.
        SurvivorState st = ClientMatchState.survivorState();
        if (!ClientMatchState.isSurvivor() || (st != null && (st.requiresRescue() || st.isCrawlOnly()))) {
            startClose();
        }
    }

    private void startClose() {
        if (closingAt >= 0) return;
        closingAt = Util.getMillis();
        UiAnim.playDismiss();
    }

    // ═════════════════════════════════════════════════════════════════
    //  Компонування
    // ═════════════════════════════════════════════════════════════════

    private void layout() {
        panelW = Math.max(220, Math.min(width - 24, 620));
        panelH = Math.max(160, Math.min(height - 24, 330));
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;

        viewX = panelX + PAD;
        viewY = panelY + PAD;
        viewW = Math.round(panelW * 0.40f);
        viewH = panelH - PAD * 2;

        textX = viewX + viewW + 18;
        textW = panelX + panelW - PAD - textX;
        textTop = panelY + PAD;

        chipW = font.width("ESC") + 10;
        chipH = font.lineHeight + 4;
        chipX = panelX + panelW - PAD - chipW;
        chipY = panelY + PAD;

        int lh = font.lineHeight;
        kickerY = textTop + 2;
        // Заголовок: 2× за замовчуванням, а довга назва зменшується, щоб не налізти на плашку ESC.
        titleScale = 2f;
        int titleMax = textW - 4;
        while (titleScale > 1f && font.width(title) * titleScale > titleMax) titleScale -= 0.25f;
        titleY = kickerY + lh + 7;
        dividerY = titleY + Math.round(lh * titleScale) + 7;
        bodyTop = dividerY + 10;
        bodyH = Math.max(40, panelY + panelH - PAD - bodyTop);

        rebuildText();
    }

    /** Підбирає масштаб тексту, при якому стаття влазить у праву колонку. */
    private void rebuildText() {
        float[] candidates = {1.0f, 0.9f, 0.82f, 0.74f, 0.66f};
        for (float sc : candidates) {
            textScale = sc;
            layoutRows(Math.max(48, Math.round(textW / sc)));
            if (contentH * sc <= bodyH) break;
        }
    }

    private void layoutRows(int wrapW) {
        List<Row> out = new ArrayList<>();

        String descKey = key("desc");
        addSection(out, wrapW, "maniacmod.wiki.section.desc",
            has(descKey) ? tr(descKey) : tr("maniacmod.wiki.no_entry"), Kind.BODY);

        String useKey = key("use");
        if (has(useKey)) addSection(out, wrapW, "maniacmod.wiki.section.use", tr(useKey), Kind.BODY);

        // Характеристики: «Назва|Значення», до 8 штук.
        List<String[]> facts = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            String k = key("fact." + i);
            if (!has(k)) break;
            String raw = tr(k);
            int bar = raw.indexOf('|');
            facts.add(bar < 0 ? new String[]{"", raw} : new String[]{raw.substring(0, bar).trim(), raw.substring(bar + 1).trim()});
        }
        if (!facts.isEmpty()) {
            out.add(headerRow("maniacmod.wiki.section.stats"));
            int labelW = 0;
            for (String[] f : facts) labelW = Math.max(labelW, font.width(f[0]));
            labelW = Math.min(labelW + 10, Math.round(wrapW * 0.5f));
            for (String[] f : facts) {
                List<String> lines = wrap(f[1], Math.max(30, wrapW - labelW));
                for (int i = 0; i < lines.size(); i++) {
                    String label = i == 0 ? f[0] : null;
                    out.add(new Row(Kind.FACT, label, lines.get(i), labelW,
                        (label == null ? 0 : label.length()) + lines.get(i).length()));
                }
            }
            for (String line : wrap(tr("maniacmod.wiki.defaults_note"), wrapW)) {
                out.add(new Row(Kind.NOTE, null, line, 0, line.length()));
            }
        }

        String tipKey = key("tip");
        if (has(tipKey)) {
            String tip = tr("maniacmod.wiki.tip_prefix") + " " + tr(tipKey);
            for (String line : wrap(tip, wrapW - 8)) {
                out.add(new Row(Kind.TIP, null, line, 8, line.length()));
            }
        }

        // Вертикальні позиції.
        int lh = font.lineHeight + 2;
        int[] ys = new int[out.size()];
        int y = 0;
        for (int i = 0; i < out.size(); i++) {
            Kind k = out.get(i).kind();
            if (k == Kind.HEADER && i > 0) y += 7;
            if (k == Kind.TIP && i > 0 && out.get(i - 1).kind() != Kind.TIP) y += 6;
            ys[i] = y;
            y += lh;
        }
        rows = out;
        rowY = ys;
        contentH = y;
    }

    private void addSection(List<Row> out, int wrapW, String headerKey, String body, Kind kind) {
        out.add(headerRow(headerKey));
        for (String line : wrap(body, wrapW)) out.add(new Row(kind, null, line, 0, line.length()));
    }

    private Row headerRow(String headerKey) {
        String text = tr(headerKey).toUpperCase(Locale.ROOT);
        return new Row(Kind.HEADER, null, text, 0, text.length());
    }

    private List<String> wrap(String text, int maxW) {
        List<String> lines = new ArrayList<>();
        for (FormattedText part : font.getSplitter().splitLines(text, Math.max(20, maxW), Style.EMPTY)) {
            lines.add(part.getString());
        }
        if (lines.isEmpty()) lines.add("");
        return lines;
    }

    private String key(String suffix) {
        return "maniacmod.wiki.item." + itemId + "." + suffix;
    }

    private static String tr(String key) {
        return Language.getInstance().getOrDefault(key);
    }

    private static boolean has(String key) {
        return Language.getInstance().has(key);
    }

    // ═════════════════════════════════════════════════════════════════
    //  Рендер
    // ═════════════════════════════════════════════════════════════════

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long now = Util.getMillis();
        float dt = UiAnim.frameDt(frameClock);

        float open = UiAnim.easeOutCubic((now - openedAt) / OPEN_MS);
        float close = closingAt < 0 ? 0f : UiAnim.clamp01((now - closingAt) / CLOSE_MS);
        if (close >= 1f) {                 // повністю згасло — закриє tick()
            readyToClose = true;
            return;
        }
        float life = open * (1f - close * close);
        float ease = open * (1f - close);

        stepPhysics(dt, mouseX, mouseY);
        drawBackdrop(g, life);

        GeneratorFuelUiTheme.beginAppear(g, width / 2f, height / 2f, ease, 12f);
        drawPanel(g, now, open, life);
        drawViewport(g, now, dt, life);
        drawTitleBlock(g, now, life);
        drawArticle(g, now, life);
        drawEscChip(g, life);
        GeneratorFuelUiTheme.endAppear(g);
    }

    private void drawBackdrop(GuiGraphics g, float life) {
        g.fill(0, 0, width, height, GeneratorFuelUiTheme.alpha(0xFF04060A, 0.78f * life));
        int edge = Math.max(40, height / 4);
        int c = GeneratorFuelUiTheme.alpha(0xFF000000, 0.42f * life);
        g.fillGradient(0, 0, width, edge, c, 0x00000000);
        g.fillGradient(0, height - edge, width, height, 0x00000000, c);
    }

    private void drawPanel(GuiGraphics g, long now, float open, float life) {
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, GeneratorFuelUiTheme.alpha(0xFF090B0E, 0.96f * life));
        UiFx.glow(g, panelX, panelY, panelW, panelH, 0xC2C9D6, 0.20f * life, 5);
        ManiacUiTheme.border1px(g, panelX, panelY, panelW, panelH, GeneratorFuelUiTheme.alpha(ManiacUiTheme.BORDER, life));

        // Верхня лінія росте з центру; кутові скоби злітаються до панелі.
        float grow = UiAnim.easeOutCubic((now - openedAt - 80) / 650f);
        int half = Math.round(panelW / 2f * grow);
        int cx = panelX + panelW / 2;
        g.fill(cx - half, panelY, cx + half, panelY + 2, GeneratorFuelUiTheme.alpha(GeneratorFuelUiTheme.HIGHLIGHT, 0.85f * life));
        UiFx.brackets(g, panelX, panelY, panelW, panelH,
            GeneratorFuelUiTheme.alpha(ManiacUiTheme.BORDER_BRIGHT, 0.85f * life), 14, 1f - open, 10);

        // Роздільник між моделлю й текстом.
        int sx = viewX + viewW + 9;
        g.fill(sx, panelY + PAD, sx + 1, panelY + panelH - PAD, GeneratorFuelUiTheme.alpha(ManiacUiTheme.BORDER, life));
    }

    // ── Ліва частина: модель ─────────────────────────────────────────

    private void stepPhysics(float dt, int mx, int my) {
        boolean overView = inView(mx, my);
        viewHover = UiAnim.approach(viewHover, overView ? 1f : 0f, 12f, dt);
        chipHover = UiAnim.approach(chipHover, inChip(mx, my) ? 1f : 0f, 14f, dt);
        zoom = UiAnim.approach(zoom, zoomTarget, 10f, dt);

        if (!dragging) {
            // Автообертання + інерція від останнього руху мишею.
            yaw += (30f + yawVel) * dt;
            yawVel = UiAnim.approach(yawVel, 0f, 2.6f, dt);
            tilt = UiAnim.approach(tilt, 14f, 3f, dt);
        }
    }

    private boolean inView(double mx, double my) {
        return mx >= viewX && mx < viewX + viewW && my >= viewY && my < viewY + viewH;
    }

    private boolean inChip(double mx, double my) {
        return mx >= chipX && mx < chipX + chipW && my >= chipY && my < chipY + chipH;
    }

    private void drawViewport(GuiGraphics g, long now, float dt, float life) {
        float t = (now - openedAt) / 1000f;
        float cx = viewX + viewW / 2f;
        float cy = viewY + viewH / 2f - 6f;
        float R = Math.min(viewW, viewH) * 0.50f;
        float floorY = cy + R * 0.82f;

        // Фон вікна.
        g.fill(viewX, viewY, viewX + viewW, viewY + viewH, GeneratorFuelUiTheme.alpha(0xFF05070A, 0.92f * life));

        g.enableScissor(viewX, viewY, viewX + viewW, viewY + viewH);

        // Радіальне сяйво за предметом + тугіше ядро; пульсує.
        float pulse = 0.85f + 0.15f * Mth.sin(t * 2.1f);
        CircleFx.disc(g, cx, cy, R * 1.25f,
            GeneratorFuelUiTheme.alpha(0xFFC2C9D6, 0.24f * pulse * life), 0x00C2C9D6);
        CircleFx.disc(g, cx, cy, R * 0.55f,
            GeneratorFuelUiTheme.alpha(0xFFFFFFFF, 0.10f * pulse * life), 0x00FFFFFF);

        // Промені, що повільно обертаються.
        for (int k = 0; k < 7; k++) {
            float a = CircleFx.TAU * k / 7f + t * 0.22f;
            CircleFx.sector(g, cx, cy, R * 2.1f, a, 0.12f,
                GeneratorFuelUiTheme.alpha(0xFFE8ECF4, 0.10f * life), 0x00E8ECF4);
        }

        // Майданчик під предметом: еліптичні кільця (стиснутий по Y шар).
        g.pose().pushPose();
        g.pose().translate(cx, floorY, 0f);
        g.pose().scale(1f, 0.26f, 1f);
        CircleFx.disc(g, 0f, 0f, R * 0.78f,
            GeneratorFuelUiTheme.alpha(0xFF000000, 0.55f * life), 0x00000000);
        CircleFx.ring(g, 0f, 0f, R * 0.66f, R * 0.675f,
            GeneratorFuelUiTheme.alpha(ManiacUiTheme.BORDER_BRIGHT, 0.55f * life));
        CircleFx.ticks(g, 0f, 0f, R * 0.70f, R * 0.77f, 48, 1.4f, -t * 0.5f, i ->
            GeneratorFuelUiTheme.alpha(0xFFC2C9D6, (i % 4 == 0 ? 0.65f : 0.25f) * life));
        g.pose().popPose();

        // Ударна хвиля при появі.
        float wave = (now - openedAt - 140f) / 760f;
        if (wave > 0f && wave < 1f) {
            float r = wave * R * 1.9f;
            CircleFx.ring(g, cx, cy, r, r + 2.5f * (1f - wave) + 0.5f,
                GeneratorFuelUiTheme.alpha(0xFFFFFFFF, 0.55f * (1f - wave) * life));
        }

        // Модель. Поява: «вискакує» з відскоком і докручується зі швидкого обертання.
        float appear = UiAnim.easeOutBack((now - openedAt - 60f) / 700f, 1.1f);
        float spinIn = (1f - UiAnim.easeOutCubic((now - openedAt) / 1300f)) * 540f;
        float bob = Mth.sin(t * 1.7f) * R * 0.035f;
        float roll = Mth.sin(t * 1.1f) * 3f;
        float itemSize = R * 1.05f * zoom * Math.max(0f, appear);

        boolean drawn = itemSize > 1f && ItemWikiModel3d.render(g, stack, itemId,
            cx, cy + bob, itemSize, yaw + spinIn, tilt, roll);
        if (!drawn && !ItemWikiModel3d.available()) drawFallbackIcon(g, cx, cy + bob, R, life);

        // Іскри: підіймаються від майданчика; орбітальні вогники.
        emitSparks(dt, cx, floorY, R);
        sparks.tickAndRender(g, dt, -16f);
        drawMotes(g, t, cx, cy, R, life);

        // Сканувальний промінь раз на кілька секунд.
        float beam = ((t - 1.2f) % 5.2f) / 1.3f;
        if (t > 1.2f && beam >= 0f && beam <= 1f) {
            UiFx.scanBeam(g, viewX, viewY, viewW, viewH, beam, 14, 0xFFFFFF, Math.round(34 * life));
        }
        // Дрібні горизонтальні лінії — «екран приладу».
        int lineCol = GeneratorFuelUiTheme.alpha(0xFF000000, 0.16f * life);
        for (int y = viewY + ((int) (t * 12) % 4); y < viewY + viewH; y += 4) {
            g.fill(viewX, y, viewX + viewW, y + 1, lineCol);
        }

        g.disableScissor();

        // Рамка й скоби: при наведенні розходяться.
        ManiacUiTheme.border1px(g, viewX, viewY, viewW, viewH,
            GeneratorFuelUiTheme.alpha(GeneratorFuelUiTheme.mix(ManiacUiTheme.BORDER, ManiacUiTheme.BORDER_BRIGHT, viewHover), life));
        UiFx.brackets(g, viewX, viewY, viewW, viewH,
            GeneratorFuelUiTheme.alpha(ManiacUiTheme.BORDER_BRIGHT, (0.45f + 0.4f * viewHover) * life), 9, viewHover, 4);

        // Підказка під моделлю.
        String hint = tr("maniacmod.wiki.hint.rotate");
        int hc = GeneratorFuelUiTheme.alpha(COL_DIM, (0.55f + 0.35f * viewHover) * life);
        if (visible(hc)) g.drawCenteredString(font, hint, viewX + viewW / 2, viewY + viewH - font.lineHeight - 5, hc);
    }

    private void emitSparks(float dt, float cx, float floorY, float R) {
        emitAcc += dt * 22f;
        while (emitAcc >= 1f) {
            emitAcc -= 1f;
            float ang = (float) (Math.random() * CircleFx.TAU);
            float rad = (float) Math.sqrt(Math.random()) * R * 0.62f;
            float x = cx + Mth.cos(ang) * rad;
            float y = floorY + Mth.sin(ang) * rad * 0.26f;
            sparks.emit(x, y, (float) (Math.random() - 0.5) * 6f, -(10f + (float) Math.random() * 26f),
                1.4f + (float) Math.random() * 1.8f, Math.random() < 0.25 ? 0xFFFFFF : 0xC2C9D6,
                Math.random() < 0.2 ? 2 : 1);
        }
    }

    /** Три вогники по нахиленій орбіті навколо предмета, зі слідом. */
    private void drawMotes(GuiGraphics g, float t, float cx, float cy, float R, float life) {
        for (int k = 0; k < 3; k++) {
            for (int trail = 0; trail < 5; trail++) {
                float a = t * (1.1f + 0.25f * k) + CircleFx.TAU * k / 3f - trail * 0.11f;
                float x = cx + Mth.cos(a) * R * 0.78f;
                float y = cy + Mth.sin(a) * R * 0.78f * 0.32f + Mth.sin(a * 0.5f) * 6f - R * 0.05f;
                float f = 1f - trail / 5f;
                int c = GeneratorFuelUiTheme.alpha(0xFFFFFFFF, 0.8f * f * f * life);
                int s = trail == 0 ? 2 : 1;
                g.fill(Math.round(x), Math.round(y), Math.round(x) + s, Math.round(y) + s, c);
            }
        }
    }

    /** Запасний малюнок, якщо 3D-рендер предмета вимкнено (див. {@link ItemWikiModel3d}). */
    private void drawFallbackIcon(GuiGraphics g, float cx, float cy, float R, float life) {
        CircleFx.ring(g, cx, cy, R * 0.42f, R * 0.44f, GeneratorFuelUiTheme.alpha(ManiacUiTheme.BORDER_BRIGHT, life));
        String letter = title.isEmpty() ? "?" : title.substring(0, 1).toUpperCase(Locale.ROOT);
        g.pose().pushPose();
        g.pose().translate(cx, cy - 8f, 0f);
        g.pose().scale(3f, 3f, 1f);
        g.drawCenteredString(font, letter, 0, 0, GeneratorFuelUiTheme.alpha(COL_BRIGHT, life));
        g.pose().popPose();
    }

    // ── Права частина: заголовок ─────────────────────────────────────

    private void drawTitleBlock(GuiGraphics g, long now, float life) {
        // Рядок-«кікер» друкується швидко ще до заголовка.
        int shown = Math.min(kicker.length(), (int) (Math.max(0, now - openedAt - 150) / 1000f * KICKER_CPS));
        int kc = GeneratorFuelUiTheme.alpha(COL_DIM, life);
        if (visible(kc) && shown > 0) {
            boolean blink = ((now / 380) & 1) == 0;
            g.fill(textX, kickerY + 1, textX + 3, kickerY + 4, GeneratorFuelUiTheme.alpha(ACCENT, (blink ? 1f : 0.35f) * life));
            g.drawString(font, kicker.substring(0, shown), textX + 7, kickerY, kc, false);
        }

        drawTitle(g, now, life);

        // Роздільник росте зліва направо; по ньому біжить відблиск.
        float d = UiAnim.easeOutCubic((now - openedAt - 380f) / 650f);
        int w = Math.round(textW * d);
        if (w > 0) {
            g.fill(textX, dividerY, textX + w, dividerY + 1, GeneratorFuelUiTheme.alpha(ManiacUiTheme.BORDER_BRIGHT, 0.55f * life));
            g.fill(textX, dividerY, textX + Math.min(w, 34), dividerY + 1, GeneratorFuelUiTheme.alpha(GeneratorFuelUiTheme.HIGHLIGHT, life));
            if (d >= 1f) {
                int gx = textX + (int) ((now / 1000f * 150f) % (textW + 60)) - 30;
                for (int i = -12; i <= 12; i++) {
                    int px = gx + i;
                    if (px < textX || px >= textX + w) continue;
                    float k = 1f - Math.abs(i) / 12f;
                    g.fill(px, dividerY, px + 1, dividerY + 1, GeneratorFuelUiTheme.alpha(0xFFFFFFFF, 0.75f * k * life));
                }
            }
        }
    }

    private void drawTitle(GuiGraphics g, long now, float life) {
        int n = title.length();
        float glitch = glitchAmount(now, n);
        float done = (now - openedAt - 220f - n * 42f - 280f);
        float sweep = done > 0 ? ((now - openedAt) / 1000f * 16f) % (n + 26) - 13f : -99f;

        float cursor = 0f;
        for (int i = 0; i < n; i++) {
            String ch = title.substring(i, i + 1);
            float a = UiAnim.easeOutCubic((now - openedAt - 220f - i * 42f) / 280f);
            if (a > 0.01f) {
                float shimmer = Math.max(0f, 1f - Math.abs(i - sweep) / 3f);
                int col = GeneratorFuelUiTheme.mix(0xFFCDD3DD, 0xFFFFFFFF, shimmer);
                g.pose().pushPose();
                g.pose().translate(textX + cursor, titleY - (1f - a) * 10f, 0f);
                g.pose().scale(titleScale, titleScale, 1f);
                if (glitch > 0.02f) {
                    // Хроматична аберація: червона й блакитна «тіні» по боках.
                    g.drawString(font, ch, -1, 0, GeneratorFuelUiTheme.alpha(0xFFFF3A4A, 0.6f * glitch * a * life), false);
                    g.drawString(font, ch, 1, 0, GeneratorFuelUiTheme.alpha(0xFF3AE6FF, 0.6f * glitch * a * life), false);
                }
                int c = GeneratorFuelUiTheme.alpha(col, a * life);
                if (visible(c)) g.drawString(font, ch, 0, 0, c, true);
                g.pose().popPose();
            }
            cursor += font.width(ch) * titleScale;
        }
    }

    /**
     * Глітч заголовка: сильний, поки літери ще «збираються», далі — короткі
     * спалахи раз на ~7 с (рахуємо від відкриття, щоб не залежати від годинника гри).
     */
    private float glitchAmount(long now, int titleLen) {
        float sinceOpen = (now - openedAt) / 1000f;
        float introEnd = (220f + titleLen * 42f + 500f) / 1000f;
        float intro = sinceOpen < introEnd ? 1f - UiAnim.clamp01((sinceOpen - 0.2f) / (introEnd - 0.2f)) : 0f;
        float burstPhase = sinceOpen % 7.3f;
        float burst = sinceOpen > introEnd && burstPhase < 0.12f ? 0.8f * (1f - burstPhase / 0.12f) : 0f;
        return Math.max(intro * 0.9f, burst);
    }

    // ── Права частина: стаття, що друкується ─────────────────────────

    private void drawArticle(GuiGraphics g, long now, float life) {
        float elapsed = Math.max(0f, now - openedAt - TYPE_DELAY_MS) / 1000f;
        float budget = skipTyping ? 1e9f : elapsed * CPS;

        g.pose().pushPose();
        g.pose().translate(textX, bodyTop, 0f);
        g.pose().scale(textScale, textScale, 1f);
        g.enableScissor(
            textX - 2, bodyTop - 2,
            textX + textW + 2, bodyTop + bodyH + 2);

        int lastVisible = -1;
        int lastShown = 0;
        boolean allDone = true;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.kind() == Kind.HEADER && i > 0) budget -= 10f;   // пауза перед новим розділом
            int shown = (int) Math.max(0f, Math.min(r.len(), budget));
            budget -= r.len() + 2f;
            if (shown <= 0) {
                allDone = false;
                break;
            }
            drawRow(g, r, rowY[i], shown, life);
            lastVisible = i;
            lastShown = shown;
            if (shown < r.len()) {
                allDone = false;
                break;
            }
        }

        // Курсор: блок у кінці надрукованого; після завершення — повільне мигання підкреслення.
        if (lastVisible >= 0) {
            Row r = rows.get(lastVisible);
            boolean blink = ((now / 420) & 1) == 0;
            if (!allDone || blink) {
                int cx = rowEndX(r, lastShown);
                int cy = rowY[lastVisible];
                int cc = GeneratorFuelUiTheme.alpha(allDone ? ACCENT : 0xFFFFFFFF, life);
                if (allDone) g.fill(cx + 2, cy + font.lineHeight - 1, cx + 7, cy + font.lineHeight, cc);
                else g.fill(cx, cy, cx + 1, cy + font.lineHeight, cc);
            }
        }
        if (allDone && lastVisible == rows.size() - 1 && !typingDoneSounded) {
            typingDoneSounded = true;
            UiAnim.playReady();
        }

        g.disableScissor();
        g.pose().popPose();
    }

    private void drawRow(GuiGraphics g, Row r, int y, int shown, float life) {
        switch (r.kind()) {
            case HEADER -> {
                int hc = GeneratorFuelUiTheme.alpha(ACCENT, life);
                g.fill(0, y + 3, 3, y + 6, hc);
                if (visible(hc)) g.drawString(font, r.text().substring(0, shown), 7, y, hc, true);
                // Лінія за заголовком «дотягується» разом із друком.
                int ls = 7 + font.width(r.text()) + 6;
                int lw = Math.round((Math.round(textW / textScale) - ls) * (shown / (float) r.len()));
                if (lw > 0) g.fill(ls, y + 4, ls + lw, y + 5, GeneratorFuelUiTheme.alpha(ManiacUiTheme.BORDER_BRIGHT, 0.35f * life));
            }
            case BODY -> drawTyped(g, r.text(), 0, y, shown, COL_BODY, life);
            case NOTE -> drawTyped(g, r.text(), 0, y, shown, COL_DIM, life);
            case TIP -> {
                g.fill(0, y, 2, y + font.lineHeight + 2, GeneratorFuelUiTheme.alpha(ACCENT, 0.9f * life));
                drawTyped(g, r.text(), r.indent(), y, shown, GeneratorFuelUiTheme.mix(COL_BODY, 0xFFF2F4F8, 0.6f), life);
            }
            case FACT -> {
                int ll = r.label() == null ? 0 : r.label().length();
                if (r.label() != null) drawTyped(g, r.label(), 0, y, Math.min(shown, ll), COL_DIM, life);
                int vs = Math.max(0, shown - ll);
                if (vs > 0) drawTyped(g, r.text(), r.indent(), y, vs, COL_BRIGHT, life);
            }
        }
    }

    /** Малює перші {@code shown} символів; щойно надрукований — білий (ефект «гарячої» літери). */
    private void drawTyped(GuiGraphics g, String full, int x, int y, int shown, int color, float life) {
        shown = Math.min(shown, full.length());
        if (shown <= 0) return;
        int c = GeneratorFuelUiTheme.alpha(color, life);
        if (!visible(c)) return;
        if (shown < full.length()) {
            String head = full.substring(0, shown - 1);
            g.drawString(font, head, x, y, c, true);
            g.drawString(font, full.substring(shown - 1, shown), x + font.width(head), y,
                GeneratorFuelUiTheme.alpha(0xFFFFFFFF, life), true);
        } else {
            g.drawString(font, full, x, y, c, true);
        }
    }

    /** X (у координатах масштабованого блока тексту) одразу за надрукованою частиною рядка. */
    private int rowEndX(Row r, int shown) {
        switch (r.kind()) {
            case HEADER:
                return 7 + font.width(r.text().substring(0, Math.min(shown, r.text().length())));
            case FACT: {
                int ll = r.label() == null ? 0 : r.label().length();
                if (shown <= ll && r.label() != null) return font.width(r.label().substring(0, shown));
                int vs = Math.min(Math.max(0, shown - ll), r.text().length());
                return r.indent() + font.width(r.text().substring(0, vs));
            }
            case TIP:
                return r.indent() + font.width(r.text().substring(0, Math.min(shown, r.text().length())));
            default:
                return font.width(r.text().substring(0, Math.min(shown, r.text().length())));
        }
    }

    private void drawEscChip(GuiGraphics g, float life) {
        int border = GeneratorFuelUiTheme.mix(ManiacUiTheme.BORDER, ManiacUiTheme.BORDER_BRIGHT, chipHover);
        ManiacUiTheme.border1px(g, chipX, chipY, chipW, chipH, GeneratorFuelUiTheme.alpha(border, life));
        if (chipHover > 0.02f) {
            g.fill(chipX + 1, chipY + 1, chipX + chipW - 1, chipY + chipH - 1,
                GeneratorFuelUiTheme.alpha(0xFFC2C9D6, 0.10f * chipHover * life));
        }
        int tc = GeneratorFuelUiTheme.alpha(GeneratorFuelUiTheme.mix(COL_DIM, COL_BRIGHT, chipHover), life);
        if (visible(tc)) g.drawCenteredString(font, "ESC", chipX + chipW / 2, chipY + 2, tc);
    }

    /** Ванільний Font вважає колір із майже нульовою альфою НЕпрозорим — тож майже невидимий текст не малюємо. */
    private static boolean visible(int argb) {
        return ((argb >>> 24) & 0xFF) >= 8;
    }

    // ═════════════════════════════════════════════════════════════════
    //  Ввід
    // ═════════════════════════════════════════════════════════════════

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && closingAt < 0) {
            if (inChip(mouseX, mouseY)) {
                UiAnim.playClick();
                startClose();
                return true;
            }
            if (inView(mouseX, mouseY)) {
                dragging = true;
                yawVel = 0f;
                UiAnim.playClick();
                sparks.burst((float) mouseX, (float) mouseY, 14, 90f, 0f, -1f, 0.6f, 0xFFFFFF);
                return true;
            }
            if (!skipTyping) {
                skipTyping = true;    // клік по тексту — дорисувати все одразу
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) dragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging && button == 0) {
            yaw += (float) dragX * 0.9f;
            yawVel = Mth.lerp(0.4f, yawVel, (float) dragX * 40f);
            tilt = Mth.clamp(tilt + (float) dragY * 0.5f, -45f, 45f);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        zoomTarget = Mth.clamp(zoomTarget + (float) delta * 0.12f, 0.6f, 1.6f);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || ManiacKeybinds.ITEM_WIKI.matches(keyCode, scanCode)) {
            startClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_SPACE) {
            skipTyping = true;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Гарантує, що відпущена поза екраном миша не залишить модель у режимі перетягування. */
    @Override
    public void removed() {
        dragging = false;
        sparks.clear();
    }
}
