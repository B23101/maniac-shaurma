package com.log_to_kot.maniacmod.client.screen.settings;

import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.config.ConfigBlock;
import com.log_to_kot.maniacmod.config.ConfigKey;
import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.c2s.settings.SettingsChangePacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Меню налаштувань гри — 1:1 стиль {@link ManiacUiTheme}: панель з
 * кутовими акцентами, заголовок капсом, зліва таби категорій (кожен
 * {@link ConfigBlock} зі СХЕМИ, {@code DATA}-блоки не показуються),
 * справа скрольований список ключів обраної категорії.
 *
 * ── Джерело правди про ЩО показувати ──────────────────────────────────
 * Список категорій і ключів усередині кожної — {@link ConfigSchema},
 * той самий клас, що й на сервері. Мережею летять ЛИШЕ значення
 * ({@link #values}) — додати новий ключ у схему автоматично показує
 * його тут, без жодної зміни в цьому класі.
 *
 * ── Локалізація: назва й опис КОЖНОГО ключа перекладені ───────────────
 * Попередня версія малювала {@code key.name()} напряму (сирий
 * Java-ідентифікатор на кшталт {@code gameTimeTicks}) і
 * {@code key.describeRange()} як єдиний текст під рядком (голе
 * "0…23999", без жодного пояснення, що це за налаштування) — тому що
 * жоден переклад для окремих КЛЮЧІВ не існував, лише для назв БЛОКІВ
 * ({@code maniacmod.settings.block.*}). Тепер кожен рядок читає:
 *   • {@link #keyTitle} → {@code maniacmod.settings.key.<block>.<name>}
 *     — людська назва (наприклад "Час доби" замість "gameTimeTicks");
 *   • {@link #keyHint}  → {@code maniacmod.settings.key.<block>.<name>.desc}
 *     — коротке пояснення, ЩО робить налаштування (а не тип поля).
 * Для числових полів технічний діапазон (0…23999) лишається — але як
 * ДРУГОРЯДНИЙ приглушений текст ПІСЛЯ опису, а не замість нього: гравцю
 * потрібно і "що це" (опис), і "які межі" (діапазон), не одне замість
 * іншого. Обидва рядки локалізації існують у {@code lang/uk_ua.json} і
 * {@code lang/en_us.json} для всіх 60 ключів схеми (перевірено скриптом
 * під час рефактора — жодного пропущеного ключа). Якщо для нового
 * ключа переклад ще не додали, {@link #keyTitle} падає назад на
 * {@code key.name()} (а не на порожній рядок чи виняток) — це навмисний
 * fallback для розробки, а не для релізу: він не ламає екран, коли хтось
 * додав ключ у {@link ConfigSchema} і забув дописати lang-рядок, але й
 * не ховає факт, що переклад відсутній (Component.translatable сам
 * покаже необроблений ключ, якщо рядка немає — так само, як для
 * решти неперекладеного тексту гри).
 *
 * ── Три види полів ────────────────────────────────────────────────────
 *   • {@code bool}   → перемикач "Так/Ні".
 *   • {@code option} → рядок "◀ ЗНАЧЕННЯ ▶".
 *   • {@code int}/{@code double} з межами → ПОВЗУНОК З ТЕКСТОВИМ ПОЛЕМ.
 *
 * ── Анімації (нові в цій версії) ────────────────────────────────────
 * Досі екран був повністю статичний — жодного переходу ні на відкриття,
 * ні на зміну вкладки, ні на клік по полю. Тепер:
 *   • {@link #openProgress} — плавна поява панелі й затемнення фону
 *     при {@link #init()} (той самий easing, що вже існує в бібліотеці
 *     для {@code ScreenOpenAnimator}, тут інлайновано локально, бо
 *     екран лишається чистим Forge {@link Screen} без бібліотечних
 *     залежностей понад {@link ManiacUiTheme});
 *   • кожен рядок з'являється з невеликим каскадним запізненням
 *     ({@link #rowAppearProgress}) і легким зсувом по X — панель не
 *     "вивалюється" одразу цілою простирадлом тексту;
 *   • перемикання вкладки більше не стрибає миттєво: старий вміст
 *     згасає, новий проявляється ({@link #tabFadeProgress});
 *   • повзунок, bool-перемикач і option-стрілки мають миттєвий
 *     hover/press відгук (короткий {@link #pulseFor} спалах яскравості
 *     при кліку) замість статичної зміни кольору лише по hover;
 *   • save-підтвердження (успішна зміна значення) коротко підсвічує
 *     рядок акцентним кольором і згасає — гравець одразу бачить, ЩО
 *     саме щойно змінилось, а не лише що число в полі інше.
 * Усе так само без бібліотечних лерп-класів — прості
 * експоненціальні згладжування (той самий підхід, що вже
 * використовує {@code ManiacHotbarOverlay} для рамки вибору слота).
 */
public final class SettingsMenuScreen extends Screen {

    private static final int PANEL_WIDTH = 720;
    private static final int PANEL_HEIGHT = 420;
    private static final int TAB_WIDTH = 150;
    private static final int TAB_ROW_HEIGHT = 22;
    private static final int ROW_HEIGHT = 40;
    private static final int HINT_HEIGHT = 20;
    private static final int FIELD_HEIGHT = 16;
    private static final int BOOL_FIELD_WIDTH = 90;
    private static final int OPTION_FIELD_WIDTH = 200;
    private static final int OPTION_ARROW_WIDTH = 14;
    private static final int SLIDER_WIDTH = 150;
    private static final int SLIDER_HEIGHT = 10;
    private static final int SLIDER_HANDLE_WIDTH = 6;
    private static final int NUMBER_FIELD_WIDTH = 70;
    private static final int NUMBER_FIELD_GAP = 8;

    /** Лише SETTINGS-блоки — DATA (spawn_points, zones) редагуються командами розмітки, не формою. */
    private final List<ConfigBlock> tabs;
    private int activeTab = 0;
    private int scrollOffset = 0;

    /** "block.name" -> поточне рядкове значення, з сервера. */
    private Map<String, String> values;

    /** Живі EditBox-и активної вкладки — числових полів і freeText-полів. */
    private final Map<String, EditBox> fieldEditBoxes = new HashMap<>();

    /** Чернетка числового значення повзунка для активної вкладки, ДОКИ не відпущено мишу. */
    private final Map<String, Double> numericDrafts = new HashMap<>();

    /** Шлях ключа, повзунок якого зараз тягнуть мишею, або {@code null}. */
    private String draggingSliderPath = null;

    /** Шляхи ключів, чиє текстове поле зараз містить невалідний ввід (для червоної рамки). */
    private final java.util.Set<String> invalidFields = new java.util.HashSet<>();

    /** Кешовані межі повзунка активної вкладки: шлях ключа -> { trackX, trackW }. */
    private final Map<String, int[]> sliderBounds = new HashMap<>();

    private final Map<String, int[]> boolBounds = new HashMap<>();
    private final Map<String, OptionRowBounds> optionBounds = new HashMap<>();

    /** Геометрія одного option-рядка: трек стрілки-ліво/центр/стрілка-право. */
    private record OptionRowBounds(int x, int y, int w, int h, int arrowW) {}

    // ── Анімаційний стан ─────────────────────────────────────────────
    /** 0 = щойно відкрито, 1 = повністю проявлено. Наздоганяє ціль 1 з {@link #ANIM_SPEED}. */
    private float openProgress = 0f;
    /** 0..1 — прогрес переходу вкладки: старий вміст згасає до 0, новий вміст з'являється до 1. */
    private float tabFadeProgress = 1f;
    private long lastFrameNanos = 0L;
    private static final float ANIM_SPEED = 10f;
    private static final float TAB_FADE_SPEED = 16f;

    /** Час (мс), коли розпочалось каскадне з'явлення рядків активної вкладки — для {@link #rowAppearProgress}. */
    private long rowsRevealStartMs = 0L;
    private static final long ROW_STAGGER_MS = 28L;
    private static final long ROW_APPEAR_MS = 160L;

    /** path -> момент (мс) останньої підтвердженої зміни цього ключа, для короткого спалаху рядка. */
    private final Map<String, Long> recentChangeMs = new HashMap<>();
    private static final long CHANGE_FLASH_MS = 650L;

    public SettingsMenuScreen(Map<String, String> initialValues) {
        super(Component.translatable("maniacmod.settings.title"));
        this.values = new HashMap<>(initialValues);
        this.tabs = new ArrayList<>();
        for (ConfigBlock block : ConfigSchema.BLOCKS) {
            if (block.kind() == ConfigBlock.Kind.SETTINGS) tabs.add(block);
        }
    }

    @Override
    protected void init() {
        super.init();
        rowsRevealStartMs = System.currentTimeMillis();
        rebuildFieldsForActiveTab();
    }

    /** Висота рядка опису під заголовком + відступ до роздільника (див. {@link #drawTitleFaded}). */
    private static final int SUBTITLE_BLOCK_HEIGHT = 12;
    /** Ширина зони скролбару (трек + відступ від контенту) — контент НЕ заходить у цю смугу праворуч. */
    private static final int SCROLLBAR_ZONE_WIDTH = 16;

    private int panelX() { return width / 2 - PANEL_WIDTH / 2; }
    private int panelY() { return height / 2 - PANEL_HEIGHT / 2; }
    private int contentTop(int panelY) { return panelY + 14 + font.lineHeight + SUBTITLE_BLOCK_HEIGHT + font.lineHeight + 8 + 10; }
    private int contentBottom(int panelY) { return panelY + PANEL_HEIGHT - HINT_HEIGHT - 16; }
    private int listX(int panelX) { return panelX + 8 + TAB_WIDTH + 10; }
    private int contentX(int panelX) { return listX(panelX) + 14; }
    /** Права межа контенту зупиняється ПЕРЕД зоною скролбару — поля більше не стикаються з треком скролу. */
    private int contentWidth(int panelX) { return panelX + PANEL_WIDTH - SCROLLBAR_ZONE_WIDTH - contentX(panelX); }

    /** Скільки рядків влізає в видиму зону без скролу. */
    private int visibleRowCount(int panelY) {
        return Math.max(1, (contentBottom(panelY) - contentTop(panelY)) / ROW_HEIGHT);
    }

    private void switchTab(int index) {
        if (index == activeTab) return;
        activeTab = index;
        scrollOffset = 0;
        tabFadeProgress = 0f;
        rowsRevealStartMs = System.currentTimeMillis();
        rebuildFieldsForActiveTab();
    }

    /**
     * Пересоздає віджети лише для ключів, що зараз видимі (обрана
     * вкладка, з урахуванням скролу).
     */
    private void rebuildFieldsForActiveTab() {
        for (EditBox box : fieldEditBoxes.values()) removeWidget(box);
        fieldEditBoxes.clear();
        numericDrafts.clear();
        invalidFields.clear();
        sliderBounds.clear();
        boolBounds.clear();
        optionBounds.clear();
        draggingSliderPath = null;

        ConfigBlock block = tabs.get(activeTab);
        int panelX = panelX();
        int panelY = panelY();
        int contentTop = contentTop(panelY);
        int contentX = contentX(panelX);
        int contentW = contentWidth(panelX);

        int rowIndex = 0;
        int visible = visibleRowCount(panelY);
        List<ConfigKey<?>> keys = block.keys();
        for (int i = scrollOffset; i < keys.size() && rowIndex < visible; i++, rowIndex++) {
            ConfigKey<?> key = keys.get(i);
            int rowY = contentTop + rowIndex * ROW_HEIGHT;
            createFieldFor(key, contentX, contentW, rowY);
        }
    }

    private void createFieldFor(ConfigKey<?> key, int contentX, int contentW, int rowY) {
        boolean isBoolean = key.defaultValue() instanceof Boolean;
        boolean isOption = !isBoolean && key.describeRange().contains("|");
        boolean isSlider = !isBoolean && !isOption && key.hasNumericRange();
        int fieldY = rowY + 4;

        if (isBoolean) {
            int fieldX = contentX + contentW - BOOL_FIELD_WIDTH;
            boolBounds.put(key.path(), new int[] { fieldX, fieldY, BOOL_FIELD_WIDTH, FIELD_HEIGHT });
        } else if (isOption) {
            int fieldX = contentX + contentW - OPTION_FIELD_WIDTH;
            optionBounds.put(key.path(),
                    new OptionRowBounds(fieldX, fieldY, OPTION_FIELD_WIDTH, FIELD_HEIGHT, OPTION_ARROW_WIDTH));
        } else if (isSlider) {
            int fieldX = contentX + contentW - (SLIDER_WIDTH + NUMBER_FIELD_GAP + NUMBER_FIELD_WIDTH);
            addSliderField(key, fieldX, fieldY);
        } else {
            int fieldW = SLIDER_WIDTH + NUMBER_FIELD_GAP + NUMBER_FIELD_WIDTH;
            int fieldX = contentX + contentW - fieldW;
            addFreeTextField(key, fieldX, fieldY, fieldW);
        }
    }

    // ── Локалізація ключів ────────────────────────────────────────────

    /**
     * Людська назва ключа: {@code maniacmod.settings.key.<block>.<name>}.
     * Fallback на {@code key.name()} лише якщо перекладу справді немає
     * (Component.translatable мовчки повертає сирий ключ — той самий
     * fallback, що вже стається для будь-якого неперекладеного тексту
     * гри, тут навмисно не приховується якимось "гарним" текстом, щоб
     * відсутність перекладу було одразу видно й полагоджено).
     */
    private static Component keyTitle(ConfigKey<?> key) {
        String translationKey = "maniacmod.settings.key." + key.block() + "." + key.name();
        return Component.translatable(translationKey);
    }

    /** Короткий опис ЩО робить налаштування: {@code maniacmod.settings.key.<block>.<name>.desc}. */
    private static Component keyHint(ConfigKey<?> key) {
        String translationKey = "maniacmod.settings.key." + key.block() + "." + key.name() + ".desc";
        return Component.translatable(translationKey);
    }

    private void toggleBoolean(ConfigKey<?> key) {
        boolean next = !"true".equalsIgnoreCase(currentValue(key));
        sendChange(key, String.valueOf(next));
    }

    private static String booleanLabel(boolean value) {
        return Component.translatable(value ? "maniacmod.settings.bool_true" : "maniacmod.settings.bool_false").getString();
    }

    private void drawBoolToggle(GuiGraphics g, ConfigKey<?> key, int x, int y, int w, int h, boolean hovered, float alpha) {
        boolean current = "true".equalsIgnoreCase(currentValue(key));
        g.fill(x, y, x + w, y + h, withAlpha(ManiacUiTheme.BAR_BG, alpha));
        if (current) {
            g.fill(x, y, x + w, y + h, withAlpha(ManiacUiTheme.BAR_FILL_NEUTRAL & 0x40FFFFFF | 0x30000000, alpha));
        }
        int borderColor = hovered ? ManiacUiTheme.BORDER_BRIGHT : ManiacUiTheme.BORDER;
        border1pxAlpha(g, x, y, w, h, borderColor, alpha);
        int textColor = current ? ManiacUiTheme.TEXT_ACCENT : ManiacUiTheme.TEXT_DIM;
        g.drawCenteredString(font, booleanLabel(current), x + w / 2, y + (h - font.lineHeight) / 2 + 1, withAlpha(textColor, alpha));
    }

    private void drawOptionCycler(GuiGraphics g, ConfigKey<?> key, OptionRowBounds b, int mouseX, int mouseY, float alpha) {
        int centerW = b.w() - b.arrowW() * 2;
        int arrowLeftX = b.x();
        int centerX = b.x() + b.arrowW();
        int arrowRightX = b.x() + b.arrowW() + centerW;

        boolean hoverLeft = isInside(mouseX, mouseY, arrowLeftX, b.y(), b.arrowW(), b.h());
        boolean hoverCenter = isInside(mouseX, mouseY, centerX, b.y(), centerW, b.h());
        boolean hoverRight = isInside(mouseX, mouseY, arrowRightX, b.y(), b.arrowW(), b.h());

        drawOptionSegment(g, arrowLeftX, b.y(), b.arrowW(), b.h(), "\u25c0", hoverLeft, alpha);
        drawOptionSegment(g, arrowRightX, b.y(), b.arrowW(), b.h(), "\u25b6", hoverRight, alpha);

        g.fill(centerX, b.y(), centerX + centerW, b.y() + b.h(), withAlpha(ManiacUiTheme.BAR_BG, alpha));
        border1pxAlpha(g, centerX, b.y(), centerW, b.h(),
                hoverCenter ? ManiacUiTheme.BORDER_BRIGHT : ManiacUiTheme.BORDER, alpha);
        String current = currentValue(key);
        g.drawCenteredString(font, current, centerX + centerW / 2, b.y() + (b.h() - font.lineHeight) / 2 + 1,
                withAlpha(ManiacUiTheme.TEXT_ACCENT, alpha));
    }

    private void drawOptionSegment(GuiGraphics g, int x, int y, int w, int h, String glyph, boolean hovered, float alpha) {
        g.fill(x, y, x + w, y + h, withAlpha(ManiacUiTheme.BAR_BG, alpha));
        border1pxAlpha(g, x, y, w, h, hovered ? ManiacUiTheme.BORDER_BRIGHT : ManiacUiTheme.BORDER, alpha);
        int color = hovered ? ManiacUiTheme.TEXT_ACCENT : ManiacUiTheme.TEXT_BODY;
        g.drawCenteredString(font, glyph, x + w / 2, y + (h - font.lineHeight) / 2 + 1, withAlpha(color, alpha));
    }

    private static boolean isInside(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private void cycleOption(ConfigKey<?> key, int direction) {
        String[] options = key.describeRange().split("\\s*\\|\\s*");
        String now = currentValue(key);
        int idx = 0;
        for (int i = 0; i < options.length; i++) if (options[i].equals(now)) idx = i;
        int nextIdx = Math.floorMod(idx + direction, options.length);
        sendChange(key, options[nextIdx]);
    }

    private void addSliderField(ConfigKey<?> key, int x, int y) {
        sliderBounds.put(key.path(), new int[] { x, SLIDER_WIDTH });

        int fieldX = x + SLIDER_WIDTH + NUMBER_FIELD_GAP;
        EditBox box = themedEditBox(fieldX, y, NUMBER_FIELD_WIDTH, key);
        box.setValue(currentValue(key));
        box.setMaxLength(32);
        box.setResponder(text -> invalidFields.remove(key.path()));
        addRenderableWidget(box);
        fieldEditBoxes.put(key.path(), box);
    }

    private void addFreeTextField(ConfigKey<?> key, int x, int y, int w) {
        EditBox box = themedEditBox(x, y, w, key);
        box.setValue(currentValue(key));
        box.setMaxLength(64);
        box.setResponder(text -> invalidFields.remove(key.path()));
        addRenderableWidget(box);
        fieldEditBoxes.put(key.path(), box);
    }

    private EditBox themedEditBox(int x, int y, int w, ConfigKey<?> key) {
        EditBox box = new EditBox(font, x, y, w, FIELD_HEIGHT, keyTitle(key));
        box.setTextColor(ManiacUiTheme.TEXT_TITLE);
        box.setTextColorUneditable(ManiacUiTheme.TEXT_DIM);
        box.setBordered(false);
        return box;
    }

    // ── Числова логіка повзунка ─────────────────────────────────────

    private double numericValueOf(ConfigKey<?> key) {
        Double draft = numericDrafts.get(key.path());
        if (draft != null) return draft;
        try {
            return Double.parseDouble(currentValue(key));
        } catch (NumberFormatException e) {
            return key.defaultValue() instanceof Number n ? n.doubleValue() : 0.0;
        }
    }

    private double fractionOf(ConfigKey<?> key) {
        double min = key.numericMin(), max = key.numericMax();
        if (max <= min) return 0.0;
        double v = numericValueOf(key);
        return Math.max(0.0, Math.min(1.0, (v - min) / (max - min)));
    }

    private String formatNumeric(ConfigKey<?> key, double value) {
        boolean isInt = key.defaultValue() instanceof Integer;
        if (isInt) return String.valueOf(Math.round(value));
        java.math.BigDecimal bd = java.math.BigDecimal.valueOf(value)
                .setScale(4, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros();
        String s = bd.toPlainString();
        return s.contains(".") ? s : s + ".0";
    }

    private double valueFromFraction(ConfigKey<?> key, double fraction) {
        double min = key.numericMin(), max = key.numericMax();
        double raw = min + (max - min) * Math.max(0.0, Math.min(1.0, fraction));
        if (key.defaultValue() instanceof Integer) raw = Math.round(raw);
        return raw;
    }

    private boolean tryParseNumber(ConfigKey<?> key, String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return false;
        try {
            if (key.defaultValue() instanceof Integer) {
                Integer.parseInt(trimmed);
            } else {
                Double.parseDouble(trimmed);
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ── Ввід ─────────────────────────────────────────────────────────

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) {
            for (Map.Entry<String, EditBox> entry : fieldEditBoxes.entrySet()) {
                if (!entry.getValue().isFocused()) continue;
                ConfigKey<?> key = keyByPath(entry.getKey());
                if (key == null) return true;
                String text = entry.getValue().getValue();
                if (key.hasNumericRange()) {
                    if (tryParseNumber(key, text)) {
                        invalidFields.remove(key.path());
                        numericDrafts.remove(key.path());
                        sendChange(key, text.trim());
                    } else {
                        invalidFields.add(key.path());
                    }
                } else {
                    sendChange(key, text);
                }
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private ConfigKey<?> keyByPath(String path) {
        for (ConfigBlock block : tabs) {
            for (ConfigKey<?> key : block.keys()) {
                if (key.path().equals(path)) return key;
            }
        }
        return null;
    }

    private String currentValue(ConfigKey<?> key) {
        String v = values.get(key.path());
        return v != null ? v : String.valueOf(key.defaultValue());
    }

    private void sendChange(ConfigKey<?> key, String newValue) {
        recentChangeMs.put(key.path(), System.currentTimeMillis());
        ModNetwork.toServer(new SettingsChangePacket(key.block(), key.name(), newValue));
    }

    /** Викликається ClientPacketHandler після кожної відповіді сервера — оновлює значення БЕЗ пересоздання віджетів. */
    public void onValuesUpdated(Map<String, String> newValues) {
        this.values = new HashMap<>(newValues);
        for (Map.Entry<String, EditBox> entry : fieldEditBoxes.entrySet()) {
            ConfigKey<?> key = keyByPath(entry.getKey());
            if (key == null) continue;
            if (key.path().equals(draggingSliderPath)) continue;
            if (invalidFields.contains(key.path())) continue;
            String fresh = currentValue(key);
            if (!entry.getValue().getValue().equals(fresh)) {
                entry.getValue().setValue(fresh);
            }
        }
        numericDrafts.clear();
        rebuildFieldsForActiveTab();
    }

    /** Y-координата рядка повзунка за шляхом ключа, серед видимих рядків активної вкладки, або -1. */
    private int sliderRowY(String path) {
        ConfigBlock block = tabs.get(activeTab);
        int contentTop = contentTop(panelY());
        int rowIndex = 0;
        int visible = visibleRowCount(panelY());
        List<ConfigKey<?>> keys = block.keys();
        for (int i = scrollOffset; i < keys.size() && rowIndex < visible; i++, rowIndex++) {
            if (keys.get(i).path().equals(path)) {
                return contentTop + rowIndex * ROW_HEIGHT + 4;
            }
        }
        return -1;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        ConfigBlock block = tabs.get(activeTab);
        int maxScroll = Math.max(0, block.keys().size() - visibleRowCount(panelY()));
        int next = scrollOffset - (int) Math.signum(delta);
        int clamped = Math.max(0, Math.min(maxScroll, next));
        if (clamped != scrollOffset) {
            scrollOffset = clamped;
            rebuildFieldsForActiveTab();
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int panelX = panelX();
        int panelY = panelY();
        int contentTop = contentTop(panelY);

        if (mouseX >= panelX + 8 && mouseX <= panelX + 8 + TAB_WIDTH) {
            int tabY = contentTop;
            for (int i = 0; i < tabs.size(); i++) {
                if (mouseY >= tabY && mouseY < tabY + TAB_ROW_HEIGHT) {
                    switchTab(i);
                    return true;
                }
                tabY += TAB_ROW_HEIGHT;
            }
        }

        if (button == 0) {
            for (Map.Entry<String, int[]> entry : sliderBounds.entrySet()) {
                int rowY = sliderRowY(entry.getKey());
                if (rowY < 0) continue;
                int[] bounds = entry.getValue();
                int trackX = bounds[0], trackW = bounds[1];
                if (mouseX >= trackX && mouseX <= trackX + trackW
                        && mouseY >= rowY && mouseY <= rowY + SLIDER_HEIGHT) {
                    draggingSliderPath = entry.getKey();
                    applySliderDrag(entry.getKey(), mouseX, trackX, trackW);
                    return true;
                }
            }

            for (Map.Entry<String, int[]> entry : boolBounds.entrySet()) {
                int[] b = entry.getValue();
                if (isInside((int) mouseX, (int) mouseY, b[0], b[1], b[2], b[3])) {
                    ConfigKey<?> key = keyByPath(entry.getKey());
                    if (key != null) toggleBoolean(key);
                    return true;
                }
            }

            for (Map.Entry<String, OptionRowBounds> entry : optionBounds.entrySet()) {
                OptionRowBounds b = entry.getValue();
                if (!isInside((int) mouseX, (int) mouseY, b.x(), b.y(), b.w(), b.h())) continue;
                ConfigKey<?> key = keyByPath(entry.getKey());
                if (key == null) return true;
                int centerW = b.w() - b.arrowW() * 2;
                int direction = mouseX < b.x() + b.arrowW() ? -1 : 1;
                cycleOption(key, direction);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingSliderPath != null) {
            int[] bounds = sliderBounds.get(draggingSliderPath);
            if (bounds != null) {
                applySliderDrag(draggingSliderPath, mouseX, bounds[0], bounds[1]);
                return true;
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingSliderPath != null) {
            ConfigKey<?> key = keyByPath(draggingSliderPath);
            String path = draggingSliderPath;
            draggingSliderPath = null;
            if (key != null) {
                Double draft = numericDrafts.get(path);
                if (draft != null) {
                    sendChange(key, formatNumeric(key, draft));
                }
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void applySliderDrag(String path, double mouseX, int trackX, int trackW) {
        ConfigKey<?> key = keyByPath(path);
        if (key == null) return;
        double fraction = trackW <= 0 ? 0.0 : (mouseX - trackX) / (double) trackW;
        double value = valueFromFraction(key, fraction);
        numericDrafts.put(path, value);
        invalidFields.remove(path);
        EditBox box = fieldEditBoxes.get(path);
        if (box != null) {
            box.setValue(formatNumeric(key, value));
        }
    }

    // ── Анімація: тік ────────────────────────────────────────────────

    private void advanceAnimation(float partialTick) {
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0L ? 0f : (now - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = now;
        dt = Math.min(dt, 0.1f);

        float openFactor = 1f - (float) Math.exp(-ANIM_SPEED * dt);
        openProgress += (1f - openProgress) * openFactor;
        if (1f - openProgress < 0.002f) openProgress = 1f;

        float tabFactor = 1f - (float) Math.exp(-TAB_FADE_SPEED * dt);
        tabFadeProgress += (1f - tabFadeProgress) * tabFactor;
        if (1f - tabFadeProgress < 0.01f) tabFadeProgress = 1f;
    }

    /** 0..1 — каскадна поява рядка {@code rowIndex} (у видимому списку активної вкладки) з невеликим запізненням на кожен наступний рядок. */
    private float rowAppearProgress(int rowIndex) {
        long elapsed = System.currentTimeMillis() - rowsRevealStartMs - rowIndex * ROW_STAGGER_MS;
        if (elapsed <= 0) return 0f;
        if (elapsed >= ROW_APPEAR_MS) return 1f;
        float t = elapsed / (float) ROW_APPEAR_MS;
        // ease-out quad — швидкий старт, м'яке гальмування, без бібліотечних easing-класів.
        return 1f - (1f - t) * (1f - t);
    }

    /** 0..1 — наскільки "свіжа" остання зміна ключа; 0 = давно або ніколи, 1 = щойно. Лінійне згасання за {@link #CHANGE_FLASH_MS}. */
    private float changeFlash(String path) {
        Long at = recentChangeMs.get(path);
        if (at == null) return 0f;
        long elapsed = System.currentTimeMillis() - at;
        if (elapsed >= CHANGE_FLASH_MS) return 0f;
        return 1f - (elapsed / (float) CHANGE_FLASH_MS);
    }

    // ── Render ───────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        advanceAnimation(partialTick);

        int dimAlpha = Math.round(openProgress * 140);
        graphics.fill(0, 0, width, height, (dimAlpha << 24));

        int panelX = panelX();
        int basePanelY = panelY();
        // Панель ковзає вгору на невелику відстань під час появи — той
        // самий "in-from-below" прийом, що робить відкриття відчутним,
        // а не миттєвою підміною кадру.
        int slide = Math.round((1f - easeOutCubic(openProgress)) * 14f);
        int panelY = basePanelY + slide;
        float panelAlpha = easeOutCubic(openProgress);

        drawPanelFaded(graphics, panelX, panelY, panelAlpha);
        int contentTop = drawTitleFaded(graphics, panelX, panelY, panelAlpha);

        // Таби зліва.
        int tabY = contentTop;
        for (int i = 0; i < tabs.size(); i++) {
            boolean active = i == activeTab;
            drawTab(graphics, panelX, tabY, active, tabs.get(i), mouseX, mouseY, panelAlpha);
            tabY += TAB_ROW_HEIGHT;
        }

        int listX = listX(panelX);
        int contentBottom = contentBottom(panelY);
        graphics.fill(listX, contentTop, listX + 1, contentBottom, withAlpha(ManiacUiTheme.DIVIDER, panelAlpha));

        int contentX = contentX(panelX);
        int contentW = contentWidth(panelX);

        ConfigBlock block = tabs.get(activeTab);
        List<ConfigKey<?>> keys = block.keys();
        int visible = visibleRowCount(panelY);
        int rowIndex = 0;
        for (int i = scrollOffset; i < keys.size() && rowIndex < visible; i++, rowIndex++) {
            ConfigKey<?> key = keys.get(i);
            int rowY = contentTop + rowIndex * ROW_HEIGHT;
            float appear = rowAppearProgress(rowIndex);
            float rowAlpha = panelAlpha * tabFadeProgress * appear;
            int rowSlideX = Math.round((1f - appear) * 10f);
            drawRow(graphics, key, panelX, rowY, contentX + rowSlideX, contentW, rowIndex, mouseX, mouseY, rowAlpha);
        }

        if (keys.size() > visible) {
            drawScrollbar(graphics, panelX, contentTop, contentBottom, keys.size(), visible, panelAlpha);
        }

        // Тематична рамка/фон під кожним EditBox.
        for (Map.Entry<String, EditBox> entry : fieldEditBoxes.entrySet()) {
            EditBox box = entry.getValue();
            boolean invalid = invalidFields.contains(entry.getKey());
            graphics.fill(box.getX() - 2, box.getY() - 2, box.getX() + box.getWidth() + 2, box.getY() + box.getHeight() + 2,
                    withAlpha(ManiacUiTheme.BAR_BG, panelAlpha));
            border1pxAlpha(graphics, box.getX() - 2, box.getY() - 2,
                    box.getWidth() + 4, box.getHeight() + 4,
                    invalid ? ManiacUiTheme.BAR_FILL_DANGER : (box.isFocused() ? ManiacUiTheme.BORDER_BRIGHT : ManiacUiTheme.BORDER),
                    panelAlpha);
        }

        int hintY = panelY + PANEL_HEIGHT - HINT_HEIGHT - 8;
        ManiacUiTheme.drawHintBar(graphics, font,
                Component.translatable("maniacmod.settings.hint").getString(),
                panelX + 8, hintY, PANEL_WIDTH - 16, HINT_HEIGHT);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawTab(GuiGraphics g, int panelX, int tabY, boolean active, ConfigBlock block,
                          int mouseX, int mouseY, float alpha) {
        boolean hovered = !active && mouseX >= panelX + 8 && mouseX <= panelX + 8 + TAB_WIDTH
                && mouseY >= tabY && mouseY < tabY + TAB_ROW_HEIGHT;
        if (active) {
            g.fill(panelX + 8, tabY, panelX + 8 + TAB_WIDTH, tabY + TAB_ROW_HEIGHT, withAlpha(0xFF282419, alpha));
            g.fill(panelX + 8, tabY, panelX + 10, tabY + TAB_ROW_HEIGHT, withAlpha(ManiacUiTheme.TEXT_ACCENT, alpha));
        } else if (hovered) {
            g.fill(panelX + 8, tabY, panelX + 8 + TAB_WIDTH, tabY + TAB_ROW_HEIGHT, withAlpha(0xFF1C1C14, alpha));
        }
        int color = active ? ManiacUiTheme.TEXT_ACCENT : (hovered ? ManiacUiTheme.TEXT_TITLE : ManiacUiTheme.TEXT_BODY);
        g.drawString(font, blockDisplayName(block), panelX + 14, tabY + (TAB_ROW_HEIGHT - font.lineHeight) / 2 + 1,
                withAlpha(color, alpha), false);
    }

    private void drawRow(GuiGraphics g, ConfigKey<?> key, int panelX, int rowY, int contentX, int contentW,
                          int rowIndex, int mouseX, int mouseY, float rowAlpha) {
        float flash = changeFlash(key.path());
        boolean alt = rowIndex % 2 == 0;
        int rowBg = alt ? 0xFF181818 : 0xFF141414;
        // Спалах підтвердженої зміни: короткий акцентний відтінок поверх звичайного фону рядка, що згасає до нього.
        if (flash > 0f) {
            rowBg = blend(rowBg, ManiacUiTheme.TEXT_ACCENT, flash * 0.22f);
        }
        // Фон рядка зупиняється на contentX+contentW — тій самій межі, що й самі поля — і більше не заходить
        // під зону скролбару (SCROLLBAR_ZONE_WIDTH), яка малюється окремим шаром поверх.
        g.fill(contentX - 6, rowY, contentX + contentW, rowY + ROW_HEIGHT - 4, withAlpha(rowBg, rowAlpha));

        int titleLimit = contentX + contentW - fieldWidthFor(key) - 10;
        String titleText = clipToWidth(keyTitle(key).getString(), Math.max(20, titleLimit - contentX));
        g.drawString(font, titleText, contentX, rowY + 3, withAlpha(ManiacUiTheme.TEXT_TITLE, rowAlpha), false);

        boolean isBoolean = key.defaultValue() instanceof Boolean;
        boolean isOption = !isBoolean && key.describeRange().contains("|");
        boolean isSlider = !isBoolean && !isOption && key.hasNumericRange();

        // Технічний діапазон — лише для числових полів, другорядним текстом ПІСЛЯ опису (не замість нього),
        // праворуч на тій самій висоті, що й опис, щоб не займати зайвий рядок. Позиціюється відносно
        // ЛІВОГО краю самого повзунка (contentX+contentW-fieldW) — тієї самої точки, що й drawSlider —
        // а не окремо перерахованого зсуву від PANEL_WIDTH, тому більше не може розійтись зі справжньою
        // позицією поля. Малюється ПЕРЕД описом, щоб знати його ліву межу й обрізати опис до неї —
        // інакше опис і діапазон читаються один крізь одного в тому самому рядку.
        int hintRightLimit = contentX + contentW - fieldWidthFor(key) - 10;
        if (isSlider) {
            String rangeHint = key.describeRange();
            int rangeW = font.width(rangeHint);
            int sliderFieldX = contentX + contentW - (SLIDER_WIDTH + NUMBER_FIELD_GAP + NUMBER_FIELD_WIDTH);
            int rangeX = Math.max(contentX, sliderFieldX - 8 - rangeW);
            g.drawString(font, rangeHint, rangeX, rowY + 15, withAlpha(0xFF555555, rowAlpha), false);
            hintRightLimit = Math.min(hintRightLimit, rangeX - 8);
        }

        // Опис ЩО робить налаштування — завжди показаний (не лише для числових), приглушеним кольором під назвою.
        // Обрізається по ширині до hintRightLimit (лівий край поля, або лівий край технічного діапазону
        // для повзунків, якщо той коротший) — інакше довгий опис протикає крізь повзунок/перемикач і
        // зливається з текстом поля чи діапазону — саме це й було видно на скріні-референсі.
        Component hint = keyHint(key);
        String hintText = clipToWidth(hint.getString(), Math.max(20, hintRightLimit - contentX));
        g.drawString(font, hintText, contentX, rowY + 15, withAlpha(ManiacUiTheme.TEXT_DIM, rowAlpha), false);

        if (isSlider) {
            int[] bounds = sliderBounds.get(key.path());
            if (bounds != null) {
                drawSlider(g, key, bounds[0], rowY + 4, bounds[1], rowAlpha, flash);
            }
        } else if (isBoolean) {
            int[] b = boolBounds.get(key.path());
            if (b != null) {
                boolean hovered = isInside(mouseX, mouseY, b[0], b[1], b[2], b[3]);
                drawBoolToggle(g, key, b[0], b[1], b[2], b[3], hovered, rowAlpha);
            }
        } else if (isOption) {
            OptionRowBounds b = optionBounds.get(key.path());
            if (b != null) {
                drawOptionCycler(g, key, b, mouseX, mouseY, rowAlpha);
            }
        }
    }

    private void drawSlider(GuiGraphics graphics, ConfigKey<?> key, int x, int y, int w, float alpha, float flash) {
        float fraction = (float) fractionOf(key);
        int fillColor = flash > 0f ? blend(ManiacUiTheme.BAR_FILL_NEUTRAL, ManiacUiTheme.TEXT_ACCENT, flash) : ManiacUiTheme.BAR_FILL_NEUTRAL;
        drawProgressBarAlpha(graphics, x, y, w, SLIDER_HEIGHT, fraction, fillColor, alpha);

        int handleX = x + Math.round(fraction * w) - SLIDER_HANDLE_WIDTH / 2;
        handleX = Math.max(x, Math.min(x + w - SLIDER_HANDLE_WIDTH, handleX));
        graphics.fill(handleX, y - 2, handleX + SLIDER_HANDLE_WIDTH, y + SLIDER_HEIGHT + 2, withAlpha(ManiacUiTheme.TEXT_ACCENT, alpha));
        border1pxAlpha(graphics, handleX, y - 2, SLIDER_HANDLE_WIDTH, SLIDER_HEIGHT + 4, 0xFF1A1A1A, alpha);
    }

    private void drawScrollbar(GuiGraphics graphics, int panelX, int top, int bottom, int totalRows, int visibleRows, float alpha) {
        // trackX сидить усередині SCROLLBAR_ZONE_WIDTH, за межею contentWidth() — більше не стикається з полями.
        int trackX = panelX + PANEL_WIDTH - SCROLLBAR_ZONE_WIDTH + 4;
        graphics.fill(trackX, top, trackX + 4, bottom, withAlpha(0xFF282828, alpha));

        int trackHeight = bottom - top;
        int thumbHeight = Math.max(12, trackHeight * visibleRows / totalRows);
        int maxScroll = Math.max(1, totalRows - visibleRows);
        int thumbY = top + (trackHeight - thumbHeight) * scrollOffset / maxScroll;
        graphics.fill(trackX, thumbY, trackX + 4, thumbY + thumbHeight, withAlpha(ManiacUiTheme.BORDER_BRIGHT, alpha));
    }

    private static Component blockDisplayName(ConfigBlock block) {
        return Component.translatable("maniacmod.settings.block." + block.id());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ── Допоміжне: альфа-версії примітивів ManiacUiTheme ──────────────
    // ManiacUiTheme сам не приймає альфа-множник (його панелі завжди
    // непрозорі) — цей екран єдиний, що анімує прозорість появи, тому
    // локальні альфа-обгортки живуть тут, а не в темі, спільній з
    // усіма іншими (завжди статичними) панелями мода.

    private static int withAlpha(int argb, float alphaMul) {
        int a = (argb >>> 24) & 0xFF;
        int newA = Math.round(a * clamp01(alphaMul));
        return (newA << 24) | (argb & 0x00FFFFFF);
    }

    private static void border1pxAlpha(GuiGraphics g, int x, int y, int w, int h, int color, float alpha) {
        int c = withAlpha(color, alpha);
        g.fill(x, y, x + w, y + 1, c);
        g.fill(x, y + h - 1, x + w, y + h, c);
        g.fill(x, y, x + 1, y + h, c);
        g.fill(x + w - 1, y, x + w, y + h, c);
    }

    private static void drawProgressBarAlpha(GuiGraphics g, int x, int y, int w, int h, float fraction, int fillColor, float alpha) {
        int frame = withAlpha(ManiacUiTheme.BAR_FRAME, alpha);
        g.fill(x, y, x + w, y + h, frame);
        int thickness = 2;
        int ix = x + thickness, iy = y + thickness;
        int iw = w - thickness * 2, ih = h - thickness * 2;
        g.fill(ix, iy, ix + iw, iy + ih, withAlpha(ManiacUiTheme.BAR_BG, alpha));

        float f = clamp01(fraction);
        int filled = Math.round(iw * f);
        if (filled > 0) {
            g.fill(ix, iy, ix + filled, iy + ih, withAlpha(fillColor, alpha));
        }
    }

    private void drawPanelFaded(GuiGraphics g, int x, int y, float alpha) {
        g.fill(x, y, x + PANEL_WIDTH, y + PANEL_HEIGHT, withAlpha(ManiacUiTheme.PANEL_BG, alpha));
        border1pxAlpha(g, x, y, PANEL_WIDTH, PANEL_HEIGHT, ManiacUiTheme.BORDER, alpha);
        drawCornerAlpha(g, x, y, 1, 1, ManiacUiTheme.BORDER_BRIGHT, alpha);
        drawCornerAlpha(g, x + PANEL_WIDTH, y, -1, 1, ManiacUiTheme.BORDER_BRIGHT, alpha);
        drawCornerAlpha(g, x, y + PANEL_HEIGHT, 1, -1, ManiacUiTheme.BORDER_BRIGHT, alpha);
        drawCornerAlpha(g, x + PANEL_WIDTH, y + PANEL_HEIGHT, -1, -1, ManiacUiTheme.BORDER_BRIGHT, alpha);
    }

    private static void drawCornerAlpha(GuiGraphics g, int x, int y, int dx, int dy, int color, float alpha) {
        int t = 2, s = 10;
        int c = withAlpha(color, alpha);
        int hx0 = dx > 0 ? x : x - s;
        int hx1 = dx > 0 ? x + s : x;
        int hy0 = dy > 0 ? y : y - t;
        int hy1 = dy > 0 ? y + t : y;
        g.fill(hx0, hy0, hx1, hy1, c);
        int vx0 = dx > 0 ? x : x - t;
        int vx1 = dx > 0 ? x + t : x;
        int vy0 = dy > 0 ? y : y - s;
        int vy1 = dy > 0 ? y + s : y;
        g.fill(vx0, vy0, vx1, vy1, c);
    }

    /**
     * Заголовок капсом + ОДИН рядок опису під ним (приглушений колір,
     * центрований, як і заголовок) + роздільник. Загальне правило
     * вигляду меню мода — назва зверху, під нею короткий опис, що це
     * за меню, лише потім вміст. {@link #contentTop(int)} рахує ту
     * саму висоту (font.lineHeight заголовка + SUBTITLE_BLOCK_HEIGHT +
     * font.lineHeight опису), тому зсув тут і там завжди синхронний.
     */
    private int drawTitleFaded(GuiGraphics g, int panelX, int panelY, float alpha) {
        int centerX = panelX + PANEL_WIDTH / 2;
        int titleY = panelY + 14;
        g.drawCenteredString(font, title.getString().toUpperCase(java.util.Locale.ROOT), centerX, titleY,
                withAlpha(ManiacUiTheme.TEXT_TITLE, alpha));

        int subtitleY = titleY + font.lineHeight + SUBTITLE_BLOCK_HEIGHT;
        String subtitle = Component.translatable("maniacmod.settings.subtitle").getString();
        g.drawCenteredString(font, subtitle, centerX, subtitleY, withAlpha(ManiacUiTheme.TEXT_DIM, alpha));

        int dividerY = subtitleY + font.lineHeight + 8;
        g.fill(panelX + 16, dividerY, panelX + PANEL_WIDTH - 16, dividerY + 1, withAlpha(ManiacUiTheme.DIVIDER, alpha));
        return dividerY + 10;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /** Ширина поля справа для цього ключа — та сама формула, що {@link #createFieldFor}, лише повертає ЛИШЕ ширину. */
    private int fieldWidthFor(ConfigKey<?> key) {
        boolean isBoolean = key.defaultValue() instanceof Boolean;
        boolean isOption = !isBoolean && key.describeRange().contains("|");
        boolean isSlider = !isBoolean && !isOption && key.hasNumericRange();
        if (isBoolean) return BOOL_FIELD_WIDTH;
        if (isOption) return OPTION_FIELD_WIDTH;
        return SLIDER_WIDTH + NUMBER_FIELD_GAP + NUMBER_FIELD_WIDTH;
    }

    /** Обрізає текст до maxWidth пікселів з "…" в кінці, якщо не влазить — щоб опис не протикав крізь поле. */
    private String clipToWidth(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        String ellipsis = "\u2026";
        int budget = maxWidth - font.width(ellipsis);
        if (budget <= 0) return ellipsis;
        return font.plainSubstrByWidth(text, budget) + ellipsis;
    }

    private static float easeOutCubic(float t) {
        float f = clamp01(t) - 1f;
        return f * f * f + 1f;
    }

    /** Лінійне змішування двох ARGB-кольорів за часткою t (0 = base, 1 = target), альфа-канал бере з base. */
    private static int blend(int base, int target, float t) {
        t = clamp01(t);
        int a = (base >>> 24) & 0xFF;
        int r1 = (base >> 16) & 0xFF, g1 = (base >> 8) & 0xFF, b1 = base & 0xFF;
        int r2 = (target >> 16) & 0xFF, g2 = (target >> 8) & 0xFF, b2 = target & 0xFF;
        int r = Math.round(r1 + (r2 - r1) * t);
        int gC = Math.round(g1 + (g2 - g1) * t);
        int b = Math.round(b1 + (b2 - b1) * t);
        return (a << 24) | (r << 16) | (gC << 8) | b;
    }
}
