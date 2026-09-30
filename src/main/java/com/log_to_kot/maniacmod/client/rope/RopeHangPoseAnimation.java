package com.log_to_kot.maniacmod.client.rope;

import com.log_to_kot.maniacmod.ManiacMod;
import dev.kosmx.playerAnim.api.layered.AnimationStack;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Поза «підвішений догори ногами» — повнотілова PAL-анімація
 * ({@code assets/maniacmod/player_animation/rope_hang.json}), той самий
 * механізм, що {@code DownedPoseAnimation}: окремий шар з високим
 * пріоритетом, який вмикається/вимикається щотіка для всіх гравців світу.
 *
 * Клип перевертає {@code body} на 180° по X, піднімає руки (у світі вони
 * звисають вниз) і зсуває модель, щоб голова висіла біля низу хітбокса.
 * Кут нахилу маятника додається окремо в {@link RopeHangClient}.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RopeHangPoseAnimation {

    public static final ResourceLocation CLIP_ID =
        new ResourceLocation(ManiacMod.MOD_ID, "rope_hang");

    /** Вище за downed_lie (5000): підвішений не може одночасно лежати. */
    private static final int LAYER_PRIORITY = 5100;

    private static final class Entry {
        final AbstractClientPlayer player;
        final ModifierLayer<IAnimation> layer;
        boolean playing;

        Entry(AbstractClientPlayer player, ModifierLayer<IAnimation> layer) {
            this.player = player;
            this.layer = layer;
        }
    }

    private static final Map<UUID, Entry> ENTRIES = new HashMap<>();

    private RopeHangPoseAnimation() {}

    private static KeyframeAnimation clip() {
        try {
            return PlayerAnimationRegistry.getAnimation(CLIP_ID);
        } catch (Throwable t) {
            ManiacMod.LOGGER.warn("[ManiacMod] rope_hang: PAL недоступний: {}", t.toString());
            return null;
        }
    }

    private static Entry entryFor(AbstractClientPlayer player) {
        Entry e = ENTRIES.get(player.getUUID());
        if (e != null && e.player == player) return e;
        AnimationStack stack = PlayerAnimationAccess.getPlayerAnimLayer(player);
        if (stack == null) return null;
        ModifierLayer<IAnimation> layer = new ModifierLayer<>();
        stack.addAnimLayer(LAYER_PRIORITY, layer);
        e = new Entry(player, layer);
        ENTRIES.put(player.getUUID(), e);
        return e;
    }

    private static void start(AbstractClientPlayer player) {
        Entry e = entryFor(player);
        if (e == null || e.playing) return;
        KeyframeAnimation anim = clip();
        if (anim == null) return;
        e.layer.setAnimation(new KeyframeAnimationPlayer(anim));
        e.playing = true;
    }

    private static void stop(AbstractClientPlayer player) {
        Entry e = ENTRIES.get(player.getUUID());
        if (e == null || !e.playing) return;
        e.layer.setAnimation(null);
        e.playing = false;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            ENTRIES.clear();
            return;
        }
        try {
            Set<UUID> present = new HashSet<>();
            for (AbstractClientPlayer p : level.players()) {
                present.add(p.getUUID());
                if (RopeHangClient.isHung(p)) start(p);
                else stop(p);
            }
            for (Iterator<Map.Entry<UUID, Entry>> it = ENTRIES.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<UUID, Entry> me = it.next();
                if (!present.contains(me.getKey())) {
                    me.getValue().layer.setAnimation(null);
                    it.remove();
                }
            }
        } catch (Throwable t) {
            ManiacMod.LOGGER.warn("[ManiacMod] rope_hang tick: {}", t.toString());
        }
    }

    public static void reset() {
        for (Entry e : ENTRIES.values()) e.layer.setAnimation(null);
        ENTRIES.clear();
    }
}
