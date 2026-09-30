package com.log_to_kot.maniacmod.intro;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;

/**
 * {@code /maniac replay ...} — розмітка Replay-роликів вступу з позиції й погляду
 * адміна, без ручного вписування координат.
 *
 * <pre>
 *  /maniac replay point &lt;ролик&gt; [інтервал_мс]   додати точку (камера = твої очі й погляд)
 *  /maniac replay list                          які ролики й скільки точок
 *  /maniac replay remove &lt;ролик&gt;                видалити ролик цілком
 *  /maniac replay removepoint &lt;ролик&gt; &lt;№&gt;      видалити точку
 *  /maniac replay set &lt;ролик&gt; shared &lt;мс&gt;      спільний інтервал між точками
 *  /maniac replay set &lt;ролик&gt; cut &lt;мс&gt;         примусово обірвати ролик на цій мс (0 — ні)
 *  /maniac replay set &lt;ролик&gt; shake &lt;градуси&gt;  «рука оператора» (0 — статично)
 *  /maniac replay set &lt;ролик&gt; mode linear|smooth|spline
 *  /maniac replay set &lt;ролик&gt; audience all|survivor|maniac
 *  /maniac replay set &lt;ролик&gt; transition cut|dip_to_black
 *  /maniac replay setpoint &lt;ролик&gt; &lt;№&gt; interval &lt;мс&gt;   власний інтервал відрізка «точка → наступна»
 *  /maniac replay setpoint &lt;ролик&gt; &lt;№&gt; fov &lt;градуси&gt;      поле зору в точці (0 — не чіпати)
 *  /maniac replay preview [survivor|maniac]     програти вступ собі в лобі
 *  /maniac replay clear                         стерти всі ролики
 * </pre>
 */
public final class IntroReplayCommand {

    private IntroReplayCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("replay")
            .then(Commands.literal("point")
                .then(Commands.argument("clip", StringArgumentType.word())
                    .executes(ctx -> addPoint(ctx.getSource(), StringArgumentType.getString(ctx, "clip"), 0))
                    .then(Commands.argument("intervalMs", IntegerArgumentType.integer(0, 600_000))
                        .executes(ctx -> addPoint(ctx.getSource(), StringArgumentType.getString(ctx, "clip"),
                            IntegerArgumentType.getInteger(ctx, "intervalMs"))))))
            .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
            .then(Commands.literal("remove")
                .then(Commands.argument("clip", StringArgumentType.word())
                    .executes(ctx -> removeClip(ctx.getSource(), StringArgumentType.getString(ctx, "clip")))))
            .then(Commands.literal("removepoint")
                .then(Commands.argument("clip", StringArgumentType.word())
                    .then(Commands.argument("index", IntegerArgumentType.integer(0))
                        .executes(ctx -> removePoint(ctx.getSource(), StringArgumentType.getString(ctx, "clip"),
                            IntegerArgumentType.getInteger(ctx, "index"))))))
            .then(Commands.literal("set")
                .then(Commands.argument("clip", StringArgumentType.word())
                    .then(Commands.literal("shared")
                        .then(Commands.argument("ms", IntegerArgumentType.integer(50, 600_000))
                            .executes(ctx -> setField(ctx.getSource(), ctx, "sharedIntervalMs",
                                IntegerArgumentType.getInteger(ctx, "ms")))))
                    .then(Commands.literal("cut")
                        .then(Commands.argument("ms", IntegerArgumentType.integer(0, 600_000))
                            .executes(ctx -> setField(ctx.getSource(), ctx, "cutAtMs",
                                IntegerArgumentType.getInteger(ctx, "ms")))))
                    .then(Commands.literal("shake")
                        .then(Commands.argument("deg", DoubleArgumentType.doubleArg(0, 10))
                            .executes(ctx -> setField(ctx.getSource(), ctx, "shake",
                                DoubleArgumentType.getDouble(ctx, "deg")))))
                    .then(Commands.literal("mode")
                        .then(Commands.argument("value", StringArgumentType.word())
                            .suggests((c, b) -> { for (String s : List.of("linear", "smooth", "spline")) b.suggest(s); return b.buildFuture(); })
                            .executes(ctx -> setEnum(ctx.getSource(), ctx, "interpolation",
                                List.of("LINEAR", "SMOOTH", "SPLINE")))))
                    .then(Commands.literal("audience")
                        .then(Commands.argument("value", StringArgumentType.word())
                            .suggests((c, b) -> { for (String s : List.of("all", "survivor", "maniac")) b.suggest(s); return b.buildFuture(); })
                            .executes(ctx -> setEnum(ctx.getSource(), ctx, "audience",
                                List.of("ALL", "SURVIVOR", "MANIAC")))))
                    .then(Commands.literal("transition")
                        .then(Commands.argument("value", StringArgumentType.word())
                            .suggests((c, b) -> { for (String s : List.of("cut", "dip_to_black")) b.suggest(s); return b.buildFuture(); })
                            .executes(ctx -> setEnum(ctx.getSource(), ctx, "transition",
                                List.of("CUT", "DIP_TO_BLACK")))))))
            .then(Commands.literal("setpoint")
                .then(Commands.argument("clip", StringArgumentType.word())
                    .then(Commands.argument("index", IntegerArgumentType.integer(0))
                        .then(Commands.literal("interval")
                            .then(Commands.argument("ms", IntegerArgumentType.integer(0, 600_000))
                                .executes(ctx -> setPoint(ctx.getSource(), ctx, "intervalMs",
                                    IntegerArgumentType.getInteger(ctx, "ms")))))
                        .then(Commands.literal("fov")
                            .then(Commands.argument("deg", DoubleArgumentType.doubleArg(0, 170))
                                .executes(ctx -> setPoint(ctx.getSource(), ctx, "fov",
                                    DoubleArgumentType.getDouble(ctx, "deg"))))))))
            .then(Commands.literal("preview")
                .executes(ctx -> preview(ctx.getSource(), false))
                .then(Commands.literal("survivor").executes(ctx -> preview(ctx.getSource(), false)))
                .then(Commands.literal("maniac").executes(ctx -> preview(ctx.getSource(), true))))
            .then(Commands.literal("clear").executes(ctx -> clear(ctx.getSource())));
    }

    // ── Виконання ────────────────────────────────────────────────────────

    private static int addPoint(CommandSourceStack src, String clip, int intervalMs) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        int count = IntroReplayConfigs.addPoint(clip, p.getX(), p.getEyeY(), p.getZ(),
            p.getYRot(), p.getXRot(), intervalMs);
        ok(src, "Ролик '" + clip + "': точка №" + (count - 1) + " додана (усього " + count + "). "
            + "Позиція й погляд — твої.");
        return count;
    }

    private static int list(CommandSourceStack src) {
        List<String> lines = IntroReplayConfigs.describe();
        if (lines.isEmpty()) {
            ok(src, "Роликів немає. Стань де має бути камера й виконай /maniac replay point <ролик>.");
            return 0;
        }
        for (String line : lines) ok(src, line);
        return lines.size();
    }

    private static int removeClip(CommandSourceStack src, String clip) {
        if (!IntroReplayConfigs.removeClip(clip)) { fail(src, "Ролика '" + clip + "' немає."); return 0; }
        ok(src, "Ролик '" + clip + "' видалено.");
        return 1;
    }

    private static int removePoint(CommandSourceStack src, String clip, int index) {
        if (!IntroReplayConfigs.removePoint(clip, index)) { fail(src, "Немає ролика '" + clip + "' або точки №" + index + "."); return 0; }
        ok(src, "Точку №" + index + " ролика '" + clip + "' видалено.");
        return 1;
    }

    private static int setField(CommandSourceStack src, com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
                                String field, Object value) {
        String clip = StringArgumentType.getString(ctx, "clip");
        if (!IntroReplayConfigs.setClipField(clip, field, value)) { fail(src, "Ролика '" + clip + "' немає."); return 0; }
        ok(src, "Ролик '" + clip + "': " + field + " = " + value);
        return 1;
    }

    private static int setEnum(CommandSourceStack src, com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
                               String field, List<String> allowed) {
        String value = StringArgumentType.getString(ctx, "value").toUpperCase(Locale.ROOT);
        if (!allowed.contains(value)) { fail(src, "Допустимо: " + String.join(", ", allowed).toLowerCase(Locale.ROOT)); return 0; }
        return setField(src, ctx, field, value);
    }

    private static int setPoint(CommandSourceStack src, com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
                                String field, Object value) {
        String clip = StringArgumentType.getString(ctx, "clip");
        int index = IntegerArgumentType.getInteger(ctx, "index");
        if (!IntroReplayConfigs.setPointField(clip, index, field, value)) {
            fail(src, "Немає ролика '" + clip + "' або точки №" + index + ".");
            return 0;
        }
        ok(src, "Ролик '" + clip + "', точка №" + index + ": " + field + " = " + value);
        return 1;
    }

    private static int preview(CommandSourceStack src, boolean maniac) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        MatchOrchestrator match = ManiacMod.match();
        if (match == null || !match.intro().preview(p, maniac)) {
            fail(src, "Прев'ю можливе лише в лобі, коли воно вже не йде.");
            return 0;
        }
        ok(src, "Прев'ю вступу (" + (maniac ? "маньяк" : "виживий") + "). Триває ~"
            + (IntroTimeline.fullCinematicTicks() + IntroTimeline.SCATTER_HOLD_TICKS) / 20 + " с.");
        return 1;
    }

    private static int clear(CommandSourceStack src) {
        IntroReplayConfigs.clearAll();
        ok(src, "Усі Replay-ролики стерто.");
        return 1;
    }

    private static void ok(CommandSourceStack src, String text) {
        src.sendSuccess(() -> Component.literal(text), false);
    }

    private static void fail(CommandSourceStack src, String text) {
        src.sendFailure(Component.literal(text));
    }
}
