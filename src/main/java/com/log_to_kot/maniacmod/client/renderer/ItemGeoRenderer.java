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
 * ── Рука предмета = частина його geo-моделі, НЕ окремий шар ─────────
 * Синхронність "рука + предмет" тут забезпечується НЕ вимкненням
 * геометрії в руці (як у попередній, ПОМИЛКОВІЙ версії цього класу —
 * див. нижче), а навпаки: аніматор включає кістку з іменем
 * {@code "RightArm"}/{@code "LeftArm"} ПРЯМО В ту саму geo-ієрархію, що
 * й сама геометрія предмета (наприклад лома), в тому самому
 * Blockbench-файлі й тому самому {@code .animation.json}. Одна модель,
 * один таймлайн — рука фізично не може розійтись із предметом, бо це
 * одна кістка-батько з тими самими keyframe-даними.
 * <p>
 * Сам рушій рендеру (див. {@code AnimatedGeoItemRenderer} у lib-forge)
 * автоматично підставляє на цю кістку скін РЕАЛЬНОЇ шкіри гравця
 * замість геометрії — художнику досить дати кістці правильне ім'я,
 * жодного коду. Прив'язка до конкретної руки (яку саме ховати —
 * MAIN_HAND чи OFF_HAND) описана в {@code ItemDefinition.armOverride}
 * ({@link dev.shaurmalib.common.item.ArmOverride}) — див. звідти щодо
 * перемикання предмета в іншу руку (swapHands, F).
 * <p>
 * ЦЕЙ клас ({@code ItemGeoRenderer}, а не {@code AnimatedGeoItemRenderer})
 * використовується для geo-предметів, яким рука-в-моделі НЕ потрібна —
 * предмет, що лежить у руці "як є" без анімованого хвату (декоративні
 * предмети, ключі тощо). Якщо предмету потрібна анімована рука в
 * ГЕОМЕТРІЇ (лом, аптечка), реєструйте його через
 * {@code AnimatedGeoItemRenderer} замість цього класу — див. приклад
 * у {@code AnimatedGeoItemRenderer} клас-докстрінгу.
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
