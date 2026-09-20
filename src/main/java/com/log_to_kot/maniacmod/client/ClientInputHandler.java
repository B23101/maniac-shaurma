package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.entity.GeneratorEntity;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.c2s.hold.GeneratorRepairHoldPacket;
import com.log_to_kot.maniacmod.net.c2s.hold.RescueHoldPacket;
import com.log_to_kot.maniacmod.net.c2s.intent.AbilityActivatePacket;
import com.log_to_kot.maniacmod.net.c2s.intent.HighlightTogglePacket;
import com.log_to_kot.maniacmod.net.c2s.intent.StandUpPacket;
import com.log_to_kot.maniacmod.net.c2s.intent.TrapPlacePacket;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.AbilityCooldownPacket;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Читає клавіші й перетворює їх на C2S-пакети.
 *
 * ── Принцип ──────────────────────────────────────────────────────────
 * Клієнт надсилає НАМІР, а не результат. Тутешні перевірки (роль,
 * фаза, стан) — лише щоб не засмічувати мережу свідомо марними
 * пакетами; сервер усе одно перевіряє все заново, бо клієнт може
 * бути модифікований.
 *
 * ── Утримання (Shift / ПКМ) ──────────────────────────────────────────
 * Надсилається двома пакетами — на початку й у кінці утримання, а не
 * щотік. Поля rescueHeld/repairHeld тримають локальний стан, щоб
 * зафіксувати момент зміни.
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientInputHandler {

    private static boolean rescueHeld = false;
    private static boolean repairHeld = false;

    /**
     * Чи пробіл був затиснутий на ПОПЕРЕДНЬОМУ тіку — для виявлення
     * фронту натискання (відпущено → натиснуто). Без цього
     * {@code isDown()} лишався б істинним увесь час утримання, і
     * пакет вставання летів би щотік: 10 «натискань» набиралися б за
     * пів секунди простим утриманням, а не справжнім тисненням.
     */
    private static boolean standUpWasDown = false;

    private ClientInputHandler() {}

    /**
     * Примусово скидає локальний прапор утримання ремонту БЕЗ надсилання
     * пакета — викликається {@code ClientPacketHandler} рівно в момент
     * відкриття міні-гри ремонту.
     *
     * Поки відкритий {@code TargetMinigameScreen}/{@code WireMinigameScreen}
     * ({@code mc.screen != null}), {@link #onClientTick} виходить раніше
     * виклику {@link #handleGeneratorRepair()} — тому фізичне відпускання
     * ПКМ гравцем (щоб клікати мишею по міні-грі) не долітає до сервера
     * як {@code holding=false}, і локальний {@code repairHeld} лишається
     * застряглим у {@code true} до закриття екрана. Серверній сесії
     * ремонту це не шкодить (вона свідомо не закривається, поки триває
     * міні-гра — див. {@code GeneratorModule.stopRepair}), але без цього
     * скидання перший тік після закриття міні-гри бачив би застарілий
     * {@code repairHeld=true} й міг би не помітити, що гравець насправді
     * вже відпустив кнопку. Скидання тут прибирає розходження одразу:
     * наступний {@link #handleGeneratorRepair()} перечитує реальний стан
     * клавіші "з чистого аркуша", наче ремонт щойно почався заново.
     */
    static void forceReleaseRepairHold() {
        repairHeld = false;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            // Вийшли зі світу — скидаємо все, інакше наступний матч
            // почнеться з чужим станом (v3-баг із залиплими оверлеями).
            if (rescueHeld) rescueHeld = false;
            if (repairHeld) repairHeld = false;
            standUpWasDown = false;
            return;
        }
        if (mc.screen != null) {
            // Відкрите меню — клавіші не наші. Але фронт пробілу мусить
            // лишатись актуальним: інакше пробіл, натиснутий у меню й
            // ще затиснутий після його закриття, зарахувався б як нове
            // натискання вставання.
            standUpWasDown = ManiacKeybinds.isStandUpDown();
            return;
        }

        handleAbilities();
        handleTraps();
        handleHighlight();
        handleStandUp();
        handleRescueHold();
        handleGeneratorRepair();
    }

    /** Клавіші 1, 2, 3 — здібності маньяка. */
    private static void handleAbilities() {
        if (!ClientMatchState.isManiac()) return;
        if (!ClientMatchState.allows(PhaseRule.ABILITIES)) return;

        for (int slot = 0; slot < ManiacKeybinds.ABILITIES.length; slot++) {
            boolean pressed = false;
            while (ManiacKeybinds.ABILITIES[slot].consumeClick()) pressed = true;
            if (!pressed) continue;

            // Локальна перевірка кулдауну — щоб не слати пакет, який
            // сервер усе одно відхилить. Сам кулдаун тримає сервер.
            if (onCooldown(AbilityCooldownPacket.abilityId(slot))) continue;
            ModNetwork.toServer(new AbilityActivatePacket(slot));
        }
    }

    /** Клавіші Z, X, C — пастки маньяка. */
    private static void handleTraps() {
        if (!ClientMatchState.isManiac()) return;
        if (!ClientMatchState.allows(PhaseRule.TRAPS)) return;

        for (int slot = 0; slot < ManiacKeybinds.TRAPS.length; slot++) {
            boolean pressed = false;
            while (ManiacKeybinds.TRAPS[slot].consumeClick()) pressed = true;
            if (!pressed) continue;

            if (onCooldown(AbilityCooldownPacket.trapId(slot))) continue;
            ModNetwork.toServer(new TrapPlacePacket(slot));
        }
    }

    private static boolean onCooldown(String actionId) {
        Minecraft mc = Minecraft.getInstance();
        long tick = mc.level != null ? mc.level.getGameTime() : 0L;
        return ClientMatchState.abilityCooldownFraction(actionId, tick) > 0f;
    }

    private static void handleHighlight() {
        if (!ClientMatchState.isSurvivor()) return;
        if (!ClientMatchState.allows(PhaseRule.HUD)) return;

        while (ManiacKeybinds.HIGHLIGHT.consumeClick()) {
            ModNetwork.toServer(new HighlightTogglePacket());
        }
    }

    /**
     * Пробіл, поки гравець збитий з ніг. Шле пакет на КОЖНЕ окреме
     * натискання (фронт), а не на утримання.
     *
     * ── Чому кнопку читаємо тут, а не через keyJump.consumeClick() ────
     * {@code consumeClick()} віддає лічильник натискань між тіками і
     * дозволив би не пропустити швидке подвійне тиснення, але ванільний
     * стрибок теж споживає ці самі кліки; тому тут працює той самий
     * підхід, що й у решти утримань цього класу — читання стану клавіші
     * ({@code isDown}), тільки з детекцією переходу.
     *
     * Коли гравець НЕ лежить, {@link #standUpWasDown} скидається:
     * інакше пробіл, який гравець ще тримав у момент падіння, одразу
     * зарахувався б як «нове натискання» і подарував би безкоштовний
     * прогрес.
     */
    private static void handleStandUp() {
        boolean crawling = ClientMatchState.isSurvivor()
            && ClientMatchState.allows(PhaseRule.MOVEMENT)
            && ClientMatchState.survivorState() == SurvivorState.CRAWLING;

        if (!crawling) {
            // Запам'ятовуємо реальний стан клавіші, а не false: якщо
            // гравець тримає пробіл у момент падіння, це утримання,
            // а не нове натискання — його треба спершу відпустити.
            standUpWasDown = ManiacKeybinds.isStandUpDown();
            return;
        }

        boolean down = ManiacKeybinds.isStandUpDown();
        if (down && !standUpWasDown) {
            ModNetwork.toServer(new StandUpPacket());
        }
        standUpWasDown = down;
    }

    /** Shift біля непритомного — лише зміни стану, не щотік. */
    private static void handleRescueHold() {
        if (!ClientMatchState.isSurvivor()) return;
        if (!ClientMatchState.allows(PhaseRule.RESCUE)) {
            if (rescueHeld) {
                rescueHeld = false;
                ModNetwork.toServer(new RescueHoldPacket(false));
            }
            return;
        }

        boolean held = ManiacKeybinds.isRescueHeld();
        if (held == rescueHeld) return;

        rescueHeld = held;
        ModNetwork.toServer(new RescueHoldPacket(held));
    }

    /**
     * Shift+ПКМ, дивлячись на генератор — лагодження (стадія REPAIR) чи
     * залив каністрою (стадія FUEL). Лише зміни стану, не щотік, той
     * самий принцип, що {@link #handleRescueHold()}.
     *
     * Той самий пакет {@code GeneratorRepairHoldPacket} обслуговує ОБИДВІ
     * стадії — сервер сам визначає за {@code GeneratorPoi.stage()}, що
     * саме робити з утриманням (додати прогрес ремонту чи прогрес заливу
     * з каністри в руці); клієнт тут не розрізняє стадії, лише шле намір
     * "тримаю на цій позиції".
     *
     * ── Чому не Entity.interact() ────────────────────────────────────
     * {@code Entity.interact()} спрацьовує один раз на клік ПКМ у
     * ванілі — цього досить для миттєвого саботажу маньяка
     * ({@code GeneratorEntity.interact}), але не для "тримай, поки не
     * набереться 100%". Тому тут напряму читається стан ванільних
     * клавіш "sneak"+"use" — {@link ManiacKeybinds#isRepairHeld()},
     * точно за тим самим підходом, що {@link ManiacKeybinds#isRescueHeld()}
     * (Shift) чи {@link ManiacKeybinds#isStandUpDown()} (Пробіл).
     *
     * ── Визначення генератора ─────────────────────────────────────────
     * {@code Minecraft.getInstance().hitResult} — той самий рейкаст,
     * що ванільний клієнт щокадру рахує для підсвітки блоку/сутності
     * під прицілом; якщо це {@link EntityHitResult} на
     * {@link GeneratorEntity}, надсилаємо позицію ЦІЄЇ сутності. Якщо
     * гравець відводить приціл убік, продовжуючи тримати Shift+ПКМ, ми
     * НЕ шлемо нічого нового (тут завжди максимум один активний
     * генератор на клієнта) — сесія на сервері лишається за старою
     * позицією, доки утримання фізично не відпущено чи не почалась
     * міні-гра.
     *
     * ── Міні-гра посеред утримання ────────────────────────────────────
     * Коли на цього гравця випадає міні-гра, відкривається екран
     * ({@code mc.screen != null}), і цей метод узагалі не викликається
     * ({@link #onClientTick} виходить раніше) — фізичне відпускання
     * ПКМ під час гри мишею по міні-грі тому НЕ долітає до сервера як
     * {@code holding=false}. Щоб {@link #repairHeld} не лишався
     * застряглим, {@code ClientPacketHandler.onTargetMinigameOpen}/
     * {@code onWireMinigameOpen} скидають його напряму через
     * {@link #forceReleaseRepairHold()} в момент відкриття екрана —
     * без надсилання пакета, бо серверна сесія свідомо НЕ закривається
     * на час міні-гри (див. {@code GeneratorModule.stopRepair}).
     * Перший тік після закриття міні-гри тому починає з чистого
     * прапорця й коректно визначає, чи гравець і далі тримає Shift+ПКМ.
     */
    private static void handleGeneratorRepair() {
        if (!ClientMatchState.isSurvivor()) return;

        if (!ClientMatchState.allows(PhaseRule.GENERATOR_REPAIR) || !ManiacKeybinds.isRepairHeld()) {
            if (repairHeld) {
                repairHeld = false;
                ModNetwork.toServer(new GeneratorRepairHoldPacket(false, BlockPos.ZERO));
            }
            return;
        }

        if (repairHeld) return; // вже тримаємо — сервер сам веде прогрес, повторно слати нічого не треба

        Minecraft mc = Minecraft.getInstance();
        HitResult hit = mc.hitResult;
        if (!(hit instanceof EntityHitResult entityHit)) return;
        if (!(entityHit.getEntity() instanceof GeneratorEntity generator)) return;

        repairHeld = true;
        ModNetwork.toServer(new GeneratorRepairHoldPacket(true, generator.generatorPos()));
    }
}
