package com.log_to_kot.maniacmod.server;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.config.ConfigDiagnostics;
import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.config.MapPointConfigs;
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
 *   /maniac morph <maniac|survivor|reset> — дебаг-перетворення в лобі
 *   /maniac phase <фаза>          — ручний перехід (налагодження)
 *   /maniac status                — хто в якій ролі, яка фаза, чи готова карта
 *   /maniac hp [гравець]          — хп/стан виживого(-их)
 *   /maniac hp set <гравець> <0-100> — дебаг: виставити хп у % (0 = downed)
 *   /maniac generators complete   — дебаг: миттєво полагодити всі генератори
 *   /maniac revive <гравець>      — дебаг: підняти непритомного гравця (як інший гравець підняв)
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

        root.then(Commands.literal("settings")
            .executes(ctx -> openSettings(ctx.getSource())));

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

        // /maniac revive <гравець> — дебаг: підняти непритомного гравця
        // без другого гравця, що фізично тримає ПКМ 8 секунд.
        root.then(Commands.literal("revive")
            .then(Commands.argument("survivor", EntityArgument.player())
                .executes(ctx -> revive(ctx.getSource(),
                    EntityArgument.getPlayer(ctx, "survivor")))));

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
    private static int reloadConfig(CommandSourceStack source) {
        ConfigDiagnostics diag = ManiacConfigs.reload("команда /maniac reload");
        MapPointConfigs.reload();
        MatchOrchestrator match = ManiacMod.match();
        if (match != null) match.reloadConfiguredMap();

        // loot.sparkleEnabled читає клієнт із пакета, не з файлу — без
        // цього /maniac reload міняв би блиск лише для тих, хто перезайде.
        ModNetwork.toPlayers(source.getServer().getPlayerList().getPlayers(),
            new com.log_to_kot.maniacmod.net.s2c.loot.GroundItemVisualSettingsPacket(
                ManiacConfigs.get(ConfigSchema.GROUND_ITEM_SPARKLE_ENABLED)));

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
