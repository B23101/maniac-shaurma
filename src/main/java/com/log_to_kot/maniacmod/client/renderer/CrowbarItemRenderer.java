package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.items.CrowbarItem;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.renderer.GeoItemRenderer;

/** Рендерер лома — найпростіший {@link GeoItemRenderer}, як у каністри. */
@OnlyIn(Dist.CLIENT)
public class CrowbarItemRenderer extends GeoItemRenderer<CrowbarItem> {

    public CrowbarItemRenderer() {
        super(new CrowbarGeoModel());
    }
}
