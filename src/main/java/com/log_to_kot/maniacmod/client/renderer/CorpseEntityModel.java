package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.entity.CorpseEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.model.GeoModel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GeoModel for CorpseEntity.
 *
 * Uses geo/entity/corpse.geo.json (player skeleton rotated flat).
 * Texture: dynamically resolved to the dead player's skin.
 *
 * Skin resolution order:
 *  1. Look up the player by name in the current player list (online → exact skin)
 *  2. Fall back to default Steve skin
 *
 * The skin texture is cached per player name so we don't query every frame.
 */
@OnlyIn(Dist.CLIENT)
public class CorpseEntityModel extends GeoModel<CorpseEntity> {

    private static final ResourceLocation CORPSE_GEO =
        new ResourceLocation(ManiacMod.MOD_ID, "geo/entity/corpse.geo.json");

    private static final ResourceLocation CORPSE_ANIM =
        new ResourceLocation(ManiacMod.MOD_ID, "animations/entity/corpse.animation.json");

    private static final ResourceLocation DEFAULT_SKIN =
        new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png");

    /** Cache: playerName → skin ResourceLocation */
    private static final Map<String, ResourceLocation> skinCache = new ConcurrentHashMap<>();

    @Override
    public ResourceLocation getModelResource(CorpseEntity entity) {
        return CORPSE_GEO;
    }

    @Override
    public ResourceLocation getTextureResource(CorpseEntity entity) {
        String name = entity.getDeadPlayerName();
        if (name == null || name.isEmpty()) return DEFAULT_SKIN;

        // Check cache
        if (skinCache.containsKey(name)) return skinCache.get(name);

        // Try to find the player in the current game's player list
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            var playerInfo = mc.getConnection() != null
                ? mc.getConnection().getOnlinePlayers().stream()
                    .filter(p -> p.getProfile().getName().equalsIgnoreCase(name))
                    .findFirst().orElse(null)
                : null;

            if (playerInfo != null) {
                ResourceLocation skin = playerInfo.getSkinLocation();
                skinCache.put(name, skin);
                return skin;
            }
        }

        // Fallback: use Steve skin
        return DEFAULT_SKIN;
    }

    @Override
    public ResourceLocation getAnimationResource(CorpseEntity entity) {
        return CORPSE_ANIM;
    }

    /** Clear cache between games so skins reload fresh */
    public static void clearCache() { skinCache.clear(); }
}
