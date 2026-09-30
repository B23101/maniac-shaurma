package com.log_to_kot.maniacmod.client.chat;

import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.UiFx;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import dev.shaurmalib.common.chat.ChatChannel;
import dev.shaurmalib.common.chat.ChatChannelRegistry;
import dev.shaurmalib.common.chat.ChatEntry;
import dev.shaurmalib.common.chat.ChatFormatEngine;
import dev.shaurmalib.forge.chat.ChatEntryRenderer;
import dev.shaurmalib.forge.chat.ChatEntryRendererRegistry;
import dev.shaurmalib.forge.chat.ChatModule;
import dev.shaurmalib.forge.chat.ChatScreenButton;
import dev.shaurmalib.forge.chat.ChatScreenButtonRegistry;
import dev.shaurmalib.forge.network.ShaurmaLibNetwork;
import dev.shaurmalib.forge.network.packets.ChatSendPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Екран чату режиму «Маньяк» (T або /) — власна верстка мода замість
 * {@code ChatHistoryScreen} із shaurma-lib.
 *
 * <h3>Навіщо окремий екран</h3>
 * Бібліотечний екран мав два дефекти, які з боку мода не виправити:
 * <ul>
 *   <li><b>Накладання.</b> Стрічка повідомлень закінчувалась на 6 px над
 *       рядом каналів, а ряд кнопок дій (⚙ Налаштування гри) стояв на
 *       4–20 px над тим самим рядом — тобто ВСЕРЕДИНІ стрічки. На вузькому
 *       екрані кнопка лягала прямо на нижні повідомлення.</li>
 *   <li><b>Інший стиль.</b> Сірі прямокутники snipers_shaurma поруч із
 *       рештою інтерфейсу мода ({@link ManiacUiTheme}) виглядали чужими.</li>
 * </ul>
 * Дані (історія, канали, кнопки дій, пакет відправки) беруться з
 * бібліотеки ті самі — змінена лише верстка й малювання.
 *
 * <h3>Розкладка (знизу вгору)</h3>
 * <pre>
 *   поле вводу
 *   ряд каналів [ліворуч]  +  кнопки дій [праворуч]   ← один ряд, якщо влазить,
 *                                                       інакше кнопки дій — рядом вище
 *   ───────── стрічка повідомлень закінчується ТУТ ─────────
 * </pre>
 * Низ стрічки рахується від верхнього ряду кнопок, тому вона фізично не
 * може перекритись із кнопками, при будь-якому розмірі вікна.
 */
@OnlyIn(Dist.CLIENT)
public final class ManiacChatScreen extends Screen {

    // ── Розкладка ────────────────────────────────────────────────────────
    private static final int SIDE = 8;
    private static final int FEED_W = 330;
    private static final int PANEL_W = 420;
    /** Відступ знизу: над хотбаром мода. */
    private static final int BOTTOM = 40;
    private static final int INPUT_H = 22;
    private static final int BTN_H = 16;
    private static final int BTN_GAP = 4;
    private static final int BTN_PAD = 8;
    private static final int ROW_GAP = 4;
    /** Просвіт між стрічкою і верхнім рядом кнопок. */
    private static final int FEED_GAP = 8;
    private static final int GROUP_GAP = 12;

    private static final int ENTRY_PAD_X = 8;
    private static final int ENTRY_PAD_Y = 5;
    private static final int ENTRY_GAP = 3;
    private static final int ENTRY_PAD_RIGHT = 8;

    private static final int COUNTER_W = 40;
    private static final int SUGGESTION_ROW_H = 12;
    private static final int MAX_VISIBLE_SUGGESTIONS = 8;
    private static final int MAX_HISTORY = 50;

    private static final int OPAQUE = 0xFF000000;

    // ── Стан ─────────────────────────────────────────────────────────────
    private EditBox inputBox;
    private String selectedChannel = ChatChannel.GLOBAL_ID;
    private final List<ChatChannel> channels = new ArrayList<>();
    private final List<ChatScreenButton> extraButtons = new ArrayList<>();
    /** Список каналів на момент останнього init — щоб перебудувати екран, якщо фаза змінила доступ. */
    private List<String> lastWritable = List.of();

    private int panelX, panelW;
    private int inputY;
    private int channelRowY, extraRowY;
    private boolean extrasMerged;
    private int feedX, feedY, feedW, feedH;

    /** Прокрутка стрічки, px: 0 = найновіші внизу. */
    private float scrollTarget = 0f;
    private float scrollShown = 0f;
    private int maxScroll = 0;
    private final long[] clock = new long[1];

    // ── Анімації ─────────────────────────────────────────────────────────
    private static final float OPEN_SECONDS = 0.30f;
    private static final long ENTRY_ANIM_MS = 340L;
    /** Прогрес відкриття екрана 0..1 (рахується за реальним часом). */
    private float openAnim = 0f;
    /** Коли кожен запис вперше побачили; 0 = був до відкриття чату (без анімації появи). */
    private final Map<ChatEntry, Long> firstSeen = new WeakHashMap<>();
    private boolean feedPrimed = false;
    /** Плавне наведення на кнопки: [0..channels) — канали, далі кнопки дій. */
    private float[] hover = new float[0];
    /** Рухомий індикатор активної вкладки каналу. */
    private float tabX, tabW;
    private boolean tabPlaced = false;
    /** Плавний фокус поля вводу 0..1 (для світіння). */
    private float focusGlow = 0f;

    /** Кеш висот записів: перераховується лише коли змінилась історія чи ширина. */
    private int[] heights = new int[0];
    private int cachedSize = -1;
    private int cachedContentW = -1;
    private ChatEntry cachedFirst, cachedLast;

    private static final List<String> messageHistory = new ArrayList<>();
    private int historyIndex = -1;

    private ParseResults<SharedSuggestionProvider> lastParse;
    private Suggestions currentSuggestions;
    private int highlightedSuggestion = -1;
    private int suggestionScrollTop = 0;

    public ManiacChatScreen() {
        super(Component.translatable("maniacmod.chat.title"));
    }

    // ══════════════════════════════════════════════════════════════════
    //  Ініціалізація
    // ══════════════════════════════════════════════════════════════════

    @Override
    protected void init() {
        String previousText = inputBox != null ? inputBox.getValue() : "";

        if (channels.isEmpty() || selectedChannel == null) {
            selectedChannel = defaultChannel();
        }
        refreshChannels();

        // ── Горизонталь ──
        panelW = Math.min(PANEL_W, this.width - SIDE * 2);
        panelX = (this.width - panelW) / 2;

        // ── Вертикаль знизу вгору ──
        inputY = this.height - BOTTOM - INPUT_H;
        channelRowY = inputY - ROW_GAP - BTN_H;

        int channelsTotal = 0;
        for (int i = 0; i < channels.size(); i++) {
            channelsTotal += channelButtonWidth(channels.get(i)) + (i > 0 ? BTN_GAP : 0);
        }
        int extrasTotal = 0;
        for (int i = 0; i < extraButtons.size(); i++) {
            extrasTotal += buttonWidth(extraButtons.get(i).label().getString()) + (i > 0 ? BTN_GAP : 0);
        }

        // Кнопки дій — в тому самому ряду, що й канали, якщо обидві групи влазять;
        // інакше окремим рядом ВИЩЕ. У будь-якому разі стрічка закінчується над ними.
        extrasMerged = extraButtons.isEmpty() || channelsTotal + GROUP_GAP + extrasTotal <= panelW;
        extraRowY = extrasMerged ? channelRowY : channelRowY - ROW_GAP - BTN_H;
        int topRowY = extraButtons.isEmpty() || extrasMerged ? channelRowY : extraRowY;

        // ── Стрічка ──
        feedW = Math.min(FEED_W, this.width - SIDE * 2);
        feedX = this.width - feedW - SIDE;
        feedY = SIDE;
        feedH = Math.max(40, topRowY - FEED_GAP - feedY);

        // ── Поле вводу (без власної рамки — рамку малюємо самі) ──
        int boxX = panelX + 10;
        int boxW = Math.max(40, panelW - 10 - COUNTER_W - 6);
        inputBox = new EditBox(this.font, boxX, inputY + (INPUT_H - 9) / 2, boxW, 10,
            Component.translatable("maniacmod.chat.input_hint"));
        inputBox.setMaxLength(ChatFormatEngine.MAX_LENGTH);
        inputBox.setBordered(false);
        inputBox.setTextColor(ManiacUiTheme.TEXT_TITLE);
        inputBox.setHint(Component.translatable("maniacmod.chat.input_hint")
            .withStyle(s -> s.withColor(ManiacUiTheme.TEXT_DIM & 0xFFFFFF)));
        inputBox.setResponder(this::onInputChanged);
        addRenderableWidget(inputBox);
        this.setInitialFocus(inputBox);
        if (!previousText.isEmpty()) inputBox.setValue(previousText);

        lastWritable = new ArrayList<>(ChatModule.clientWritableChannels());
        hover = new float[channels.size() + extraButtons.size()];
        tabPlaced = false;
    }

    private static String defaultChannel() {
        String def = ChatModule.clientDefaultChannel();
        return def == null || def.isBlank() ? ChatChannel.GLOBAL_ID : def;
    }

    /** Перечитує канали з реєстру + клієнтського дзеркала доступу. */
    private void refreshChannels() {
        channels.clear();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.add(ChatChannel.GLOBAL_ID);
        ids.addAll(ChatModule.clientWritableChannels());
        for (String id : ids) {
            channels.add(ChatChannelRegistry.byIdOrSynthetic(id));
        }
        boolean stillThere = false;
        for (ChatChannel c : channels) {
            if (c.id().equals(selectedChannel)) { stillThere = true; break; }
        }
        if (!stillThere) selectedChannel = defaultChannel();

        extraButtons.clear();
        for (ChatScreenButton button : ChatScreenButtonRegistry.all()) {
            if (button.isVisible()) extraButtons.add(button);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (inputBox != null) inputBox.tick();
        // Фаза матчу змінила доступні канали, поки чат відкритий — перебудувати вкладки.
        if (!ChatModule.clientWritableChannels().equals(lastWritable)) {
            this.rebuildWidgets();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }

    // ══════════════════════════════════════════════════════════════════
    //  Рендер
    // ══════════════════════════════════════════════════════════════════

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        float dt = UiAnim.frameDt(clock);
        scrollShown = UiAnim.approach(scrollShown, scrollTarget, 18f, dt);
        if (Math.abs(scrollShown - scrollTarget) < 0.05f) scrollShown = scrollTarget;

        openAnim = Math.min(1f, openAnim + dt / OPEN_SECONDS);
        float open = UiAnim.easeOutCubic(openAnim);
        focusGlow = UiAnim.approach(focusGlow, inputBox.isFocused() ? 1f : 0f, 10f, dt);

        // Стрічка виїжджає справа.
        g.pose().pushPose();
        g.pose().translate((1f - open) * 36f, 0f, 0f);
        renderFeed(g);
        g.pose().popPose();
        feedPrimed = true;

        // Панель вводу з кнопками виїжджає знизу.
        g.pose().pushPose();
        g.pose().translate(0f, (1f - open) * 16f, 0f);
        renderInputPanel(g);
        super.render(g, mx, my, partialTick);   // саме поле вводу
        Component tooltip = renderButtons(g, mx, my, dt);
        g.pose().popPose();

        renderCommandSuggestions(g, mx, my);
        if (tooltip != null && openAnim >= 1f) g.renderTooltip(this.font, tooltip, mx, my);
    }

    // ── Стрічка ──────────────────────────────────────────────────────────

    private void renderFeed(GuiGraphics g) {
        List<ChatEntry> entries = ChatModule.history().snapshot();
        if (entries.isEmpty()) {
            maxScroll = 0;
            scrollTarget = scrollShown = 0f;
            int h = 20;
            int y = feedY + feedH - h;
            g.fill(feedX, y, feedX + feedW, y + h, ManiacUiTheme.PANEL_BG);
            ManiacUiTheme.border1px(g, feedX, y, feedW, h, ManiacUiTheme.BORDER);
            g.drawString(this.font, Component.translatable("maniacmod.chat.empty_history"),
                feedX + ENTRY_PAD_X, y + (h - this.font.lineHeight) / 2, ManiacUiTheme.TEXT_DIM, false);
            return;
        }

        // Запам'ятовуємо момент появи КОЖНОГО запису (не лише видимих), щоб
        // старі записи, які відкриє прокрутка, не «виїжджали» вдруге.
        long now = System.currentTimeMillis();
        for (ChatEntry entry : entries) {
            firstSeen.putIfAbsent(entry, feedPrimed ? now : 0L);
        }

        ChatEntryRenderer renderer = ChatEntryRendererRegistry.get();
        int contentW = Math.max(20, feedW - ENTRY_PAD_X - ENTRY_PAD_RIGHT);
        ensureHeights(entries, renderer, contentW);

        int totalH = 0;
        for (int h : heights) totalH += h + ENTRY_GAP;
        totalH -= ENTRY_GAP;
        maxScroll = Math.max(0, totalH - feedH);
        scrollTarget = Mth.clamp(scrollTarget, 0f, maxScroll);
        scrollShown = Mth.clamp(scrollShown, 0f, maxScroll);

        int bottomY = feedY + feedH;
        int y = bottomY + Math.round(scrollShown);   // нижня межа наступного запису

        // Ножиці: запис, що частково виїхав угору/вниз, не заходить на кнопки чи в край екрана.
        g.enableScissor(feedX - 1, feedY, feedX + feedW + 1, bottomY);
        for (int i = entries.size() - 1; i >= 0; i--) {
            int h = heights[i];
            int top = y - h;
            if (y < feedY) break;               // цей і всі старші — вище за стрічку
            if (top <= bottomY) {
                drawEntry(g, entries.get(i), renderer, top, h, contentW, now);
            }
            y = top - ENTRY_GAP;
        }
        g.disableScissor();

        // Смужка прокрутки — лише якщо є що гортати.
        if (maxScroll > 0) {
            int trackX = feedX + feedW + 3;
            g.fill(trackX, feedY, trackX + 2, bottomY, GeneratorFuelUiTheme.alpha(ManiacUiTheme.BORDER, 0.9f));
            int thumbH = Math.max(12, (int) ((long) feedH * feedH / Math.max(1, totalH)));
            float frac = scrollShown / maxScroll;                    // 0 = внизу
            int thumbY = feedY + Math.round((feedH - thumbH) * (1f - frac));
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, ManiacUiTheme.BORDER_BRIGHT);
        }
    }

    private void ensureHeights(List<ChatEntry> entries, ChatEntryRenderer renderer, int contentW) {
        int n = entries.size();
        ChatEntry first = entries.get(0);
        ChatEntry last = entries.get(n - 1);
        if (n == cachedSize && contentW == cachedContentW && first == cachedFirst && last == cachedLast) return;

        heights = new int[n];
        for (int i = 0; i < n; i++) {
            int content = Math.max(1, renderer.contentHeight(entries.get(i), this.font, contentW));
            heights[i] = content + ENTRY_PAD_Y * 2;
        }
        cachedSize = n;
        cachedContentW = contentW;
        cachedFirst = first;
        cachedLast = last;
    }

    private void drawEntry(GuiGraphics g, ChatEntry entry, ChatEntryRenderer renderer,
                           int top, int h, int contentW, long now) {
        int accent = accentFor(entry);

        // Поява нового повідомлення: виїжджає справа з «дотиком» кольору групи.
        Long born = firstSeen.get(entry);
        float t = (born == null || born == 0L) ? 1f : UiAnim.clamp01((now - born) / (float) ENTRY_ANIM_MS);
        float e = UiAnim.easeOutCubic(t);

        g.pose().pushPose();
        g.pose().translate((1f - e) * 46f, 0f, 0f);

        g.fill(feedX, top, feedX + feedW, top + h, ManiacUiTheme.PANEL_BG);
        ManiacUiTheme.border1px(g, feedX, top, feedW, h, ManiacUiTheme.BORDER);
        g.fill(feedX, top, feedX + 2, top + h, accent);      // кольорова смужка групи/каналу
        renderer.render(g, entry, this.font, feedX + ENTRY_PAD_X, top + ENTRY_PAD_Y,
            contentW, h - ENTRY_PAD_Y * 2, accent);

        if (t < 1f) {
            // Спалах кольору групи, що згасає, + «шторка» затемнення = імітація fade-in.
            UiFx.glow(g, feedX, top, feedW, h, accent, (1f - e) * 1.2f, 3);
            g.fill(feedX, top, feedX + feedW, top + h,
                GeneratorFuelUiTheme.alpha(ManiacUiTheme.PANEL_BG_SOLID, (1f - e) * 0.8f));
        }
        g.pose().popPose();
    }

    private int accentFor(ChatEntry entry) {
        return switch (entry.type()) {
            case DEATH -> ManiacUiTheme.BAR_FILL_DANGER;
            case CHAT_GLOBAL, CHAT_TEAM ->
                ChatFormatEngine.parseColorOrDefault(entry.colorHex(), ManiacUiTheme.BORDER_BRIGHT);
            case SYSTEM -> ManiacUiTheme.BORDER_BRIGHT;
        };
    }

    // ── Поле вводу ───────────────────────────────────────────────────────

    private void renderInputPanel(GuiGraphics g) {
        int accent = channelColor(selectedChannel);
        // М'яке дихаюче світіння кольору каналу, поки в полі фокус.
        float pulse = 0.65f + 0.35f * (float) Math.sin(System.currentTimeMillis() / 420.0);
        UiFx.glow(g, panelX, inputY, panelW, INPUT_H, accent, focusGlow * pulse, 4);
        g.fill(panelX, inputY, panelX + panelW, inputY + INPUT_H, ManiacUiTheme.PANEL_BG);
        ManiacUiTheme.border1px(g, panelX, inputY, panelW, INPUT_H,
            inputBox.isFocused() ? ManiacUiTheme.BORDER_BRIGHT : ManiacUiTheme.BORDER);
        GeneratorFuelUiTheme.topSheen(g, panelX, inputY, panelW, 0f);
        g.fill(panelX, inputY, panelX + 2, inputY + INPUT_H, accent);

        int len = inputBox.getValue().length();
        if (len > 0) {
            String counter = len + "/" + ChatFormatEngine.MAX_LENGTH;
            int color = len >= ChatFormatEngine.MAX_LENGTH * 9 / 10
                ? ManiacUiTheme.BAR_FILL_WARN : ManiacUiTheme.TEXT_DIM;
            g.drawString(this.font, counter, panelX + panelW - 6 - this.font.width(counter),
                inputY + (INPUT_H - this.font.lineHeight) / 2, color, false);
        }
    }

    // ── Кнопки ───────────────────────────────────────────────────────────

    private int buttonWidth(String label) {
        return this.font.width(label) + BTN_PAD * 2;
    }

    private int channelButtonWidth(ChatChannel channel) {
        return buttonWidth(Component.translatable(channel.labelKey()).getString());
    }

    private int channelColor(String id) {
        return ChatChannelRegistry.byIdOrSynthetic(id).colorArgb() | OPAQUE;
    }

    /** X правої групи кнопок дій: притиснута до правого краю панелі. */
    private int extrasStartX() {
        int total = 0;
        for (int i = 0; i < extraButtons.size(); i++) {
            total += buttonWidth(extraButtons.get(i).label().getString()) + (i > 0 ? BTN_GAP : 0);
        }
        return panelX + panelW - total;
    }

    /** Малює всі кнопки; повертає підказку кнопки під курсором (малюється останньою — поверх усього). */
    private Component renderButtons(GuiGraphics g, int mx, int my, float dt) {
        int x = panelX;
        int idx = 0;
        int activeX = -1, activeW = 0, activeColor = ManiacUiTheme.BORDER_BRIGHT;
        for (ChatChannel channel : channels) {
            String label = Component.translatable(channel.labelKey()).getString();
            int w = buttonWidth(label);
            boolean active = channel.id().equals(selectedChannel);
            int color = channel.colorArgb() | OPAQUE;
            if (active) { activeX = x; activeW = w; activeColor = color; }
            float hv = stepHover(idx++, inside(mx, my, x, channelRowY, w, BTN_H), dt);
            drawButton(g, x, channelRowY, w, label, active, color, hv);
            x += w + BTN_GAP;
        }

        // Індикатор активної вкладки плавно їде до нового каналу.
        if (activeX >= 0) {
            if (!tabPlaced) { tabX = activeX; tabW = activeW; tabPlaced = true; }
            tabX = UiAnim.approach(tabX, activeX, 16f, dt);
            tabW = UiAnim.approach(tabW, activeW, 16f, dt);
            int ix = Math.round(tabX), iw = Math.round(tabW);
            g.fill(ix + 1, channelRowY + BTN_H - 2, ix + iw - 1, channelRowY + BTN_H - 1, activeColor);
            g.fill(ix + 3, channelRowY + BTN_H - 1, ix + iw - 3, channelRowY + BTN_H,
                GeneratorFuelUiTheme.alpha(activeColor, 0.45f));
        }

        Component hoveredTooltip = null;
        x = extrasStartX();
        for (ChatScreenButton button : extraButtons) {
            String label = button.label().getString();
            int w = buttonWidth(label);
            boolean over = inside(mx, my, x, extraRowY, w, BTN_H);
            float hv = stepHover(idx++, over, dt);
            drawButton(g, x, extraRowY, w, label, false, ManiacUiTheme.MENU_ACCENT_GOLD, hv);
            if (hv > 0.05f) UiFx.glow(g, x, extraRowY, w, BTN_H, ManiacUiTheme.MENU_ACCENT_GOLD, hv * 0.9f, 2);
            if (over && button.tooltip() != null) hoveredTooltip = button.tooltip();
            x += w + BTN_GAP;
        }
        return hoveredTooltip;
    }

    /** Плавне 0..1 наведення для кнопки з номером {@code i}. */
    private float stepHover(int i, boolean over, float dt) {
        if (i >= hover.length) return over ? 1f : 0f;
        hover[i] = UiAnim.approach(hover[i], over ? 1f : 0f, 18f, dt);
        if (hover[i] < 0.01f) hover[i] = 0f;
        return hover[i];
    }

    private void drawButton(GuiGraphics g, int x, int y, int w, String label,
                            boolean active, int accent, float hv) {
        int base = active ? GeneratorFuelUiTheme.mix(ManiacUiTheme.SLOT_FILL, accent, 0.22f) : ManiacUiTheme.PANEL_BG;
        int fill = GeneratorFuelUiTheme.mix(base, ManiacUiTheme.MENU_PANEL_DARK, hv * 0.9f);
        int border = active ? accent
            : GeneratorFuelUiTheme.mix(ManiacUiTheme.BORDER, ManiacUiTheme.BORDER_BRIGHT, hv);
        int text = active ? ManiacUiTheme.TEXT_ACCENT
            : GeneratorFuelUiTheme.mix(ManiacUiTheme.TEXT_BODY, ManiacUiTheme.TEXT_TITLE, hv);

        g.fill(x, y, x + w, y + BTN_H, fill);
        ManiacUiTheme.border1px(g, x, y, w, BTN_H, border);
        // Блик, що виростає від центру при наведенні.
        if (hv > 0.02f && !active) {
            int half = Math.round((w / 2f - 1) * hv);
            g.fill(x + w / 2 - half, y + 1, x + w / 2 + half, y + 2,
                GeneratorFuelUiTheme.alpha(0xFFFFFFFF, 0.35f * hv));
        }
        g.drawString(this.font, label, x + (w - this.font.width(label)) / 2,
            y + (BTN_H - this.font.lineHeight) / 2 - Math.round(hv), text, false);
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ── Підказки команд ──────────────────────────────────────────────────

    /** {x, y, w, h} панелі підказок або {@code null}, якщо їх немає. Одне джерело правди для малювання й кліків. */
    private int[] suggestionRect() {
        if (currentSuggestions == null || currentSuggestions.getList().isEmpty()) return null;
        int visible = Math.min(currentSuggestions.getList().size(), MAX_VISIBLE_SUGGESTIONS);
        int h = visible * SUGGESTION_ROW_H + 4;
        return new int[] { panelX, inputY - 2 - h, panelW, h };
    }

    private void renderCommandSuggestions(GuiGraphics g, int mx, int my) {
        int[] r = suggestionRect();
        if (r == null) return;
        List<Suggestion> list = currentSuggestions.getList();
        int total = list.size();
        int visible = Math.min(total, MAX_VISIBLE_SUGGESTIONS);

        g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], ManiacUiTheme.PANEL_BG_SOLID);
        ManiacUiTheme.border1px(g, r[0], r[1], r[2], r[3], ManiacUiTheme.BORDER_BRIGHT);

        for (int row = 0; row < visible; row++) {
            int idx = suggestionScrollTop + row;
            if (idx >= total) break;
            int rowY = r[1] + 2 + row * SUGGESTION_ROW_H;
            boolean hovered = inside(mx, my, r[0], rowY, r[2], SUGGESTION_ROW_H);
            boolean selected = idx == highlightedSuggestion;
            if (hovered || selected) {
                g.fill(r[0] + 1, rowY, r[0] + r[2] - 1, rowY + SUGGESTION_ROW_H,
                    selected ? 0x33C2C9D6 : 0x22FFFFFF);
            }
            g.drawString(this.font, list.get(idx).getText(), r[0] + 6, rowY + 2,
                selected ? ManiacUiTheme.TEXT_ACCENT : hovered ? ManiacUiTheme.TEXT_TITLE : ManiacUiTheme.TEXT_BODY, false);
        }
        if (total > visible) {
            String pos = (highlightedSuggestion < 0 ? 1 : highlightedSuggestion + 1) + "/" + total;
            g.drawString(this.font, pos, r[0] + r[2] - 6 - this.font.width(pos), r[1] + 2,
                ManiacUiTheme.TEXT_DIM, false);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Логіка підказок команд (Brigadier)
    // ══════════════════════════════════════════════════════════════════

    private void onInputChanged(String text) {
        currentSuggestions = null;
        highlightedSuggestion = -1;
        suggestionScrollTop = 0;

        if (!text.startsWith("/")) {
            lastParse = null;
            return;
        }
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null) return;

        var dispatcher = connection.getCommands();
        var source = connection.getSuggestionsProvider();
        int slashCount = ChatFormatEngine.leadingSlashCount(text);
        String withoutSlash = text.substring(slashCount);
        int cursor = Math.max(0, inputBox.getCursorPosition() - slashCount);
        cursor = Math.min(cursor, withoutSlash.length());

        try {
            ParseResults<SharedSuggestionProvider> parsed = dispatcher.parse(withoutSlash, source);
            lastParse = parsed;
            dispatcher.getCompletionSuggestions(parsed, cursor).thenAccept(result -> {
                if (lastParse != parsed) return;
                currentSuggestions = (result == null || result.isEmpty()) ? null : result;
            });
        } catch (Exception ignored) {
            lastParse = null;
        }
    }

    private void applySuggestion(int index) {
        if (currentSuggestions == null) return;
        List<Suggestion> list = currentSuggestions.getList();
        if (index < 0 || index >= list.size()) return;

        Suggestion chosen = list.get(index);
        StringRange range = currentSuggestions.getRange();

        String current = inputBox.getValue();
        if (!current.startsWith("/")) return;
        int slashCount = ChatFormatEngine.leadingSlashCount(current);
        String withoutSlash = current.substring(slashCount);

        int start = Mth.clamp(range.getStart(), 0, withoutSlash.length());
        int end = Mth.clamp(range.getEnd(), start, withoutSlash.length());

        String newValue = "/".repeat(slashCount)
            + withoutSlash.substring(0, start) + chosen.getText() + withoutSlash.substring(end);

        inputBox.setValue(newValue);
        int newCursor = slashCount + start + chosen.getText().length();
        inputBox.setCursorPosition(newCursor);
        inputBox.setHighlightPos(newCursor);
        onInputChanged(newValue);
    }

    private int suggestionCount() {
        return currentSuggestions == null ? 0 : currentSuggestions.getList().size();
    }

    private void moveSuggestionSelection(int delta) {
        int count = suggestionCount();
        if (count == 0) return;
        if (highlightedSuggestion < 0) {
            highlightedSuggestion = delta > 0 ? 0 : count - 1;
        } else {
            highlightedSuggestion = Mth.clamp(highlightedSuggestion + delta, 0, count - 1);
        }
        if (highlightedSuggestion < suggestionScrollTop) {
            suggestionScrollTop = highlightedSuggestion;
        } else if (highlightedSuggestion >= suggestionScrollTop + MAX_VISIBLE_SUGGESTIONS) {
            suggestionScrollTop = highlightedSuggestion - MAX_VISIBLE_SUGGESTIONS + 1;
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Ввід
    // ══════════════════════════════════════════════════════════════════

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        final int KEY_TAB = 258, KEY_ENTER = 257, KEY_KP_ENTER = 335, KEY_ESCAPE = 256;
        final int KEY_UP = 265, KEY_DOWN = 264;

        if (keyCode == KEY_UP) {
            if (suggestionCount() > 0) moveSuggestionSelection(-1); else recallOlderMessage();
            return true;
        }
        if (keyCode == KEY_DOWN) {
            if (suggestionCount() > 0) moveSuggestionSelection(1); else recallNewerMessage();
            return true;
        }
        if (suggestionCount() > 0 && keyCode == KEY_TAB) {
            applySuggestion(highlightedSuggestion >= 0 ? highlightedSuggestion : 0);
            return true;
        }
        if (keyCode == KEY_ENTER || keyCode == KEY_KP_ENTER) {
            if (highlightedSuggestion >= 0 && suggestionCount() > 0) {
                applySuggestion(highlightedSuggestion);
                return true;
            }
            trySendMessage();
            return true;
        }
        if (keyCode == KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            if (handleChannelClick(mx, my)) return true;
            if (handleExtraButtonClick(mx, my)) return true;

            int[] r = suggestionRect();
            if (r != null && inside(mx, my, r[0], r[1], r[2], r[3])) {
                int idx = suggestionScrollTop + (int) ((my - (r[1] + 2)) / SUGGESTION_ROW_H);
                if (idx >= 0 && idx < suggestionCount()) {
                    applySuggestion(idx);
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    private boolean handleChannelClick(double mx, double my) {
        if (my < channelRowY || my >= channelRowY + BTN_H) return false;
        int x = panelX;
        for (ChatChannel channel : channels) {
            int w = channelButtonWidth(channel);
            if (mx >= x && mx < x + w) {
                selectedChannel = channel.id();
                return true;
            }
            x += w + BTN_GAP;
        }
        return false;
    }

    private boolean handleExtraButtonClick(double mx, double my) {
        if (extraButtons.isEmpty()) return false;
        if (my < extraRowY || my >= extraRowY + BTN_H) return false;
        int x = extrasStartX();
        for (ChatScreenButton button : extraButtons) {
            int w = buttonWidth(button.label().getString());
            if (mx >= x && mx < x + w) {
                button.press(this);
                return true;
            }
            x += w + BTN_GAP;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int[] r = suggestionRect();
        if (r != null && inside(mx, my, r[0], r[1], r[2], r[3])) {
            int visible = Math.min(suggestionCount(), MAX_VISIBLE_SUGGESTIONS);
            int maxTop = Math.max(0, suggestionCount() - visible);
            suggestionScrollTop = Mth.clamp(suggestionScrollTop - (int) Math.signum(delta), 0, maxTop);
            return true;
        }
        scrollTarget = Mth.clamp(scrollTarget + (float) delta * 24f, 0f, maxScroll);
        return true;
    }

    private void trySendMessage() {
        String text = inputBox.getValue();
        if (text == null || text.isBlank()) {
            this.onClose();
            return;
        }
        String stripped = text.strip();
        if (messageHistory.isEmpty() || !messageHistory.get(messageHistory.size() - 1).equals(stripped)) {
            messageHistory.add(stripped);
            if (messageHistory.size() > MAX_HISTORY) messageHistory.remove(0);
        }
        historyIndex = -1;
        ShaurmaLibNetwork.sendToServer(new ChatSendPacket(stripped, selectedChannel));
        inputBox.setValue("");
        this.onClose();
    }

    private void recallOlderMessage() {
        if (messageHistory.isEmpty()) return;
        if (historyIndex < 0) {
            historyIndex = messageHistory.size() - 1;
        } else if (historyIndex > 0) {
            historyIndex--;
        } else {
            return;
        }
        String msg = messageHistory.get(historyIndex);
        inputBox.setValue(msg);
        inputBox.setCursorPosition(msg.length());
    }

    private void recallNewerMessage() {
        if (historyIndex < 0) return;
        historyIndex++;
        if (historyIndex >= messageHistory.size()) {
            historyIndex = -1;
            inputBox.setValue("");
        } else {
            String msg = messageHistory.get(historyIndex);
            inputBox.setValue(msg);
            inputBox.setCursorPosition(msg.length());
        }
    }
}
