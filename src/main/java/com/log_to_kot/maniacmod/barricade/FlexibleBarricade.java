package com.log_to_kot.maniacmod.barricade;

import java.util.List;

/**
 * Зареєстрована ГНУЧКА барикада: набір дощок у довільних точках (на землі, на стіні, по діагоналі).
 * Не фізичні блоки — кастомний рендер ({@code BarricadeEntity}) плюс невидимі колайдери.
 *
 * @param id        ім'я для команд ({@code /maniac barricade remove <id>})
 * @param dimension id виміру ({@code minecraft:overworld})
 */
public record FlexibleBarricade(String id, String dimension, List<BoardSpec> boards) {

    public FlexibleBarricade {
        boards = List.copyOf(boards);
    }
}
