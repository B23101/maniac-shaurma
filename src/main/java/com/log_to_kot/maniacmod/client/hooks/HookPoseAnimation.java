package com.log_to_kot.maniacmod.client.hooks;

import com.log_to_kot.maniacmod.vr.VrServerCompat;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.hooks.HookStatus;
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
 * Пози жертви: {@code carried_shoulder} (лежить на плечі маньяка) і {@code hook_hang}
 * (висить на гаку). Той самий механізм, що {@code DownedPoseAnimation}/{@code RopeHangPoseAnimation}:
 * окремий PAL-шар, який щотіка вмикається/вимикається для всіх гравців світу за статусом із
 * {@code DownedSurvivorsPacket}. Вище за downed_lie (5000) і rope_hang (5100).
 *
 * Файли: {@code assets/maniacmod/player_animation/carried_shoulder.json} (ваша поза «тримає маніяк»:
 * кістка {@code body} -90° кладе корпус горизонтально) та {@code hook_hang.json} (поза «на гачку»:
 * кістка {@code torso} нахиляє тулуб, голова вниз). Імена файлів — ASCII: {@code ResourceLocation}
 * не приймає кирилицю, тому кириличні «тримає маніяк.json»/«на гачку.json» PAL просто не бачив.
 * Правиш у Blockbench (Animation → Export for PlayerAnimator) — зберігай під цими ж іменами.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class HookPoseAnimation {

    private static final ResourceLocation CARRIED_CLIP =
        new ResourceLocation(ManiacMod.MOD_ID, "carried_shoulder");
    private static final ResourceLocation HANG_CLIP =
        new ResourceLocation(ManiacMod.MOD_ID, "hook_hang");

    private static final int LAYER_PRIORITY = 5200;

    private static final class Entry {
        final AbstractClientPlayer player;
        final ModifierLayer<IAnimation> layer;
        HookStatus playing = HookStatus.NONE;

        Entry(AbstractClientPlayer player, ModifierLayer<IAnimation> layer) {
            this.player = player;
            this.layer = layer;
        }
    }

    private static final Map<UUID, Entry> ENTRIES = new HashMap<>();

    private HookPoseAnimation() {}

    private static KeyframeAnimation clip(ResourceLocation id) {
        try {
            return PlayerAnimationRegistry.getAnimation(id);
        } catch (Throwable t) {
            ManiacMod.LOGGER.warn("[ManiacMod] {}: PAL недоступний: {}", id, t.toString());
            return null;
        }
    }

    private static Entry entryFor(AbstractClientPlayer player) {
        if (VrServerCompat.isVrPlayer(player)) return null; // VR: модель веде Vivecraft
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

    private static void apply(AbstractClientPlayer player, HookStatus wanted) {
        if (wanted.isSeated()) {
            Entry e = entryFor(player);
            if (e == null || e.playing == wanted) return;
            KeyframeAnimation anim = clip(wanted == HookStatus.CARRIED ? CARRIED_CLIP : HANG_CLIP);
            if (anim == null) return; // спробуємо знову наступного тіку
            e.layer.setAnimation(new KeyframeAnimationPlayer(anim));
            e.playing = wanted;
        } else {
            Entry e = ENTRIES.get(player.getUUID());
            if (e == null || e.playing == HookStatus.NONE) return;
            e.layer.setAnimation(null);
            e.playing = HookStatus.NONE;
        }
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
                apply(p, ClientHooks.statusOf(p));
            }
            for (Iterator<Map.Entry<UUID, Entry>> it = ENTRIES.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<UUID, Entry> me = it.next();
                if (!present.contains(me.getKey())) {
                    me.getValue().layer.setAnimation(null);
                    it.remove();
                }
            }
        } catch (Throwable t) {
            ManiacMod.LOGGER.warn("[ManiacMod] hook pose tick: {}", t.toString());
        }
    }

    public static void reset() {
        for (Entry e : ENTRIES.values()) e.layer.setAnimation(null);
        ENTRIES.clear();
    }
}
