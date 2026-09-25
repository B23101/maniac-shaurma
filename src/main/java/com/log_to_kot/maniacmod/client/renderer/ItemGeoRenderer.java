package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.items.ItemVisuals;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.renderer.GeoItemRenderer;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Рендерер БУДЬ-ЯКОГО geo-предмета мода — один на всі.
 *
 * ── Шлях рендера ─────────────────────────────────────────────────────
 * {@code GeoItemRenderer} — це {@code BlockEntityWithoutLevelRenderer},
 * і Forge викликає його з {@code IClientItemExtensions.getCustomRenderer()}
 * для будь-якого контексту, крім ванільного GUI-спрайта: у руці (перша й
 * третя особа), на землі, у рамці тощо. Модель береться з
 * {@link ItemGeoModel}, тобто з {@link ItemVisuals}.
 *
 * ── Прив'язка до предмета ────────────────────────────────────────────
 * {@link #attach} — той самий код, який інакше копіювався б у КОЖЕН
 * предмет: один ліниво створений рендерер на предмет (GeckoLib тримає в
 * рендерері стан поточного кадру — {@code currentItemStack},
 * трансформації, — тож створювати його на кожен виклик не можна).
 * Предмет лише пише:
 * <pre>
 *   &#64;Override
 *   public void initializeClient(Consumer&lt;IClientItemExtensions&gt; consumer) {
 *       ItemGeoRenderer.attach(consumer, () -&gt; new ItemGeoRenderer&lt;&gt;(VISUALS));
 *   }
 * </pre>
 * де {@code VISUALS = ItemVisuals.of("medkit")}.
 */
@OnlyIn(Dist.CLIENT)
public class ItemGeoRenderer<T extends Item & GeoAnimatable> extends GeoItemRenderer<T> {

    private final ItemVisuals visuals;

    public ItemGeoRenderer(ItemVisuals visuals) {
        super(new ItemGeoModel<>(visuals));
        this.visuals = visuals;
    }

    /** Вигляд, з яким створено цей рендерер (для діагностики й декораторів). */
    public ItemVisuals visuals() {
        return visuals;
    }

    /**
     * Готовий {@code initializeClient} для geo-предмета: передати сюди
     * фабрику рендерера і {@code Consumer} із Forge — один екземпляр буде
     * створено ліниво, при першому запиті клієнта.
     *
     * @param factory чому фабрика, а не готовий рендерер: конструктор
     *                {@code GeoItemRenderer} дістає
     *                {@code Minecraft.getInstance()} (диспетчер
     *                block-entity рендерів), а {@code initializeClient}
     *                викликається Forge-ом досить рано — створювати
     *                рендерер у ту мить ризиковано
     */
    public static <T extends Item & GeoAnimatable> void attach(
            Consumer<IClientItemExtensions> consumer, Supplier<ItemGeoRenderer<T>> factory) {
        consumer.accept(new IClientItemExtensions() {
            private ItemGeoRenderer<T> renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) renderer = factory.get();
                return renderer;
            }
        });
    }
}
