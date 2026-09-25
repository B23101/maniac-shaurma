package com.log_to_kot.maniacmod.items;

import com.log_to_kot.maniacmod.ManiacMod;
import dev.shaurmalib.common.lock.LockType;
import dev.shaurmalib.common.playeranim.PoseAction;
import dev.shaurmalib.common.playeranim.PoseActionRegistry;
import dev.shaurmalib.common.playeranim.PoseLayerId;
import dev.shaurmalib.common.playeranim.PoseSource;
import dev.shaurmalib.forge.inventory.InventorySlotAllocation;
import dev.shaurmalib.forge.playeranim.PlayerPoseController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Жива" дія предметом у ПЕРШОМУ (основному) слоті: тіло гравця веде
 * playerlib (шар {@link PoseLayerId#ITEM_ACTION}), а не GeckoLib-рендер
 * предмета в руці.
 *
 * ── Навіщо цей клас ──────────────────────────────────────────────────
 * Раніше кожен geo-предмет ({@link CrowbarItem}, {@link MedkitItem}...)
 * грав свою {@code idle}/{@code use} анімацію через власний
 * {@code AnimationController}, малюючи ЇЇ поверх ванільної руки-в-hand
 * (стандартна {@code GeoItemRenderer}-транформація). Модель гравця тим
 * часом лишалась повністю ванільною — жодна з цих анімацій ніяк не
 * узгоджена з позою тіла. У момент, коли на тіло треба накласти
 * playerlib-позу (наприклад "замах ломом", "піднести ліхтарик до
 * обличчя"), рука фізично рухається за позою, а предмет — за старою
 * ГеккоЛіб-траєкторією: вони розходяться.
 * <p>
 * Це навмисно НЕ додає нового {@code LockType} у бібліотеку: реєстр
 * {@code InteractionLockRegistry} блокує лише те, для чого нема
 * готового спеціалізованого механізму (рух/атака/use-tick/block-
 * interact/чат). Для "не дати перемкнути слот / не дати викинути
 * активний предмет" бібліотека вже має призначений і синхронізований з
 * клієнтом механізм — {@link InventorySlotAllocation} — його й
 * використовуємо тут, а не паралельний {@code LockType}.
 *
 * ── Що робить {@link #beginServer} + {@link #beginClient} ──────────────
 * <ol>
 *   <li>Сервер ({@link #beginServer}): замикає гравця на поточний
 *       hotbar-слот ({@link InventorySlotAllocation#setAllowedSlots})
 *       і забороняє скидання
 *       ({@link InventorySlotAllocation#setItemDropBlocked}) — так
 *       гравець фізично не може ані прокрутити колесо на інший предмет,
 *       ані натиснути Q, доки триває дія; обидві заборони
 *       синхронізуються на клієнт самим механізмом бібліотеки (курсор
 *       не "застряє" на недоступному слоті).</li>
 *   <li>Сервер: додатково лочить {@link LockType#MOVEMENT}, якщо
 *       {@code lockMovement=true} — той самий реєстр, яким уже
 *       користується фаза вибору кіта/каунтдаун, тож дві незалежні
 *       причини заморозки коректно співіснують (див. клас-докстрінг
 *       {@code InteractionLockRegistry}).</li>
 *   <li>Клієнт ({@link #beginClient}, кожен спостерігач, включно з
 *       власником): запускає {@link PoseAction} на
 *       {@link PoseLayerId#ITEM_ACTION} — positioning самого предмета в
 *       руці читає {@code HeldItemMixin} PlayerAnimator з кісток
 *       {@code rightItem}/{@code leftItem} файлу цієї ж анімації, тож
 *       художник розміщує предмет ОДИН РАЗ у {@code .json} кадрі, а не
 *       окремо в GeckoLib-моделі предмета.</li>
 * </ol>
 * {@link #endServer}/{@link #endClient} симетрично знімають усе.
 * {@code beginServer}/{@code endServer} ідемпотентні й безпечні до
 * виклику з мережевого пакета чи з тікового таймера
 * ({@link #beginServerTimed}) — початок/кінець анімації
 * синхронізуються як завжди (див. {@code ItemAnimPacket}).
 *
 * ── Що МАЄ зробити художник/мод, а не цей клас ──────────────────────
 * <ul>
 *   <li>Зареєструвати {@link PoseAction} для кожної дії при старті
 *       клієнта (одноразово, напр. у {@code ClientSetup}):
 *       <pre>
 *   PoseActionRegistry.register(PoseAction.of("maniacmod:crowbar_idle",
 *       PoseSource.hold("maniacmod", "crowbar_idle").withFade(4, 4),
 *       PoseLayerId.ITEM_ACTION));
 *       </pre></li>
 *   <li>Намалювати {@code assets/maniacmod/player_animations/crowbar_idle.json}
 *       (PlayerAnimator-формат, НЕ GeckoLib {@code .animation.json}) з
 *       позицією кісток {@code rightItem}/{@code rightArm}/{@code torso}.</li>
 *   <li>Прибрати {@code ItemGeoRenderer.attach(...)} для 1st/3rd-person
 *       hand-контекстів цього предмета (лишити geo-рендер лише для
 *       {@code GROUND}/{@code GUI}/{@code FIXED} — див.
 *       {@code ItemGeoRenderer} TODO щодо {@code ItemDisplayContext}).</li>
 * </ul>
 */
public final class LiveHeldItemAction {

    private LiveHeldItemAction() {}

    /** Причина блокування — одна на весь клас, дії цього типу не накладаються один на одного в одного гравця. */
    private static final String LOCK_REASON = "live_held_item_action";

    /**
     * Знімок набору слотів, дозволених рівнем "до" нашого звуження
     * (роль виживого/маньяка через {@code .withInventorySlotAllocation()}
     * у {@code ManiacMod}), по одному гравцю. Потрібен, бо в
     * {@code InventorySlotAllocation} немає публічного гетера "поточний
     * дозволений набір" — лише {@code isSlotAllowed(player, slot)} для
     * ОДНОГО слоту, тож знімок будуємо самі, переглянувши 0..8 ДО того,
     * як звузимо {@link #beginServer}.
     * <p>
     * Без цього снепшота {@link #endServer} мав би викликати
     * {@code InventorySlotAllocation.clear(player)} — а це прибирає
     * ГЛОБАЛЬНЕ обмеження ролі ("4 слоти у виживого, 0 у маньяка", яке
     * задає {@code .withInventorySlotAllocation()} у {@code ManiacMod}),
     * а не тільки наше тимчасове звуження до одного слоту. Гравець після
     * закінчення "живої" дії отримав би доступ до ВСІХ 9 хотбар-слотів
     * замість своїх штатних чотирьох — рівно той клас бага, заради
     * уникнення якого й веденться снепшот.
     */
    private static final Map<UUID, java.util.List<Integer>> PREVIOUS_SLOTS = new ConcurrentHashMap<>();

    /**
     * Починає "живу" дію: блокує слот/дроп (і, опційно, рух) на сервері.
     * Викликати з серверної гілки {@code ItemArchetype.onUse} (або
     * власного хука предмета без {@code ItemArchetype}) ПЕРЕД відправкою
     * клієнту сигналу почати позу.
     *
     * @param lockMovement {@code true} — гравець узагалі не рухається,
     *                     поки триває дія (напр. довга анімація ремонту
     *                     ломом на місці); {@code false} — дія дозволяє
     *                     рух під час програвання (напр. ліхтарик).
     */
    public static void beginServer(ServerPlayer player, boolean lockMovement) {
        UUID id = player.getUUID();
        // Знімок ДО звуження — саме той набір слотів, який на цю мить
        // дозволяла роль гравця (виживий/маньяк), а не жорстко "0..8":
        // якщо begin викликати повторно без end між ними (два предмети
        // швидко поспіль), повторний снепшот перезаписав би вже звужений
        // до одного слоту набір — тому пишемо лише коли снепшота ще нема.
        PREVIOUS_SLOTS.computeIfAbsent(id, k -> {
            java.util.List<Integer> snapshot = new java.util.ArrayList<>();
            for (int slot = 0; slot < 9; slot++) {
                if (InventorySlotAllocation.isSlotAllowed(player, slot)) snapshot.add(slot);
            }
            return snapshot;
        });

        int selected = player.getInventory().selected;
        InventorySlotAllocation.setAllowedSlots(player, java.util.List.of(selected));
        InventorySlotAllocation.setItemDropBlocked(player, true);
        if (lockMovement) {
            ManiacMod.lib().interactionLockModule().lock(id, LockType.MOVEMENT, LOCK_REASON);
        }
        PENDING_END.remove(id);
    }

    /**
     * Зручний варіант {@link #beginServer} для короткої, фіксованої за
     * тривалістю дії (напр. "бинтувати аптечку" — 2 секунди, після чого
     * гравець знову вільний). Планує {@link #endServer} через
     * {@code durationTicks} тіків самостійно — консюмеру не потрібен
     * власний лічильник/таймер для симетричного зняття блокувань.
     * <p>
     * Для дій, чия тривалість НЕ фіксована наперед (наприклад ремонт
     * генератора, що триває, доки гравець тримає ПКМ) використовуйте
     * {@link #beginServer}/{@link #endServer} напряму зі своєї
     * тік-логіки замість цього методу.
     */
    public static void beginServerTimed(ServerPlayer player, boolean lockMovement, int durationTicks) {
        beginServer(player, lockMovement);
        MinecraftServer server = player.getServer();
        long dueAtTick = (server != null ? server.getTickCount() : 0) + Math.max(1, durationTicks);
        PENDING_END.put(player.getUUID(), dueAtTick);
    }

    /**
     * Завершує "живу" дію: повертає слот/дроп-обмеження до стану, який
     * був ДО {@link #beginServer} (роль гравця — див. докстрінг
     * {@link #PREVIOUS_SLOTS}), і знімає, за потреби, заморозку руху.
     * Викликати і при природньому завершенні анімації, і при
     * примусовому перериванні (смерть, дисконект, зміна фази матчу) —
     * метод ідемпотентний: повторний виклик без відповідного
     * {@code beginServer} — no-op (снепшота нема, чіпати нічого).
     */
    public static void endServer(ServerPlayer player) {
        UUID id = player.getUUID();
        java.util.List<Integer> previous = PREVIOUS_SLOTS.remove(id);
        if (previous != null) {
            InventorySlotAllocation.setAllowedSlots(player, previous);
            InventorySlotAllocation.setItemDropBlocked(player, false);
        }
        ManiacMod.lib().interactionLockModule().unlock(id, LockType.MOVEMENT, LOCK_REASON);
        PENDING_END.remove(id);
    }

    // ── Таймер для beginServerTimed ─────────────────────────────────────

    /** playerUUID -> тік сервера, на якому треба викликати endServer. */
    private static final Map<UUID, Long> PENDING_END = new ConcurrentHashMap<>();

    /**
     * Тікає заплановані {@link #beginServerTimed} завершення.
     * <p>
     * Окремий, явно реєстрований клас, а не статичний
     * {@code @Mod.EventBusSubscriber} — той самий стиль, що
     * {@code ServerHooks}/{@code GroundItemHooks} у цьому проєкті
     * (реєстрація через {@code MinecraftForge.EVENT_BUS.register(new X())}
     * у {@code ManiacMod}, а не автопідписка класу): див.
     * докстрінг {@code ServerHooks} щодо того, чому тіковий код
     * централізовано реєструється, а не розкиданий по
     * {@code @Mod.EventBusSubscriber}-класах.
     * <p>
     * Реєструвати треба в {@code ManiacMod} поруч із рештою:
     * {@code MinecraftForge.EVENT_BUS.register(new LiveHeldItemAction.Ticker());}
     */
    public static final class Ticker {

        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END || PENDING_END.isEmpty()) return;
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return;
            long now = server.getTickCount();

            var it = PENDING_END.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                if (entry.getValue() > now) continue;
                it.remove();
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player != null) {
                    endServer(player);
                }
            }
        }
    }

    /**
     * Клієнтська частина: запускає playerlib-позу на власному
     * {@link AbstractClientPlayer}. Викликається на прийом мережевого
     * пакета (той самий, що раніше запускав лише GeckoLib {@code use}-
     * тригер) — для ВЛАСНОГО гравця з {@code Minecraft.player}; треті
     * особи бачать позу автоматично через {@code PlayerModelMixin} PAL,
     * без окремого виклику (див. клас-докстрінг {@code PlayerPoseController}).
     */
    @OnlyIn(Dist.CLIENT)
    public static boolean beginClient(String poseActionName) {
        AbstractClientPlayer player = Minecraft.getInstance().player;
        if (player == null) return false;
        return PlayerPoseController.playAction(player, poseActionName);
    }

    @OnlyIn(Dist.CLIENT)
    public static void endClient(String poseActionName) {
        AbstractClientPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        PlayerPoseController.stopAction(player, poseActionName);
    }

    /** Реєструє одну {@link PoseAction} за стандартною конвенцією іменування (modid:itemId_idle). Викликати з ClientSetup. */
    @OnlyIn(Dist.CLIENT)
    public static void registerIdlePose(String itemId) {
        PoseActionRegistry.register(PoseAction.of(
                ManiacMod.MOD_ID + ":" + itemId + "_idle",
                PoseSource.hold(ManiacMod.MOD_ID, itemId + "_idle").withFade(4, 4),
                PoseLayerId.ITEM_ACTION));
    }

    /** Реєструє одноразову {@link PoseAction} використання (modid:itemId_use). */
    @OnlyIn(Dist.CLIENT)
    public static void registerUsePose(String itemId) {
        PoseActionRegistry.register(PoseAction.of(
                ManiacMod.MOD_ID + ":" + itemId + "_use",
                PoseSource.oneShot(ManiacMod.MOD_ID, itemId + "_use").withFade(2, 4),
                PoseLayerId.ITEM_ACTION));
    }
}
