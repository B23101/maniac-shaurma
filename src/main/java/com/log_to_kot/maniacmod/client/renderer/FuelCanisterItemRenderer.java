package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.items.FuelCanisterItem;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.renderer.GeoItemRenderer;

/**
 * Рендерер предмета каністри з бензином.
 *
 * Найпростіший можливий {@link GeoItemRenderer} — на відміну від
 * {@code dev.shaurmalib.forge.item.AnimatedGeoItemRenderer}, каністра
 * не заміщує руку гравця скіном (немає {@code ArmOverride}, вона не
 * зброя-з-анімованою-рукою), тому додаткова логіка бібліотеки тут не
 * потрібна — предмет рендериться як звичайна geo-модель в руці/на
 * землі/в GUI, той самий базовий шлях, що GeckoLib дає з коробки.
 */
@OnlyIn(Dist.CLIENT)
public class FuelCanisterItemRenderer extends GeoItemRenderer<FuelCanisterItem> {

    public FuelCanisterItemRenderer() {
        super(new FuelCanisterGeoModel());
    }
}
