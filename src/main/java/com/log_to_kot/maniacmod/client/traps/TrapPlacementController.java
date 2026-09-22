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
 * ── Що показує квадрат, а що — ні ────────────────────────────────────
 * Зелений/червоний береться з {@link TrapArchetype#validatePlacement} —
 * ТІЄЇ САМОЇ функції, що й на сервері, тож геометрія (повний блок, вільне
 * місце над ним) не розходиться. Але правило «не ближче N блоків до
 * виживого» клієнт перевірити НЕ може й не повинен: він не знає, де
 * виживі, а колір квадрата став би для маньяка радаром крізь стіни.
 * Тому зелений квадрат означає «місце придатне», а не «сервер точно
 * дозволить»; відмову через близькість гравця маньяк бачить у
 * повідомленні після ПКМ.
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

    /** ПКМ був затиснутий на попередньому тіку — детекція фронту, як у ClientInputHandler. */
    private static boolean useWasDown = false;

    private TrapPlacementController() {}

    // ── Публічне читання ─────────────────────────────────────────────────

    /** Чи режим розміщення активний. Читає й міксин, що глушить ПКМ. */
    public static boolean isActive() {
        return ClientMatchState.isPlacingTrap();
    }

    public static BlockPos target() { return target; }

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
            useWasDown = isUseDown(); // ПКМ, затиснутий у момент входу, — не підтвердження
        }
    }

    public static void exit() {
        ClientMatchState.setActiveTrapSlot(-1);
        target = null;
        targetResult = TrapArchetype.PlacementResult.UNAVAILABLE;
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
        target = TrapAiming.targetFloor(mc.level, mc.player, ClientMatchState.trapPlaceRange());
        if (target == null) {
            targetResult = TrapArchetype.PlacementResult.TOO_FAR;
            return;
        }
        TrapArchetype type = trapOf(slot);
        targetResult = type == null
            ? TrapArchetype.PlacementResult.UNAVAILABLE
            : type.validatePlacement(mc.level, target);
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

        ModNetwork.toServer(new TrapPlacePacket(slot));
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
