package com.log_to_kot.maniacmod.server;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.config.ConfigDiagnostics;
import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.config.MapPointConfigs;
import com.log_to_kot.maniacmod.core.match.DebugMode;
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
 *   /maniac morph <maniac|survivor|reset> — дебаг-перетворення в лобі
 *   /maniac phase <фаза>          — ручний перехід (налагодження)
 *   /maniac status                — хто в якій ролі, яка фаза, чи готова карта
 *   /maniac lobby set             — точка лобі = позиція виконавця
 *   /maniac point add <вид> [id]  — розмітити точку на своїй позиції
 *   /maniac point list            — скільки яких точок розмічено
 *   /maniac point clear           — стерти розмітку
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

        root.then(Commands.literal("debug")
            .executes(ctx -> toggleDebug(ctx.getSource())));

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

        dispatcher.register(root);
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
                source.sendFailure(Component.translatable("maniacmod.command.start_failed"));
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
                source.sendFailure(Component.translatable("maniacmod.command.start_failed"));
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
            source.sendFailure(Component.translatable("maniacmod.command.start_failed"));
            return 0;
        }

        source.sendSuccess(() -> Component.translatable("maniacmod.command.started",
            choice.player().getName().getString()), true);
        return 1;
    }

    /**
     * Примусове перезавантаження конфігу. Зазвичай не потрібне —
     * зміна файлу підхоплюється сама протягом секунди. Команда
     * лишається, бо після неї одразу видно ПОВНИЙ список зауважень.
     */
    private static int reloadConfig(CommandSourceStack source) {
        ConfigDiagnostics diag = ManiacConfigs.reload("команда /maniac reload");
        MapPointConfigs.reload();
        MatchOrchestrator match = ManiacMod.match();
        if (match != null) match.reloadConfiguredMap();

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

    private static int toggleDebug(CommandSourceStack source) {
        boolean nowOn = DebugMode.toggleRuntime();
        source.sendSuccess(() -> Component.translatable(
            nowOn ? "maniacmod.command.debug_on" : "maniacmod.command.debug_off"), true);
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

    private static int stop(CommandSourceStack source) {
        MatchOrchestrator match = requireMatch(source);
        if (match == null) return 0;

        match.reset(source.getServer().getPlayerList().getPlayers());
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

        var pos = net.minecraft.core.BlockPos.containing(source.getPosition());
        SpawnPoint point = new SpawnPoint(pos, source.getRotation().y, kind, owner);
        match.addSpawnPoint(point);
        MapPointConfigs.add(pointType(kind),
            new MapPointConfigs.PointData(pos, source.getRotation().y, owner));

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

    private static MapPointConfigs.Type pointType(SpawnPointKind kind) {
        return switch (kind) {
            case SURVIVOR -> MapPointConfigs.Type.SURVIVOR_SPAWNS;
            case MANIAC -> MapPointConfigs.Type.MANIAC_SPAWNS;
            case ITEM -> MapPointConfigs.Type.ITEM_POINTS;
            case GENERATOR -> MapPointConfigs.Type.GENERATOR_POINTS;
            case EXIT -> MapPointConfigs.Type.EXIT_POINTS;
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
