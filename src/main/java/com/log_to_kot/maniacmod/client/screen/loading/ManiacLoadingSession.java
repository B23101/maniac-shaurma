package com.log_to_kot.maniacmod.client.screen.loading;

import com.log_to_kot.maniacmod.client.intro.IntroTips;
import com.log_to_kot.maniacmod.client.style.HorrorBackdrop;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.function.IntSupplier;

/**
 * Одна «сесія заходу»: від першого кадру «З'єднання…» до моменту, коли гра
 * віддала керування гравцю.
 *
 * ── Навіщо сесія, а не стан у самому екрані ──────────────────────────
 * Ванільний захід — це ланцюжок РІЗНИХ екранів: {@code ConnectScreen} →
 * ({@code LevelLoadingScreen} в одиночній грі) → {@code ReceivingLevelScreen}.
 * Якби кожен малював себе з нуля, анімація логотипа, серцебиття й смуга
 * прогресу починались би заново при кожній зміні екрана. Тому годинник,
 * стан смуги, назва сервера і крок живуть тут і переживають зміну екрана.
 * Якщо екранів не було довше {@link #RESET_GAP_MS} — це вже новий захід.
 *
 * ── Статика ──────────────────────────────────────────────────────────
 * Єдина свідома статика — {@link #current}: клієнтський UI-стан, який не
 * належить жодному матчу ({@code MatchContext} тут недоречний — на цей момент
 * гравець ще навіть не в світі). Нічого ігрового тут не рахується.
 *
 * ── Потоки ───────────────────────────────────────────────────────────
 * Усе, крім {@link #onVanillaStatus}, викликається з потоку рендера.
 * Ванільний статус приходить з мережевого потоку, тому лише
 * {@code volatile}-поля.
 */
@OnlyIn(Dist.CLIENT)
public final class ManiacLoadingSession {

    /** Який саме ванільний екран зараз малює сцену. */
    public enum Kind { CONNECT, LEVEL_LOADING, TERRAIN, INTERIM }

    /** Пауза між екранами, довша за яку — новий захід. */
    private static final long RESET_GAP_MS = 1500L;
    /** Скільки секунд статус «connect.*» вважається свіжим. */
    private static final long STATUS_FRESH_MS = 4000L;
    /**
     * Мінімальний час показу екрана «Завантаження місцевості» від початку заходу.
     * На швидкому з'єднанні ванільний екран зникає за долю секунди — анімація
     * не встигла б навіть скинути літери логотипа. Діє ЛИШЕ для справжнього
     * заходу (не для зміни виміру посеред гри).
     */
    static final long MIN_HOLD_MS = 2600L;

    private static final String[] STAGES_ONLINE = {"connect", "auth", "join", "terrain"};
    private static final String[] STAGES_SOLO = {"world", "spawn", "terrain"};

    private static ManiacLoadingSession current;

    private static volatile int pendingStage;
    private static volatile long pendingStageAt;
    private static volatile IntSupplier pendingLevelProgress;

    // ── Життєвий цикл ───────────────────────────────────────────────
    final long startedAt;
    /** Справжній захід (починався з підключення/завантаження світу), а не зміна виміру. */
    final boolean joinFlow;
    final HorrorBackdrop backdrop;
    final IntroTips tips = new IntroTips();
    long lastFrameAt;
    boolean exiting;
    long exitAt = -1L;

    // ── Кроки та прогрес ────────────────────────────────────────────
    boolean solo;
    int stageIdx;
    long stageEnteredAt;
    float levelPct = -1f;
    /** Монотонна ціль прогресу 0..1 — смуга ніколи не відкочується назад. */
    private float floor;
    /** Те, що реально малюється (плавно доганяє {@link #floor}). */
    float shown;
    String serverName;
    String serverAddr;
    String worldName;
    private IntSupplier levelProgress;

    // ── UI-стан ─────────────────────────────────────────────────────
    float cancelHover;
    private final long[] clock = new long[1];
    private long lastNarrate;

    private ManiacLoadingSession(Kind first, long now) {
        this.startedAt = now;
        this.lastFrameAt = now;
        this.stageEnteredAt = now;
        this.joinFlow = first != Kind.TERRAIN;
        this.backdrop = new HorrorBackdrop();
    }

    // ═══════════════ Статичний вхід ══════════════════════════════════

    /** Викликається на початку КОЖНОГО кадру одного з трьох екранів. */
    public static ManiacLoadingSession frame(Kind kind) {
        long now = Util.getMillis();
        ManiacLoadingSession s = current;
        if (s == null || s.exiting || now - s.lastFrameAt > RESET_GAP_MS) {
            s = new ManiacLoadingSession(kind, now);
            current = s;
        }
        s.lastFrameAt = now;
        s.update(kind, now);
        return s;
    }

    /** Остання сесія (для оверлея виходу); {@code null}, якщо заходу не було. */
    static ManiacLoadingSession last() {
        return current;
    }

    /** Чи варто ще потримати екран місцевості, щоб анімація не обірвалась на півслові. */
    public static boolean shouldHold() {
        ManiacLoadingSession s = current;
        if (s == null || !s.joinFlow || s.exiting) return false;
        long now = Util.getMillis();
        return now - s.startedAt < MIN_HOLD_MS && now - s.lastFrameAt < 500L;
    }

    /**
     * Чи малювати нашу сцену замість «проміжного» ванільного екрана
     * ({@code GenericDirtMessageScreen}, {@code ProgressScreen}). Ванільні
     * екрани між {@code ConnectScreen}/{@code LevelLoadingScreen}/
     * {@code ReceivingLevelScreen} на мить перебивають сцену — це і був
     * «проскок». Але ті самі класи показують «Збереження світу» при виході, тому
     * перехоплюємо ЛИШЕ коли: (а) наша сесія щойно малювалась і ще не виходить,
     * або (б) заголовок екрана — відомий ключ входу у світ.
     */
    public static boolean acceptsInterim(Screen screen) {
        ManiacLoadingSession s = current;
        long now = Util.getMillis();
        if (s != null && !s.exiting && now - s.lastFrameAt <= RESET_GAP_MS) return true;
        if (screen.getTitle().getContents() instanceof TranslatableContents tc) {
            String k = tc.getKey();
            return k.startsWith("connect.") || k.equals("selectWorld.data_read")
                || k.equals("multiplayer.downloadingTerrain");
        }
        return false;
    }

    public static boolean isLoadingScreen(Screen screen) {
        return screen instanceof ConnectScreen
            || screen instanceof ReceivingLevelScreen
            || screen instanceof LevelLoadingScreen;
    }

    /** З мережевого потоку: ванільний статус «connect.*». */
    public static void onVanillaStatus(Component status) {
        if (status == null) return;
        ComponentContents c = status.getContents();
        if (!(c instanceof TranslatableContents tc)) return;
        int stage = switch (tc.getKey()) {
            case "connect.connecting" -> 0;
            case "connect.authorizing", "connect.encrypting" -> 1;
            case "connect.joining" -> 2;
            default -> -1;
        };
        if (stage < 0) return;
        pendingStage = stage;
        pendingStageAt = Util.getMillis();
    }

    /** Конструктор {@code LevelLoadingScreen} віддає сюди джерело відсотків (0..100). */
    public static void bindLevelProgress(IntSupplier supplier) {
        pendingLevelProgress = supplier;
    }

    // ═══════════════ Оновлення стану ═════════════════════════════════

    private void update(Kind kind, long now) {
        Minecraft mc = Minecraft.getInstance();
        float dt = UiAnim.frameDt(clock);
        refreshInfo(mc);

        if (levelProgress == null && pendingLevelProgress != null) levelProgress = pendingLevelProgress;
        if (kind == Kind.LEVEL_LOADING && levelProgress != null) {
            levelPct = Mth.clamp(levelProgress.getAsInt(), 0, 100) / 100f;
        }

        int count = stageCount();
        int idx = switch (kind) {
            case CONNECT -> solo ? 0 : freshVanillaStage(now);
            case LEVEL_LOADING -> 1;
            case TERRAIN -> count - 1;
            case INTERIM -> stageIdx;
        };
        if (idx > stageIdx) {
            stageIdx = idx;
            stageEnteredAt = now;
        }

        long inStage = now - stageEnteredAt;
        float sub;
        if (kind == Kind.LEVEL_LOADING && stageIdx == 1 && levelPct >= 0f) {
            sub = levelPct;
        } else if (stageIdx == count - 1) {
            sub = 0.92f * (1f - (float) Math.exp(-inStage / 2200f));
        } else {
            sub = 0.85f * (1f - (float) Math.exp(-inStage / 1800f));
        }
        floor = Math.max(floor, (stageIdx + sub) / count);
        shown = UiAnim.approach(shown, floor, 3.2f, dt);

        if (kind == Kind.CONNECT && now - lastNarrate > 2000L) {
            lastNarrate = now;
            mc.getNarrator().sayNow(Component.translatable("narrator.joining"));
        }
    }

    /** Фаза «виходу»: смуга добігає до 100 %. Викликає оверлей виходу. */
    void tickExit(long now) {
        float dt = UiAnim.frameDt(clock);
        shown = UiAnim.approach(shown, 1f, 9f, dt);
        if (exitAt < 0L) exitAt = now;
    }

    void beginExit(long now) {
        exiting = true;
        exitAt = now;
    }

    void finish() {
        if (current == this) current = null;
        pendingStage = 0;
        pendingStageAt = 0L;
        pendingLevelProgress = null;
    }

    private static int freshVanillaStage(long now) {
        return now - pendingStageAt <= STATUS_FRESH_MS ? pendingStage : 0;
    }

    private void refreshInfo(Minecraft mc) {
        if (mc.getSingleplayerServer() != null) solo = true;
        if (!solo && serverName == null) {
            ServerData sd = mc.getCurrentServer();
            if (sd != null) {
                serverName = sd.name;
                serverAddr = sd.ip;
            }
        }
        if (solo && worldName == null) {
            try {
                worldName = mc.getSingleplayerServer().getWorldData().getLevelName();
            } catch (RuntimeException ignored) {
                // світ ще ініціалізується — спробуємо наступного кадру
            }
        }
    }

    // ═══════════════ Допоміжне для рендера ═══════════════════════════

    int stageCount() {
        return solo ? STAGES_SOLO.length : STAGES_ONLINE.length;
    }

    String stageKey(int i) {
        String[] arr = solo ? STAGES_SOLO : STAGES_ONLINE;
        return arr[Mth.clamp(i, 0, arr.length - 1)];
    }

    long lastFrameAt() {
        return lastFrameAt;
    }
}
