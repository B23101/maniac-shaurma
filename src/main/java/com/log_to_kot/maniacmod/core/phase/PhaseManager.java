package com.log_to_kot.maniacmod.core.phase;

import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Власник поточної фази матчу. Єдине місце в моді, де фаза
 * змінюється, і єдине місце, звідки про це дізнаються всі модулі.
 *
 * v3-еквівалент: приватне поле ManiacGameManager.gameState + метод
 * setState(). Проблема була в тому, що стан був частиною класу, який
 * ОДНОЧАСНО займався пастками, трупами, генераторами й боєм —
 * тому будь-хто, кому потрібна була фаза, змушений був тягнути
 * увесь ManiacGameManager. Тепер фаза — окремий маленький модуль,
 * від якого можна залежати без ризику циклічних залежностей.
 *
 * Клас навмисно НЕ знає нічого про маньяків, пастки чи генератори:
 * він лише зберігає фазу, тікає лічильник і розсилає події.
 * Хто маньяк і коли переходити далі — вирішує MatchOrchestrator.
 */
public final class PhaseManager {

    private GamePhase current = GamePhase.LOBBY;
    private long ticksInPhase = 0;

    /** LinkedHashMap — порядок реєстрації = порядок виклику listener-ів. */
    private final Map<String, PhaseListener> listeners = new LinkedHashMap<>();

    /** Міст до shaurma-lib: приймає GamePhase.LifecycleMirror. Може бути null у тестах. */
    private LifecycleSink lifecycleSink;

    /** Тонкий інтерфейс, щоб цей пакет не залежав від бібліотеки напряму. */
    public interface LifecycleSink {
        void mirror(GamePhase.LifecycleMirror state);
    }

    // ── Реєстрація модулів ───────────────────────────────────────────────

    public void register(PhaseListener listener) {
        listeners.put(listener.id(), listener);
    }

    public void unregister(String id) {
        listeners.remove(id);
    }

    public void attachLifecycleSink(LifecycleSink sink) {
        this.lifecycleSink = sink;
    }

    // ── Читання фази ─────────────────────────────────────────────────────

    public GamePhase current()        { return current; }
    public long ticksInPhase()        { return ticksInPhase; }
    public boolean isGameplay()       { return current.isGameplay(); }
    public boolean allows(PhaseRule rule) { return current.allows(rule); }
    public boolean is(GamePhase phase)    { return current == phase; }

    // ── Зміна фази ───────────────────────────────────────────────────────

    /**
     * Перехід у наступну фазу. Порядок навмисно жорсткий:
     *   1. onPhaseExit усім (модулі прибирають за собою)
     *   2. фаза змінюється, лічильник обнуляється
     *   3. дзеркалимо lifecycle бібліотеки
     *   4. onPhaseEnter усім (модулі розставляють нове)
     *
     * Завдяки цьому порядку модуль ніколи не бачить "напівстан":
     * коли викликається onPhaseEnter, current() вже нова фаза.
     */
    public void transitionTo(GamePhase next, List<ServerPlayer> players) {
        if (next == current) return;

        GamePhase previous = current;
        for (PhaseListener l : snapshot()) l.onPhaseExit(previous, players);

        current = next;
        ticksInPhase = 0;

        if (lifecycleSink != null) lifecycleSink.mirror(next.lifecycle());

        for (PhaseListener l : snapshot()) l.onPhaseEnter(next, players);
    }

    /**
     * Один серверний тік. Викликається з ServerEventHandler БЕЗ
     * жодної попередньої перевірки стану — фільтрація тепер тут.
     */
    public void tick(List<ServerPlayer> players) {
        ticksInPhase++;
        for (PhaseListener l : snapshot()) l.onPhaseTick(current, players, ticksInPhase);
    }

    /** Повне скидання (новий матч). */
    public void reset(List<ServerPlayer> players) {
        transitionTo(GamePhase.LOBBY, players);
    }

    /** Копія, щоб listener міг зареєструвати/зняти інший listener під час обходу. */
    private List<PhaseListener> snapshot() {
        return new ArrayList<>(listeners.values());
    }
}
