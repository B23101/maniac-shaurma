package com.log_to_kot.maniacmod.commands;

import com.log_to_kot.maniacmod.config.ManiacConfig;
import com.log_to_kot.maniacmod.entity.ManiacType;
import com.log_to_kot.maniacmod.game.*;
import com.log_to_kot.maniacmod.game.PendingGameStarter;
import com.log_to_kot.maniacmod.cinematic.IntroScene;
import com.log_to_kot.maniacmod.sound.ModSounds;
import com.log_to_kot.maniacmod.sound.SoundHelper;
import com.log_to_kot.maniacmod.network.ModMessages;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

public class ManiacCommand {

    private static final List<BlockPos>      pendingGenerators  = new ArrayList<>();
    private static final List<BlockPos>      pendingExits       = new ArrayList<>();
    private static final List<ItemSpawnZone> pendingZones       = new ArrayList<>();
    private static final List<EscapeZone>    pendingEscapeZones = new ArrayList<>();
    /** Зона спавну для кожного типу маньяка окремо */
    private static final java.util.Map<com.log_to_kot.maniacmod.entity.ManiacType, ManiacSpawnZone> pendingManiacSpawns = new java.util.EnumMap<>(com.log_to_kot.maniacmod.entity.ManiacType.class);
    private static final Random              RNG                = new Random();

    /** Вручну призначений маньяк (якщо maniacPlayerMode = MANUAL) */
    private static ServerPlayer manualManiac = null;

    /** Вручну призначений тип (якщо maniacTypeMode = FIXED через команду) */
    private static ManiacType   manualType   = null;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("maniac")
            .requires(src -> src.hasPermission(2))

            // /maniac start ───────────────────────────────────────────────────
            .then(Commands.literal("start")
                .executes(ctx -> startGame(ctx.getSource()))
            )

            // /maniac stop ────────────────────────────────────────────────────
            .then(Commands.literal("stop")
                .executes(ctx -> {
                    var src = ctx.getSource();
                    if (ManiacGameManager.getGameState() == ManiacGameManager.GameState.WAITING) {
                        src.sendFailure(Component.literal("§cГра не запущена.")); return 0;
                    }
                    ServerPlayer mp = src.getServer().getPlayerList()
                        .getPlayer(ManiacGameManager.getManiacUUID());
                    if (mp != null) ModMessages.clearTypeForPlayer(mp);
                    ManiacGameManager.resetGame();
                    src.getServer().getPlayerList().getPlayers().forEach(p ->
                        p.sendSystemMessage(Component.literal("§6§lГру зупинено.")));
                    src.sendSuccess(() -> Component.literal("§a✔ Зупинено."), true);
                    return 1;
                })
            )

            // /maniac setmaniac <гравець> — призначити маньяка вручну ─────────
            .then(Commands.literal("setmaniac")
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(ctx -> {
                        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                        manualManiac = target;
                        ctx.getSource().sendSuccess(() -> Component.literal(
                            "§a✔ Маньяк призначений вручну: §c" + target.getName().getString() +
                            "\n§7Активується при /maniac start (якщо maniacPlayerMode = MANUAL)"), false);
                        return 1;
                    })
                )
            )

            // /maniac settype <тип> — призначити тип маньяка вручну ───────────
            .then(Commands.literal("settype")
                .then(Commands.argument("type", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        for (ManiacType t : ManiacType.values())
                            builder.suggest(t.name().toLowerCase());
                        return builder.buildFuture();
                    })
                    .executes(ctx -> {
                        String typeName = StringArgumentType.getString(ctx, "type").toUpperCase();
                        try {
                            manualType = ManiacType.valueOf(typeName);
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                "§a✔ Тип маньяка вручну: §e" + manualType.displayName +
                                "\n§7Активується при /maniac start (якщо maniacTypeMode = FIXED або override)"), false);
                            return 1;
                        } catch (IllegalArgumentException e) {
                            ctx.getSource().sendFailure(Component.literal(
                                "§cНевідомий тип: " + typeName +
                                "\n§7Доступні: " + Arrays.stream(ManiacType.values())
                                    .map(t -> t.name().toLowerCase())
                                    .reduce((a, b) -> a + ", " + b).orElse("")));
                            return 0;
                        }
                    })
                )
            )

            // /maniac config — показати поточні налаштування конфігу ──────────
            .then(Commands.literal("config")
                .executes(ctx -> {
                    ManiacConfig.ManiacPlayerMode pm = ManiacConfig.getManiacPlayerMode();
                    ManiacConfig.ManiacTypeMode   tm = ManiacConfig.getManiacTypeMode();
                    String playerModeColor = pm == ManiacConfig.ManiacPlayerMode.RANDOM ? "§a" : "§e";
                    String typeModeColor   = tm == ManiacConfig.ManiacTypeMode.RANDOM   ? "§a" : "§e";

                    String manualManiacStr = manualManiac != null
                        ? "§c" + manualManiac.getName().getString()
                        : "§8не призначено";
                    String manualTypeStr = manualType != null
                        ? "§e" + manualType.displayName
                        : "§8не призначено";

                    ctx.getSource().sendSuccess(() -> Component.literal(
                        "§6=== КОНФІГ МАНЬЯКА ===" +
                        "\n§7Вибір гравця: " + playerModeColor + pm.name() +
                        (pm == ManiacConfig.ManiacPlayerMode.MANUAL
                            ? "\n§7  └ Призначений: " + manualManiacStr
                            : "") +
                        "\n§7Вибір типу:   " + typeModeColor + tm.name() +
                        (tm == ManiacConfig.ManiacTypeMode.FIXED
                            ? "\n§7  └ Фіксований: §e" + ManiacConfig.getFixedManiacType().displayName
                            : "") +
                        (manualType != null
                            ? "\n§7  └ Override командою: " + manualTypeStr
                            : "") +
                        "\n" +
                        "\n§7Генератори: §f" + ManiacConfig.getRepairsRequired() + " §7етапів, §f"
                            + String.format("%.0f", ManiacConfig.getRepairFailChance() * 100) + "% §7шанс зриву" +
                        "\n§7Гра: §f" + ManiacConfig.getGameDuration() + "с §7| §7Воскресіння: §f"
                            + ManiacConfig.getReviveWindow() + "с" +
                        "\n" +
                        "\n§8Файл: config/maniacmod-server.toml"
                    ), false);
                    return 1;
                })
            )

            // /maniac clearmanual — скинути ручні призначення ─────────────────
            .then(Commands.literal("clearmanual")
                .executes(ctx -> {
                    manualManiac = null;
                    manualType   = null;
                    ctx.getSource().sendSuccess(() -> Component.literal(
                        "§a✔ Ручні призначення скинуто. Використовуватимуться налаштування конфігу."), false);
                    return 1;
                })
            )

            // /maniac addgenerator ─────────────────────────────────────────────
            .then(Commands.literal("addgenerator")
                .executes(ctx -> {
                    if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return 0;
                    pendingGenerators.add(p.blockPosition());
                    ctx.getSource().sendSuccess(() -> Component.literal(
                        "§a✔ Генератор #" + pendingGenerators.size()
                        + " → " + p.blockPosition().toShortString()), false);
                    return 1;
                })
            )

            // /maniac addexit ──────────────────────────────────────────────────
            .then(Commands.literal("addexit")
                .executes(ctx -> {
                    if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return 0;
                    pendingExits.add(p.blockPosition());
                    ctx.getSource().sendSuccess(() -> Component.literal(
                        "§a✔ Вихід #" + pendingExits.size()
                        + " → " + p.blockPosition().toShortString()), false);
                    return 1;
                })
            )

            // /maniac addzone <radius> [minY] [maxY] ──────────────────────────
            .then(Commands.literal("addzone")
                .then(Commands.argument("radius", IntegerArgumentType.integer(1))
                    .executes(ctx -> {
                        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return 0;
                        int radius = IntegerArgumentType.getInteger(ctx, "radius");
                        ItemSpawnZone zone = new ItemSpawnZone(p.blockPosition(), radius);
                        pendingZones.add(zone);
                        ctx.getSource().sendSuccess(() -> Component.literal(
                            "§a✔ Зона #" + pendingZones.size() + " додана!\n"
                            + "§7" + zone.info() + "\n"
                            + "§7" + ItemLootTable.summary()), false);
                        return 1;
                    })
                    .then(Commands.argument("minY", IntegerArgumentType.integer(-64))
                        .then(Commands.argument("maxY", IntegerArgumentType.integer(-64))
                            .executes(ctx -> {
                                if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return 0;
                                int radius = IntegerArgumentType.getInteger(ctx, "radius");
                                int minY   = IntegerArgumentType.getInteger(ctx, "minY");
                                int maxY   = IntegerArgumentType.getInteger(ctx, "maxY");
                                if (minY >= maxY) {
                                    ctx.getSource().sendFailure(Component.literal("§cminY < maxY!"));
                                    return 0;
                                }
                                ItemSpawnZone zone = new ItemSpawnZone(p.blockPosition(), radius)
                                    .withYRange(minY, maxY);
                                pendingZones.add(zone);
                                ctx.getSource().sendSuccess(() -> Component.literal(
                                    "§a✔ Зона #" + pendingZones.size() + " (Y " + minY + "–" + maxY + ") додана!\n"
                                    + "§7" + zone.info()), false);
                                return 1;
                            })
                        )
                    )
                )
            )

            // /maniac escapezone <radius> ─────────────────────────────────────
            .then(Commands.literal("escapezone")
                .then(Commands.argument("radius", IntegerArgumentType.integer(1))
                    .executes(ctx -> {
                        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return 0;
                        int radius = IntegerArgumentType.getInteger(ctx, "radius");
                        EscapeZone zone = new EscapeZone(p.blockPosition(), radius);
                        pendingEscapeZones.add(zone);
                        ctx.getSource().sendSuccess(() -> Component.literal(
                            "§a✔ Зона втечі #" + pendingEscapeZones.size() + " додана!\n"
                            + "§7" + zone.info()), false);
                        return 1;
                    })
                )
            )

            // /maniac maniaczone <type> <radius> — зона спавну для конкретного маньяка
            // Приклад: /maniac maniaczone chucky 10
            .then(Commands.literal("maniaczone")
                .then(Commands.argument("type", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        for (ManiacType t : ManiacType.values())
                            builder.suggest(t.name().toLowerCase());
                        return builder.buildFuture();
                    })
                    .then(Commands.argument("radius", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return 0;
                            String typeName = StringArgumentType.getString(ctx, "type").toUpperCase();
                            ManiacType type;
                            try { type = ManiacType.valueOf(typeName); }
                            catch (IllegalArgumentException e) {
                                ctx.getSource().sendFailure(Component.literal(
                                    "§cНевідомий тип: " + typeName + "\n§7Доступні: " +
                                    java.util.Arrays.stream(ManiacType.values())
                                        .map(t -> t.name().toLowerCase())
                                        .reduce((a, b) -> a + ", " + b).orElse("")));
                                return 0;
                            }
                            int radius = IntegerArgumentType.getInteger(ctx, "radius");
                            ManiacSpawnZone zone = new ManiacSpawnZone(p.blockPosition(), radius);
                            pendingManiacSpawns.put(type, zone);
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                "§a✔ Зона спавну §e" + type.displayName + "§a!\n§7" + zone.info()), false);
                            // Show which types still missing
                            java.util.List<String> missing = java.util.Arrays.stream(ManiacType.values())
                                .filter(t -> !pendingManiacSpawns.containsKey(t))
                                .map(t -> "§c" + t.name().toLowerCase())
                                .collect(java.util.stream.Collectors.toList());
                            if (!missing.isEmpty())
                                ctx.getSource().sendSuccess(() -> Component.literal(
                                    "§7Ще не встановлено зони для: " + String.join(", ", missing)), false);
                            return 1;
                        })
                    )
                )
            )

            // /maniac clearzones ───────────────────────────────────────────────
            .then(Commands.literal("clearzones")
                .executes(ctx -> {
                    pendingZones.clear();
                    pendingEscapeZones.clear();
                    pendingManiacSpawns.clear();
                    ctx.getSource().sendSuccess(() ->
                        Component.literal("§a✔ Всі зони очищено."), false);
                    return 1;
                })
            )

            // /maniac status ───────────────────────────────────────────────────
            .then(Commands.literal("status")
                .executes(ctx -> {
                    ManiacType type = ManiacGameManager.getManiacType(ManiacGameManager.getManiacUUID());
                    String typeInfo = type != null
                        ? "§e" + type.displayName + " §7(висота: §f" + type.hitboxHeight + " бл§7)"
                        : "§7—";
                    // Build spawn zones info
                    StringBuilder spawnInfo = new StringBuilder();
                    for (com.log_to_kot.maniacmod.entity.ManiacType t : com.log_to_kot.maniacmod.entity.ManiacType.values()) {
                        ManiacSpawnZone z = pendingManiacSpawns.get(t);
                        spawnInfo.append("\n§7  ").append(t.name().toLowerCase()).append(": ");
                        spawnInfo.append(z != null ? "§a✔ " + z.info() : "§c✘ не встановлено");
                    }
                    final String spawnStr = spawnInfo.toString();
                    ctx.getSource().sendSuccess(() -> Component.literal(
                        "§e=== Маньяк ===" +
                        "\n§7Стан: §f"         + ManiacGameManager.getGameState() +
                        "\n§7Маньяк: "         + typeInfo +
                        "\n§7Виживаючих: §f"   + ManiacGameManager.getSurvivorDataMap().size() +
                        "\n§7Трупів: §6"        + ManiacGameManager.getCorpses().size() +
                        "\n§7Генераторів: §f"  + ManiacGameManager.countActiveGenerators() + "/5" +
                        "\n§7Зони спавну маньяків:" + spawnStr
                    ), false);
                    return 1;
                })
            )

            // /maniac help ────────────────────────────────────────────────────
            .then(Commands.literal("help")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                        "§6=== МАНЬЯК ===" +
                        "\n§e/maniac start §7— запустити гру" +
                        "\n§e/maniac stop §7— зупинити гру" +
                        "\n" +
                        "\n§b── Вибір маньяка ──" +
                        "\n§e/maniac setmaniac <гравець> §7— призначити маньяка вручну" +
                        "\n§e/maniac settype <тип> §7— призначити тип (chucky/slenderman)" +
                        "\n§e/maniac clearmanual §7— скинути ручні призначення" +
                        "\n§e/maniac config §7— показати поточний конфіг" +
                        "\n" +
                        "\n§b── Зони ──" +
                        "\n§e/maniac addgenerator §7— позначити генератор" +
                        "\n§e/maniac addzone <р> §7— зона спавну + предметів" +
                        "\n§e/maniac addzone <р> <minY> <maxY> §7— зона з Y-обмеженням" +
                        "\n§e/maniac escapezone <р> §7— зона втечі" +
                        "\n§e/maniac maniaczone <р> §7— зона спавну маньяка" +
                        "\n§e/maniac clearzones §7— очистити зони" +
                        "\n§e/maniac status §7— стан гри" +
                        "\n" +
                        "\n§8Режими вибору (config/maniacmod-server.toml):" +
                        "\n§8  maniacPlayerMode: RANDOM | MANUAL" +
                        "\n§8  maniacTypeMode:   RANDOM | FIXED" +
                        "\n§8  fixedManiacType:  CHUCKY | SLENDERMAN"
                    ), false);
                    return 1;
                })
            )
        );
    }

    // ── Логіка старту ─────────────────────────────────────────────────────────

    private static int startGame(CommandSourceStack src) {
        List<ServerPlayer> all = src.getServer().getPlayerList().getPlayers();

        if (ManiacGameManager.getGameState() != ManiacGameManager.GameState.WAITING) {
            src.sendFailure(Component.literal("§cГра вже іде!")); return 0; }
        if (all.size() < 2) {
            src.sendFailure(Component.literal("§cПотрібно мінімум 2 гравці!")); return 0; }
        if (pendingGenerators.isEmpty()) {
            src.sendFailure(Component.literal("§cДодай генератори: /maniac addgenerator")); return 0; }
        if (pendingZones.isEmpty()) {
            src.sendFailure(Component.literal("§cДодай зону: /maniac addzone <радіус>")); return 0; }
        if (pendingEscapeZones.isEmpty()) {
            src.sendFailure(Component.literal("§cДодай зону втечі: /maniac escapezone <радіус>")); return 0; }

        // ── Визначаємо гравця-маньяка ──────────────────────────────────────

        ManiacConfig.ManiacPlayerMode playerMode = ManiacConfig.getManiacPlayerMode();
        ServerPlayer chosenManiac;

        if (playerMode == ManiacConfig.ManiacPlayerMode.MANUAL) {
            if (manualManiac == null) {
                src.sendFailure(Component.literal(
                    "§cmaniacPlayerMode = MANUAL але маньяк не призначений!\n" +
                    "§7Використай: §e/maniac setmaniac <гравець>"));
                return 0;
            }
            boolean online = all.stream().anyMatch(p -> p.getUUID().equals(manualManiac.getUUID()));
            if (!online) {
                src.sendFailure(Component.literal(
                    "§cПризначений маньяк §e" + manualManiac.getName().getString() +
                    " §cне в грі! Призначте іншого або використайте RANDOM."));
                return 0;
            }
            chosenManiac = manualManiac;
        } else {
            // RANDOM
            chosenManiac = all.get(RNG.nextInt(all.size()));
        }

        // ── Визначаємо тип маньяка ─────────────────────────────────────────

        ManiacConfig.ManiacTypeMode typeMode = ManiacConfig.getManiacTypeMode();
        ManiacType chosenType;

        if (manualType != null) {
            // Команда /maniac settype має пріоритет над конфігом
            chosenType = manualType;
        } else if (typeMode == ManiacConfig.ManiacTypeMode.FIXED) {
            chosenType = ManiacConfig.getFixedManiacType();
        } else {
            // RANDOM
            chosenType = ManiacType.values()[RNG.nextInt(ManiacType.values().length)];
        }

        // ── Snapshots ──────────────────────────────────────────────────────
        final List<BlockPos>      gensCopy   = new ArrayList<>(pendingGenerators);
        final List<BlockPos>      exitsCopy  = new ArrayList<>(pendingExits);
        final List<ItemSpawnZone> zonesCopy  = new ArrayList<>(pendingZones);
        final List<EscapeZone>    escCopy    = new ArrayList<>(pendingEscapeZones);
        final ManiacSpawnZone     mSpawn     = pendingManiacSpawns.get(chosenType);
        final ServerPlayer        finalManiac = chosenManiac;
        final ManiacType          finalType   = chosenType;

        // ── Повідомлення про режим ─────────────────────────────────────────
        String playerModeStr = playerMode == ManiacConfig.ManiacPlayerMode.MANUAL
            ? "§e[MANUAL: " + finalManiac.getName().getString() + "]"
            : "§a[RANDOM]";
        String typeModeStr = manualType != null
            ? "§e[OVERRIDE: " + finalType.displayName + "]"
            : typeMode == ManiacConfig.ManiacTypeMode.FIXED
                ? "§e[FIXED: " + finalType.displayName + "]"
                : "§a[RANDOM: " + finalType.displayName + "]";

        src.sendSuccess(() -> Component.literal(
            "§7Маньяк: " + playerModeStr + "  Тип: " + typeModeStr), false);

        all.forEach(p -> p.sendSystemMessage(Component.literal(
            "maniacmod.game.countdown")));

        // ── Відлік ────────────────────────────────────────────────────────
        new Thread(() -> {
            try {
                for (int i = 10; i > 0; i--) {
                    final int sec = i;
                    src.getServer().execute(() -> {
                        src.getServer().getPlayerList().getPlayers()
                            .forEach(p -> p.sendSystemMessage(Component.literal("§e⏳ §f" + sec + "§e...")));
                        SoundHelper.playGlobal(
                            src.getServer().getPlayerList().getPlayers(),
                            sec <= 3 ? ModSounds.COUNTDOWN_FINAL.get() : ModSounds.COUNTDOWN_BEEP.get(),
                            sec <= 3 ? 1.0f : 0.8f);
                    });
                    Thread.sleep(1000);
                }

                src.getServer().execute(() -> {
                    List<ServerPlayer> live = src.getServer().getPlayerList().getPlayers();

                    // Перевірити що маньяк ще онлайн
                    ServerPlayer activeManiac = live.stream()
                        .filter(p -> p.getUUID().equals(finalManiac.getUUID()))
                        .findFirst()
                        .orElse(live.get(RNG.nextInt(live.size())));

                    boolean ok = ManiacGameManager.startGame(
                        live, activeManiac, gensCopy, exitsCopy,
                        finalType, zonesCopy, escCopy, mSpawn);

                    if (!ok) {
                        live.forEach(p -> p.sendSystemMessage(
                            Component.literal("§cНе вдалося запустити гру.")));
                        return;
                    }

                    pendingGenerators.clear();
                    pendingExits.clear();
                    pendingZones.clear();
                    pendingEscapeZones.clear();
                    pendingManiacSpawns.clear();
                    manualManiac = null;
                    manualType   = null;

                    // Розкрити
                    String icon = switch (finalType) {
                        case CHUCKY    -> "§c🪆";
                        case SLENDERMAN -> "§9👤";
                    };

                    live.forEach(p -> {
                        if (p.getUUID().equals(activeManiac.getUUID())) {
                            p.sendSystemMessage(Component.literal(
                                "\n§4§l☠ ТИ — МАНЬЯК! ☠" +
                                "\n§7Персонаж: " + icon + " §e" + finalType.displayName +
                                "\n§cЗнищ усіх виживаючих!\n"));
                        } else {
                            p.sendSystemMessage(Component.literal(
                                "\n§c§l══ МАНЬЯК РОЗКРИТО! ══" +
                                "\n§7Маньяк: §c" + activeManiac.getName().getString() +
                                " " + icon + " §e" + finalType.displayName +
                                "\n§a📦 Знайди предмети на карті!" +
                                "\n§a🏃 Добіжи до зони втечі!\n"));
                        }
                    });
                });

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "ManiacCountdown").start();

        return 1;
    }
}
