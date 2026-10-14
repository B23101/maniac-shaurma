package com.log_to_kot.maniacmod.client.glow;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Map;

/**
 * Шар підсвітки вільних гаків для маньяка, що несе жертву. Живе в цьому ж пакеті, бо
 * {@code ClientEntityGlow#setLayer} навмисно package-private (шар пише лише свій власник).
 */
@OnlyIn(Dist.CLIENT)
public final class HookHighlightGlow {

    public static final String LAYER = "hook_free";

    private HookHighlightGlow() {}

    public static void set(Map<Integer, Integer> idToRgb) {
        ClientEntityGlow.setLayer(LAYER, idToRgb);
    }

    public static void clear() {
        ClientEntityGlow.clearLayer(LAYER);
    }
}
