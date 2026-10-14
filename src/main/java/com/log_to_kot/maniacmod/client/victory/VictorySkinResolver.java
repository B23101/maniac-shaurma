package com.log_to_kot.maniacmod.client.victory;

import com.log_to_kot.maniacmod.victory.VictoryHookEntity;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.properties.Property;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Скін «опудала» на гачку. Порядок пошуку той самий, що в {@code OfflineSkinResolver} шауерма-ліби:
 * гравець у таб-листі → профіль зі синхронізованого {@code textures} → дефолтний скін за UUID.
 * Результат кешується (розбір base64 щокадру був би дорогий). Лише render-потік клієнта.
 */
@OnlyIn(Dist.CLIENT)
public final class VictorySkinResolver {

    private record Resolved(ResourceLocation skin, boolean slim) {}

    private static final int CACHE_LIMIT = 64;
    private static final Map<String, Resolved> CACHE = new HashMap<>();

    private VictorySkinResolver() {}

    public static ResourceLocation skin(VictoryHookEntity hook) {
        return skin(hook.skinUuid(), hook.skinTexture(), hook.skinSignature());
    }

    public static boolean slim(VictoryHookEntity hook) {
        return slim(hook.skinUuid(), hook.skinTexture(), hook.skinSignature());
    }

    /** Скін за UUID + синхронізованою властивістю {@code textures} (сутності сцен перемоги). */
    public static ResourceLocation skin(UUID uuid, String texture, String signature) {
        if (uuid == null) return DefaultPlayerSkin.getDefaultSkin();
        Minecraft mc = Minecraft.getInstance();
        PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(uuid);
        if (info != null) return info.getSkinLocation();
        return resolve(mc, uuid, texture, signature).skin();
    }

    public static boolean slim(UUID uuid, String texture, String signature) {
        if (uuid == null) return false;
        Minecraft mc = Minecraft.getInstance();
        PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(uuid);
        if (info != null) return "slim".equals(info.getModelName());
        return resolve(mc, uuid, texture, signature).slim();
    }

    private static Resolved resolve(Minecraft mc, UUID uuid, String texture, String signatureIn) {
        String key = uuid + "|" + texture;
        Resolved cached = CACHE.get(key);
        if (cached != null) return cached;

        GameProfile profile = new GameProfile(uuid, "victim");
        String value = texture == null ? "" : texture;
        if (!value.isEmpty()) {
            String signature = signatureIn == null ? "" : signatureIn;
            profile.getProperties().put("textures",
                new Property("textures", value, signature.isEmpty() ? null : signature));
        }
        ResourceLocation skin = mc.getSkinManager().getInsecureSkinLocation(profile);
        MinecraftProfileTexture tex = mc.getSkinManager().getInsecureSkinInformation(profile)
            .get(MinecraftProfileTexture.Type.SKIN);
        boolean slim = tex != null
            ? "slim".equals(tex.getMetadata("model"))
            : "slim".equals(DefaultPlayerSkin.getSkinModelName(uuid));
        Resolved resolved = new Resolved(skin, slim);
        if (CACHE.size() >= CACHE_LIMIT) CACHE.clear();
        CACHE.put(key, resolved);
        return resolved;
    }
}
