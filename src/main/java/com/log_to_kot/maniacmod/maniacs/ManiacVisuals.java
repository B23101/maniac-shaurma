package com.log_to_kot.maniacmod.maniacs;

import com.log_to_kot.maniacmod.ManiacMod;
import net.minecraft.resources.ResourceLocation;

/**
 * Описи вигляду маньяка: де лежить geo-модель, текстура, animation-файл
 * і як звуться анімації.
 *
 * ── Додати нового маньяка = одна конвенція ────────────────────────────
 * {@link #of(String)} будує шляхи з id архетипу:
 * <pre>
 *   assets/maniacmod/geo/entity/&lt;id&gt;.geo.json
 *   assets/maniacmod/animations/entity/&lt;id&gt;.animation.json
 *   assets/maniacmod/textures/entity/&lt;id&gt;.png
 * </pre>
 * Тобто новий маньяк — це клас-архетип з id {@code "my_maniac"} і три
 * файли з такими іменами. Нічого реєструвати не треба: клієнт шукає
 * вигляд за id, який уже прилітає в {@code RosterSyncPacket}.
 *
 * ── Перевизначення ───────────────────────────────────────────────────
 * Якщо художник віддав файли з іншими іменами — архетип перевизначає
 * {@link ManiacArchetype#visuals()} і повертає свої
 * {@link ResourceLocation}. Це єдина точка, де шляхи можуть відрізнятись.
 *
 * @param geoModel     файл Bedrock-геометрії
 * @param texture      PNG-текстура моделі
 * @param animations   файл анімацій (GeckoLib animation.json)
 * @param animationSet імена анімацій усередині цього файлу
 */
public record ManiacVisuals(ResourceLocation geoModel, ResourceLocation texture,
                            ResourceLocation animations, ManiacAnimationSet animationSet) {

    /** Вигляд за конвенцією: усе названо за id архетипу. */
    public static ManiacVisuals of(String id) {
        return new ManiacVisuals(
            new ResourceLocation(ManiacMod.MOD_ID, "geo/entity/" + id + ".geo.json"),
            new ResourceLocation(ManiacMod.MOD_ID, "textures/entity/" + id + ".png"),
            new ResourceLocation(ManiacMod.MOD_ID, "animations/entity/" + id + ".animation.json"),
            ManiacAnimationSet.defaults());
    }
}
