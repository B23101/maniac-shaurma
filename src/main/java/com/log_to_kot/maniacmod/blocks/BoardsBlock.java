package com.log_to_kot.maniacmod.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Заколочені дошки — ФІКСОВАНА барикада для дверей і стін. ПОДВІЙНИЙ блок (2 висоти, як двері):
 * обидві половини фізичні й блокують прохід.
 *
 * <h3>Як це працює в грі</h3>
 * Адмін ставить блок на двері/стіну, дивиться на нього й реєструє командою
 * {@code /maniac barricade door}. На початку кожного матчу {@code BarricadeModule} виставляє
 * зареєстровані блоки, а виживі ламають їх ломом (ПКМ) — ламаються одразу обидві половини.
 * Руками блок зламати не можна (міцність −1): лише лом у матчі або адмін у креативі.
 *
 * <h3>Форма</h3>
 * Тонка панель (3 px), притиснута до поверхні позаду: {@link #FACING} — куди панель «дивиться»
 * (від стіни/дверей до того, хто стоїть перед нею).
 */
public class BoardsBlock extends Block {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;

    // Панель лежить на боці, ПРОТИЛЕЖНОМУ до FACING (притиснута до поверхні позаду себе).
    private static final VoxelShape SHAPE_NORTH = Block.box(0, 0, 13, 16, 16, 16);
    private static final VoxelShape SHAPE_SOUTH = Block.box(0, 0, 0, 16, 16, 3);
    private static final VoxelShape SHAPE_WEST = Block.box(13, 0, 0, 16, 16, 16);
    private static final VoxelShape SHAPE_EAST = Block.box(0, 0, 0, 3, 16, 16);

    public BoardsBlock() {
        super(BlockBehaviour.Properties.of()
            .mapColor(MapColor.WOOD)
            .strength(-1.0F, 3_600_000.0F)
            .sound(SoundType.WOOD)
            .noLootTable()
            .noOcclusion()
            .pushReaction(PushReaction.BLOCK));
        registerDefaultState(stateDefinition.any()
            .setValue(FACING, Direction.NORTH)
            .setValue(HALF, DoubleBlockHalf.LOWER));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        Level level = context.getLevel();
        // Потрібне місце під ОБИДВІ половини.
        if (pos.getY() >= level.getMaxBuildHeight() - 1
            || !level.getBlockState(pos.above()).canBeReplaced(context)) {
            return null;
        }
        Direction face = context.getClickedFace();
        // Клікнули по боковій грані поверхні — панель притискається до неї. Клікнули по підлозі/стелі —
        // панель дивиться на гравця.
        Direction facing = face.getAxis().isHorizontal() ? face : context.getHorizontalDirection().getOpposite();
        return defaultBlockState().setValue(FACING, facing).setValue(HALF, DoubleBlockHalf.LOWER);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), 3);
    }

    /** Половини живуть парою: зникла одна — зникає й друга (як у дверей). */
    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighbor,
                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        DoubleBlockHalf half = state.getValue(HALF);
        boolean partnerSide = direction.getAxis() == Direction.Axis.Y
            && (half == DoubleBlockHalf.LOWER) == (direction == Direction.UP);
        if (!partnerSide) return state;
        if (neighbor.is(this) && neighbor.getValue(HALF) != half) return state;
        return Blocks.AIR.defaultBlockState();
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SHAPE_SOUTH;
            case WEST -> SHAPE_WEST;
            case EAST -> SHAPE_EAST;
            default -> SHAPE_NORTH;
        };
    }

    /** Нижня половина пари за будь-якою позицією (для реєстрації/зламування). */
    public static BlockPos lowerHalf(BlockState state, BlockPos pos) {
        return state.getValue(HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
    }
}
