package com.log_to_kot.maniacmod.blocks;

import com.log_to_kot.maniacmod.game.ManiacGameManager;
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
 * Генератор — блок який виживаючі ремонтують (утримання ПКМ).
 *
 * Логіка ремонту:
 *   - Виживаючий тримає ПКМ на генераторі → ServerEventHandler.onServerTick()
 *     кожен тік детектує це через raycast і викликає ManiacGameManager.tickRepairOnBlock().
 *   - Прогрес відображається overlay'єм GeneratorProgressOverlay біля курсора.
 *   - ПКМ клік (use) залишено ТІЛЬКИ для маньяка (ламати активний генератор).
 *
 * Властивості блоку:
 *   ACTIVE — true коли генератор повністю відремонтований (світиться)
 */
public class GeneratorBlock extends Block {

    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    private static final VoxelShape SHAPE = Block.box(2, 0, 2, 14, 14, 14);

    public GeneratorBlock() {
        super(Properties.of()
            .mapColor(MapColor.METAL)
            .strength(3.5f, 6.0f)
            .sound(SoundType.METAL)
            .lightLevel(state -> state.getValue(ACTIVE) ? 10 : 0)
            .requiresCorrectToolForDrops()
        );
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

        // Тільки маньяк може ламати активний генератор кліком
        if (ManiacGameManager.isManiac(sp)) {
            if (state.getValue(ACTIVE)) {
                level.setBlock(pos, state.setValue(ACTIVE, false), 3);
                ManiacGameManager.deactivateGenerator(pos);
                sp.sendSystemMessage(Component.translatable("maniacmod.generator.broken"));
            } else {
                sp.sendSystemMessage(Component.translatable("maniacmod.generator.not_active"));
            }
            return InteractionResult.SUCCESS;
        }

        // Виживаючий — реєструємо/оновлюємо ремонт (use() викликається повторно поки тримаєш ПКМ)
        if (!state.getValue(ACTIVE)) {
            ManiacGameManager.refreshRepair(sp, pos);
        }
        return InteractionResult.SUCCESS;
    }

    /** Викликається з ManiacGameManager коли генератор активується — оновлює blockstate. */
    public static void activateInWorld(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof GeneratorBlock) {
            level.setBlock(pos, state.setValue(ACTIVE, true), 3);
        }
    }
}
