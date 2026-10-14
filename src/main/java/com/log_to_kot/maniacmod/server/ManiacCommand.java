package com.log_to_kot.maniacmod.server;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.config.ConfigDiagnostics;
import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.config.MapPointConfigs;
import com.log_to_kot.maniacmod.config.DarkZoneConfigs;
import com.log_to_kot.maniacmod.map.zones.DarkZonePolygon;
import com.log_to_kot.maniacmod.core.match.DebugMode;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.maniacs.ManiacArchetype;
import com.log_to_kot.maniacmod.maniacs.ManiacRegistry;
import com.log_to_kot.maniacmod.maniacs.ManiacSelection;
import com.log_to_kot.maniacmod.spawn.SpawnPlanner;
import com.log_to_kot.maniacmod.spawn.SpawnPoint;
import com.log_to_kot.maniacmod.spawn.SpawnPointKind;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

/**
 * Адмінська команда {@code /maniac}.
 *
 * ── Структура ────────────────────────────────────────────────────────
 *   /maniac start [маньяк]        — старт матчу
 *   /maniac stop                  — примусове завершення
 *   /maniac debug                 — увімкнути/вимкнути дебаг-режим
 *   /maniac debug role <auto|maniac|survivor> — роль для дебаг-старту
 *   /maniac debug traps           — дебаг: примусово відкрити TrapChooseScreen виконавцю
 *   /maniac debug unmorph         — дебаг: зняти роль маньяка з виконавця в БУДЬ-ЯКІЙ фазі (не лише лобі)
 *   /maniac debug strike [гравець]     — дебаг: удар маньяка по гравцю (шкода + віддача + ефект екрана)
 *   /maniac debug strike fx [гравець]  — те саме, але без шкоди (лише віддача й ефект екрана)
 *   /maniac morph <maniac|survivor|reset> — дебаг-перетворення в лобі
 *   /maniac phase <фаза>          — ручний перехід (налагодження)
 *   /maniac status                — хто в якій ролі, яка фаза, чи готова карта
 *   /maniac hp [гравець]          — хп/стан виживого(-их)
 *   /maniac hp set <гравець> <0-100> — дебаг: виставити хп у % (0 = downed)
 *   /maniac generators complete   — дебаг: миттєво полагодити всі генератори
 *   /maniac win <maniac|survivors> — примусово завершити матч перемогою маніяка (сцена «гачки з тілами»)
 *                                    або виживих (усі лишені «втікають»), далі екран підсумків
 *   /maniac revive <гравець>      — дебаг: підняти непритомного гравця (як інший гравець підняв)
 *   /maniac hook spawn|hang <гравець>|clear — дебаг гаків (тестовий гак, повісити, прибрати)
 *   /maniac lobby set             — точка лобі = позиція виконавця
 *   /maniac point add <вид> [id]  — розмітити точку на своїй позиції
 *   /maniac point list            — скільки яких точок розмічено
 *   /maniac point clear           — стерти розмітку
 *   /maniac point add receiver    — точка приймача (дивись, КУДИ має дивитись приймач; yaw = твій)
 *   /maniac point add hook        — точка гака (дивись, КУДИ має «дивитись» гак; жертва висітиме обличчям туди)
 *   /maniac clearing create <n>   — нова галявина (зона запуску ракети)
 *   /maniac clearing corner <n>   — додати кут (рівно два, діагональ прямокутника; Y не важить)
 *   /maniac clearing heli <n>     — точка, над якою висітиме вертоліт (всередині прямокутника)
 *   /maniac clearing reset|remove|list|clear — керування галявинами
 *   /maniac heli entry [clear]    — у який бік від галявини вертоліт з’являється (напрям)
 *
 * ── Чому команда коротка ─────────────────────────────────────────────
 * v3 ManiacCommand — 526 рядків, бо кожен тип зони мав власну гілку з
 * власним парсингом координат і власним повідомленням. Тут усі точки
 * однієї форми (SpawnPoint), тому гілка одна, а вид точки — аргумент.
 * Додати новий вид точки = додати значення в SpawnPointKind.
 */
public final class ManiacCommand {

    private static final java.util.Random RNG = new java.util.Random();

    private ManiacCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("maniac")
            .requires(src -> src.hasPermission(2));
        // /maniac start        — звичайний старт (маньяк обирається конфігом)
        // /maniac start <нік>  — маньяк = вказаний гравець (і в дебазі теж)

        root.then(Commands.literal("start")
            .executes(ctx -> start(ctx.getSource(), null))
            .then(Commands.argument("maniac", EntityArgument.player())
                .executes(ctx -> start(ctx.getSource(),
                    EntityArgument.getPlayer(ctx, "maniac")))));

        root.then(Commands.literal("stop")
            .executes(ctx -> stop(ctx.getSource())));

        root.then(Commands.literal("reload")
            .executes(ctx -> reloadConfig(ctx.getSource())));

        // /maniac reviews — відкриває екран відгуків (хто що поставив + коментар);
        // з консолі (немає кому показати екран) — текстове зведення.
        // /maniac reviews chat — те саме зведення текстом у чаті.
        root.then(Commands.literal("reviews")
            .executes(ctx -> openReviews(ctx.getSource()))
            .then(Commands.literal("chat")
                .executes(ctx -> reviews(ctx.getSource()))));

        root.then(Commands.literal("settings")
            .executes(ctx -> openSettings(ctx.getSource())));

        // /maniac history          — архів ВСІХ матчів (екран; адмін бачить усе)
        // /maniac history <гравець> — особиста історія вказаного гравця
        root.then(Commands.literal("history")
            .executes(ctx -> openHistory(ctx.getSource(), null))
            .then(Commands.argument("player", EntityArgument.player())
                .executes(ctx -> openHistory(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))));

        // ── /maniac debug ────────────────────────────────────────────────
        // Піддерево дебаг-інструментів, згруповане в одному місці за
        // єдиним стилем (кожна гілка = один сценарій тестування, коротке
        // "чому" у docstring відповідного private-методу):
        //   /maniac debug                       — статус (нічого не змінює)
        //   /maniac debug on|off                 — явно увімк./вимк. (без вгадування з toggle)
        //   /maniac debug role <auto|maniac|survivor>
        //   /maniac debug swap maniac [гравець]  — гаряче перетворення, БУДЬ-ЯКА фаза, без spectator
        //   /maniac debug swap survivor [гравець]
        //   /maniac debug traps
        //   /maniac debug stun [гравець]         — оглушити маніяка як ударом лома
        //   /maniac debug strike [fx] [гравець]  — удар маніяка по гравцю (fx — без шкоди)
        //   /maniac debug unmorph [гравець]      — зняти роль, БУДЬ-ЯКА фаза
        root.then(Commands.literal("debug")
            .executes(ctx -> debugStatus(ctx.getSource()))
            .then(Commands.literal("on")
                .executes(ctx -> setDebug(ctx.getSource(), true)))
            .then(Commands.literal("off")
                .executes(ctx -> setDebug(ctx.getSource(), false)))
            .then(Commands.literal("role")
                .then(Commands.argument("role", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        for (DebugMode.Role role : DebugMode.Role.values()) {
                            builder.suggest(role.name().toLowerCase());
                        }
                        return builder.buildFuture();
                    })
                    .executes(ctx -> setDebugRole(ctx.getSource(),
                        StringArgumentType.getString(ctx, "role")))))
            .then(Commands.literal("swap")
                .then(Commands.literal("maniac")
                    .executes(ctx -> debugSwapManiac(ctx.getSource(),
                        ctx.getSource().getEntity() instanceof ServerPlayer p ? p : null))
                    .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> debugSwapManiac(ctx.getSource(),
                            EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("survivor")
                    .executes(ctx -> debugSwapSurvivor(ctx.getSource(),
                        ctx.getSource().getEntity() instanceof ServerPlayer p ? p : null))
                    .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> debugSwapSurvivor(ctx.getSource(),
                            EntityArgument.getPlayer(ctx, "player"))))))
            .then(Commands.literal("traps")
                .executes(ctx -> debugTraps(ctx.getSource())))
            .then(Commands.literal("stun")
                .executes(ctx -> debugStun(ctx.getSource(), null))
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(ctx -> debugStun(ctx.getSource(),
                        EntityArgument.getPlayer(ctx, "player")))))
            .then(Commands.literal("strike")
                .executes(ctx -> debugStrike(ctx.getSource(), null, true))
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(ctx -> debugStrike(ctx.getSource(),
                        EntityArgument.getPlayer(ctx, "player"), true)))
                .then(Commands.literal("fx")
                    .executes(ctx -> debugStrike(ctx.getSource(), null, false))
                    .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> debugStrike(ctx.getSource(),
                            EntityArgument.getPlayer(ctx, "player"), false)))))
            .then(Commands.literal("unmorph")
                .executes(ctx -> debugUnmorph(ctx.getSource(),
                    ctx.getSource().getEntity() instanceof ServerPlayer p ? p : null))
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(ctx -> debugUnmorph(ctx.getSource(),
                        EntityArgument.getPlayer(ctx, "player"))))));

        // /maniac morph — лобі-механіка "хто ким буде до старту матчу"
        // (не дебаг-інструмент mid-match: для гарячого перетворення
        // посеред матчу є /maniac debug swap).
        root.then(Commands.literal("morph")
            .then(Commands.literal("maniac")
                .executes(ctx -> morphManiac(ctx.getSource(),
                    ctx.getSource().getEntity() instanceof ServerPlayer p ? p : null)))
            .then(Commands.literal("survivor")
                .executes(ctx -> morphSurvivor(ctx.getSource(),
                    ctx.getSource().getEntity() instanceof ServerPlayer p ? p : null)))
            .then(Commands.literal("reset")
                .executes(ctx -> unmorph(ctx.getSource(),
                    ctx.getSource().getEntity() instanceof ServerPlayer p ? p : null))));

        root.then(Commands.literal("status")
            .executes(ctx -> status(ctx.getSource())));

        // /maniac hp                          — хп/стан УСІХ виживих
        // /maniac hp <гравець>                — хп/стан ОДНОГО виживого
        // /maniac hp set <гравець> <0-100>    — дебаг: виставити хп у %
        root.then(Commands.literal("hp")
            .executes(ctx -> hp(ctx.getSource(), null))
            .then(Commands.literal("set")
                .then(Commands.argument("survivor", EntityArgument.player())
                    .then(Commands.argument("percent", IntegerArgumentType.integer(0, 100))
                        .executes(ctx -> setHp(ctx.getSource(),
                            EntityArgument.getPlayer(ctx, "survivor"),
                            IntegerArgumentType.getInteger(ctx, "percent"))))))
            .then(Commands.argument("survivor", EntityArgument.player())
                .executes(ctx -> hp(ctx.getSource(),
                    EntityArgument.getPlayer(ctx, "survivor")))));

        // /maniac generators complete — дебаг: миттєво полагодити ВСІ генератори матчу
        root.then(Commands.literal("generators")
            .then(Commands.literal("complete")
                .executes(ctx -> completeGenerators(ctx.getSource()))));

        // /maniac win <maniac|survivors> — примусове завершення матчу з перемогою вказаної сторони.
        // Іде звичайним шляхом кінця матчу: перемога маніяка → сцена VICTORY_SCENE (якщо розмічена)
        // → підсумки; перемога виживих → анонси втечі → підсумки з титулом «ВИЖИВІ ПЕРЕМОГЛИ».
        root.then(Commands.literal("win")
            .then(Commands.literal("maniac")
                .executes(ctx -> forceWin(ctx.getSource(), false)))
            .then(Commands.literal("survivors")
                .executes(ctx -> forceWin(ctx.getSource(), true)))
            .then(Commands.literal("survivor")
                .executes(ctx -> forceWin(ctx.getSource(), true))));

        // /maniac revive <гравець> — дебаг: підняти непритомного гравця
        // без другого гравця, що фізично тримає ПКМ 8 секунд.
        root.then(Commands.literal("revive")
            .then(Commands.argument("survivor", EntityArgument.player())
                .executes(ctx -> revive(ctx.getSource(),
                    EntityArgument.getPlayer(ctx, "survivor")))));

        // /maniac hook ... — дебаг гаків без матчу й без розмітки:
        //   hook spawn           — тестовий гак на моїй позиції, «обличчям» туди, куди я дивлюсь
        //   hook hang <гравець>  — повісити непритомного (/maniac hp set <гравець> 0) на найближчий гак
        //   hook clear           — прибрати всі гаки (і зняти тих, хто висить)
        // Положення жертви підганяється в hooks.yml (hangForward/hangSide/hangUp/hangYawDegrees)
        // і підхоплюється на льоту — повторювати команду не треба.
        root.then(Commands.literal("hook")
            .then(Commands.literal("spawn")
                .executes(ctx -> hookSpawn(ctx.getSource())))
            .then(Commands.literal("hang")
                .then(Commands.argument("survivor", EntityArgument.player())
                    .executes(ctx -> hookHang(ctx.getSource(),
                        EntityArgument.getPlayer(ctx, "survivor")))))
            .then(Commands.literal("clear")
                .executes(ctx -> hookClear(ctx.getSource()))));

        root.then(Commands.literal("phase")
            .then(Commands.argument("phase", StringArgumentType.word())
                .suggests((ctx, builder) -> {
                    for (GamePhase phase : GamePhase.values()) {
                        builder.suggest(phase.name().toLowerCase());
                    }
                    return builder.buildFuture();
                })
                .executes(ctx -> setPhase(ctx.getSource(),
                    StringArgumentType.getString(ctx, "phase")))));

        root.then(Commands.literal("lobby")
            .then(Commands.literal("set")
                .executes(ctx -> setLobby(ctx.getSource()))));

        root.then(Commands.literal("point")
            .then(Commands.literal("add")
                .then(Commands.argument("kind", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        for (SpawnPointKind kind : SpawnPointKind.values()) {
                            builder.suggest(kind.name().toLowerCase());
                        }
                        return builder.buildFuture();
                    })
                    .executes(ctx -> addPoint(ctx.getSource(),
                        StringArgumentType.getString(ctx, "kind"), null))
                    .then(Commands.argument("owner", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            ManiacRegistry.all().keySet().forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .executes(ctx -> addPoint(ctx.getSource(),
                            StringArgumentType.getString(ctx, "kind"),
                            StringArgumentType.getString(ctx, "owner"))))))
            .then(Commands.literal("list")
                .executes(ctx -> listPoints(ctx.getSource())))
            .then(Commands.literal("remove")
                .then(Commands.argument("kind", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        for (SpawnPointKind kind : SpawnPointKind.values()) {
                            builder.suggest(kind.name().toLowerCase());
                        }
                        return builder.buildFuture();
                    })
                    .then(Commands.argument("index", IntegerArgumentType.integer(0))
                        .executes(ctx -> removePoint(ctx.getSource(),
                            StringArgumentType.getString(ctx, "kind"),
                            IntegerArgumentType.getInteger(ctx, "index"))))))
            .then(Commands.literal("clear")
                .executes(ctx -> clearPoints(ctx.getSource()))));

        // ── /maniac clearing, /maniac heli ──────────────────────────────
        // Галявини евакуації: два кути (Y не важить — контур сам лягає на землю) і точка
        // зависання вертольота. Усе командами на місці, без ручних координат.
        root.then(Commands.literal("clearing")
            .then(Commands.literal("create")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> clearingCreate(ctx.getSource(),
                        StringArgumentType.getString(ctx, "name")))))
            .then(Commands.literal("corner")
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests(ManiacCommand::suggestClearings)
                    .executes(ctx -> clearingCorner(ctx.getSource(),
                        StringArgumentType.getString(ctx, "name")))))
            .then(Commands.literal("heli")
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests(ManiacCommand::suggestClearings)
                    .executes(ctx -> clearingHeli(ctx.getSource(),
                        StringArgumentType.getString(ctx, "name")))))
            .then(Commands.literal("reset")
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests(ManiacCommand::suggestClearings)
                    .executes(ctx -> clearingSimple(ctx.getSource(),
                        StringArgumentType.getString(ctx, "name"), "reset"))))
            .then(Commands.literal("remove")
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests(ManiacCommand::suggestClearings)
                    .executes(ctx -> clearingSimple(ctx.getSource(),
                        StringArgumentType.getString(ctx, "name"), "remove"))))
            .then(Commands.literal("list")
                .executes(ctx -> clearingList(ctx.getSource())))
            .then(Commands.literal("clear")
                .executes(ctx -> {
                    com.log_to_kot.maniacmod.config.ClearingConfigs.clearAll();
                    ctx.getSource().sendSuccess(() -> Component.translatable(
                        "maniacmod.command.clearing_cleared"), true);
                    return 1;
                })));
        root.then(Commands.literal("heli")
            .then(Commands.literal("entry")
                .executes(ctx -> heliEntry(ctx.getSource(), false))
                .then(Commands.literal("clear")
                    .executes(ctx -> heliEntry(ctx.getSource(), true)))));

        // ── /maniac replay ──────────────────────────────────────────────
        // Replay-ролики кіно-вступу: точки з позиції адміна, інтервали, прев'ю.
        root.then(com.log_to_kot.maniacmod.intro.IntroReplayCommand.build());

        // ── /maniac darkzone ────────────────────────────────────────────
        // Розмітка темних зон: 1..6+ точок командою на поточній позиції
        // виконавця, БЕЗ ручного вписування координат. Y не враховується
        // (див. DarkZonePolygon) — зона діє по всій висоті стовпця XZ.
        //   /maniac darkzone create <name>      — нова порожня зона
        //   /maniac darkzone point <name>       — додати поточну позицію
        //   /maniac darkzone list                — які зони і скільки точок
        //   /maniac darkzone remove <name>       — видалити зону цілком
        //   /maniac darkzone clear               — стерти всю розмітку
        root.then(Commands.literal("darkzone")
            .then(Commands.literal("create")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> darkZoneCreate(ctx.getSource(),
                        StringArgumentType.getString(ctx, "name")))))
            .then(Commands.literal("point")
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        for (DarkZonePolygon zone : DarkZoneConfigs.snapshot()) {
                            builder.suggest(zone.name());
                        }
                        return builder.buildFuture();
                    })
                    .executes(ctx -> darkZonePoint(ctx.getSource(),
                        StringArgumentType.getString(ctx, "name")))))
            .then(Commands.literal("list")
                .executes(ctx -> darkZoneList(ctx.getSource())))
            .then(Commands.literal("remove")
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        for (DarkZonePolygon zone : DarkZoneConfigs.snapshot()) {
                            builder.suggest(zone.name());
                        }
                        return builder.buildFuture();
                    })
                    .executes(ctx -> darkZoneRemove(ctx.getSource(),
                        StringArgumentType.getString(ctx, "name")))))
            .then(Commands.literal("clear")
                .executes(ctx -> darkZoneClear(ctx.getSource()))));

        // ── /maniac barricade ──────────────────────────────────────────
        // Закриті зони: дошки, які виживі ламають ломом. Деталі — barricade/README.md.
        root.then(com.log_to_kot.maniacmod.barricade.BarricadeCommand.build());

        dispatcher.register(root);

        // Аліаси /startgame і /stopgame — окремі корені дерева команд,
        // ті самі callback-и, що /maniac start і /maniac stop. Додано
        // на прохання: адмінам звичніше коротке /startgame, ніж повне
        // /maniac start.
        dispatcher.register(Commands.literal("startgame")
            .requires(src -> src.hasPermission(2))
            .executes(ctx -> start(ctx.getSource(), null))
            .then(Commands.argument("maniac", EntityArgument.player())
                .executes(ctx -> start(ctx.getSource(),
                    EntityArgument.getPlayer(ctx, "maniac")))));

        dispatcher.register(Commands.literal("stopgame")
            .requires(src -> src.hasPermission(2))
            .executes(ctx -> stop(ctx.getSource())));
    }

    // ── Матч ─────────────────────────────────────────────────────────────

    private static int start(CommandSourceStack source, ServerPlayer chosenManiac) {
        MatchOrchestrator match = requireMatch(source);
        if (match == null) return 0;

        var players = source.getServer().getPlayerList().getPlayers();
        var participants = match.eligiblePlayers(players);

        // Дебаг: один гравець — вже матч (DebugMode). Перевірка тут, а не
        // тільки всередині match.start, щоб адмін бачив ту саму межу, за
        // якою старт відмовляється працювати.
        boolean debug = DebugMode.enabled();
        int minPlayers = debug ? 1 : ManiacConfigs.get(ConfigSchema.MIN_PLAYERS);
        if (participants.size() < minPlayers) {
            source.sendFailure(Component.translatable("maniacmod.command.need_players", minPlayers));
            return 0;
        }

        // ── Дебаг «ти виживий» ────────────────────────────────────────
        // Маньяка немає взагалі, тому реєстр маньяків не потрібен: сенс
        // режиму саме в тому, щоб перевіряти все, що не залежить від
        // маньяка (слоти, стаміна, генератори, лут).
        if (debug && DebugMode.role() == DebugMode.Role.SURVIVOR) {
            if (!match.start(players, null, null)) {
                sendStartFailed(source, match);
                return 0;
            }
            source.sendSuccess(() -> Component.translatable("maniacmod.command.started_debug_survivor"), true);
            return 1;
        }

        // Порожній реєстр — нормальний стан під час розробки, тому
        // окреме зрозуміле повідомлення замість падіння. У дебазі
        // «ти маньяк» він теж не завада: архетип лишиться null, і
        // гравець обере персонажа сам у фазі ROLE_REVEAL.
        if (ManiacRegistry.isEmpty() && !debug) {
            source.sendFailure(Component.translatable("maniacmod.command.no_maniacs"));
            return 0;
        }

        // ── Дебаг «ти маньяк» ─────────────────────────────────────────
        // Маньяк = виконавець команди (або нік в аргументі), виживих
        // може не бути взагалі.
        if (debug && DebugMode.role() == DebugMode.Role.MANIAC) {
            ServerPlayer requested = chosenManiac;
            if (requested == null && source.getEntity() instanceof ServerPlayer self) requested = self;
            final ServerPlayer maniac = (requested == null || !participants.contains(requested))
                ? participants.get(0) : requested;

            ManiacSelection.Choice debugChoice = ManiacRegistry.isEmpty()
                ? null : ManiacSelection.choose(participants, maniac, RNG);
            ManiacArchetype archetype = debugChoice == null ? null : debugChoice.archetype();
            if (debugChoice != null && debugChoice.needsMenu()) archetype = null;

            if (!match.start(players, maniac, archetype)) {
                sendStartFailed(source, match);
                return 0;
            }
            source.sendSuccess(() -> Component.translatable("maniacmod.command.started",
                maniac.getName().getString()), true);
            return 1;
        }

        ManiacSelection.Choice choice = ManiacSelection.choose(
            participants, chosenManiac, RNG);
        if (choice == null) {
            source.sendFailure(Component.translatable("maniacmod.command.no_maniacs"));
            return 0;
        }

        // Режим MENU: архетип null — гравець обере сам у фазі ROLE_REVEAL.
        if (!match.start(players, choice.player(), choice.archetype())) {
            sendStartFailed(source, match);
            return 0;
        }

        source.sendSuccess(() -> Component.translatable("maniacmod.command.started",
            choice.player().getName().getString()), true);
        return 1;
    }

    /**
     * «Не вдалося почати матч» + конкретна причина, якщо це розмітка карти
     * (нестача точок, надто мало точок генераторів тощо).
     */
    private static void sendStartFailed(CommandSourceStack source, MatchOrchestrator match) {
        source.sendFailure(Component.translatable("maniacmod.command.start_failed"));
        String reason = match.lastStartFailure();
        if (reason != null) {
            source.sendFailure(Component.translatable("maniacmod.command.start_failed_reason", reason));
        }
    }

    /**
     * Примусове перезавантаження конфігу. Зазвичай не потрібне —
     * зміна файлу підхоплюється сама протягом секунди. Команда
     * лишається, бо після неї одразу видно ПОВНИЙ список зауважень.
     */
    /** {@code /maniac reviews} — екран гравцю, текст консолі. */
    private static int openReviews(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return reviews(source);
        }
        com.log_to_kot.maniacmod.server.ServerPacketHandler.onAdminReviewsRequest(player);
        return 1;
    }

    /**
     * Зведення оцінок маньяків. Читає файли з диска (а не кеш): команда
     * рідкісна, а свіжі відгуки адміну потрібні саме зараз.
     */
    private static int reviews(CommandSourceStack source) {
        var all = com.log_to_kot.maniacmod.server.rating.ManiacRatingStorage.loadAll();
        var summary = com.log_to_kot.maniacmod.server.rating.ManiacRatingService.summarize(all);

        source.sendSuccess(() -> Component.translatable("maniacmod.command.reviews.header", all.size()), false);
        for (var row : summary) {
            boolean game = com.log_to_kot.maniacmod.net.s2c.rating.ManiacRatingSyncPacket.GAME_ID.equals(row.maniacId());
            String name = game ? Component.translatable("maniacmod.rating.tab.game").getString()
                : ManiacRegistry.exists(row.maniacId())
                    ? ManiacRegistry.get(row.maniacId()).displayName() : row.maniacId();
            if (row.voters() == 0) {
                source.sendSuccess(() -> Component.translatable("maniacmod.command.reviews.none", name), false);
            } else {
                source.sendSuccess(() -> Component.translatable(
                    game ? "maniacmod.command.reviews.row_game" : "maniacmod.command.reviews.row",
                    name, row.voters(),
                    String.format(java.util.Locale.ROOT, "%.1f", row.overall()),
                    String.format(java.util.Locale.ROOT, "%.1f", row.look()),
                    String.format(java.util.Locale.ROOT, "%.1f", row.abilities()),
                    String.format(java.util.Locale.ROOT, "%.1f", row.fear())), false);
            }
        }
        for (var review : com.log_to_kot.maniacmod.server.rating.ManiacRatingService.latestComments(all, 5)) {
            source.sendSuccess(() -> Component.translatable("maniacmod.command.reviews.comment",
                review.player(), review.comment()), false);
        }
        return 1;
    }

    private static int reloadConfig(CommandSourceStack source) {
        ConfigDiagnostics diag = ManiacConfigs.reload("команда /maniac reload");
        MapPointConfigs.reload();
        MatchOrchestrator match = ManiacMod.match();
        if (match != null) match.reloadConfiguredMap();

        // loot.sparkleEnabled і loot.pickupRangeBlocks читає клієнт із
        // пакета, не з файлу — без цього /maniac reload міняв би їх лише
        // для тих, хто перезайде.
        ModNetwork.toPlayers(source.getServer().getPlayerList().getPlayers(),
            new com.log_to_kot.maniacmod.net.s2c.loot.GroundItemVisualSettingsPacket(
                ManiacConfigs.get(ConfigSchema.GROUND_ITEM_SPARKLE_ENABLED),
                ManiacConfigs.get(ConfigSchema.GROUND_ITEM_PICKUP_RANGE)));

        if (diag.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("maniacmod.command.reload_clean"), true);
            return 1;
        }

        source.sendSuccess(() -> Component.translatable("maniacmod.command.reload_issues",
            diag.entries().size()), true);
        for (ConfigDiagnostics.Entry entry : diag.entries()) {
            boolean warn = entry.level() == ConfigDiagnostics.Level.WARN;
            source.sendSystemMessage(Component.literal(
                (warn ? "§e⚠ " : "§7• ") + entry.message()));
        }
        return 1;
    }

    /**
     * {@code /maniac settings} — відкриває {@link
     * com.log_to_kot.maniacmod.client.screen.settings.SettingsMenuScreen}
     * виконавцю команди. Консольний виконавець (немає ServerPlayer)
     * отримує зрозумілу відмову — екран нема кому показати.
     */
    /**
     * {@code /maniac history [гравець]} — відкриває екран історії тому, хто виконав команду. Права
     * гарантує корінь {@code /maniac} (оператор); дані екран усе одно запитує в сервера, який перевіряє
     * їх сам ({@code HistoryAccess}) — тут нічого не дублюємо.
     */
    private static int openHistory(CommandSourceStack source, ServerPlayer target) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.translatable("maniacmod.command.history_players_only"));
            return 0;
        }
        ModNetwork.toPlayer(player, new com.log_to_kot.maniacmod.net.s2c.history.OpenHistoryPacket(
            target == null ? null : target.getUUID(),
            target == null ? "" : target.getGameProfile().getName()));
        return 1;
    }

    private static int openSettings(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.translatable("maniacmod.command.settings_players_only"));
            return 0;
        }
        // Той самий шлях, що кнопка "⚙ Налаштування гри" у чаті —
        // ServerPacketHandler.onOpenSettingsMenuRequest сам перевіряє
        // hasPermissions(2) (тут вона й так гарантована коренем /maniac,
        // але дублювати перевірку в двох місцях — саме той витік,
        // якого AI_CODE_GUIDE.md просить уникати).
        com.log_to_kot.maniacmod.server.ServerPacketHandler.onOpenSettingsMenuRequest(player);
        return 1;
    }

    /**
     * {@code /maniac debug} без аргументів — показує поточний стан
     * замість того, щоб щось міняти наосліп. Раніше голе {@code /maniac
     * debug} перемикало runtime-toggle (увімкнено/вимкнено) без жодного
     * підтвердження, ким саме зараз керує режим (конфіг чи runtime), і
     * без згадки поточної ролі — довелось би пам'ятати стан напам'ять
     * або зазирати в конфіг-файл. Явні {@code on}/{@code off} нижче
     * лишають toggle-семантику доступною, але свідомою, а не побічним
     * ефектом виклику без аргументів.
     */
    private static int debugStatus(CommandSourceStack source) {
        boolean enabled = DebugMode.enabled();
        boolean runtimeOn = DebugMode.isRuntimeToggled();
        boolean configOn = ManiacConfigs.get(ConfigSchema.DEBUG_MODE);
        source.sendSuccess(() -> Component.literal(
            "Дебаг-режим: " + (enabled ? "§aувімкнено" : "§7вимкнено")
            + " (runtime=" + runtimeOn + ", конфіг=" + configOn + ")"
            + " | роль: " + DebugMode.role().name().toLowerCase()), false);
        return 1;
    }

    /**
     * {@code /maniac debug on} / {@code /maniac debug off} — явний
     * перемикач runtime-toggle замість вгадування напряму зі старого
     * {@code /maniac debug} без аргументів. Ідемпотентно: повторний
     * {@code on}, коли вже увімкнено (через конфіг чи попередній
     * runtime-toggle), не робить нічого зайвого — просто підтверджує
     * стан.
     */
    private static int setDebug(CommandSourceStack source, boolean on) {
        if (DebugMode.isRuntimeToggled() != on) {
            DebugMode.toggleRuntime();
        }
        source.sendSuccess(() -> Component.translatable(
            on ? "maniacmod.command.debug_on" : "maniacmod.command.debug_off"), true);
        return 1;
    }

    /**
     * {@code /maniac debug role <auto|maniac|survivor>} — обирає, ким
     * буде єдиний гравець наступного {@code /maniac start} у дебазі,
     * без правки config-файлу. Не вмикає сам дебаг-режим (для цього
     * лишається окреме {@code /maniac debug}) — лише готує роль
     * наперед, щоб її можна було перемкнути одразу перед стартом.
     */
    private static int setDebugRole(CommandSourceStack source, String raw) {
        DebugMode.Role role;
        try {
            role = DebugMode.Role.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.translatable("maniacmod.command.unknown_debug_role", raw));
            return 0;
        }
        DebugMode.setRuntimeRole(role);
        source.sendSuccess(() -> Component.translatable(
            "maniacmod.command.debug_role_set", role.name()), true);
        return 1;
    }

    /**
     * {@code /maniac debug traps} — UI-тест {@code TrapChooseScreen} без
     * залежності від того, скільки пасток реально зареєстровано.
     *
     * ── Навіщо окрема команда ────────────────────────────────────────
     * Звичайний шлях ({@code TrapModule#syncCatalogIfChoiceNeeded}) не
     * шле каталог, якщо пасток архетипу ≤ ліміту (зараз завжди так,
     * бо в {@code TrapRegistry} лише 1 пастка) — екран вибору просто
     * не з'явиться, скільки не морф. Ця команда шле
     * {@link com.log_to_kot.maniacmod.net.s2c.traps.TrapCatalogPacket}
     * напряму через {@code TrapModule#debugForceCatalog}, в обхід тієї
     * перевірки, — суто клієнтський UI-тест, ігрову логіку вибору не
     * чіпає.
     *
     * ── Що робить крок за кроком ─────────────────────────────────────
     *  1) Виконавець має бути гравцем (не консоль) і в матчі має йти
     *     фаза LOBBY — так само, як {@code /maniac morph}, бо
     *     {@link com.log_to_kot.maniacmod.core.match.MatchOrchestrator#morphManiac}
     *     інакше нічого не робить.
     *  2) Якщо виконавець ще не маньяк — морфимо його
     *     (той самий шлях, що {@code /maniac morph maniac}), архетип =
     *     перший з реєстру (або {@code TestManiacArchetype}, поки
     *     справжнього нема). Якщо реєстр порожній — команда відмовляє:
     *     без архетипу немає списку пасток, який можна було б показати.
     *  3) Форсимо каталог. {@code debugForceCatalog} повертає 0, лише
     *     якщо в архетипу взагалі немає жодної пастки в
     *     {@code traps()} — тоді екрану нема що показувати навіть
     *     примусово, і адмін отримує зрозуміле повідомлення, а не
     *     порожній екран.
     */
    private static int debugTraps(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.translatable("maniacmod.command.morph_need_player"));
            return 0;
        }
        MatchOrchestrator match = requireMatch(source);
        if (match == null) return 0;
        if (!match.phases().is(GamePhase.LOBBY)) {
            source.sendFailure(Component.translatable("maniacmod.command.morph_lobby_only"));
            return 0;
        }

        ManiacArchetype archetype = match.maniacArchetype();
        if (!match.isManiac(player.getUUID()) || archetype == null) {
            if (ManiacRegistry.isEmpty()) {
                source.sendFailure(Component.translatable("maniacmod.command.no_maniacs"));
                return 0;
            }
            archetype = ManiacRegistry.all().values().iterator().next();
            match.morphManiac(player, archetype);
        }

        int count = match.traps().debugForceCatalog(player, archetype);
        if (count == 0) {
            source.sendFailure(Component.translatable("maniacmod.command.debug_traps_empty"));
            return 0;
        }

        source.sendSuccess(() -> Component.translatable("maniacmod.command.debug_traps_opened", count), true);
        return 1;
    }

    /**
     * {@code /maniac debug unmorph [гравець]} — дебаг-зняття ролі
     * (маньяка чи виживого) з гравця в БУДЬ-ЯКІЙ фазі матчу, не лише в
     * лобі (на відміну від {@code /maniac morph reset}, який лишається
     * обмежений лобі — це звичайна лобі-механіка, її поведінку не
     * чіпаємо). Без аргументу — виконавець сам собі; з аргументом —
     * можна зняти роль будь-кому, як і решта дебаг-команд з опціональним
     * гравцем.
     *
     * Стан самого матчу (фаза, чи лишився ще хтось маньяком) свідомо
     * НЕ підлаштовується — команда для дебаг-зміни ролі, а не для
     * ігрової здачі/капітуляції. Див. {@link MatchOrchestrator#debugUnmorph}
     * щодо того, чому це окремий метод, а не просто зняте обмеження
     * фази в {@code unmorph}.
     */
    private static int debugUnmorph(CommandSourceStack source, ServerPlayer player) {
        if (player == null) {
            source.sendFailure(Component.translatable("maniacmod.command.morph_need_player"));
            return 0;
        }
        MatchOrchestrator match = requireMatch(source);
        if (match == null) return 0;

        match.debugUnmorph(player);
        source.sendSuccess(() -> Component.translatable("maniacmod.command.morph_reset"), true);
        return 1;
    }

    /**
     * {@code /maniac debug swap maniac [гравець]} — гаряче перетворення
     * на маньяка в БУДЬ-ЯКІЙ фазі матчу (на відміну від {@code /maniac
     * morph maniac}, обмеженого лобі). Без аргументу — виконавець сам
     * собі.
     *
     * ── Чому не через SPECTATOR ──────────────────────────────────────
     * Раніше єдиний шлях перетворити виживого на маньяка мід-матч —
     * спершу {@code /maniac debug unmorph} (виживий → глядач), і лише
     * тоді {@code /maniac morph maniac} — але та команда сама обмежена
     * LOBBY, тож і це не працювало поза лобі. Ця команда обходить
     * SPECTATOR узагалі: {@link MatchOrchestrator#debugSwapToManiac}
     * знімає стару роль (якщо була) і одразу призначає нову — гравець
     * ніколи не бачить проміжного стану глядача.
     *
     * Архетип: якщо реєстр не порожній — перший зареєстрований (як і
     * дебаг-старт "ти маньяк"); порожній реєстр — {@code null}, гравець
     * обирає сам у меню, якщо фаза це дозволяє.
     */
    private static int debugSwapManiac(CommandSourceStack source, ServerPlayer player) {
        if (player == null) {
            source.sendFailure(Component.translatable("maniacmod.command.morph_need_player"));
            return 0;
        }
        MatchOrchestrator match = requireMatch(source);
        if (match == null) return 0;

        ManiacArchetype archetype = ManiacRegistry.isEmpty()
            ? null : ManiacRegistry.all().values().iterator().next();
        match.debugSwapToManiac(player, archetype);
        source.sendSuccess(() -> Component.translatable("maniacmod.command.morph_maniac"), true);
        return 1;
    }

    /**
     * {@code /maniac debug swap survivor [гравець]} — гаряче
     * перетворення на виживого в БУДЬ-ЯКІЙ фазі матчу (на відміну від
     * {@code /maniac morph survivor}, обмеженого лобі). Без аргументу —
     * виконавець сам собі. Так само, як {@link #debugSwapManiac}, у
     * жодний момент не проходить через SPECTATOR: якщо гравець щойно
     * був маньяком, {@link MatchOrchestrator#debugSwapToSurvivor} знімає
     * маньячі модифікатори і одразу видає роль виживого.
     */
    private static int debugSwapSurvivor(CommandSourceStack source, ServerPlayer player) {
        if (player == null) {
            source.sendFailure(Component.translatable("maniacmod.command.morph_need_player"));
            return 0;
        }
        MatchOrchestrator match = requireMatch(source);
        if (match == null) return 0;

        match.debugSwapToSurvivor(player);
        source.sendSuccess(() -> Component.translatable("maniacmod.command.morph_survivor"), true);
        return 1;
    }

    /**
     * {@code /maniac debug stun [гравець]} — оглушує маніяка так, ніби
     * його щойно вдарили ломом, але без самого удару (без виживого поруч,
     * без списання міцності лома). Для тестування оглушення в одиночку.
     *
     * Без аргументу — оглушується поточний маніяк матчу (перший онлайн
     * гравець з роллю маніяка). З аргументом — саме цей гравець, і він
     * мусить бути маніяком: оглушення виживого не має сенсу, ця механіка
     * існує лише для маніяка.
     */
    private static int debugStun(CommandSourceStack source, ServerPlayer target) {
        MatchOrchestrator match = requireMatch(source);
        if (match == null) return 0;

        ServerPlayer maniac = target;
        if (maniac == null) {
            for (ServerPlayer online : match.onlinePlayers()) {
                if (match.isManiac(online.getUUID())) {
                    maniac = online;
                    break;
                }
            }
        }

        if (maniac == null || !match.isManiac(maniac.getUUID())) {
            source.sendFailure(Component.translatable("maniacmod.command.stun_no_maniac"));
            return 0;
        }

        if (!match.maniacStun().debugStun(maniac)) {
            source.sendFailure(Component.translatable("maniacmod.command.stun_already"));
            return 0;
        }

        ServerPlayer stunned = maniac;
        source.sendSuccess(() -> Component.translatable(
            "maniacmod.command.stun_applied", stunned.getDisplayName()), true);
        return 1;
    }

    /**
     * Дебаг-удар маньяка: жертва — вказаний гравець, а без аргумента —
     * виконавець. Напрямок віддачі береться від маньяка матчу (якщо він
     * є й це не сама жертва), інакше — назад від погляду жертви.
     */
    private static int debugStrike(CommandSourceStack source, ServerPlayer target, boolean withDamage) {
        MatchOrchestrator match = requireMatch(source);
        if (match == null) return 0;

        ServerPlayer victim = target;
        if (victim == null) {
            if (source.getEntity() instanceof ServerPlayer self) {
                victim = self;
            } else {
                source.sendFailure(Component.translatable("maniacmod.command.strike_need_player"));
                return 0;
            }
        }

        ServerPlayer attacker = null;
        for (ServerPlayer online : match.onlinePlayers()) {
            if (match.isManiac(online.getUUID())) {
                attacker = online;
                break;
            }
        }

        boolean downed = match.combat().debugStrike(victim, attacker, withDamage);

        ServerPlayer hit = victim;
        source.sendSuccess(() -> Component.translatable(
            withDamage ? "maniacmod.command.strike_applied" : "maniacmod.command.strike_fx",
            hit.getDisplayName()), true);
        if (downed) {
            source.sendSuccess(() -> Component.translatable(
                "maniacmod.command.strike_downed", hit.getDisplayName()), false);
        }
        return 1;
    }

    private static int morphManiac(CommandSourceStack source, ServerPlayer player) {
        if (player == null) {
            source.sendFailure(Component.translatable("maniacmod.command.morph_need_player"));
            return 0;
        }
        MatchOrchestrator match = requireMatch(source);
        if (match == null) return 0;
        if (!match.phases().is(com.log_to_kot.maniacmod.core.phase.GamePhase.LOBBY)) {
            source.sendFailure(Component.translatable("maniacmod.command.morph_lobby_only"));
            return 0;
        }
        // Архетип: якщо реєстр порожній — null (маньяк без архетипу, як у дебазі)
        ManiacArchetype archetype = ManiacRegistry.isEmpty()
            ? null : ManiacRegistry.all().values().iterator().next();
        match.morphManiac(player, archetype);
        source.sendSuccess(() -> Component.translatable("maniacmod.command.morph_maniac"), true);
        return 1;
    }

    private static int morphSurvivor(CommandSourceStack source, ServerPlayer player) {
        if (player == null) {
            source.sendFailure(Component.translatable("maniacmod.command.morph_need_player"));
            return 0;
        }
        MatchOrchestrator match = requireMatch(source);
        if (match == null) return 0;
        if (!match.phases().is(com.log_to_kot.maniacmod.core.phase.GamePhase.LOBBY)) {
            source.sendFailure(Component.translatable("maniacmod.command.morph_lobby_only"));
            return 0;
        }
        match.morphSurvivor(player);
        source.sendSuccess(() -> Component.translatable("maniacmod.command.morph_survivor"), true);
        return 1;
    }

    private static int unmorph(CommandSourceStack source, ServerPlayer player) {
        if (player == null) {
            source.sendFailure(Component.translatable("maniacmod.command.morph_need_player"));
            return 0;
        }
        MatchOrchestrator match = requireMatch(source);
        if (match == null) return 0;
        if (!match.phases().is(com.log_to_kot.maniacmod.core.phase.GamePhase.LOBBY)) {
            source.sendFailure(Component.translatable("maniacmod.command.morph_lobby_only"));
            return 0;
        }
        match.unmorph(player);
        source.sendSuccess(() -> Component.translatable("maniacmod.command.morph_reset"), true);
        return 1;
    }

    /**
     * {@code /maniac win <maniac|survivors>}: примусово завершує матч. Уся логіка — в
     * {@link MatchOrchestrator#forceEnd}; тут лише повідомлення адміну.
     */
    private static int forceWin(CommandSourceStack source, boolean survivorsWin) {
        MatchOrchestrator match = requireMatch(source);
        if (match == null) return 0;

        MatchOrchestrator.ForceEndResult result;
        try {
            result = match.forceEnd(survivorsWin, source.getServer().getPlayerList().getPlayers());
        } catch (Exception e) {
            ManiacMod.LOGGER.error("[ManiacMod] /maniac win впала", e);
            source.sendFailure(Component.literal("Помилка при завершенні матчу: "
                + e.getClass().getSimpleName()
                + (e.getMessage() != null ? " — " + e.getMessage() : "")
                + " (повний стек — у latest.log)"));
            return 0;
        }
        switch (result) {
            case REJECTED -> {
                source.sendFailure(Component.translatable("maniacmod.command.win_not_in_game"));
                return 0;
            }
            case SURVIVORS -> source.sendSuccess(
                () -> Component.translatable("maniacmod.command.win_survivors"), true);
            case MANIAC_SCENE -> source.sendSuccess(
                () -> Component.translatable("maniacmod.command.win_maniac"), true);
            case MANIAC_RESULT_ONLY -> source.sendSuccess(
                () -> Component.translatable("maniacmod.command.win_maniac_no_scene"), true);
        }
        return 1;
    }

    private static int stop(CommandSourceStack source) {
        MatchOrchestrator match = requireMatch(source);
        if (match == null) return 0;

        try {
            match.reset(source.getServer().getPlayerList().getPlayers());
        } catch (Exception e) {
            // Без цього гравець бачить лише загальне «сталася неочікувана
            // помилка». Тепер у чаті — тип і текст, у логах — повний stacktrace.
            ManiacMod.LOGGER.error("[ManiacMod] /maniac stop впала", e);
            source.sendFailure(Component.literal("Помилка при зупинці матчу: "
                + e.getClass().getSimpleName()
                + (e.getMessage() != null ? " — " + e.getMessage() : "")
                + " (повний стек — у latest.log)"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("maniacmod.command.stopped"), true);
        return 1;
    }

    private static int setPhase(CommandSourceStack source, String raw) {
        MatchOrchestrator match = requireMatch(source);
        if (match == null) return 0;

        GamePhase phase;
        try {
            phase = GamePhase.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.translatable("maniacmod.command.unknown_phase", raw));
            return 0;
        }

        match.advanceTo(phase, source.getServer().getPlayerList().getPlayers());
        source.sendSuccess(() -> Component.translatable("maniacmod.command.phase_set",
            phase.name()), true);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        MatchOrchestrator match = requireMatch(source);
        if (match == null) return 0;

        GamePhase phase = match.phases().current();

        source.sendSuccess(() -> Component.literal(
            "Фаза: " + phase.name() + " (" + phase.kind() + ")"
            + " | " + match.debugStatusLine()
            + " | маньяків у реєстрі: " + ManiacRegistry.all().size()), false);
        return 1;
    }

    /**
     * Хп і стан виживого(-их). Без аргументу — по одному рядку на
     * кожного зареєстрованого виживого; з аргументом — лише вказаний
     * гравець. Читає ті самі дані, що {@code SurvivorVitalsPacket}
     * (через {@link MatchOrchestrator#hpOf}/{@code maxHpOf}/
     * {@code survivorStateOf}), тому число тут завжди збігається з
     * тим, що бачить сам гравець на HUD.
     */
    private static int hp(CommandSourceStack source, ServerPlayer target) {
        MatchOrchestrator match = requireMatch(source);
        if (match == null) return 0;

        if (target != null) {
            if (!match.isSurvivor(target.getUUID())) {
                source.sendFailure(Component.translatable("maniacmod.command.hp_not_survivor", target.getName().getString()));
                return 0;
            }
            source.sendSuccess(() -> Component.literal(hpLine(match, target.getUUID(), target.getName().getString())), false);
            return 1;
        }

        var survivorIds = match.survivorIds();
        if (survivorIds.isEmpty()) {
            source.sendFailure(Component.translatable("maniacmod.command.hp_no_survivors"));
            return 0;
        }
        for (var id : survivorIds) {
            ServerPlayer p = source.getServer().getPlayerList().getPlayer(id);
            String name = p != null ? p.getName().getString() : id.toString();
            source.sendSuccess(() -> Component.literal(hpLine(match, id, name)), false);
        }
        return survivorIds.size();
    }

    private static String hpLine(MatchOrchestrator match, java.util.UUID id, String name) {
        int hp = match.hpOf(id);
        int maxHp = match.maxHpOf(id);
        var state = match.survivorStateOf(id);
        return name + ": " + hp + "/" + maxHp + " хп (" + state.name() + ")";
    }

    /**
     * Дебаг-команда: виставити хп виживого напряму у відсотках (0-100).
     * Чистий дебаг-інструмент для тестування HUD/станів — не претендує
     * бути "справжнім джерелом урону" (те лишається за
     * {@code ManiacCombatModule.onAttack}), але на 0% свідомо заводить
     * гравця в UNCONSCIOUS ТИМ САМИМ шляхом, що реальний удар
     * ({@code survivors().onSurvivorDowned}) — інакше команда лишила б
     * гравця "на нулі хп, але стоячи", і решта стейт-машини (лежання,
     * bleed-out, підняття) просто не знала б, що він мертвий.
     */
    private static int setHp(CommandSourceStack source, ServerPlayer target, int percent) {
        MatchOrchestrator match = requireMatch(source);
        if (match == null) return 0;

        if (!match.isSurvivor(target.getUUID())) {
            source.sendFailure(Component.translatable("maniacmod.command.hp_not_survivor", target.getName().getString()));
            return 0;
        }

        boolean downed = match.setSurvivorHpPercent(target.getUUID(), percent);
        if (downed) {
            match.survivors().onSurvivorDowned(target);
        } else {
            match.survivors().forceVitalsRefresh(target);
        }

        source.sendSuccess(() -> Component.translatable("maniacmod.command.hp_set",
            target.getName().getString(), percent, hpLine(match, target.getUUID(), target.getName().getString())), true);
        return 1;
    }

    /**
     * Дебаг-команда: миттєво полагоджує ВСІ ще не готові генератори
     * матчу — {@link com.log_to_kot.maniacmod.map.GeneratorModule#completeAllGenerators}
     * робить фактичну роботу (форсить кожен генератор, гасить прогрес-бари
     * тим, хто саме лагодив, шле один сумарний GeneratorCompletedPacket);
     * тут лише парсинг команди й повідомлення адміну.
     *
     * Наступний {@code onPhaseTick} сам помітить {@code allRequiredGeneratorsDone()}
     * і переведе фазу HUNT → POWERED звичайним шляхом — команді не треба
     * форсити перехід фази окремо.
     */
    private static int completeGenerators(CommandSourceStack source) {
        MatchOrchestrator match = requireMatch(source);
        if (match == null) return 0;

        var players = source.getServer().getPlayerList().getPlayers();
        int count = match.generatorModule().completeAllGenerators(players);

        if (count == 0) {
            source.sendSuccess(() -> Component.translatable("maniacmod.command.generators_already_done"), false);
        } else {
            source.sendSuccess(() -> Component.translatable("maniacmod.command.generators_completed", count), true);
        }
        return count;
    }

    /**
     * Дебаг-команда: піднімає непритомного {@code target} рівно тим самим
     * шляхом, що звичайний rescue іншим гравцем ({@code SurvivorModule.reviveDowned}
     * через {@link com.log_to_kot.maniacmod.survivors.SurvivorModule#debugRevive}) —
     * гравець встає із {@code reviveHp} хп, а не отримує повне здоров'я
     * напряму. Для тестів HUD/станів, коли немає другого гравця під
     * рукою, щоб фізично постояти 8 секунд поруч із лежачим.
     */
    private static int revive(CommandSourceStack source, ServerPlayer target) {
        MatchOrchestrator match = requireMatch(source);
        if (match == null) return 0;

        if (!match.isSurvivor(target.getUUID())) {
            source.sendFailure(Component.translatable("maniacmod.command.hp_not_survivor", target.getName().getString()));
            return 0;
        }

        boolean revived = match.survivors().debugRevive(target);
        if (!revived) {
            source.sendFailure(Component.translatable("maniacmod.command.revive_not_downed",
                target.getName().getString()));
            return 0;
        }

        source.sendSuccess(() -> Component.translatable("maniacmod.command.revived",
            target.getName().getString()), true);
        return 1;
    }

    // ── Гаки (дебаг) ─────────────────────────────────────────────────────

    private static int hookSpawn(CommandSourceStack source) {
        MatchOrchestrator match = requireMatch(source);
        if (match == null) return 0;
        var pos = net.minecraft.core.BlockPos.containing(source.getPosition());
        match.hooks().spawnHookAt(source.getLevel(), pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
            source.getRotation().y);
        source.sendSuccess(() -> Component.translatable("maniacmod.command.hook_spawned",
            pos.toShortString()), true);
        return 1;
    }

    private static int hookHang(CommandSourceStack source, ServerPlayer target) {
        MatchOrchestrator match = requireMatch(source);
        if (match == null) return 0;
        if (!match.hooks().debugHang(target)) {
            source.sendFailure(Component.translatable("maniacmod.command.hook_hang_failed",
                target.getName().getString()));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("maniacmod.command.hook_hung",
            target.getName().getString()), true);
        return 1;
    }

    private static int hookClear(CommandSourceStack source) {
        MatchOrchestrator match = requireMatch(source);
        if (match == null) return 0;
        match.hooks().debugClear();
        source.sendSuccess(() -> Component.translatable("maniacmod.command.hook_cleared"), true);
        return 1;
    }

    // ── Карта ────────────────────────────────────────────────────────────

    private static int setLobby(CommandSourceStack source) {
        MatchOrchestrator match = requireMatch(source);
        if (match == null) return 0;

        var pos = source.getPosition();
        float yaw = source.getRotation().y;
        match.setLobbySpawn(pos.x, pos.y, pos.z, yaw);
        MapPointConfigs.writeLobby(pos.x, pos.y, pos.z, yaw);

        source.sendSuccess(() -> Component.translatable("maniacmod.command.lobby_set"), true);
        return 1;
    }

    private static int addPoint(CommandSourceStack source, String rawKind, String owner) {
        MatchOrchestrator match = requireMatch(source);
        if (match == null) return 0;

        SpawnPointKind kind;
        try {
            kind = SpawnPointKind.valueOf(rawKind.toUpperCase());
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.translatable("maniacmod.command.unknown_point", rawKind));
            return 0;
        }

        // Точка маньяка без власника непридатна: SpawnPlanner шукає
        // точки конкретного архетипу і просто не знайде цю.
        if (kind == SpawnPointKind.MANIAC && owner == null) {
            source.sendFailure(Component.translatable("maniacmod.command.maniac_point_needs_owner"));
            return 0;
        }

        // Точки сцен перемоги й freecam точні (не центр блока): камери — позиція ОЧЕЙ адміна й нахил
        // погляду, гачок/маніяк/гравець/привид — де стоїть адмін. Решта видів — по центру блока.
        var exact = source.getPosition();
        boolean cameraKind = kind == SpawnPointKind.VICTORY_CAMERA
            || kind == SpawnPointKind.VICTORY_PLAYERS_CAMERA || kind == SpawnPointKind.STATS_CAMERA;
        if (cameraKind && source.getEntity() != null) exact = source.getEntity().getEyePosition();
        boolean victoryKind = kind == SpawnPointKind.VICTORY_HOOK || kind == SpawnPointKind.VICTORY_MANIAC
            || kind == SpawnPointKind.VICTORY_PLAYER || kind == SpawnPointKind.VICTORY_GHOST
            || cameraKind;
        int cap = pointCap(kind);
        if (cap > 0 && match.spawnPoints().stream().filter(p -> p.kind() == kind).count() >= cap) {
            source.sendFailure(Component.translatable("maniacmod.command.point_cap_full", kind.name(), cap));
            return 0;
        }
        var pos = net.minecraft.core.BlockPos.containing(source.getPosition());
        float yaw = source.getRotation().y;
        float pitch = cameraKind ? source.getRotation().x : 0f;
        SpawnPoint point = victoryKind
            ? new SpawnPoint(pos, yaw, kind, owner, pitch, exact.x, exact.y, exact.z)
            : new SpawnPoint(pos, yaw, kind, owner);
        match.addSpawnPoint(point);
        MapPointConfigs.add(pointType(kind), victoryKind
            ? new MapPointConfigs.PointData(pos, yaw, owner, pitch, exact.x, exact.y, exact.z)
            : new MapPointConfigs.PointData(pos, yaw, owner));

        source.sendSuccess(() -> Component.translatable("maniacmod.command.point_added",
            kind.name(), pos.toShortString()), true);
        return 1;
    }

    private static int listPoints(CommandSourceStack source) {
        MatchOrchestrator match = requireMatch(source);
        if (match == null) return 0;

        Map<SpawnPointKind, Integer> counts =
            SpawnPlanner.countByKind(match.spawnPoints());

        StringBuilder sb = new StringBuilder("Розмітка:");
        counts.forEach((kind, count) -> sb.append(' ').append(kind.name())
            .append('=').append(count));

        source.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int clearPoints(CommandSourceStack source) {
        MatchOrchestrator match = requireMatch(source);
        if (match == null) return 0;

        match.clearMap();
        MapPointConfigs.clearAll();
        source.sendSuccess(() -> Component.translatable("maniacmod.command.points_cleared"), true);
        return 1;
    }

    private static int removePoint(CommandSourceStack source, String rawKind, int index) {
        SpawnPointKind kind;
        try {
            kind = SpawnPointKind.valueOf(rawKind.toUpperCase());
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.translatable("maniacmod.command.unknown_point", rawKind));
            return 0;
        }
        if (!MapPointConfigs.remove(pointType(kind), index)) {
            source.sendFailure(Component.literal("Немає точки з індексом " + index + "."));
            return 0;
        }
        MatchOrchestrator match = requireMatch(source);
        if (match != null) match.reloadConfiguredMap();
        source.sendSuccess(() -> Component.literal("Точку " + kind.name()
            + " #" + index + " видалено."), true);
        return 1;
    }

    // ── Галявини евакуації ───────────────────────────────────────────────

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
    suggestClearings(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
                     com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        for (var zone : com.log_to_kot.maniacmod.config.ClearingConfigs.snapshot()) builder.suggest(zone.name());
        return builder.buildFuture();
    }

    private static int clearingCreate(CommandSourceStack source, String name) {
        var result = com.log_to_kot.maniacmod.config.ClearingConfigs.create(name);
        if (result != com.log_to_kot.maniacmod.config.ClearingConfigs.Result.OK) {
            source.sendFailure(Component.translatable("maniacmod.command.clearing_exists", name));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("maniacmod.command.clearing_created", name, name, name), true);
        return 1;
    }

    private static int clearingCorner(CommandSourceStack source, String name) {
        var pos = net.minecraft.core.BlockPos.containing(source.getPosition());
        var result = com.log_to_kot.maniacmod.config.ClearingConfigs.addCorner(name, pos);
        switch (result) {
            case NO_SUCH_ZONE -> { source.sendFailure(Component.translatable("maniacmod.command.clearing_missing", name)); return 0; }
            case CORNERS_FULL -> { source.sendFailure(Component.translatable("maniacmod.command.clearing_corners_full", name, name)); return 0; }
            default -> { }
        }
        var zone = com.log_to_kot.maniacmod.config.ClearingConfigs.find(name);
        int count = zone == null ? 0 : zone.corners().size();
        source.sendSuccess(() -> Component.translatable("maniacmod.command.clearing_corner_added",
            name, pos.toShortString(), count), true);
        return 1;
    }

    private static int clearingHeli(CommandSourceStack source, String name) {
        var pos = net.minecraft.core.BlockPos.containing(source.getPosition());
        var result = com.log_to_kot.maniacmod.config.ClearingConfigs.setHeli(name, pos);
        switch (result) {
            case NO_SUCH_ZONE -> { source.sendFailure(Component.translatable("maniacmod.command.clearing_missing", name)); return 0; }
            case NEED_CORNERS -> { source.sendFailure(Component.translatable("maniacmod.command.clearing_need_corners", name)); return 0; }
            case HELI_OUTSIDE -> { source.sendFailure(Component.translatable("maniacmod.command.clearing_heli_outside", name)); return 0; }
            default -> { }
        }
        source.sendSuccess(() -> Component.translatable("maniacmod.command.clearing_heli_set", name, pos.toShortString()), true);

        // Підказка адміну: вертоліт висітиме на ropeLength над ЗЕМЛЕЮ в цій колонці. Блоки в
        // колонці (крона дерева, дах) — мотузка й гвинт пройдуть крізь них.
        var level = source.getLevel();
        int ropeLen = com.log_to_kot.maniacmod.config.ManiacConfigs.get(
            com.log_to_kot.maniacmod.config.ConfigSchema.EVAC_ROPE_LENGTH);
        int ground = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            pos.getX(), pos.getZ());
        for (int y = ground; y <= ground + ropeLen + 3; y++) {
            var p = new net.minecraft.core.BlockPos(pos.getX(), y, pos.getZ());
            if (!level.getBlockState(p).isAir()) {
                source.sendSystemMessage(Component.translatable(
                    "maniacmod.command.clearing_heli_blocked", ropeLen).withStyle(net.minecraft.ChatFormatting.YELLOW));
                break;
            }
        }
        return 1;
    }

    private static int clearingSimple(CommandSourceStack source, String name, String action) {
        boolean ok;
        if (action.equals("remove")) {
            ok = com.log_to_kot.maniacmod.config.ClearingConfigs.remove(name);
        } else {
            ok = com.log_to_kot.maniacmod.config.ClearingConfigs.resetCorners(name)
                == com.log_to_kot.maniacmod.config.ClearingConfigs.Result.OK;
        }
        if (!ok) {
            source.sendFailure(Component.translatable("maniacmod.command.clearing_missing", name));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("maniacmod.command.clearing_" + action + (action.equals("remove") ? "d" : ""), name), true);
        return 1;
    }

    private static int clearingList(CommandSourceStack source) {
        var zones = com.log_to_kot.maniacmod.config.ClearingConfigs.snapshot();
        if (zones.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Галявин немає."), false);
            return 1;
        }
        StringBuilder sb = new StringBuilder("Галявини:");
        for (var z : zones) {
            sb.append(' ').append(z.name()).append(z.isComplete() ? " ✓" : " (неповна: "
                + z.corners().size() + "/2 кути" + (z.heli() == null ? ", без точки вертольота" : "") + ")");
        }
        source.sendSuccess(() -> Component.literal(sb.toString()), false);
        return zones.size();
    }

    private static int heliEntry(CommandSourceStack source, boolean clear) {
        if (clear) {
            com.log_to_kot.maniacmod.config.ClearingConfigs.setEntry(null);
            source.sendSuccess(() -> Component.translatable("maniacmod.command.heli_entry_cleared"), true);
            return 1;
        }
        var pos = net.minecraft.core.BlockPos.containing(source.getPosition());
        com.log_to_kot.maniacmod.config.ClearingConfigs.setEntry(pos);
        source.sendSuccess(() -> Component.translatable("maniacmod.command.heli_entry_set", pos.toShortString()), true);
        return 1;
    }

    // ── Темні зони ───────────────────────────────────────────────────────

    /**
     * {@code /maniac darkzone create <name>} — резервує ім'я зони з
     * НУЛЬОВОЮ кількістю точок. Точки додаються далі окремо командою
     * {@code point} — так адмін ходить по карті й натискає одну коротку
     * команду в кожному потрібному куті, замість вписувати координати
     * вручну (саме цього просив дизайн).
     */
    private static int darkZoneCreate(CommandSourceStack source, String name) {
        if (!DarkZoneConfigs.create(name)) {
            source.sendFailure(Component.translatable("maniacmod.command.darkzone_exists", name));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(
            "maniacmod.command.darkzone_created", name, name), true);
        return 1;
    }

    /**
     * {@code /maniac darkzone point <name>} — додає ПОТОЧНУ позицію
     * виконавця (X/Z; Y не зберігається — {@link DarkZonePolygon}
     * ігнорує висоту) як наступну точку зони. Порядок доданих точок і
     * визначає форму: 1 точка → квадрат навколо неї, 2 → прямокутник
     * по діагоналі, 3+ → полігон у тому порядку, як додавали.
     */
    private static int darkZonePoint(CommandSourceStack source, String name) {
        var pos = net.minecraft.core.BlockPos.containing(source.getPosition());
        if (!DarkZoneConfigs.addPoint(name, pos)) {
            source.sendFailure(Component.translatable(
                "maniacmod.command.darkzone_not_found", name, name));
            return 0;
        }
        DarkZonePolygon zone = DarkZoneConfigs.find(name);
        int index = zone == null ? 0 : zone.points().size();
        source.sendSuccess(() -> Component.translatable("maniacmod.command.darkzone_point_added",
            index, name, pos.toShortString()), true);
        return 1;
    }

    private static int darkZoneList(CommandSourceStack source) {
        var zones = DarkZoneConfigs.snapshot();
        if (zones.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("maniacmod.command.darkzone_empty_list"), false);
            return 1;
        }
        StringBuilder sb = new StringBuilder("Темні зони:");
        for (DarkZonePolygon zone : zones) {
            sb.append(' ').append(zone.name()).append('=').append(zone.points().size()).append("т.");
        }
        source.sendSuccess(() -> Component.literal(sb.toString()), false);
        return zones.size();
    }

    private static int darkZoneRemove(CommandSourceStack source, String name) {
        if (!DarkZoneConfigs.remove(name)) {
            source.sendFailure(Component.translatable(
                "maniacmod.command.darkzone_not_found", name, name));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("maniacmod.command.darkzone_removed", name), true);
        return 1;
    }

    private static int darkZoneClear(CommandSourceStack source) {
        DarkZoneConfigs.clearAll();
        source.sendSuccess(() -> Component.translatable("maniacmod.command.darkzone_cleared"), true);
        return 1;
    }

    private static MapPointConfigs.Type pointType(SpawnPointKind kind) {
        return switch (kind) {
            case SURVIVOR -> MapPointConfigs.Type.SURVIVOR_SPAWNS;
            case MANIAC -> MapPointConfigs.Type.MANIAC_SPAWNS;
            case ITEM -> MapPointConfigs.Type.ITEM_POINTS;
            case GENERATOR -> MapPointConfigs.Type.GENERATOR_POINTS;
            case EXIT -> MapPointConfigs.Type.EXIT_POINTS;
            case RECEIVER -> MapPointConfigs.Type.RECEIVER_POINTS;
            case HOOK -> MapPointConfigs.Type.HOOK_POINTS;
            case VICTORY_HOOK -> MapPointConfigs.Type.VICTORY_HOOK_POINTS;
            case VICTORY_MANIAC -> MapPointConfigs.Type.VICTORY_MANIAC_POINTS;
            case VICTORY_CAMERA -> MapPointConfigs.Type.VICTORY_CAMERA_POINTS;
            case VICTORY_PLAYER -> MapPointConfigs.Type.VICTORY_PLAYER_POINTS;
            case VICTORY_GHOST -> MapPointConfigs.Type.VICTORY_GHOST_POINTS;
            case VICTORY_PLAYERS_CAMERA -> MapPointConfigs.Type.VICTORY_PLAYERS_CAMERA_POINTS;
            case STATS_CAMERA -> MapPointConfigs.Type.STATS_CAMERA_POINTS;
        };
    }

    /** Скільки точок цього виду приймає розмітка (0 — без ліміту). */
    private static int pointCap(SpawnPointKind kind) {
        return switch (kind) {
            case VICTORY_CAMERA, VICTORY_PLAYERS_CAMERA -> 2;
            case STATS_CAMERA -> 1;
            case VICTORY_PLAYER, VICTORY_GHOST -> 10;
            default -> 0;
        };
    }

    // ── Допоміжне ────────────────────────────────────────────────────────

    /**
     * Матч існує лише поки живий сервер. Повідомлення замість NPE —
     * єдина причина, чому це окремий метод.
     */
    private static MatchOrchestrator requireMatch(CommandSourceStack source) {
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) {
            source.sendFailure(Component.translatable("maniacmod.command.no_match"));
        }
        return match;
    }
}
