package com.log_to_kot.maniacmod.client.overlay.roster;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.style.ManiacTabTheme;
import com.log_to_kot.maniacmod.client.style.UiFx;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.net.s2c.identity.RoleSyncPacket;
import com.log_to_kot.maniacmod.net.s2c.matchstate.ObjectiveSyncPacket;
import com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket.RosterEntry;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import dev.shaurmalib.forge.tab.TabListStyle;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Зріз одного кадру tab-екрана: усе, що потрібно картці, щоб малювати, і
 * нічого зайвого. Збирається раз на кадр у {@link TabRosterOverlay} і
 * передається картці аргументом — картки не лізуть у
 * {@link ClientMatchState} самі й тому не розходяться в тому, «що зараз
 * правда».
 *
 * ── Розбиття гравців ─────────────────────────────────────────────────
 * Один ростер розкладається на ЧОТИРИ групи, а не на плоский список:
 *   • {@link #maniac}      — на кого полюють; може бути {@code null}
 *                            (архетип іще не обрано);
 *   • {@link #alive}       — живі виживі, найтерміновіші (потрібна
 *                            допомога) — ЗГОРИ;
 *   • {@link #out}         — втекли/загинули: більше не грають, але
 *                            підсумок видно до кінця матчу;
 *   • {@link #spectators}  — глядачі.
 * У лобі ролей іще нема (усі SPECTATOR) — там {@link #lobby} = true і
 * єдиний список {@link #all}.
 *
 * Поля НЕ final: об'єкт один на весь час життя оверлея й перевикористовується
 * (менше сміття щокадру); {@link #reset} очищує списки, не створюючи нових.
 */
final class TabContext {

    // ── Рендер ───────────────────────────────────────────────────────
    GuiGraphics g;
    Font font;
    TabListStyle style;
    ManiacTabTheme theme;
    UiFx.Sparks sparks;

    // ── Час ──────────────────────────────────────────────────────────
    /** Секунд від відкриття Tab — вісь усіх вступних анімацій. */
    float t;
    /** Дельта кадру, секунди (обмежена, стійка до лагів). */
    float dt;
    long nowMs;
    /**
     * Чи можна зараз малювати 3D-моделі: вступна анімація дійшла до
     * повного розміру панелі й вона вже не згасає. Модель під час
     * розгортання/згасання спотворилась би (pose масштабується по Y),
     * а її власна прозорість не підкоряється глобальному множнику.
     */
    boolean model3d;

    // ── Стан матчу ───────────────────────────────────────────────────
    UUID me;
    RoleSyncPacket.Role myRole = RoleSyncPacket.Role.SPECTATOR;
    GamePhase phase = GamePhase.LOBBY;
    boolean lobby;
    ObjectiveSyncPacket objectives = ObjectiveSyncPacket.empty();

    // ── Гравці ───────────────────────────────────────────────────────
    /** Перший маньяк (для місць, де потрібен один); усі — у {@link #maniacs}. */
    RosterEntry maniac;
    /** Усі маньяки матчу: картка перемикається між ними, якщо їх 2+. */
    final List<RosterEntry> maniacs = new ArrayList<>();
    RosterEntry myEntry;
    final List<RosterEntry> alive = new ArrayList<>();
    final List<RosterEntry> out = new ArrayList<>();
    final List<RosterEntry> spectators = new ArrayList<>();
    final List<RosterEntry> all = new ArrayList<>();

    /** Черга терміновості: лежачі → повзуть → зламана нога → здорові (за спаданням хп). */
    private static final Comparator<RosterEntry> URGENCY = Comparator
        .comparingInt((RosterEntry e) -> urgencyRank(e.state()))
        .thenComparing(Comparator.comparingInt(RosterEntry::hp).reversed())
        .thenComparing(RosterEntry::displayName, String.CASE_INSENSITIVE_ORDER);

    private static final Comparator<RosterEntry> BY_NAME =
        Comparator.comparing(RosterEntry::displayName, String.CASE_INSENSITIVE_ORDER);

    static int urgencyRank(SurvivorState s) {
        return switch (s) {
            case UNCONSCIOUS -> 0;
            case CRAWLING    -> 1;
            case BROKEN_LEG  -> 2;
            case HEALTHY     -> 3;
            case ELIMINATED, ESCAPED -> 4;
        };
    }

    void reset() {
        maniac = null;
        maniacs.clear();
        myEntry = null;
        alive.clear();
        out.clear();
        spectators.clear();
        all.clear();
    }

    /** Розкладає ростер по групах. Викликати після встановлення {@link #me} і {@link #lobby}. */
    void partition(List<RosterEntry> roster) {
        reset();
        all.addAll(roster);
        all.sort(BY_NAME);

        List<RosterEntry> terminal = new ArrayList<>();
        for (RosterEntry e : roster) {
            if (e.playerId().equals(me)) myEntry = e;
            if (lobby) continue;
            switch (e.role()) {
                case MANIAC -> { maniacs.add(e); if (maniac == null) maniac = e; }
                case SURVIVOR -> { if (e.state().isTerminal()) terminal.add(e); else alive.add(e); }
                case SPECTATOR -> spectators.add(e);
            }
        }
        // Вибулих фільтруємо ПІСЛЯ циклу: «чи це моя жертва» залежить від
        // кількості маньяків, яка відома лише коли пройдено весь ростер.
        for (RosterEntry e : terminal) {
            if (visibleToMe(e)) out.add(e);
        }
        alive.sort(URGENCY);
        out.sort(BY_NAME);
        spectators.sort(BY_NAME);
    }

    /**
     * Маньяк бачить не всіх вибулих:
     *  • «втекли» — лише коли фаза допускає втечу (раніше втекти не можна,
     *    а сам рядок підказував би, що хтось уже знайшов вихід);
     *  • «вибули» — лише ЙОГО жертви. Без відомого вбивці (смерть без
     *    удару) жертва зараховується єдиному маньяку, а при кількох —
     *    нікому, щоб не приписати чужу.
     * Виживі й глядачі бачать усе.
     */
    private boolean visibleToMe(RosterEntry e) {
        if (!iAmManiac()) return true;
        if (e.state() == SurvivorState.ESCAPED) return escapePhase();
        return isMyKill(e);
    }

    boolean isMyKill(RosterEntry e) {
        if (e.killerId() != null) return e.killerId().equals(me);
        return maniacs.size() <= 1;
    }

    /** Фаза, у якій виживі вже можуть тікати (за правилом фази, а не за її назвою). */
    boolean escapePhase() { return phase.allows(PhaseRule.ESCAPE); }

    boolean iAmManiac()   { return myRole == RoleSyncPacket.Role.MANIAC; }
    boolean iAmSurvivor() { return myRole == RoleSyncPacket.Role.SURVIVOR; }

    /** Лежать без свідомості просто зараз. */
    int downedCount() {
        int n = 0;
        for (RosterEntry e : alive) if (e.state() == SurvivorState.UNCONSCIOUS) n++;
        return n;
    }

    int escapedCount() {
        int n = 0;
        for (RosterEntry e : out) if (e.state() == SurvivorState.ESCAPED) n++;
        return n;
    }

    int eliminatedCount() {
        int n = 0;
        for (RosterEntry e : out) if (e.state() == SurvivorState.ELIMINATED) n++;
        return n;
    }
}
