package com.log_to_kot.maniacmod.barricade;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.blocks.BoardsBlock;
import com.log_to_kot.maniacmod.config.BarricadeConfigs;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.registry.ModBlocks;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * {@code /maniac barricade ...} — розмітка барикад.
 * <pre>
 *   build [on|off]   — режим будування гнучкої барикади (без аргументу — перемкнути)
 *   create [name]    — зареєструвати ВЕСЬ буфер дощок як гнучку барикаду (без прицілювання)
 *   buffer clear     — стерти незареєстровані дошки
 *   door             — зареєструвати блок-дошки (maniacmod:boards), на який дивишся
 *   remove [name]    — видалити: за ім'ям, або ту, на яку дивишся (блок чи гнучка)
 *   list | clear     — перелік / видалити всі
 *   restore          — виставити розмітку у світ зараз (як на старті матчу)
 * </pre>
 */
public final class BarricadeCommand {

    private static final double AIM_RANGE = 8.0;

    private BarricadeCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("barricade")
            .then(Commands.literal("build")
                .executes(ctx -> build(ctx.getSource(), null))
                .then(Commands.argument("enabled", BoolArgumentType.bool())
                    .executes(ctx -> build(ctx.getSource(), BoolArgumentType.getBool(ctx, "enabled")))))
            .then(Commands.literal("create")
                .executes(ctx -> create(ctx.getSource(), null))
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> create(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
            .then(Commands.literal("buffer")
                .then(Commands.literal("clear").executes(ctx -> clearBuffer(ctx.getSource()))))
            .then(Commands.literal("door").executes(ctx -> door(ctx.getSource())))
            .then(Commands.literal("remove")
                .executes(ctx -> removeAimed(ctx.getSource()))
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        for (FlexibleBarricade b : BarricadeConfigs.flexible()) builder.suggest(b.id());
                        return builder.buildFuture();
                    })
                    .executes(ctx -> removeNamed(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
            .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
            .then(Commands.literal("clear").executes(ctx -> clear(ctx.getSource())))
            .then(Commands.literal("restore").executes(ctx -> restore(ctx.getSource())));
    }

    private static MatchOrchestrator match(CommandSourceStack src) {
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) src.sendFailure(Component.translatable("maniacmod.barricade.err_no_match"));
        return match;
    }

    private static int build(CommandSourceStack src, Boolean enabled) {
        MatchOrchestrator match = match(src);
        if (match == null) return 0;
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.translatable("maniacmod.barricade.err_player_only"));
            return 0;
        }
        match.barricades().builder().toggle(player, enabled);
        return 1;
    }

    private static int create(CommandSourceStack src, String name) {
        MatchOrchestrator match = match(src);
        if (match == null) return 0;
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.translatable("maniacmod.barricade.err_player_only"));
            return 0;
        }
        int boards = match.barricades().builder().bufferSize(player);
        String error = match.barricades().builder().create(player, name);
        if (error != null) {
            src.sendFailure(Component.translatable(error));
            return 0;
        }
        src.sendSuccess(() -> Component.translatable("maniacmod.barricade.created", boards), true);
        return 1;
    }

    private static int clearBuffer(CommandSourceStack src) {
        MatchOrchestrator match = match(src);
        if (match == null || !(src.getEntity() instanceof ServerPlayer player)) return 0;
        match.barricades().builder().clearBuffer(player);
        src.sendSuccess(() -> Component.translatable("maniacmod.barricade.buffer_cleared"), false);
        return 1;
    }

    private static int door(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.translatable("maniacmod.barricade.err_player_only"));
            return 0;
        }
        BlockHitResult hit = aimedBlock(player);
        if (hit == null || !(player.level() instanceof ServerLevel level)) {
            src.sendFailure(Component.translatable("maniacmod.barricade.err_aim_boards"));
            return 0;
        }
        BlockState state = level.getBlockState(hit.getBlockPos());
        if (!state.is(ModBlocks.BOARDS.get())) {
            src.sendFailure(Component.translatable("maniacmod.barricade.err_aim_boards"));
            return 0;
        }
        BlockPos lower = BoardsBlock.lowerHalf(state, hit.getBlockPos());
        FixedBarricade fixed = new FixedBarricade(BarricadeModule.dimId(level), lower,
            state.getValue(BoardsBlock.FACING));
        if (!BarricadeConfigs.addFixed(fixed)) {
            src.sendFailure(Component.translatable("maniacmod.barricade.err_already_registered"));
            return 0;
        }
        src.sendSuccess(() -> Component.translatable("maniacmod.barricade.door_registered",
            lower.getX(), lower.getY(), lower.getZ()), true);
        return 1;
    }

    private static int removeAimed(CommandSourceStack src) {
        MatchOrchestrator match = match(src);
        if (match == null) return 0;
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.translatable("maniacmod.barricade.err_player_only"));
            return 0;
        }
        var flexible = match.barricades().findFlexible(player, AIM_RANGE);
        if (flexible != null) return removeNamed(src, flexible.entity().barricadeId());

        BlockHitResult hit = aimedBlock(player);
        if (hit != null && player.level() instanceof ServerLevel level
            && level.getBlockState(hit.getBlockPos()).is(ModBlocks.BOARDS.get())) {
            BlockPos pos = hit.getBlockPos();
            BarricadeConfigs.removeFixed(BarricadeModule.dimId(level), pos);
            match.barricades().removeFixedBlocks(level, pos);
            src.sendSuccess(() -> Component.translatable("maniacmod.barricade.removed"), true);
            return 1;
        }
        src.sendFailure(Component.translatable("maniacmod.barricade.err_aim_barricade"));
        return 0;
    }

    private static int removeNamed(CommandSourceStack src, String name) {
        MatchOrchestrator match = match(src);
        if (match == null) return 0;
        if (!BarricadeConfigs.removeFlexible(name)) {
            src.sendFailure(Component.translatable("maniacmod.barricade.err_unknown", name));
            return 0;
        }
        match.barricades().removeFlexibleEntities(src.getServer(), name);
        src.sendSuccess(() -> Component.translatable("maniacmod.barricade.removed"), true);
        return 1;
    }

    private static int list(CommandSourceStack src) {
        var fixed = BarricadeConfigs.fixed();
        var flexible = BarricadeConfigs.flexible();
        if (fixed.isEmpty() && flexible.isEmpty()) {
            src.sendSuccess(() -> Component.translatable("maniacmod.barricade.list_empty"), false);
            return 0;
        }
        for (FixedBarricade f : fixed) {
            src.sendSuccess(() -> Component.translatable("maniacmod.barricade.list_fixed",
                f.pos().getX(), f.pos().getY(), f.pos().getZ(), f.facing().getName()), false);
        }
        for (FlexibleBarricade b : flexible) {
            src.sendSuccess(() -> Component.translatable("maniacmod.barricade.list_flexible",
                b.id(), b.boards().size()), false);
        }
        return fixed.size() + flexible.size();
    }

    private static int clear(CommandSourceStack src) {
        MatchOrchestrator match = match(src);
        if (match == null) return 0;
        for (FlexibleBarricade b : BarricadeConfigs.flexible()) {
            match.barricades().removeFlexibleEntities(src.getServer(), b.id());
        }
        for (FixedBarricade f : BarricadeConfigs.fixed()) {
            ServerLevel level = src.getServer().getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                new net.minecraft.resources.ResourceLocation(f.dimension())));
            if (level != null) match.barricades().removeFixedBlocks(level, f.pos());
        }
        BarricadeConfigs.clearAll();
        src.sendSuccess(() -> Component.translatable("maniacmod.barricade.cleared"), true);
        return 1;
    }

    private static int restore(CommandSourceStack src) {
        MatchOrchestrator match = match(src);
        if (match == null) return 0;
        match.barricades().restoreAll(src.getServer());
        src.sendSuccess(() -> Component.translatable("maniacmod.barricade.restored"), false);
        return 1;
    }

    private static BlockHitResult aimedBlock(ServerPlayer player) {
        HitResult hit = player.pick(AIM_RANGE, 1.0F, false);
        return hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK ? block : null;
    }
}
