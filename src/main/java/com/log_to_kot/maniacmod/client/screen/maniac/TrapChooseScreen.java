package com.log_to_kot.maniacmod.client.screen.maniac;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.c2s.traps.TrapChoosePacket;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Екран вибору пасток маньяка — «обери до {@code limit} пасток зі
 * списку». Відкривається сервером ({@code TrapCatalogPacket}) на фазі
 * ROLE_REVEAL, лише коли пасток в архетипу БІЛЬШЕ, ніж можна взяти
 * (інакше вибирати нема що — маньяк отримує всі автоматично).
 *
 * ── Стиль (сітка карток) ────────────────────────────────────────────
 * На відміну від інших меню мода (вертикальний список рядків), цей
 * екран — горизонтальна СІТКА квадратних карток-іконок, за референсом
 * заданим дизайнером: заголовок капсом, лічильник «X / limit» під
 * ним, картки в ряд по центру, підказка-назва знизу (з'являється при
 * наведенні), кнопка підтвердження окремим блоком під усім цим.
 * {@link ManiacUiTheme} все одно постачає панель/рамки/кольори — лише
 * розкладка карток і ряду власна, ванільні {@link Button} використані
 * тільки для кнопки підтвердження.
 *
 * ── Дві фази, два різні рухи (важливо!) ────────────────────────────
 * 1) ПІД ЧАС вибору (клік по картці, до підтвердження): картка НЕ
 *    рухається нікуди. Вона миттєво позначається як обрана прямо у
 *    своїй комірці сітки — золота рамка, яскравіша іконка, лічильник
 *    оновлюється. {@link #selected} — єдине джерело правди для цього
 *    стану, рендер лише читає його щокадру.
 * 2) ПІСЛЯ підтвердження ({@link #confirm()}): ось тоді і тільки тоді
 *    вмикається рух — обрані картки анімовано з'їжджають вниз-вліво,
 *    кожна у свій слот (як розкладання по клавішах 5/6/7), а НЕ обрані
 *    так само анімовано їдуть вгору й розчиняються, звільняючи місце.
 *    {@link #confirmedAtMillis} фіксує момент старту цієї єдиної
 *    анімації; мережевий пакет {@link TrapChoosePacket} шлеться лише
 *    коли вона добігає кінця (щокадрова перевірка в
 *    {@link #maybeFinishConfirm}) — гравець встигає побачити власне
 *    підтвердження перед переходом.
 *
 * ── Хто вирішує, що можна ───────────────────────────────────────────
 * Це лише інтерфейс. Клік перемикає ЛОКАЛЬНИЙ набір {@link #selected};
 * підтвердити можна, лише коли розмір набору в межах {@code [1, limit]}
 * (не 0 — маньяк без жодної пастки безглуздий). Сервер
 * ({@code TrapModule#onTrapsChosen}) усе одно перевіряє все заново —
 * цей екран лише не дає надіслати завідомо неможливий запит.
 *
 * ── Esc і час ────────────────────────────────────────────────────────
 * Esc закриває екран (ванільна поведінка {@link Screen}, нічого не
 * перевизначено) БЕЗ підтвердження. Це safe: {@code TrapModule.chooseAll}
 * бере всі пастки автоматично на вході в ігрову фазу, якщо вибір так і
 * не надійшов — маньяк не лишається без жодної пастки через
 * випадковий Esc чи вихід за таймаут ROLE_REVEAL.
 *
 * ── Оновлення каталогу «на льоту» ───────────────────────────────────
 * {@link #onCatalogUpdated} викликається {@code ClientPacketHandler},
 * якщо пакет прийшов, коли екран уже відкритий (реконект). Раніше
 * зроблений вибір, що й досі є в новому каталозі, зберігається.
 */
public final class TrapChooseScreen extends Screen {

    private static final int PANEL_WIDTH = 620;

    private static final int CARD_SIZE = 92;
    private static final int CARD_GAP = 18;
    private static final int CARD_ICON = 48;

    private static final int SLOT_SIZE = 40;
    private static final int SLOT_GAP = 10;
    /** Простір під слот-смугу (де "осідають" обрані картки після підтвердження) між сіткою і hint-баром. */
    private static final int SLOT_STRIP_GAP_ABOVE = 16;
    private static final int SLOT_STRIP_GAP_BELOW = 14;

    private static final int HINT_HEIGHT = 22;

    private static final int CONFIRM_BUTTON_HEIGHT = 22;
    private static final int CONFIRM_BUTTON_WIDTH = 170;
    private static final int CONFIRM_GAP_TOP = 18;

    /** Каскадна затримка появи між сусідніми картками при відкритті екрана, мс. */
    private static final int APPEAR_STAGGER_MS = 55;
    /** Тривалість анімації появи однієї картки, мс. */
    private static final int APPEAR_DURATION_MS = 260;

    /** Каскадна затримка між картками в анімації РОЗКЛАДАННЯ (після підтвердження), мс. */
    private static final int CONFIRM_STAGGER_MS = 45;
    /** Тривалість руху однієї картки в анімації розкладання, мс. */
    private static final int CONFIRM_MOVE_DURATION_MS = 260;

    private List<String> trapIds;
    private int limit;

    /** LinkedHashSet: порядок вибору зберігається — перше обране лишається слотом 5, друге — 6. */
    private final Set<String> selected = new LinkedHashSet<>();

    private Button confirmButton;

    /** Межі кожної картки для mouseClicked/hover — перебудовуються в init() і при зміні каталогу. */
    private final List<int[]> cardBounds = new ArrayList<>();

    /** Момент відкриття екрана (або останнього onCatalogUpdated) — точка відліку появи карток. */
    private long openedAtMillis;

    /**
     * Момент натискання "Підтвердити", або -1, поки підтвердження не було.
     * Єдиний перемикач між фазою 1 (вибір без руху) і фазою 2 (розкладання
     * з рухом): доки -1, {@link #renderCard} малює картки СТАТИЧНО за
     * {@link #selected}; щойно виставлено — стартує рух і клік по картках
     * ігнорується (див. {@link #mouseClicked}).
     */
    private long confirmedAtMillis = -1;
    /** Список, зафіксований у момент підтвердження — {@link #selected} після цього більше не міняється,
     *  але тримаємо окрему копію, щоб порядок слотів не "поплив", якби хтось десь ще чіпав selected. */
    private List<String> confirmedSelection = List.of();
    /** Пакет із запитом на вибір вже надіслано — щоб не продублювати з кількох кадрів поспіль. */
    private boolean confirmPacketSent = false;

    public TrapChooseScreen(List<String> trapIds, int limit) {
        super(Component.translatable("maniacmod.trapchoose.title"));
        this.trapIds = List.copyOf(trapIds);
        this.limit = limit;
    }

    /** Новий каталог прийшов, поки екран відкритий (реконект). Зберігаємо перетин вибору. */
    public void onCatalogUpdated(List<String> trapIds, int limit) {
        this.trapIds = List.copyOf(trapIds);
        this.limit = limit;
        selected.retainAll(this.trapIds);
        clearWidgets();
        init();
    }

    @Override
    protected void init() {
        openedAtMillis = Util.getMillis();
        layoutCards();

        int buttonY = confirmButtonY();
        confirmButton = Button.builder(Component.translatable("maniacmod.trapchoose.confirm"),
                button -> confirm())
            .bounds(panelX() + (PANEL_WIDTH - CONFIRM_BUTTON_WIDTH) / 2, buttonY,
                    CONFIRM_BUTTON_WIDTH, CONFIRM_BUTTON_HEIGHT)
            .build();
        addRenderableWidget(confirmButton);
        updateConfirmState();
    }

    /** Розкладає картки в один ряд по центру панелі (сітка одним рядком — макс. 6 карток на референсі). */
    private void layoutCards() {
        cardBounds.clear();
        int count = trapIds.size();
        int totalW = count * CARD_SIZE + Math.max(0, count - 1) * CARD_GAP;
        int startX = panelX() + (PANEL_WIDTH - totalW) / 2;
        int cardY = gridTop();
        for (int i = 0; i < count; i++) {
            int cardX = startX + i * (CARD_SIZE + CARD_GAP);
            cardBounds.add(new int[]{cardX, cardY, CARD_SIZE, CARD_SIZE});
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true; // безпечно: сервер автовибере решту, якщо вибір так і не надійде
    }

    private int panelX() { return (width - PANEL_WIDTH) / 2; }
    private int panelY() { return Math.max(20, (height - panelHeight()) / 2); }

    private int titleTop() { return panelY() + 16; }
    private int counterTop() { return titleTop() + font.lineHeight + 8; }
    private int gridTop() { return counterTop() + font.lineHeight + 18; }
    private int slotStripTop() { return gridTop() + CARD_SIZE + SLOT_STRIP_GAP_ABOVE; }
    private int hintTop() { return slotStripTop() + SLOT_SIZE + SLOT_STRIP_GAP_BELOW; }
    private int confirmButtonY() { return hintTop() + HINT_HEIGHT + CONFIRM_GAP_TOP; }

    private int panelHeight() {
        // Висота рахується "знизу вгору" від фіксованих внутрішніх відступів,
        // а не через titleTop()/gridTop() (ті самі залежать від panelY() -> panelHeight() -> цикл).
        int top = 16 + font.lineHeight + 8 + font.lineHeight + 18;
        int afterGrid = CARD_SIZE + SLOT_STRIP_GAP_ABOVE + SLOT_SIZE + SLOT_STRIP_GAP_BELOW
            + HINT_HEIGHT + CONFIRM_GAP_TOP + CONFIRM_BUTTON_HEIGHT;
        return top + afterGrid + 20;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Немає власного renderBackground: фон гри лишається видимим,
        // лише панель темніша — той самий підхід, що TargetMinigameScreen.
        int panelX = panelX(), panelY = panelY();
        int panelH = panelHeight();
        long now = Util.getMillis();

        maybeFinishConfirm(now);

        ManiacUiTheme.drawPanel(graphics, panelX, panelY, PANEL_WIDTH, panelH, true);

        graphics.drawCenteredString(font, getTitle().getString().toUpperCase(Locale.ROOT),
            panelX + PANEL_WIDTH / 2, titleTop(), ManiacUiTheme.TEXT_TITLE);

        String counter = String.format(Locale.ROOT,
            Component.translatable("maniacmod.trapchoose.counter").getString(), selected.size(), limit);
        graphics.drawCenteredString(font, counter, panelX + PANEL_WIDTH / 2, counterTop(), ManiacUiTheme.TEXT_DIM);

        // Порожні рамки-слоти (клавіші 5/6/7), видимі лише після підтвердження — до того часу
        // немає "місця призначення" на екрані, це заплутувало б, а не пояснювало.
        if (confirmedAtMillis >= 0) {
            for (int i = 0; i < limit; i++) {
                int[] slot = slotBounds(i);
                graphics.fill(slot[0], slot[1], slot[0] + slot[2], slot[1] + slot[3], ManiacUiTheme.SLOT_FILL);
                ManiacUiTheme.border1px(graphics, slot[0], slot[1], slot[2], slot[3], ManiacUiTheme.BORDER);
            }
        }

        String hoveredName = null;
        for (int i = 0; i < trapIds.size(); i++) {
            String trapId = trapIds.get(i);
            boolean hovered = renderCard(graphics, mouseX, mouseY, trapId, cardBounds.get(i), i, now);
            if (hovered) hoveredName = Component.translatable("maniacmod.trap.name." + trapId).getString();
        }

        // Наведено на картку -> показуємо її назву. Інакше, поки нічого не
        // обрано, підказуємо навести курсор (як на референсі); щойно з'явився
        // перший вибір — переключаємось на функціональну підказку про клавіші
        // 5/6/7, бо саме тоді вона стає доречною (є що розміщувати в грі).
        String hintText;
        if (hoveredName != null) {
            hintText = hoveredName;
        } else if (selected.isEmpty()) {
            hintText = Component.translatable("maniacmod.trapchoose.hint").getString();
        } else {
            hintText = Component.translatable("maniacmod.trapchoose.hint.keys").getString();
        }
        ManiacUiTheme.drawHintBar(graphics, font, hintText, panelX + 16, hintTop(), PANEL_WIDTH - 32, HINT_HEIGHT);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * Малює одну картку. Поведінка залежить ЛИШЕ від фази:
     *  - до підтвердження ({@code confirmedAtMillis < 0}): картка нерухома
     *    у своїй комірці сітки; "обрано" — лише зміна кольору рамки/іконки,
     *    жодного зсуву позиції чи розміру;
     *  - після підтвердження: обрані картки анімовано їдуть+стискаються до
     *    свого слота внизу-зліва, НЕ обрані — їдуть вгору і розчиняються.
     * Повертає true, якщо миша над карткою В КІНЦЕВІЙ (поточній анімованій)
     * позиції — використовується для підказки з назвою знизу.
     */
    private boolean renderCard(GuiGraphics graphics, int mouseX, int mouseY, String trapId, int[] bounds, int index, long now) {
        int baseX = bounds[0], baseY = bounds[1], w = bounds[2], h = bounds[3];

        // ── Поява екрана: fade + slide-up, каскад зліва направо ──
        long appearStart = openedAtMillis + (long) index * APPEAR_STAGGER_MS;
        float appearT = Mth.clamp((now - appearStart) / (float) APPEAR_DURATION_MS, 0f, 1f);
        float appearEase = easeOutCubic(appearT);
        int appearYOffset = Mth.floor((1f - appearEase) * 24);
        float appearAlpha = appearEase;

        boolean chosen = selected.contains(trapId);

        int x = baseX, y = baseY + appearYOffset;
        int curW = w, curH = h;
        float fadeAlpha = 1f; // додаткове затухання лише в фазі розкладання (не обрані картки зникають)
        float moveEase = 0f;  // 0 = у сітці, 1 = у слоті; лишається 0 поза фазою 2 і для не обраних карток

        if (confirmedAtMillis >= 0) {
            // ── Фаза 2: розкладання по слотах, рух стартує лише тут ──
            long moveStart = confirmedAtMillis + (long) index * CONFIRM_STAGGER_MS;
            float moveT = Mth.clamp((now - moveStart) / (float) CONFIRM_MOVE_DURATION_MS, 0f, 1f);
            moveEase = easeOutCubic(moveT);

            if (chosen) {
                int slotIndex = confirmedSelection.indexOf(trapId);
                int[] slot = slotBounds(Math.max(slotIndex, 0));
                int targetX = slot[0] - baseX;
                int targetY = slot[1] - baseY;
                x = baseX + Mth.floor(targetX * moveEase);
                y = baseY + Mth.floor(targetY * moveEase); // замінює appearYOffset: рух вже враховує кінцеву появу
                curW = Mth.floor(Mth.lerp(moveEase, w, SLOT_SIZE));
                curH = Mth.floor(Mth.lerp(moveEase, h, SLOT_SIZE));
            } else {
                y = baseY - Mth.floor(moveEase * 30);
                fadeAlpha = 1f - moveEase;
            }
            // Картка "росте/стискається" з центру своєї базової позиції, не з кута — рух виглядає плавним стягуванням.
            x += (w - curW) / 2;
            y += (h - curH) / 2;
        }

        boolean hovered = confirmedAtMillis < 0 && appearT >= 1f && isInside(mouseX, mouseY, x, y, curW, curH);

        float alpha = appearAlpha * fadeAlpha;
        int alpha255 = Mth.floor(alpha * 255f);
        int fillColor = (alpha255 << 24) | (ManiacUiTheme.SLOT_FILL & 0x00FFFFFF);
        int borderColorBase = chosen ? ManiacUiTheme.MENU_ACCENT_GOLD
            : hovered ? ManiacUiTheme.BORDER_BRIGHT : ManiacUiTheme.BORDER;
        int borderColor = (alpha255 << 24) | (borderColorBase & 0x00FFFFFF);

        graphics.fill(x, y, x + curW, y + curH, fillColor);
        border1pxAlpha(graphics, x, y, curW, curH, borderColor);
        if (chosen) border1pxAlpha(graphics, x + 1, y + 1, curW - 2, curH - 2, borderColor);

        int iconSize = CARD_ICON;
        if (confirmedAtMillis >= 0 && chosen) {
            // Обрана картка в польоті до слота — іконка стискається разом з карткою, тим самим moveEase.
            iconSize = Mth.floor(Mth.lerp(moveEase, CARD_ICON, SLOT_SIZE - 12));
        }
        int iconX = x + (curW - iconSize) / 2;
        int iconY = y + (curH - iconSize) / 2;
        ResourceLocation icon = new ResourceLocation(ManiacMod.MOD_ID, "textures/gui/traps/" + trapId + ".png");
        float tint = chosen ? 1.0f : 0.65f;
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(tint, tint, tint, alpha);
        graphics.blit(icon, iconX, iconY, iconSize, iconSize, 0f, 0f, 32, 32, 32, 32);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();

        return hovered;
    }

    /** Позиції слот-смуги внизу-зліва панелі, куди "прилітають" обрані картки після підтвердження (імітує клавіші 5/6/7). */
    private int[] slotBounds(int slotIndex) {
        int stripY = slotStripTop();
        int stripX = panelX() + 16 + slotIndex * (SLOT_SIZE + SLOT_GAP);
        return new int[]{stripX, stripY, SLOT_SIZE, SLOT_SIZE};
    }

    private static float easeOutCubic(float t) {
        float f = t - 1f;
        return f * f * f + 1f;
    }

    private void border1pxAlpha(GuiGraphics g, int x, int y, int w, int h, int colorWithAlpha) {
        g.fill(x, y, x + w, y + 1, colorWithAlpha);
        g.fill(x, y + h - 1, x + w, y + h, colorWithAlpha);
        g.fill(x, y, x + 1, y + h, colorWithAlpha);
        g.fill(x + w - 1, y, x + w, y + h, colorWithAlpha);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Після підтвердження вибір заблокований — картки вже в польоті до слотів,
        // клік по них нічого не повинен міняти (сама кнопка теж вимкнена, див. confirm()).
        if (button == 0 && confirmedAtMillis < 0) {
            for (int i = 0; i < cardBounds.size(); i++) {
                int[] b = cardBounds.get(i);
                if (isInside((int) mouseX, (int) mouseY, b[0], b[1], b[2], b[3])) {
                    toggle(trapIds.get(i));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** Фаза 1 — миттєве позначення, БЕЗ жодного руху чи анімації позиції. */
    private void toggle(String trapId) {
        if (selected.remove(trapId)) {
            updateConfirmState();
            return;
        }
        if (selected.size() >= limit) return; // повно — новий вибір ігнорується, старі лишаються
        selected.add(trapId);
        updateConfirmState();
    }

    private void updateConfirmState() {
        confirmButton.active = confirmedAtMillis < 0 && !selected.isEmpty() && selected.size() <= limit;
    }

    /** Натиснуто "Підтвердити": фіксуємо вибір і стартуємо анімацію розкладання (фаза 2). Пакет ще НЕ йде тут. */
    private void confirm() {
        confirmedSelection = List.copyOf(selected);
        confirmedAtMillis = Util.getMillis();
        confirmPacketSent = false;
        confirmButton.active = false;
    }

    /** Викликається щокадру: коли анімація розкладання добігла кінця — шле пакет рівно один раз.
     *  Тривалість рахується від реальної кількості карток (остання стартує з найбільшим stagger-зсувом,
     *  а не від фіксованого запасу) — коректно, скільки б трапів не було в каталозі. */
    private void maybeFinishConfirm(long now) {
        if (confirmedAtMillis < 0 || confirmPacketSent) return;
        long lastCardStagger = (long) Math.max(0, trapIds.size() - 1) * CONFIRM_STAGGER_MS;
        long totalDuration = lastCardStagger + CONFIRM_MOVE_DURATION_MS;
        if (now - confirmedAtMillis < totalDuration) return;
        confirmPacketSent = true;
        List<Integer> indices = new ArrayList<>();
        for (String id : confirmedSelection) indices.add(trapIds.indexOf(id));
        ModNetwork.toServer(new TrapChoosePacket(indices));
        // Екран НЕ закривається тут сам: ClientPacketHandler.onTrapLoadout
        // закриє його, коли прийде застосований результат від сервера —
        // так гравець бачить свою анімацію розкладання до кінця, без
        // різкого закриття посеред польоту карток.
    }

    private static boolean isInside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
