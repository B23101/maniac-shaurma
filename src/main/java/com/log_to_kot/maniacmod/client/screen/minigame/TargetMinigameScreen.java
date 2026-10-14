package com.log_to_kot.maniacmod.client.screen.minigame;

import com.log_to_kot.maniacmod.client.vr.VrCompat;

import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.UiFx;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.c2s.minigame.TargetMinigameClickPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * "Generator Startup" — Hits: 0/N. Тонка ПАЛКА-повзунок їздить
 * вліво-вправо рівномірно, гравець натискає ПРОБІЛ, щоб зупинити її
 * всередині нерухомого ПРЯМОКУТНИКА-цілі (це і є зона влучання —
 * ширина прямокутника дорівнює {@code hitZoneWidth}, тож те, що
 * гравець бачить, збігається з тим, що перевіряє сервер).
 *
 * ── Промах ───────────────────────────────────────────────────────────
 * Промах фарбує все вікно в червоне. Червоним воно лишається до
 * закриття: екран закриває серверний {@code RepairMinigameResultPacket},
 * а щоб червоний стан встигав побачити, клієнт тримає екран відкритим
 * {@link #FAIL_LINGER_MS} після вердикту (див. {@link #onResult}).
 *
 * Вигляд — {@link ManiacUiTheme}: темна панель з кутовими акцентами,
 * заголовок капсом + роздільник, лічильник попадань, смуга-повзунок,
 * окрема панель-підказка знизу ("Пробел — остановить"). 1:1 референс
 * "Вирватись із пастки" — та сама панель обслуговує обидва текстові
 * контексти (втеча з пастки МАНЬЯКА і запуск генератора), різниця
 * лише в {@code Component}-перекладах, що приходять у конструктор.
 *
 * ── Хто що рахує ─────────────────────────────────────────────────────
 * Позиція повзунка — ЛОКАЛЬНИЙ клієнтський розрахунок від
 * {@link #openedAtMillis} і {@link #cursorSpeed} (трикутна хвиля:
 * туди-назад між 0 і 1). {@code seed} зберігається на випадок, якщо
 * рух ускладниться (наприклад випадковими змінами швидкості) і йому
 * знадобиться детермінований, а не суто часовий розрахунок — поки що
 * сама лише трикутна хвиля симетрична й seed не читає. На клік клієнт лише повідомляє серверу, де,
 * на його думку, зараз стоїть повзунок ({@link TargetMinigameClickPacket})
 * — сервер сам вирішує, влучання це чи ні, і присилає
 * {@code RepairMinigameProgressPacket}/{@code RepairMinigameResultPacket}
 * у відповідь. Тому цей екран НІКОЛИ сам не малює "влучив!" — лише
 * реагує на те, що прийшло з сервера ({@link #onHit}/{@link #onResult}).
 *
 * ── Чому не закривається по ESC ──────────────────────────────────────
 * За дизайном гравець не може просто вийти з міні-гри — вихід
 * трактується як провал (перевіряється й караеться СЕРВЕРОМ через
 * дистанцію до генератора в {@code GeneratorModule.tickMinigameTimeouts},
 * а не цим екраном). {@link #shouldCloseOnEsc()} тому явно false, а
 * {@link #onClose()} нічого не робить — закриває екран лише виклик
 * {@link #forceClose()} з {@code ClientPacketHandler} у відповідь на
 * серверний результат.
 */
public final class TargetMinigameScreen extends Screen {

    private static final int PANEL_WIDTH = 280;
    private static final int BAR_WIDTH = 240;
    private static final int BAR_HEIGHT = 18;
    private static final int HINT_HEIGHT = 26;
    private static final int HINT_GAP = 10;

    /** Товщина палки-повзунка, px. Тонка — щоб було видно, що вона «стоїть» у прямокутнику. */
    private static final int STICK_WIDTH = 3;

    /** Наскільки палка виступає над/під смугою, px. */
    private static final int STICK_OVERSHOOT = 5;

    /**
     * Скільки мс екран лишається відкритим після провалу, щоб червоний
     * стан було видно. Сервер закриває гру одразу на промах, тож без
     * цього вікно зникало б раніше, ніж гравець встигне його помітити.
     */
    private static final long FAIL_LINGER_MS = 900;

    /** Червоні відтінки провалу (ARGB). */
    private static final int FAIL_PANEL_BG = 0xE63A0A0A;
    private static final int FAIL_BORDER = 0xFFB0242F;
    private static final int FAIL_BAR_BG = 0xFF2A0C0E;

    /** Скільки мс триває спалах-фідбек влучання/промаху. */
    private static final long FLASH_MS = 320;

    /** Відтінки фідбеку влучання (ARGB). */
    private static final int HIT_ZONE = 0xFF7FD68D;
    private static final int HIT_TEXT = 0xFFA9F0B4;
    /** Відтінки фідбеку промаху (ARGB). Збігаються з провальними — це той самий провал по суті. */
    private static final int MISS_ZONE = 0xFFB0242F;
    private static final int MISS_TEXT = 0xFFFF7A7A;

    private final long seed;
    private final double cursorSpeed;
    private final double hitZoneWidth;
    /**
     * Позиція цілі (0.0-1.0). НЕ final: ціль ПЕРЕМІЩУЄТЬСЯ — у капкані після
     * КОЖНОЇ спроби (ТЗ 1.2), у генераторі після кожного ВЛУЧАННЯ (промах
     * там — провал, гра закривається). Сервер присилає нову позицію у
     * {@code TrapEscapeAttemptPacket}, і {@link #onTrapAttempt} її тут
     * підставляє.
     */
    private double targetPosition;
    private final int hitsRequired;
    private final long openedAtMillis;

    /** Коли (мс) закінчується спалах-фідбек спроби; 0 — немає активного. */
    private long flashUntilMillis = 0;

    /** true — спалах показує ПОПАДАННЯ; false — промах. Читається лише поки {@link #flashUntilMillis} не минув. */
    private boolean flashWasHit;

    private int hits = 0;
    private boolean finished = false;

    /** true, щойно гравець промахнувся — вікно червоніє до закриття. */
    private boolean failed = false;

    /** Коли (мс) закривати екран після провалу; 0 — закриття не заплановане. */
    private long closeAtMillis = 0;

    /**
     * Позиція палки, на якій її ЗАМОРОЖЕНО в момент натискання. Після
     * провалу палка мусить лишитись там, де промахнулась, — інакше
     * гравець не бачив би, наскільки схибив.
     */
    private double frozenCursor = -1;

    /**
     * Пробіл затиснуто й ще не відпущено після попередньої спроби.
     * Початкове значення береться з РЕАЛЬНОГО стану клавіші в момент
     * відкриття ({@code keyJump.isDown()}): гравець, що ще тримає пробіл
     * (стрибав, коли випала міні-гра), не має отримати «безкоштовну»
     * першу спробу — спершу відпустить. Жорстке {@code true} було б
     * гірше: якщо пробіл НЕ тримали, {@code keyReleased} ніколи б не
     * прийшов, і гравець не міг би зробити жодної спроби взагалі.
     */
    private boolean spaceHeld;

    // ── Анімації (чисто косметика: логіку й перевірку влучання не чіпають) ──

    /** Поява панелі: підйом + м'який масштаб, доки по смузі пробігає промінь. */
    private static final long INTRO_MS = 320;

    /** Скільки екран висить після УСПІХУ, щоб встигла зіграти анімація перемоги. */
    private static final long SUCCESS_LINGER_MS = 350;

    /** Тривалість тремтіння панелі на промаху. */
    private static final long SHAKE_MS = 380;

    /** Тривалість «підскоку» лічильника й піпки після влучання. */
    private static final long POP_MS = 260;

    /** Тривалість розширюваного кільця навколо зони. */
    private static final long RING_MS = 520;

    /** Шлейф повзунка: скільки «привидів» і з яким кроком у часі (сек). */
    private static final int TRAIL_STEPS = 6;
    private static final double TRAIL_STEP_SECONDS = 0.024;

    /** Понад стільки влучань піпки не малюємо (не влізуть) — лишається лише текст. */
    private static final int MAX_PIPS = 12;

    private static final int GREEN = 0x7FD68D;
    private static final int RED = 0xE0424E;

    /** Іскра-частинка. Живе в екранних координатах, малюється квадратиком. */
    private static final class Spark {
        float x, y, vx, vy, life, maxLife, size;
        int rgb;
    }

    private final List<Spark> sparks = new ArrayList<>();
    private final java.util.Random fxRandom = new java.util.Random();
    private final long[] frameClock = new long[1];

    /** Позиція цілі, яку МАЛЮЄМО: плавно їде до {@link #targetPosition} (у капкані ціль «перестрибує»). */
    private float displayTarget;

    /** Яскравість спалаху зони (1 → 0). */
    private float zoneGlow = 0f;

    /** Наскільки панель зараз підсвічена кольором останньої події (1 → 0). */
    private float panelFlash = 0f;
    private int panelFlashRgb = GREEN;

    private long shakeStartMillis = 0;
    private long popStartMillis = 0;
    private long ringStartMillis = 0;
    private boolean ringWasHit;

    /** Позиція (0..1) для вибуху іскор, поставлена подією; малюється в render, де відома геометрія. */
    private double pendingBurstPos = -1;
    private boolean pendingBurstHit;

    /** true — гру виграно; екран ще висить {@link #SUCCESS_LINGER_MS}. */
    private boolean success = false;

    /**
     * @param titleKey      ключ локалізації заголовка. Екран ОДИН на дві
     *                      міні-гри (ремонт генератора й визволення з
     *                      капкана — та сама механіка), але заголовок у
     *                      них різний, тож його вибирає той, хто відкриває
     *                      екран ({@code ClientPacketHandler}) за своїм
     *                      станом, а не сама механіка.
     */
    public TargetMinigameScreen(String titleKey, long seed, double cursorSpeed, double hitZoneWidth,
                                 double targetPosition, int hitsRequired) {
        super(Component.translatable(titleKey));
        this.seed = seed;
        this.cursorSpeed = cursorSpeed;
        this.hitZoneWidth = hitZoneWidth;
        this.targetPosition = targetPosition;
        this.displayTarget = (float) targetPosition;
        this.hitsRequired = hitsRequired;
        this.openedAtMillis = System.currentTimeMillis();
        // Прямо GLFW, а не keyJump: keyPressed нижче ловить фізичний пробіл,
        // і початковий стан мусить відповідати ТІЙ САМІЙ клавіші (гравець
        // міг перепризначити «стрибок» на іншу).
        this.spaceHeld = VrCompat.isVrActive()
            ? Minecraft.getInstance().options.keyJump.isDown()
            : org.lwjgl.glfw.GLFW.glfwGetKey(
            Minecraft.getInstance().getWindow().getWindow(),
            org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
    }

    /** Поточна позиція повзунка (0.0-1.0) — трикутна хвиля туди-назад. */
    private double cursorPosition() {
        // Після провалу палка стоїть там, де промахнулась.
        if (frozenCursor >= 0) return frozenCursor;
        double elapsedSeconds = (System.currentTimeMillis() - openedAtMillis) / 1000.0;
        // Спільна з сервером формула — сервер перевіряє клік нею ж.
        return com.log_to_kot.maniacmod.map.minigame.TargetMinigameSpec.cursorAt(elapsedSeconds, cursorSpeed);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    /**
     * Екран закривається (успіх, провал — будь-який шлях). Якщо гравець ще
     * тримає пробіл, гра одразу після закриття перечитає його як «стрибок»;
     * вартовий глушить цей стрибок, доки пробіл не відпущено.
     */
    @Override
    public void removed() {
        super.removed();
        com.log_to_kot.maniacmod.client.MinigameJumpGuard.armIfSpaceHeld();
    }

    @Override
    public void onClose() {
        // Навмисно порожньо — див. клас-докстрінг "Чому не закривається по ESC".
    }

    /**
     * Викликається {@code ClientPacketHandler} після
     * {@code TrapEscapeAttemptPacket} — результат ОДНІЄЇ спроби (в капкані
     * або влучання в генераторі) разом із НОВОЮ позицією цілі.
     *
     * <p>Це і є {"візуальний та звуковий feedback" із ТЗ 1.2: спалах
     * зони/тексту (зелений на попадання, червоний на промах) + окремий
     * звук, а також перенесення нерухомої цілі в нове місце. На відміну
     * від {@link #onResult}, це НЕ кінець гри — гра триває, лічильник
     * просто оновлюється.</p>
     *
     * <p>Годинник повзунка НЕ скидається: сервер звіряє позицію від
     * моменту відкриття екрана, і зсув розійшовся б із ним.</p>
     */
    public void onTrapAttempt(int hitsSoFar, boolean hit, double newTargetPosition) {
        this.hits = hitsSoFar;
        // Де саме повзунок зупинили — для вибуху іскор (до розморозки нижче).
        double where = frozenCursor >= 0 ? frozenCursor : cursorPosition();
        this.targetPosition = newTargetPosition;
        // Повзунок знову їде — спроба завершена, наступна з чистого аркуша.
        this.frozenCursor = -1;
        triggerFeedback(where, hit);
        this.flashWasHit = hit;
        this.flashUntilMillis = System.currentTimeMillis() + FLASH_MS;
        playFeedbackSound(hit);
    }

    /** Звук попадання/промаху — клієнтський, лише для цього гравця. */
    private void playFeedbackSound(boolean hit) {
        net.minecraftforge.registries.RegistryObject<net.minecraft.sounds.SoundEvent> event =
            hit ? com.log_to_kot.maniacmod.registry.ModSounds.MINIGAME_HIT
                : com.log_to_kot.maniacmod.registry.ModSounds.MINIGAME_MISS;
        // Подія може бути не зареєстрована (напр. гравці з різними
        // версіями мода) — тоді просто без звуку, а не краш.
        if (!event.isPresent()) return;
        Minecraft.getInstance().getSoundManager().play(
            net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(event.get(), 1.0f, 1.0f));
    }

    /** Викликається ClientPacketHandler після RepairMinigameProgressPacket.targetHit(...). */
    public void onHit(int hitsSoFar) {
        this.hits = hitsSoFar;
        triggerFeedback(frozenCursor >= 0 ? frozenCursor : cursorPosition(), true);
        // Влучання: палка знову їде — наступна спроба з чистого аркуша.
        // Відлік часу НЕ скидаємо: сервер перевіряє позицію за
        // годинником від відкриття гри, і зсув розійшовся б із ним.
        this.frozenCursor = -1;
    }

    /**
     * Викликається ClientPacketHandler після RepairMinigameResultPacket.
     *
     * Успіх закриває екран одразу. Провал НЕ закриває його негайно:
     * вікно червоніє й висить {@link #FAIL_LINGER_MS}, щоб гравець
     * побачив, що схибив; фактичне закриття робить {@link #tick()}.
     */
    public void onResult(boolean success) {
        if (finished) return;
        if (success) {
            finished = true;
            this.success = true;
            this.hits = Math.max(this.hits, hitsRequired);
            // Не закриваємо миттєво: даємо зіграти анімації перемоги.
            if (frozenCursor < 0) frozenCursor = cursorPosition();
            triggerFeedback(frozenCursor, true);
            closeAtMillis = System.currentTimeMillis() + SUCCESS_LINGER_MS;
            return;
        }
        // Провал приходить і від промаху, і від виходу за дистанцію чи
        // кінця фази — в обох випадках гравцю показуємо червоне вікно,
        // а не глухо зникаємо. Сервер гру вже закрив: далі натискання
        // нікуди не йдуть, тому finished = true.
        markFailed();
        finished = true;
    }

    /** Вмикає червоний стан і планує закриття. Ідемпотентно. */
    private void markFailed() {
        if (failed) return;
        failed = true;
        if (frozenCursor < 0) frozenCursor = cursorPosition();
        triggerFeedback(frozenCursor, false);
        closeAtMillis = System.currentTimeMillis() + FAIL_LINGER_MS;
    }

    @Override
    public void tick() {
        super.tick();
        if (closeAtMillis != 0 && System.currentTimeMillis() >= closeAtMillis) {
            Minecraft.getInstance().setScreen(null);
        }
    }

    /**
     * ПРОБІЛ — зупинити палку. Раніше це була ЛКМ; пробіл зручніший
     * (не треба цілитись мишею й тримати її над панеллю).
     *
     * Автоповтор: якщо пробіл затиснуто, GLFW шле {@code keyPressed}
     * повторно, і Screen.keyPressed не відрізняє повтор від першого
     * натискання. Тому {@link #spaceHeld} вимагає спершу ВІДПУСТИТИ
     * пробіл ({@link #keyReleased}) — лише свідоме натискання рахується
     * спробою. Клієнт лише повідомляє серверу позицію палки — вердикт
     * виносить сервер (див. клас-докстрінг).
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE) {
            attempt();
            return true;
        }
        // ESC та решта клавіш навмисно ігноруються — вихід із міні-гри = провал.
        return true;
    }

    /** Одна спроба влучити (пробіл або, у VR, тригер контролера). */
    private void attempt() {
        if (!spaceHeld && !finished && !failed) {
            spaceHeld = true;
            double position = cursorPosition();
            // Локально «заморожуємо» палку одразу: якщо це промах,
            // вона лишиться на місці схибленого натискання. Якщо влучання —
            // сервер підтвердить onHit(), і ми розморозимо (див. onHit).
            frozenCursor = position;
            ModNetwork.toServer(new TargetMinigameClickPacket(position));
        }
    }

    /** Відпускання пробілу знову дозволяє наступну спробу. */
    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE) {
            spaceHeld = false;
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    /**
     * Миша на цьому екрані нічого не робить: керування — пробілом.
     * Клік споживаємо, щоб він не «проваливався» в ігровий світ під панеллю.
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // VR: клавіатури немає — тригер контролера (клік указівника) = «пробіл».
        if (VrCompat.isVrActive() && button == 0) attempt();
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (VrCompat.isVrActive() && button == 0) spaceHeld = false;
        return true;
    }

    /**
     * Запускає візуальний відгук ОДНІЄЇ події (влучання / промах): спалах
     * зони й панелі, кільце, тремтіння (на промаху), «підскок» лічильника
     * та вибух іскор. Сама геометрія відома лише в {@link #render}, тож
     * іскри тут лише ставляться в чергу.
     */
    private void triggerFeedback(double positionOnBar, boolean hit) {
        long now = System.currentTimeMillis();
        pendingBurstPos = positionOnBar;
        pendingBurstHit = hit;
        zoneGlow = 1f;
        panelFlash = hit ? 0.75f : 1f;
        panelFlashRgb = hit ? GREEN : RED;
        ringStartMillis = now;
        ringWasHit = hit;
        if (hit) popStartMillis = now;
        else shakeStartMillis = now;
    }

    private void spawnBurst(float x, float y, boolean hit) {
        int count = hit ? 18 : 12;
        for (int i = 0; i < count; i++) {
            Spark sp = new Spark();
            double ang = fxRandom.nextDouble() * Math.PI * 2;
            // Влучання — віялом вгору/убік; промах — короткий різкий «розліт».
            float speed = (hit ? 40f : 55f) + fxRandom.nextFloat() * (hit ? 70f : 45f);
            sp.x = x;
            sp.y = y;
            sp.vx = (float) Math.cos(ang) * speed;
            sp.vy = (float) Math.sin(ang) * speed - (hit ? 28f : 0f);
            sp.maxLife = sp.life = 0.35f + fxRandom.nextFloat() * 0.35f;
            sp.size = fxRandom.nextFloat() < 0.3f ? 2f : 1f;
            sp.rgb = hit ? (fxRandom.nextFloat() < 0.4f ? 0xFFFFFF : GREEN)
                         : (fxRandom.nextFloat() < 0.4f ? 0xFF9A9A : RED);
            sparks.add(sp);
        }
    }

    private void updateSparks(float dt) {
        for (Iterator<Spark> it = sparks.iterator(); it.hasNext(); ) {
            Spark sp = it.next();
            sp.life -= dt;
            if (sp.life <= 0f) { it.remove(); continue; }
            sp.x += sp.vx * dt;
            sp.y += sp.vy * dt;
            sp.vy += 120f * dt;          // легка «гравітація»
            sp.vx *= (1f - 2.2f * dt);   // і тертя
        }
    }

    private void drawSparks(GuiGraphics g) {
        for (Spark sp : sparks) {
            float k = sp.life / sp.maxLife;
            int a = Math.round(255f * k);
            int size = Math.max(1, Math.round(sp.size));
            int x = Math.round(sp.x), y = Math.round(sp.y);
            g.fill(x, y, x + size, y + size, (a << 24) | sp.rgb);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Навмисно НЕ renderBackground(graphics) — референс показує
        // ігровий світ ПОЗАДУ панелі (затемнений, але видимий), а не
        // суцільну ванільну заглушку на весь екран.
        long now = System.currentTimeMillis();
        float dt = UiAnim.frameDt(frameClock);

        // Плавний рух відображуваної цілі + згасання спалахів.
        displayTarget = UiAnim.approach(displayTarget, (float) targetPosition, 18f, dt);
        zoneGlow = UiAnim.approach(zoneGlow, 0f, 4.5f, dt);
        panelFlash = UiAnim.approach(panelFlash, 0f, 3.2f, dt);
        updateSparks(dt);

        int panelHeight = 150;
        int panelX = width / 2 - PANEL_WIDTH / 2;
        int panelY = height / 2 - panelHeight / 2 - (HINT_HEIGHT + HINT_GAP) / 2;

        // ── поява панелі ──
        float intro = UiAnim.clamp01((now - openedAtMillis) / (float) INTRO_MS);
        float introEase = UiAnim.easeOutCubic(intro);

        // ── тремтіння на промаху: затухаюче коливання по X ──
        float shakeX = 0f;
        if (shakeStartMillis != 0) {
            float p = (now - shakeStartMillis) / (float) SHAKE_MS;
            if (p >= 1f) shakeStartMillis = 0;
            else shakeX = (float) Math.sin(p * 38f) * 5f * (1f - p) * (1f - p);
        }

        graphics.pose().pushPose();
        graphics.pose().translate(shakeX, 0f, 0f);
        GeneratorFuelUiTheme.beginAppear(graphics, panelX + PANEL_WIDTH / 2f,
            panelY + panelHeight / 2f, introEase, 14f);

        drawPanelFrame(graphics, panelX, panelY, PANEL_WIDTH, panelHeight);
        // Кольорова підсвітка панелі по краях після влучання/промаху.
        UiFx.innerGlow(graphics, panelX, panelY, PANEL_WIDTH, panelHeight, panelFlashRgb, panelFlash, 6);
        int contentY = ManiacUiTheme.drawTitle(graphics, font, title, panelX, panelY, PANEL_WIDTH);

        // ── лічильник влучань: текст «підскакує» + піпки ──
        float pop = popStartMillis == 0 ? 0f
            : UiAnim.clamp01(1f - (now - popStartMillis) / (float) POP_MS);
        int textColor = failed ? FAIL_BORDER : (success ? HIT_TEXT : ManiacUiTheme.TEXT_BODY);
        Component hitsText = Component.translatable("maniacmod.minigame.target.hits", hits, hitsRequired);
        float scale = 1f + 0.30f * pop * pop;
        graphics.pose().pushPose();
        float textCx = panelX + PANEL_WIDTH / 2f;
        float textCy = contentY + font.lineHeight / 2f;
        graphics.pose().translate(textCx, textCy, 0f);
        graphics.pose().scale(scale, scale, 1f);
        graphics.pose().translate(-textCx, -textCy, 0f);
        int drawColor = pop > 0.01f ? GeneratorFuelUiTheme.mix(0xFF000000 | textColor, 0xFFFFFFFF, pop) : textColor;
        graphics.drawCenteredString(font, hitsText, panelX + PANEL_WIDTH / 2, contentY, drawColor);
        graphics.pose().popPose();

        int pipsY = contentY + font.lineHeight + 4;
        if (hitsRequired > 0 && hitsRequired <= MAX_PIPS) {
            drawPips(graphics, panelX + PANEL_WIDTH / 2, pipsY, now, pop);
        }

        int barX = panelX + (PANEL_WIDTH - BAR_WIDTH) / 2;
        int barY = pipsY + 5 + 14;

        // Смуга-трек: рамка + фон. У провалі — червоні.
        graphics.fill(barX, barY, barX + BAR_WIDTH, barY + BAR_HEIGHT,
            failed ? FAIL_BORDER : ManiacUiTheme.BAR_FRAME);
        graphics.fill(barX + 2, barY + 2, barX + BAR_WIDTH - 2, barY + BAR_HEIGHT - 2,
            failed ? FAIL_BAR_BG : ManiacUiTheme.BAR_BG);
        // Ледь помітні поділки на треку — дають відчуття швидкості руху повзунка.
        int tickColor = GeneratorFuelUiTheme.alpha(failed ? FAIL_BORDER : 0xFF8E97A5, 0.22f);
        for (int i = 1; i < 20; i++) {
            int tx = barX + 2 + Math.round(i * (BAR_WIDTH - 4) / 20f);
            int th = (i % 5 == 0) ? 4 : 2;
            graphics.fill(tx, barY + BAR_HEIGHT - 2 - th, tx + 1, barY + BAR_HEIGHT - 2, tickColor);
        }

        // ЦІЛЬ — прямокутник. Його ширина дорівнює hitZoneWidth, тобто
        // рівно тій зоні, яку перевіряє сервер (TargetMinigameSpec.isHit:
        // |cursor − target| ≤ hitZoneWidth/2). Малюється за displayTarget —
        // це та сама позиція, лише плавно доїхала (у капкані ціль після
        // спроби «перестрибує»; так видно, куди вона поїхала).
        int targetCenterX = barX + (int) Math.round(displayTarget * BAR_WIDTH);
        int zoneHalf = Math.max(1, (int) Math.round(hitZoneWidth / 2.0 * BAR_WIDTH));
        int zoneLeft = Math.max(barX + 2, targetCenterX - zoneHalf);
        int zoneRight = Math.min(barX + BAR_WIDTH - 2, targetCenterX + zoneHalf);
        int zoneTop = barY + 2, zoneH = BAR_HEIGHT - 4;

        // Спалах-фідбек спроби (ТЗ 1.2): зелений на попадання, червоний на
        // промах. Живе лише {@link #FLASH_MS}; стан «провал» має пріоритет.
        boolean flash = flashUntilMillis != 0 && now < flashUntilMillis;
        if (flashUntilMillis != 0 && !flash) flashUntilMillis = 0;

        int zoneFill = failed ? 0xFF6A1A20
            : (flash ? (flashWasHit ? 0xFF2C5F38 : 0xFF5F1A22) : 0xFF3F7F4A);
        int zoneEdge = failed ? FAIL_BORDER
            : (flash ? (flashWasHit ? HIT_ZONE : MISS_ZONE) : 0xFF7FD68D);
        int zoneRgb = failed ? 0xB0242F : (flash && !flashWasHit ? 0xB0242F : GREEN);

        // «Дихання» зони: повільний пульс світіння підказує, куди цілитись.
        float breathe = 0.5f + 0.5f * (float) Math.sin(now / 260.0);
        if (!failed && !success) {
            UiFx.glow(graphics, zoneLeft, zoneTop, zoneRight - zoneLeft, zoneH, zoneRgb,
                0.35f + 0.25f * breathe + 0.6f * zoneGlow, 4);
        } else if (zoneGlow > 0.02f) {
            UiFx.glow(graphics, zoneLeft, zoneTop, zoneRight - zoneLeft, zoneH, zoneRgb, zoneGlow, 5);
        }
        graphics.fill(zoneLeft, zoneTop, zoneRight, zoneTop + zoneH, zoneFill);
        // Ковзний блік усередині зони — «жива» ціль.
        if (!failed && zoneRight - zoneLeft > 4) {
            UiFx.scanBeam(graphics, zoneLeft, zoneTop, zoneRight - zoneLeft, zoneH,
                (now % 1400) / 1400f, 5, 0xFFFFFF, 46);
        }
        // Спалах-заливка на влучанні / промаху.
        if (zoneGlow > 0.02f) {
            int glowFill = 0xFF000000 | (ringWasHit ? 0xC8FFD0 : 0xFFB0B0);
            graphics.fill(zoneLeft, zoneTop, zoneRight, zoneTop + zoneH,
                GeneratorFuelUiTheme.alpha(glowFill, zoneGlow * 0.55f));
        }
        ManiacUiTheme.border1px(graphics, zoneLeft, zoneTop, zoneRight - zoneLeft, zoneH, zoneEdge);

        // Кільце-«ударна хвиля» навколо зони.
        if (ringStartMillis != 0) {
            float rt = (now - ringStartMillis) / (float) RING_MS;
            if (rt >= 1f) ringStartMillis = 0;
            else {
                int ringColor = 0xFF000000 | (ringWasHit ? GREEN : RED);
                GeneratorFuelUiTheme.pulseRing(graphics, zoneLeft, zoneTop, zoneRight - zoneLeft, zoneH,
                    ringColor, UiAnim.easeOutCubic(rt), 14);
            }
        }

        // Текстова частина фідбеку — другим каналом, щоб попадання/промах
        // читались навіть тим, хто не розрізняє відтінки кольору. Плавно
        // виїжджає й згасає.
        if (flash) {
            float ft = 1f - (flashUntilMillis - now) / (float) FLASH_MS;
            float slide = (1f - UiAnim.easeOutCubic(Math.min(1f, ft * 2.5f))) * 6f;
            float fade = ft < 0.6f ? 1f : 1f - (ft - 0.6f) / 0.4f;
            String label = Component.translatable(flashWasHit
                ? "maniacmod.minigame.trap.hit" : "maniacmod.minigame.trap.miss").getString();
            graphics.drawCenteredString(font, label, panelX + PANEL_WIDTH / 2,
                Math.round(barY + BAR_HEIGHT + 6 + slide),
                GeneratorFuelUiTheme.alpha(0xFF000000 | ((flashWasHit ? HIT_TEXT : MISS_TEXT) & 0xFFFFFF),
                    Math.max(0.05f, fade)));
        }

        // ── ПАЛКА: шлейф + ореол + сама риска ──
        double cursor = cursorPosition();
        boolean moving = frozenCursor < 0 && !failed && !finished;
        if (moving) {
            double elapsedSeconds = (now - openedAtMillis) / 1000.0;
            for (int i = TRAIL_STEPS; i >= 1; i--) {
                double past = com.log_to_kot.maniacmod.map.minigame.TargetMinigameSpec
                    .cursorAt(elapsedSeconds - i * TRAIL_STEP_SECONDS, cursorSpeed);
                int gx = barX + (int) Math.round(past * BAR_WIDTH) - STICK_WIDTH / 2;
                float ga = 0.20f * (1f - (i - 1) / (float) TRAIL_STEPS);
                graphics.fill(gx, barY + 2, gx + STICK_WIDTH, barY + BAR_HEIGHT - 2,
                    GeneratorFuelUiTheme.alpha(0xFFC2C9D6, ga));
            }
        }
        int stickCenterX = barX + (int) Math.round(cursor * BAR_WIDTH);
        int stickLeft = stickCenterX - STICK_WIDTH / 2;
        boolean inZone = Math.abs(cursor - displayTarget) <= hitZoneWidth / 2.0;
        int stickColor = failed ? 0xFFFFFFFF
            : GeneratorFuelUiTheme.mix(ManiacUiTheme.BAR_FILL_NEUTRAL, 0xFFFFFFFF, inZone ? 1f : 0f);
        // Ореол: два прозорих шари навколо палки; в зоні — зеленуватий.
        int haloRgb = inZone && !failed ? GREEN : 0xC2C9D6;
        graphics.fill(stickLeft - 2, barY - STICK_OVERSHOOT, stickLeft + STICK_WIDTH + 2,
            barY + BAR_HEIGHT + STICK_OVERSHOOT, GeneratorFuelUiTheme.alpha(0xFF000000 | haloRgb, 0.10f));
        graphics.fill(stickLeft - 1, barY - STICK_OVERSHOOT, stickLeft + STICK_WIDTH + 1,
            barY + BAR_HEIGHT + STICK_OVERSHOOT, GeneratorFuelUiTheme.alpha(0xFF000000 | haloRgb, 0.20f));
        graphics.fill(stickLeft, barY - STICK_OVERSHOOT,
            stickLeft + STICK_WIDTH, barY + BAR_HEIGHT + STICK_OVERSHOOT, stickColor);
        ManiacUiTheme.border1px(graphics, stickLeft - 1, barY - STICK_OVERSHOOT - 1,
            STICK_WIDTH + 2, BAR_HEIGHT + STICK_OVERSHOOT * 2 + 2, 0xFF2A2A2A);
        // Маленькі «наконечники» на кінцях палки — читається як стрілка-вказівник.
        graphics.fill(stickLeft - 2, barY - STICK_OVERSHOOT - 2, stickLeft + STICK_WIDTH + 2,
            barY - STICK_OVERSHOOT - 1, stickColor);
        graphics.fill(stickLeft - 2, barY + BAR_HEIGHT + STICK_OVERSHOOT + 1, stickLeft + STICK_WIDTH + 2,
            barY + BAR_HEIGHT + STICK_OVERSHOOT + 2, stickColor);

        // Промінь по смузі під час появи панелі.
        if (intro < 1f) {
            UiFx.scanBeam(graphics, barX, barY, BAR_WIDTH, BAR_HEIGHT, introEase, 12, 0xFFFFFF, 70);
        }

        // Іскри — з позиції, де повзунок зупинили.
        if (pendingBurstPos >= 0) {
            spawnBurst(barX + (float) (pendingBurstPos * BAR_WIDTH), barY + BAR_HEIGHT / 2f, pendingBurstHit);
            pendingBurstPos = -1;
        }
        drawSparks(graphics);

        int hintY = panelY + panelHeight + HINT_GAP;
        drawHintFrame(graphics, panelX, hintY, PANEL_WIDTH, HINT_HEIGHT);

        GeneratorFuelUiTheme.endAppear(graphics);
        graphics.pose().popPose();

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * Ряд піпок «влучань»: заповнена — влучили, порожня — ще ні. Остання
     * влучена «підскакує» разом із текстом; при перемозі всі коротко блимають.
     */
    private void drawPips(GuiGraphics g, int centerX, int y, long now, float pop) {
        final int w = 12, h = 4, gap = 3;
        int total = hitsRequired * w + (hitsRequired - 1) * gap;
        int x0 = centerX - total / 2;
        for (int i = 0; i < hitsRequired; i++) {
            int x = x0 + i * (w + gap);
            boolean filled = i < hits;
            boolean justHit = filled && i == hits - 1 && pop > 0.01f;
            int grow = justHit ? Math.round(2f * pop) : 0;
            int fill;
            if (failed) fill = filled ? 0xFF8A2A30 : 0xFF2A0C0E;
            else if (filled) {
                fill = 0xFF000000 | GREEN;
                if (success) {
                    float wave = 0.5f + 0.5f * (float) Math.sin(now / 90.0 - i * 0.7);
                    fill = GeneratorFuelUiTheme.mix(fill, 0xFFFFFFFF, wave * 0.55f);
                } else if (justHit) fill = GeneratorFuelUiTheme.mix(fill, 0xFFFFFFFF, pop);
            } else fill = 0xFF1A1F26;
            if (filled && !failed) {
                UiFx.glow(g, x, y, w, h, GREEN, 0.5f + (justHit ? pop : 0f), 2);
            }
            g.fill(x - grow, y - grow, x + w + grow, y + h + grow, fill);
            ManiacUiTheme.border1px(g, x - grow, y - grow, w + grow * 2, h + grow * 2,
                failed ? 0xFF5A1A20 : (filled ? 0xFF2C5F38 : 0xFF3A424E));
        }
    }

    /** Панель міні-гри; у провалі — червоний фон і рамка замість стандартних. */
    private void drawPanelFrame(GuiGraphics graphics, int x, int y, int w, int h) {
        if (!failed) {
            ManiacUiTheme.drawPanel(graphics, x, y, w, h);
            return;
        }
        graphics.fill(x, y, x + w, y + h, FAIL_PANEL_BG);
        ManiacUiTheme.border1px(graphics, x, y, w, h, FAIL_BORDER);
        // Кутові акценти — як у стандартної панелі, але червоні.
        int t = 2, c = 10;
        graphics.fill(x, y, x + c, y + t, FAIL_BORDER);
        graphics.fill(x, y, x + t, y + c, FAIL_BORDER);
        graphics.fill(x + w - c, y, x + w, y + t, FAIL_BORDER);
        graphics.fill(x + w - t, y, x + w, y + c, FAIL_BORDER);
        graphics.fill(x, y + h - t, x + c, y + h, FAIL_BORDER);
        graphics.fill(x, y + h - c, x + t, y + h, FAIL_BORDER);
        graphics.fill(x + w - c, y + h - t, x + w, y + h, FAIL_BORDER);
        graphics.fill(x + w - t, y + h - c, x + w, y + h, FAIL_BORDER);
    }

    /** Панель-підказка: «Пробіл — зупинити»; у провалі теж червона. */
    private void drawHintFrame(GuiGraphics graphics, int x, int y, int w, int h) {
        String text = Component.translatable("maniacmod.minigame.stop_hint").getString();
        if (!failed) {
            ManiacUiTheme.drawHintBar(graphics, font, text, x, y, w, h);
            return;
        }
        graphics.fill(x, y, x + w, y + h, FAIL_PANEL_BG);
        ManiacUiTheme.border1px(graphics, x, y, w, h, FAIL_BORDER);
        graphics.drawCenteredString(font, text, x + w / 2, y + (h - font.lineHeight) / 2, FAIL_BORDER);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
