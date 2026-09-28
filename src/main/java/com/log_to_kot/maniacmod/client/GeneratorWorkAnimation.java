package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.ManiacMod;
import dev.shaurmalib.common.playeranim.PoseAction;
import dev.shaurmalib.common.playeranim.PoseActionRegistry;
import dev.shaurmalib.common.playeranim.PoseLayerId;
import dev.shaurmalib.common.playeranim.PoseSource;
import dev.shaurmalib.forge.playeranim.PlayerPoseController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Поза рук під час роботи з генератором (ТЗ 13): ремонт і залив бензину.
 *
 * ── Розподіл обов'язків ──────────────────────────────────────────────
 * СЕРВЕР — єдине джерело правди: він вирішує, хто справді працює, і на
 * якій стадії (ремонт чи залив), і розсилає це всім
 * ({@code GeneratorInteractionPacket}). Швидкість роботи, прогрес,
 * лок руху — теж серверні.
 *
 * ЦЕЙ КЛАС — чиста косметика: перекладає «хтось працює над генератором»
 * на playerlib-позу конкретного гравця. Він нічого не вирішує й нічого
 * не може зламати: якщо клас прибрати, ремонт працюватиме як працював,
 * просто без анімації рук.
 *
 * ── Чому не через {@code LiveHeldItemAction} ─────────────────────────
 * {@code LiveHeldItemAction} обслуговує дію ВЛАСНОГО гравця з предметом
 * у руці (старт по пакету, кінець по таймеру або по відпусканню). Тут
 * інша природа: тривалість невідома наперед (доки тримаєш кнопку),
 * і головне — поза потрібна ТРЕТІМ особам теж, бо це стан тіла в світі,
 * а не UI-відгук на мою дію. Тому це окремий драйвер, який щоразу
 * звіряє повний серверний список «хто працює».
 *
 * ── Один шар на обидві пози ──────────────────────────────────────────
 * Ремонт і залив живуть на {@link PoseLayerId#ITEM_ACTION} і ніколи не
 * відбуваються одночасно (стадія генератора одна) — тримати два шари
 * було б зайвим. Перехід між ними — це підміна кліпа в тому ж шарі, з
 * плавним fade з {@link PoseSource}, тож руки переходять без ривка.
 *
 * ── Друга особа ──────────────────────────────────────────────────────
 * Поза застосовується до СУТНОСТІ гравця, тому її бачать і всі інші —
 * але лише тому, що КОЖЕН клієнт запускає її для всіх, кого знає
 * (PAL застосовує {@code AnimationStack} кожного
 * {@code AbstractClientPlayer} під час рендера). Саме заради цього й
 * існує мережевий пакет: «я ремонтую» — це локальний ввід, який без
 * нього не покинув би мій клієнт.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GeneratorWorkAnimation {

    /** Файл: {@code assets/maniacmod/player_animations/generator_repair.json}. */
    public static final String REPAIR_ACTION = ManiacMod.MOD_ID + ":generator_repair";

    /** Файл: {@code assets/maniacmod/player_animations/generator_refuel.json}. */
    public static final String REFUEL_ACTION = ManiacMod.MOD_ID + ":generator_refuel";

    /** Кому яку позу зараз граємо — щоб не перезапускати кліп щотіка. */
    private static final Map<UUID, Integer> playing = new HashMap<>();

    private GeneratorWorkAnimation() {}

    /**
     * Реєстрація обох дій. Рівно один раз при старті клієнта
     * ({@code ClientSetup.onClientSetup}).
     *
     * Обидві — {@code hold} (файли з {@code looping: true}): дія триває,
     * доки гравець тримає кнопку, тож кліп мусить крутитися циклічно, а
     * зупиняє його наш драйвер за сигналом сервера (а не кінець файлу,
     * як у {@code oneShot}). Порожній цикл не потрібен: fade-in 4 тіки
     * прибирає ривок на старті, fade-out 6 — на відпусканні.
     */
    public static void registerActions() {
        PoseActionRegistry.register(PoseAction.of(
            REPAIR_ACTION,
            PoseSource.hold(ManiacMod.MOD_ID, "generator_repair").withFade(4, 6),
            PoseLayerId.ITEM_ACTION));

        PoseActionRegistry.register(PoseAction.of(
            REFUEL_ACTION,
            PoseSource.hold(ManiacMod.MOD_ID, "generator_refuel").withFade(4, 6),
            PoseLayerId.ITEM_ACTION));
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            playing.clear();
            return;
        }

        // 1. Хто працює ЗАРАЗ — повний список від сервера.
        List<UUID> handled = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : ClientMatchState.generatorInteractions()) {
            UUID id = entry.getKey();
            handled.add(id);

            AbstractClientPlayer player = findPlayer(level, id);
            if (player == null) continue;   // не в зоні видимості — нічого малювати

            int action = entry.getValue();
            if (playing.getOrDefault(id, ClientMatchState.GENERATOR_ACTION_NONE) == action) continue;

            String actionName = action == ClientMatchState.GENERATOR_ACTION_REFUEL
                ? REFUEL_ACTION : REPAIR_ACTION;
            // false = PAL ще не готовий; тоді НЕ запам'ятовуємо «граємо»,
            // щоб спробувати наступного тіка (контракт PlayerPoseController).
            if (PlayerPoseController.playAction(player, actionName)) {
                playing.put(id, action);
            }
        }

        // 2. Хто скінчив (або працює, але його не видно — нічого зупиняти,
        //    і наступного тіка він так само не потрапить у мапу, якщо
        //    справді скінчив).
        for (UUID id : new ArrayList<>(playing.keySet())) {
            if (handled.contains(id)) continue;
            playing.remove(id);
            AbstractClientPlayer player = findPlayer(level, id);
            if (player != null) {
                // Обидві дії — один шар, тож досить одного stopAction:
                // він зупиняє шар цілком, а не конкретний кліп.
                PlayerPoseController.stopAction(player, REPAIR_ACTION);
            }
        }
    }

    private static AbstractClientPlayer findPlayer(ClientLevel level, UUID id) {
        for (Player player : level.players()) {
            if (player.getUUID().equals(id) && player instanceof AbstractClientPlayer clientPlayer) {
                return clientPlayer;
            }
        }
        if (level.getPlayerByUUID(id) instanceof AbstractClientPlayer clientPlayer) {
            return clientPlayer;
        }
        return null;
    }

    /** Скидання стану поза матчем. */
    public static void reset() {
        playing.clear();
    }
}
