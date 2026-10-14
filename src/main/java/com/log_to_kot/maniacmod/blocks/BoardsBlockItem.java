package com.log_to_kot.maniacmod.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Предмет «Дошки». Єдина відмінність від звичайного {@code BlockItem}: клік по ВЕРХНІЙ половині
 * дверей ставить пару так само, як клік по нижній (двері двовисокі, і дошки мають лягти на всю
 * висоту проходу, а не на верхню половину + повітря над дверима).
 */
public class BoardsBlockItem extends BlockItem {

    public BoardsBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public BlockPlaceContext updatePlacementContext(BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        if (!face.getAxis().isHorizontal()) return context;

        Level level = context.getLevel();
        // Блок, по якому клікнули: від позиції розміщення — крок назад. Якщо клікнули по замінюваному
        // блоку (трава), позиція розміщення збігається з ним — тоді це не двері, нічого не міняємо.
        BlockPos clicked = context.getClickedPos().relative(face.getOpposite());
        BlockState state = level.getBlockState(clicked);
        if (state.getBlock() instanceof DoorBlock
            && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
            return BlockPlaceContext.at(context, clicked.below(), face);
        }
        return context;
    }
}
