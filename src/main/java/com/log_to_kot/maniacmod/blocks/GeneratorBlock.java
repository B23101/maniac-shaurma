package com.log_to_kot.maniacmod.blocks;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Блок генератора.
 *
 * ── Що змінилось відносно v3 ─────────────────────────────────────────
 * v3-блок звертався до ManiacGameManager напряму — і тим самим тягнув
 * у себе весь матч: пастки, бій, трупи. Тепер блок знає рівно два
 * питання: «чи дозволяє фаза ремонт» і «кому передати намір».
 *
 * Перевірка фази — тут, а не всередині модуля: якщо фаза не ігрова,
 * не варто навіть створювати сесію ремонту.
 */
public class GeneratorBlock extends Block {

    /** true, коли генератор повністю готовий — блок світиться. */
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    private static final VoxelShape SHAPE = Block.box(2, 0, 2, 14, 14, 14);

    public GeneratorBlock() {
        super(Properties.of()
            .mapColor(MapColor.METAL)
            .strength(3.5f, 6.0f)
            .sound(SoundType.METAL)
            .lightLevel(state -> state.getValue(ACTIVE) ? 10 : 0)
            .requiresCorrectToolForDrops());
        registerDefaultState(this.stateDefinition.any().setValue(ACTIVE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ACTIVE);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos,
                                 Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.FAIL;

        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return InteractionResult.PASS;
        if (!match.phases().allows(PhaseRule.GENERATOR_REPAIR)) return InteractionResult.PASS;

        // Маньяк ламає готовий генератор.
        if (match.isManiac(sp.getUUID())) {
            if (!state.getValue(ACTIVE)) {
                sp.sendSystemMessage(Component.translatable("maniacmod.generator.not_active"));
                return InteractionResult.SUCCESS;
            }
            level.setBlock(pos, state.setValue(ACTIVE, false), 3);
            match.generatorModule().sabotage(pos);
            sp.sendSystemMessage(Component.translatable("maniacmod.generator.broken"));
            return InteractionResult.SUCCESS;
        }

        // Виживий працює над генератором. use() приходить повторно,
        // поки тримається ПКМ — модуль сам розуміє, коли відпустили.
        if (match.isSurvivor(sp.getUUID()) && !state.getValue(ACTIVE)) {
            match.generatorModule().refreshRepair(sp, pos);
        }
        return InteractionResult.SUCCESS;
    }

    /** Вмикає світло блоку. Викликається модулем генераторів при завершенні. */
    public static void activateInWorld(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof GeneratorBlock) {
            level.setBlock(pos, state.setValue(ACTIVE, true), 3);
        }
    }
}
