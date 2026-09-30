package com.log_to_kot.maniacmod.client.traps;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.ManiacKeybinds;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.c2s.intent.TrapPlacePacket;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.AbilityCooldownPacket;
import com.log_to_kot.maniacmod.traps.TrapAiming;
import com.log_to_kot.maniacmod.traps.TrapArchetype;
import com.log_to_kot.maniacmod.traps.TrapRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Клієнтський РЕЖИМ РОЗМІЩЕННЯ пастки: кнопка 5/6/7 → зелений/червоний
 * квадрат на блоці під прицілом → ПКМ підтвердити → Esc скасувати.
 *
 * ── Чия тут правда ───────────────────────────────────────────────────
 * Цей клас — лише ІНТЕРФЕЙС. Він показує підказку й шле намір «слот N,
 * підтверджую»; чи можна ставити насправді, вирішує сервер
 * ({@code TrapModule#onPlaceConfirmed}) і перераховує все заново.
 *
 * ── Що показує квадрат ───────────────────────────────────────────────
 * Зелений/червоний береться з {@link TrapArchetype#validatePlacement} —
 * ТІЄЇ САМОЇ функції, що й на сервері, — плюс клієнтського дзеркала
 * правил про людей і вже поставлені пастки ({@link TrapPlacementClientRules}):
 * «гравець у клітинці», «пастка вже стоїть», «гравець на лінії дроту».
 * Тож червоний квадрат означає реальну відмову, а ПРИЧИНА ({@link #targetResult()})
 * пишеться на екрані одразу ({@link TrapPlacementHintOverlay}), без очікування
 * відповіді сервера після ПКМ. Сервер усе одно перерахує при підтвердженні.
 *
 * ── ПКМ і Esc ────────────────────────────────────────────────────────
 *   • ПКМ у режимі — лише підтвердження. Ванільне використання глушить
 *     {@code GeneratorRepairSwingGuardMixin} (через {@link #isActive()}).
 *   • Esc — скасовує режим і НЕ відкриває меню паузи (перехоплюємо
 *     відкриття {@link PauseScreen}). Меню паузи відкривається лише
 *     коли режиму немає.
 *
 * ── Вихід із режиму ──────────────────────────────────────────────────
 * Автоматично: пастки закінчились, фаза більше не дозволяє їх, гравець
 * не маньяк, слот на перезарядці після розміщення, відкрито будь-який
 * екран. Це прибирає залипання режиму між матчами (v3-баг залиплих
 * оверлеїв, який мод уже виправляв для решти HUD).
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TrapPlacementController {

    /**
     * Блок-підлога під прицілом, або null. Оновлюється щотіка, читається
     * рендерером квадрата. Зберігається окремо від валідності, щоб
     * рендерер не рахував рейкаст удруге.
     */
    private static BlockPos target = null;
    private static TrapArchetype.PlacementResult targetResult = TrapArchetype.PlacementResult.UNAVAILABLE;

    /**
     * ПЕРША точка, уже підтверджена для {@code TWO_POINTS}-пастки
     * (електричний дріт). Поки вона є — рендерер малює лінію-прев'ю від
     * неї до поточної цілі (ТЗ 2.2). Для одноточкових пасток завжди null.
     */
    private static BlockPos firstPoint = null;

    /** ПКМ був затиснутий на попередньому тіку — детекція фронту, як у ClientInputHandler. */
    private static boolean useWasDown = false;

    private TrapPlacementController() {}

    // ── Публічне читання ─────────────────────────────────────────────────

    /** Чи режим розміщення активний. Читає й міксин, що глушить ПКМ. */
    public static boolean isActive() {
        return ClientMatchState.isPlacingTrap();
    }

    public static BlockPos target() { return target; }

    /** Перша точка дрібного вибору, або null (див. {@link #firstPoint}). */
    public static BlockPos firstPoint() { return firstPoint; }

    /** Чи активна ДВОТОЧКОВА пастка (електричний дріт) для поточного слоту. */
    public static boolean isTwoPointMode() {
        TrapArchetype type = trapOf(ClientMatchState.activeTrapSlot());
        return type != null && type.placementShape() == TrapArchetype.PlacementShape.TWO_POINTS;
    }

    /** Причина, з якої місце під прицілом не підходить (або OK). Для напису на екрані. */
    public static TrapArchetype.PlacementResult targetResult() { return targetResult; }

    /** Чи місце під прицілом придатне (зелений квадрат). false, якщо цілі немає. */
    public static boolean targetValid() {
        return target != null && targetResult.ok();
    }

    // ── Вхід / вихід ─────────────────────────────────────────────────────

    /**
     * Натиснули клавішу пастки. Повторне натискання того ж слота — вихід
     * (перемикач); інший слот — переходимо на нього без виходу.
     */
    public static void toggle(int slot) {
        if (slot < 0 || slot >= ClientMatchState.trapIds().size()) return;
        if (ClientMatchState.activeTrapSlot() == slot) {
            exit();
        } else {
            ClientMatchState.setActiveTrapSlot(slot);
            // Новий слот — новий вибір: перша точка з попередньої пастки
            // не має лишатись «висячою» прев'ю-лінією.
            firstPoint = null;
            useWasDown = isUseDown(); // ПКМ, затиснутий у момент входу, — не підтвердження
        }
    }

    public static void exit() {
        ClientMatchState.setActiveTrapSlot(-1);
        target = null;
        targetResult = TrapArchetype.PlacementResult.UNAVAILABLE;
        // Вихід із режиму скидає й незавершений вибір першої точки дроту:
        // наступний вхід має починатись з чистого аркуша.
        firstPoint = null;
    }

    // ── Тік ──────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!isActive()) {
            useWasDown = false;
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null
            || mc.screen != null                       // будь-яке меню/чат/інвентар
            || !ClientMatchState.isManiac()
            || !ClientMatchState.allows(PhaseRule.TRAPS)) {
            exit();
            return;
        }

        int slot = ClientMatchState.activeTrapSlot();
        // Слот на перезарядці — ставити нічого. Виходимо, а не висимо
        // в режимі, який нічого не може підтвердити.
        if (onCooldown(slot)) {
            exit();
            return;
        }

        updateTarget(mc, slot);
        handleConfirm(slot);
    }

    private static void updateTarget(Minecraft mc, int slot) {
        double range = ClientMatchState.trapPlaceRange();
        TrapArchetype type = trapOf(slot);

        // 1) Спершу шукаємо блок у межах дальності (звичайний випадок).
        BlockPos floor = TrapAiming.targetFloor(mc.level, mc.player, range);
        if (floor != null) {
            target = floor;
        } else {
            // 2) Не на підлогу / задалеко. Куб НЕ зникає: показуємо
            //    червоний на тому, у що дивимось, щоб було видно, що не так.
            var hit = TrapAiming.rawHit(mc.level, mc.player, Math.max(range, 1.0) + 8.0);
            if (hit == null) {
                target = null;
                targetResult = TrapArchetype.PlacementResult.TOO_FAR;
                return;
            }
            target = hit.getBlockPos();
            boolean topFace = hit.getDirection() == net.minecraft.core.Direction.UP;
            targetResult = topFace
                ? TrapArchetype.PlacementResult.TOO_FAR
                : TrapArchetype.PlacementResult.NOT_FULL_BLOCK;
            return;
        }

        if (type == null) {
            targetResult = TrapArchetype.PlacementResult.UNAVAILABLE;
            return;
        }

        // Дальність міряємо тією ж формулою, що й сервер
        // (TrapPlacementRules.isWithinReach): до центру блока, з запасом +1.
        double limit = range + 1.0;
        boolean reachable = mc.player.getEyePosition()
            .distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(target)) <= limit * limit;

        TrapArchetype.PlacementResult single = type.validatePlacement(mc.level, target);
        if (!single.ok()) {
            targetResult = single;
            return;
        }
        if (!reachable) {
            targetResult = TrapArchetype.PlacementResult.TOO_FAR;
            return;
        }

        // Правила про людей і вже поставлені пастки — те, що раніше клієнт
        // лишав серверу (і маньяк дізнавався про відмову лише після ПКМ).
        TrapArchetype.PlacementResult around = TrapPlacementClientRules.checkPlayers(mc.level, target);
        if (!around.ok()) {
            targetResult = around;
            return;
        }
        around = TrapPlacementClientRules.checkOccupied(mc.level, target);
        if (!around.ok()) {
            targetResult = around;
            return;
        }

        // Друга точка дроту: колір має відображати перевірку ПАРИ
        // (Y, довжина, перешкоди, гравець на лінії), а не лише цієї точки.
        if (firstPoint != null && type.placementShape() == TrapArchetype.PlacementShape.TWO_POINTS) {
            TrapArchetype.PlacementResult pair = type.validatePlacement(mc.level, firstPoint, target);
            if (pair.ok()) pair = TrapPlacementClientRules.checkWireSegment(mc.level, firstPoint, target);
            targetResult = pair;
            return;
        }
        targetResult = single;
    }

    /**
     * ПКМ — підтвердити. Шлемо пакет лише на ФРОНТ натискання й лише
     * коли місце придатне: інакше затиснутий ПКМ засипав би сервер
     * пакетами, а червоний квадрат «підтверджував» би неможливе.
     * Після підтвердження режим лишається — маньяк може ставити далі,
     * поки не спрацює перезарядка (тоді {@link #onClientTick} вийде сам).
     */
    private static void handleConfirm(int slot) {
        boolean down = isUseDown();
        boolean pressed = down && !useWasDown;
        useWasDown = down;
        if (!pressed) return;
        if (!targetValid()) return;

        // Двоточкова пастка (електричний дріт): перший ПКМ фіксує точку №1,
        // другий — точку №2 і ставить дріт (ТЗ 2.1/2.2). Куди саме — все
        // одно рахує сервер за поглядом; клієнт лише каже, яка це точка.
        // Локальний firstPoint — винятково для прев'ю-лінії.
        if (isTwoPointMode()) {
            boolean second = firstPoint != null;
            ModNetwork.toServer(new TrapPlacePacket(slot, second, target));
            firstPoint = second ? null : target;
            return;
        }

        ModNetwork.toServer(new TrapPlacePacket(slot, false, target));
    }

    // ── Esc ──────────────────────────────────────────────────────────────

    /**
     * Esc у режимі розміщення скасовує його, а не відкриває меню паузи.
     * Перехоплюємо саме {@link PauseScreen}: усі інші екрани (чат,
     * інвентар, налаштування) проходять як завжди.
     */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!isActive()) return;
        if (!(event.getNewScreen() instanceof PauseScreen)) return;
        event.setCanceled(true);
        exit();
    }

    // ── Допоміжне ────────────────────────────────────────────────────────

    private static boolean isUseDown() {
        return Minecraft.getInstance().options.keyUse.isDown();
    }

    private static boolean onCooldown(int slot) {
        Minecraft mc = Minecraft.getInstance();
        long tick = mc.level != null ? mc.level.getGameTime() : 0L;
        return ClientMatchState.abilityCooldownFraction(AbilityCooldownPacket.trapId(slot), tick) > 0f;
    }

    /** Пастка слота за id з останнього {@code TrapLoadoutPacket}. */
    public static TrapArchetype trapOf(int slot) {
        var ids = ClientMatchState.trapIds();
        if (slot < 0 || slot >= ids.size()) return null;
        try {
            return TrapRegistry.get(ids.get(slot));
        } catch (IllegalArgumentException unknown) {
            return null; // клієнт і сервер з різним набором пасток
        }
    }
}
