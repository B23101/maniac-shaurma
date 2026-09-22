package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.entity.BearTrapEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Рендерер капкана. На відміну від {@link GeneratorRenderer}, власного
 * повороту не має: капкан симетричний, тож напрямок не помітний, і
 * зайвий код повороту нічого б не давав.
 */
@OnlyIn(Dist.CLIENT)
public class BearTrapRenderer extends GeoEntityRenderer<BearTrapEntity> {

    public BearTrapRenderer(EntityRendererProvider.Context context) {
        super(context, new BearTrapGeoModel());
        // Плоский предмет на землі — велика тінь виглядала б дивно.
        this.shadowRadius = 0.3f;
    }
}
