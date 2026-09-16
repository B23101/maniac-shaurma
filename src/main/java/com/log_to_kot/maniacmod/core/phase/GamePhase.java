package com.log_to_kot.maniacmod.core.phase;

import java.util.EnumSet;
import java.util.Set;

/**
 * ЄДИНИЙ список фаз матчу. Це той самий "спільний список", про який
 * ішлося в ТЗ: кожна фаза сама каже, ТЕХНІЧНА вона чи ІГРОВА, і що
 * саме в ній дозволено. Ніхто в моді більше не тримає власну копію
 * цього знання.
 *
 * ── Потік матчу ──────────────────────────────────────────────────────
 *   LOBBY        технічна  очікування гравців у зоні лобі
 *   CINEMATIC    технічна  всім показується кіно-вступ (заморожені)
 *   SCATTER      технічна  розкидання по карті + спавн генераторів/луту
 *   ROLE_REVEAL  технічна  оголошення ролей (хто маньяк, хто виживший)
 *   HUNT         ІГРОВА    основна фаза: ремонт генераторів, полювання
 *   POWERED      ІГРОВА    усі генератори полагоджені → відкриваються виходи
 *   FINALE       ІГРОВА    фінал: лишився останній / ворота відкриті
 *   ENDING       технічна  підсумки, статистика, ніхто нікого не б'є
 *   RESET        технічна  прибирання сутностей, повернення в LOBBY
 *
 * ── Як додати нову фазу ──────────────────────────────────────────────
 *   Дописати один рядок нижче з її PhaseKind і набором PhaseRule.
 *   Все інше (мережа, HUD, предмети, пастки, генератори) підхопить
 *   її автоматично, бо питає Phases.allows(...), а не назву фази.
 *
 * ── Мапінг на shaurma-lib ────────────────────────────────────────────
 *   Бібліотечний MatchLifecycleState грубий (IDLE/ACTIVE/ENDING) —
 *   тут кожна фаза одразу знає, у що вона мапиться, тож у коді більше
 *   немає жодного switch-у по фазах для lifecycle.
 */
public enum GamePhase {

    LOBBY(PhaseKind.TECHNICAL, LifecycleMirror.IDLE,
        EnumSet.of(PhaseRule.MOVEMENT, PhaseRule.ITEM_USE)),

    CINEMATIC(PhaseKind.TECHNICAL, LifecycleMirror.IDLE,
        EnumSet.noneOf(PhaseRule.class)),

    SCATTER(PhaseKind.TECHNICAL, LifecycleMirror.IDLE,
        EnumSet.noneOf(PhaseRule.class)),

    ROLE_REVEAL(PhaseKind.TECHNICAL, LifecycleMirror.IDLE,
        EnumSet.of(PhaseRule.HUD)),

    HUNT(PhaseKind.GAMEPLAY, LifecycleMirror.ACTIVE,
        EnumSet.of(PhaseRule.MOVEMENT, PhaseRule.DAMAGE, PhaseRule.TRAPS,
                   PhaseRule.GENERATOR_REPAIR, PhaseRule.ITEM_USE,
                   PhaseRule.ABILITIES, PhaseRule.HUD,
                   PhaseRule.SURVIVOR_VITALS, PhaseRule.RESCUE)),

    POWERED(PhaseKind.GAMEPLAY, LifecycleMirror.ACTIVE,
        EnumSet.of(PhaseRule.MOVEMENT, PhaseRule.DAMAGE, PhaseRule.TRAPS,
                   PhaseRule.ITEM_USE, PhaseRule.ABILITIES, PhaseRule.HUD,
                   PhaseRule.SURVIVOR_VITALS, PhaseRule.RESCUE,
                   PhaseRule.ESCAPE)),

    FINALE(PhaseKind.GAMEPLAY, LifecycleMirror.ACTIVE,
        EnumSet.of(PhaseRule.MOVEMENT, PhaseRule.DAMAGE, PhaseRule.TRAPS,
                   PhaseRule.ITEM_USE, PhaseRule.ABILITIES, PhaseRule.HUD,
                   PhaseRule.SURVIVOR_VITALS, PhaseRule.ESCAPE)),

    ENDING(PhaseKind.TECHNICAL, LifecycleMirror.ENDING,
        EnumSet.of(PhaseRule.MOVEMENT, PhaseRule.HUD)),

    RESET(PhaseKind.TECHNICAL, LifecycleMirror.IDLE,
        EnumSet.noneOf(PhaseRule.class));

    // ─────────────────────────────────────────────────────────────────

    /** Грубий стан бібліотеки, у який мапиться ця фаза. */
    public enum LifecycleMirror { IDLE, ACTIVE, ENDING }

    private final PhaseKind kind;
    private final LifecycleMirror lifecycle;
    private final Set<PhaseRule> rules;

    GamePhase(PhaseKind kind, LifecycleMirror lifecycle, Set<PhaseRule> rules) {
        this.kind = kind;
        this.lifecycle = lifecycle;
        this.rules = Set.copyOf(rules);
    }

    public PhaseKind kind()              { return kind; }
    public LifecycleMirror lifecycle()   { return lifecycle; }
    public Set<PhaseRule> rules()        { return rules; }

    /** Чи це реально ігрова фаза (не технічна вставка). */
    public boolean isGameplay()          { return kind == PhaseKind.GAMEPLAY; }

    /** Чи фаза дозволяє конкретну дію. Головний метод усього модуля. */
    public boolean allows(PhaseRule rule) { return rules.contains(rule); }

    /** Переклад назви для HUD/чату (ключ локалізації). */
    public String translationKey()       { return "maniacmod.phase." + name().toLowerCase(); }
}
