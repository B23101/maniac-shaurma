package com.log_to_kot.maniacmod.barricade;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Зареєстрована ФІКСОВАНА барикада — подвійний блок {@code maniacmod:boards}, поставлений на двері
 * чи стіну. Зберігаємо нижню половину й напрямок, щоб модуль міг виставити блок знову на старті
 * кожного матчу, коли виживі його вже поламали.
 *
 * @param dimension id виміру
 * @param pos       нижня половина блока
 * @param facing    куди «дивиться» панель (від поверхні, до якої притиснута)
 */
public record FixedBarricade(String dimension, BlockPos pos, Direction facing) {

    public FixedBarricade {
        pos = pos.immutable();
    }

    public boolean sameAs(String otherDimension, BlockPos other) {
        return dimension.equals(otherDimension) && (pos.equals(other) || pos.above().equals(other));
    }
}
