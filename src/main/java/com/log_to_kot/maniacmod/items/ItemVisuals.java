package com.log_to_kot.maniacmod.items;

import com.log_to_kot.maniacmod.ManiacMod;
import net.minecraft.resources.ResourceLocation;

/**
 * Описи вигляду geo-предмета: де лежить модель, текстура й анімації та
 * як звуться анімації в файлі.
 *
 * ── Додати geo-предмет = одна конвенція ───────────────────────────────
 * {@link #of(String)} будує шляхи з id предмета:
 * <pre>
 *   assets/maniacmod/geo/item/&lt;id&gt;.geo.json
 *   assets/maniacmod/animations/item/&lt;id&gt;.animation.json
 *   assets/maniacmod/textures/item/&lt;id&gt;.png
 * </pre>
 * Тобто новий предмет — це клас-предмет із {@code GeoItem} (або з
 * {@link ItemArchetype}, як аптечка) і три файли з такими іменами плюс
 * один рядок {@code register("my_item", MyItem::new, ...)} в
 * {@link ItemRegistry}. Ані клієнтської моделі, ані рендерера писати не
 * треба — їх дає {@code client.renderer.ItemGeoRenderer}, якому досить
 * оцих шляхів.
 *
 * ── Анімована рука предмета ──────────────────────────────────────────
 * Це поле НЕ описує, чи в руці буде анімована рука гравця — це
 * властивість самого {@code .geo.json} (кістка {@code "RightArm"}/
 * {@code "LeftArm"} у файлі), а не окремий прапорець тут. Дивіться
 * {@code client.renderer.ItemGeoRenderer} клас-докстрінг і
 * {@code ArmOverride} щодо того, як художник це вмикає.
 *
 * ── Перевизначення ───────────────────────────────────────────────────
 * Якщо художник віддав файли з іншими іменами — предмет повертає свої
 * {@link ResourceLocation} ({@link #ItemVisuals}), і це ЄДИНА точка, де
 * шляхи можуть відрізнятись. Рівно той самий підхід, що
 * {@code ManiacVisuals} у маньяків.
 *
 * @param geoModel     файл Bedrock-геометрії
 * @param texture      PNG-текстура моделі
 * @param animations   файл анімацій (GeckoLib animation.json)
 * @param animationSet імена анімацій усередині цього файлу
 */
public record ItemVisuals(ResourceLocation geoModel, ResourceLocation texture,
                          ResourceLocation animations, ItemAnimationSet animationSet) {

    /** Вигляд за конвенцією: усе названо за id предмета. */
    public static ItemVisuals of(String id) {
        return new ItemVisuals(
            new ResourceLocation(ManiacMod.MOD_ID, "geo/item/" + id + ".geo.json"),
            new ResourceLocation(ManiacMod.MOD_ID, "textures/item/" + id + ".png"),
            new ResourceLocation(ManiacMod.MOD_ID, "animations/item/" + id + ".animation.json"),
            ItemAnimationSet.defaults());
    }
}
