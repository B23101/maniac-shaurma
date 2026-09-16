package com.log_to_kot.maniacmod.core.phase;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Підписник на зміну фаз. Кожен модуль (пастки, генератори, виживші,
 * маньяки, мережа, HUD) реєструє СВІЙ listener і сам вирішує, що
 * робити на вході/виході з фази.
 *
 * НАВІЩО: у v3 уся послідовність старту гри лежала одним шматком у
 * ManiacGameManager.startGame() — там же видавались предмети, там же
 * спавнились зони, там же слались пакети. Будь-яка нова механіка
 * означала ще один блок коду в тому самому методі.
 *
 * Тепер: PhaseManager оголошує "фаза SCATTER почалась", а хто що
 * робить у SCATTER — вирішує сам модуль у своєму onEnter(). Модулі
 * не знають один про одного.
 *
 * Усі методи мають default-реалізацію, тому модуль перевизначає лише
 * ті фази, які йому цікаві.
 */
public interface PhaseListener {

    /** Читабельний ідентифікатор для логів: "traps", "generators", "hud". */
    String id();

    /** Викликається один раз, коли фаза почалась. */
    default void onPhaseEnter(GamePhase phase, List<ServerPlayer> players) {}

    /** Викликається один раз, коли фаза завершується (перед наступною). */
    default void onPhaseExit(GamePhase phase, List<ServerPlayer> players) {}

    /**
     * Тік у межах фази. Викликається ТІЛЬКИ поки фаза активна —
     * модулю більше не потрібно самому перевіряти стан гри.
     *
     * @param ticksInPhase скільки тіків минуло від початку фази
     */
    default void onPhaseTick(GamePhase phase, List<ServerPlayer> players, long ticksInPhase) {}
}
