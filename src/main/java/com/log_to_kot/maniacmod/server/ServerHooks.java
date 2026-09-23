package com.log_to_kot.maniacmod.server;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.config.MapPointConfigs;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.s2c.matchstate.PhaseSyncPacket;
import dev.shaurmalib.forge.chat.ChatModule;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Міст між подіями Forge і матчем.
 *
 * ── Чому цей клас такий короткий ─────────────────────────────────────
 * v3 ServerEventHandler на 162 рядки робив усе одразу: перевіряв стан
 * гри, тікав пастки, синхронізував кулдаун щотік, перевіряв ремонт,
 * перевіряв втечу, міняв хітбокс. Кожна нова механіка дописувала туди
 * ще кілька рядків — і файл ставав другим центром логіки поряд із
 * ManiacGameManager.
 *
 * Тут лишається лише переадресація. Механіки реагують на фази у своїх
 * PhaseListener-ах і в цей файл не заглядають.
 */
public final class ServerHooks {

    /** Раз на секунду перевіряємо, чи не змінився конфіг на диску. */
    private static final int CONFIG_CHECK_INTERVAL_TICKS = 20;

    private int tickCounter = 0;

    /**
     * UUID гравців, чий /gamemode щойно змінився цього тіку — обробляється
     * на onServerTick НАСТУПНОГО тіку, коли player.isCreative() вже
     * відповідає новому режиму (див. коментар у onGameModeChange).
     */
    private final Set<UUID> pendingAllocationRecalc = ConcurrentHashMap.newKeySet();

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ManiacCommand.register(event.getDispatcher());
    }

    /**
     * Один рядок замість шести ручних тіків. Перевірки фази теж немає:
     * PhaseManager сам вирішує, кого будити.
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        // Автопідхоплення конфігу йде незалежно від матчу: адмін має
        // змогу правити yml і в лобі, і посеред гри.
        if (++tickCounter >= CONFIG_CHECK_INTERVAL_TICKS) {
            tickCounter = 0;
            ManiacConfigs.tickWatcher();
            if (MapPointConfigs.tickWatcher()) {
                MatchOrchestrator current = ManiacMod.match();
                if (current != null) current.reloadConfiguredMap();
            }
        }

        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;

        match.tick(event.getServer().getPlayerList().getPlayers());

        if (!pendingAllocationRecalc.isEmpty()) {
            for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
                if (pendingAllocationRecalc.remove(player.getUUID())) {
                    match.inventoryAllocation().applyOnJoin(player);
                }
            }
            // Гравець, що вийшов між зміною режиму і цим тіком (лишиться
            // в множині, бо цикл вище пройшов лише по онлайн-гравцях) —
            // прибираємо, щоб множина не текла для відключених UUID.
            pendingAllocationRecalc.clear();
        }
    }

    /**
     * Удар ЛКМ по будь-якій сутності. Тут вирішується, ХТО кого може бити.
     *
     * ── Правила (у порядку перевірки) ────────────────────────────────
     * 1. Б'є МАНЬЯК → ванільний удар скасовується завжди: подія тут
     *    ЛИШЕ гасить ванільну шкоду й замах. Реальний удар (хто саме
     *    постраждав, за якою дальністю) рахує {@code ManiacCombatModule
     *    #onAttack} окремо, за {@code ManiacStrikePacket} — НЕ за
     *    {@code event.getTarget()}. Причина: ванільна подія виникає
     *    лише коли клієнтський raytrace знайшов сутність у межах
     *    ванільного pick range (~3 блоки), а дальність архетипу може
     *    бути й більшою (до 8 блоків у конфізі) — тоді подія для
     *    далекої цілі просто ніколи не приходить. Див. докстрінг
     *    {@code ManiacCombatModule} для повного пояснення.
     * 2. Б'є ВИЖИВИЙ по КАПКАНУ → звільнення/знешкодження, див. нижче.
     * 3. Б'є ВИЖИВИЙ ЛОМОМ по МАНЬЯКУ → оглушення, той самий принцип
     *    ванільного скасування + делегування {@code ManiacStunModule}.
     *    Перевіряється ПІСЛЯ капкана (різні цілі, не перетинаються) і
     *    ОБОВ'ЯЗКОВО ДО правила 4 (загальний PvP-бан), інакше загальне
     *    правило перехопило б цей удар раніше, ніж дійде до цієї гілки.
     * 4. Б'є ВИЖИВИЙ по ГРАВЦЮ (виживий, маньяк чи будь-хто) →
     *    скасовується без винятків: PvP між тими, хто виживає, вимкнено.
     *    Раніше цього ніде не було — скасовувався лише удар маньяка, тож
     *    виживі спокійно били один одного ванільним {@code Player.attack}.
     * 5. Решта (виживий б'є генератор, предмет, мобів) — не наша справа,
     *    подію не чіпаємо.
     *
     * Це ПЕРШИЙ шар захисту від PvP. Другий — інтерцептор у
     * {@code ManiacMod} ({@code LivingHurtEvent}), який ловить шкоду, що
     * прийшла не через ЛКМ (снаряд, вибух, чужий мод).
     */
    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer attacker)) return;

        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;

        if (match.isManiac(attacker.getUUID())) {
            // Лише гасимо ванільний шлях — реальний удар обробляється
            // окремо в ServerPacketHandler.onManiacStrike, ініційованому
            // клієнтським ManiacStrikePacket (не цією подією).
            event.setCanceled(true);
            return;
        }

        // Виживий б'є ЛОМОМ по капкану — звільнення (своє чи тімейта) або
        // знешкодження. Ванільний удар скасовується завжди, коли ціль —
        // капкан: шкоди йому бути не повинно, а що сталось (зараховано чи
        // лом на перезарядці/зламаний), вирішує TrapModule.
        if (event.getTarget() instanceof com.log_to_kot.maniacmod.entity.BearTrapEntity trap
            && match.isSurvivor(attacker.getUUID())) {
            event.setCanceled(true);
            match.traps().onCrowbarHit(attacker, trap, attacker.getMainHandItem());
            return;
        }

        // Виживий б'є ЛОМОМ САМЕ ПО МАНЬЯКУ — оглушення. Той самий
        // принцип, що капкан-гілка вище: ванільний удар скасовується
        // завжди, коли ціль — маньяк, а дистанцію/кулдаун/знос вирішує
        // ManiacStunModule. Обов'язково ДО загального PvP-бана нижче,
        // інакше цей удар ніколи не дійшов би сюди.
        if (event.getTarget() instanceof ServerPlayer targetPlayer
            && match.isSurvivor(attacker.getUUID())
            && match.isManiac(targetPlayer.getUUID())) {
            event.setCanceled(true);
            match.maniacStun().onCrowbarHitManiac(attacker, targetPlayer, attacker.getMainHandItem());
            return;
        }

        // Не маньяк. Якщо ціль — гравець, це PvP: у грі його немає.
        // Перевіряємо роль ЦІЛІ, а не лише фазу: у лобі (де ролей ще
        // немає) звичайне ванільне поводження лишається на інтерцептору
        // і гейммоду ADVENTURE, який ставить sendToLobby.
        if (event.getTarget() instanceof ServerPlayer && match.isSurvivor(attacker.getUUID())) {
            event.setCanceled(true);
        }
    }

    /**
     * Синхронізація стану при вході.
     *
     * v3 цього не робив узагалі: гравець, що перезайшов посеред матчу,
     * лишався з порожнім клієнтським станом — без HUD, без ролі, і з
     * оверлеями від попередньої гри.
     *
     * ── Витік, знайдений і виправлений тут (AI_CODE_GUIDE.md, розділ 0) ──
     * До цієї правки жоден код мода не викликав ні
     * {@code lib.lobbyModule().sendToLobby(...)}, ні
     * {@code lib.playerLifecycleModule().applyJoin(...)}/{@code applyReturn(...)}.
     * Обидва лишались "готовими, але не підключеними" фасадами
     * shaurma-lib: сам {@code LobbyModule} явно документує, що консюмер
     * має викликати {@code sendToLobby} сам, "точно в тому місці, де
     * оригінал зараз викликає власний sendToLobby(player)" — цього
     * місця в maniacmod просто не було. Наслідки саме ті, що
     * спостерігались: гравця не телепортує в лобі й не переводить у
     * ADVENTURE, тож лишається дефолтний SURVIVAL із ванільною
     * регенерацією хп/їжі/досвіду, і гравці в лобі не безсмертні (нема
     * інвалідації урону поза матчем).
     */
    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;

        var lib = ManiacMod.lib();
        boolean hadRole = match.isManiac(player.getUUID()) || match.isSurvivor(player.getUUID());

        if (!match.phases().isGameplay()) {
            // Немає активного матчу (LOBBY/CINEMATIC/SCATTER/ROLE_REVEAL
            // ще не встиг призначити ролі/ENDING/RESET) — гравець, що
            // заходить, завжди йде в лобі-точку тим самим шляхом, яким
            // ішов в оригіналі snipers_shaurma.
            lib.lobbyModule().sendToLobby(player);
        } else if (hadRole) {
            // Був у цьому матчі (маньяк ніколи не видаляється зі складу
            // при виході; виживий видаляється лише при остаточному
            // вибутті — див. MatchOrchestrator.onPlayerLeft) і матч ще
            // йде — це повернення посеред гри, а не новий вхід.
            lib.playerLifecycleModule().applyReturn(player, true);
        } else {
            // Новий гравець зайшов, поки матч уже йде — глядач за
            // сконфігурованою JoinPolicy (SPECTATE).
            lib.playerLifecycleModule().applyJoin(player);
        }

        lib.lobbyModule().hideNameTag(player);

        ModNetwork.toPlayer(player, new PhaseSyncPacket(match.phases().current()));
        ModNetwork.toPlayer(player, match.roleSyncFor(player));
        ModNetwork.toPlayer(player, new com.log_to_kot.maniacmod.net.s2c.loot.GroundItemVisualSettingsPacket(
            com.log_to_kot.maniacmod.config.ManiacConfigs.get(
                com.log_to_kot.maniacmod.config.ConfigSchema.GROUND_ITEM_SPARKLE_ENABLED)));
        // Кнопки каналів у чаті залежать від ролі/фази — надсилаємо одразу,
        // щоб гравець, що зайшов посеред матчу, не чекав наступного переходу.
        ChatModule.syncChannels(player);
        sendSettingsButtonIfOperator(player);
        match.inventoryAllocation().applyOnJoin(player);
        // Новий гравець у таб-екрані для всіх, і всі вже присутні —
        // у табі гравця, що щойно зайшов.
        broadcastRoster(event.getEntity().getServer().getPlayerList().getPlayers());
    }

    /**
     * Вихід гравця. Сам матч вирішує, чи це вибуття, чи тимчасова
     * відсутність — тут лише повідомлення.
     */
    @SubscribeEvent
    public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;

        match.onPlayerLeft(player);
        // player.getServer() тут уже може не містити гравця, що
        // виходить, — це саме те, що потрібно табу решти.
        var server = player.getServer();
        if (server != null) broadcastRoster(server.getPlayerList().getPlayers());
    }

    /**
     * Витік, знайдений при роботі над цією ж правкою (AI_CODE_GUIDE.md,
     * розділ 0): {@code InventoryAllocationModule} перераховує кількість
     * дозволених слотів лише на вході фази й на вході гравця
     * ({@code applyOnJoin}) — а сам {@code InventorySlotAllocation}
     * звільняє гравця в CREATIVE/SPECTATOR від будь-яких обмежень
     * ({@code isExemptFromAllocation}). Разом це означає: перемикання
     * ADVENTURE → CREATIVE знімає обмеження (правильно), але перемикання
     * НАЗАД у CREATIVE → ADVENTURE/SURVIVAL нічого не перераховує, доки
     * не станеться наступний перехід фази чи релогін — гравець лишається
     * без обмеження слотів посеред матчу.
     * <p>
     * {@code PlayerChangeGameModeEvent} — це PRE-подія: на момент її
     * виклику {@code player.getGameMode()}/{@code isCreative()} ще
     * повертають СТАРИЙ режим (нове значення застосовується мотором
     * Forge вже ПІСЛЯ диспетчеризації, лише якщо подію не скасовано —
     * https://github.com/MinecraftForge/MinecraftForge/issues/8439).
     * {@code applyOnJoin} читає {@code player.isCreative()} напряму,
     * тому викликати його синхронно тут перерахувало б за старим
     * режимом. Тому лише плануємо перерахунок на НАСТУПНИЙ тік
     * сервера — на той момент режим уже застосований.
     */
    @SubscribeEvent
    public void onGameModeChange(PlayerEvent.PlayerChangeGameModeEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.isCanceled()) return;

        pendingAllocationRecalc.add(player.getUUID());
    }

    /**
     * Сутність зі старого матчу може бути в чанку, який завантажиться вже
     * після старту сервера. Persistent-маркер ловить її тут і не дає їй
     * повернутися на карту.
     */
    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel)) return;
        if (!com.log_to_kot.maniacmod.core.match.MatchRuntimeRegistry
                .isRegistered(event.getEntity())) return;

        MatchOrchestrator match = ManiacMod.match();
        if (match == null || match.phases().is(com.log_to_kot.maniacmod.core.phase.GamePhase.LOBBY)) {
            event.setCanceled(true);
            event.getEntity().discard();
        }
    }

    /**
     * Падіння з висоти. SurvivorModule сам вирішує, чи висота достатня
     * (fallKnockdownHeightBlocks) і чи ламається нога (legBreakChanceFor — за висотою);
     * тут лише переадресація й скасування ванільного урону від
     * падіння, коли модуль підтвердив, що обробив його сам.
     *
     * Окремо від DamageInterceptorRegistry: LivingFallEvent — не
     * LivingHurtEvent, тому загальна "ванільний урон вимкнено на весь
     * матч" гвардія його не ловить — цей хук закриває саме цю прогалину.
     */
    @SubscribeEvent
    public void onLivingFall(net.minecraftforge.event.entity.living.LivingFallEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;

        boolean handled = match.survivors().onFall(player, event.getDistance());
        if (handled) event.setCanceled(true);
    }

    // TODO(міграція maniacs): EntityEvent.Size → хітбокс і висота очей
    //   з архетипу маньяка (v3 ServerEventHandler.onEntitySize).

    // ── Допоміжне ────────────────────────────────────────────────────────

    /**
     * Кнопка "⚙ Налаштування" у ВАНІЛЬНОМУ системному чаті (не через
     * власний {@code ChatModule}/{@code ManiacChatEntryRenderer}, який
     * малює лише текст лінії без clickEvent-ів — див. окремий
     * докстрінг-нотатку в {@code SettingsMenuScreen}). Надсилається
     * лише оператору ({@code hasPermissions(2)} — той самий рівень, що
     * корінь команди {@code /maniac} і {@link ManiacCommand#openSettings}),
     * один раз при вході, а не щоразу — гравець сам вирішує, коли
     * натиснути, кнопка не набридає повторним нагадуванням.
     */
    private static void sendSettingsButtonIfOperator(ServerPlayer player) {
        if (!player.hasPermissions(2)) return;

        net.minecraft.network.chat.MutableComponent button =
            net.minecraft.network.chat.Component.translatable("maniacmod.chat.settings_button")
                .withStyle(style -> style
                    .withColor(net.minecraft.ChatFormatting.GOLD)
                    .withUnderlined(true)
                    .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                        net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, "/maniac settings"))
                    .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                        net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                        net.minecraft.network.chat.Component.translatable("maniacmod.chat.settings_button_hover"))));

        player.sendSystemMessage(button);
    }

    /** Розсилає фазу всім — викликається матчем при кожному переході. */
    public static void broadcastPhase(List<ServerPlayer> players,
                                      com.log_to_kot.maniacmod.core.phase.GamePhase phase) {
        ModNetwork.toPlayers(players, new PhaseSyncPacket(phase));
        // Рольові канали чату відкриваються/закриваються разом з фазою
        // (технічні фази — спільний канал лобі, ігрові — канал ролі).
        ChatModule.syncChannels(players);
    }

    /**
     * Розсилає зведення по всіх гравцях для tab-екрана. Викликається на
     * ПОДІЮ (зміна фази/ролі/стану виживого/hp), не щотік — той самий
     * принцип, що вже описаний у {@code net/README.md} для
     * SurvivorVitalsPacket.
     */
    public static void broadcastRoster(List<ServerPlayer> players) {
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;
        ModNetwork.toPlayers(players,
            new com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket(match.rosterEntries(players)));
    }
}
