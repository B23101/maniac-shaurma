package com.log_to_kot.maniacmod.client;

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
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HashSet;

/**
 * Лежача поза впалого виживого — ПОВНОТІЛА анімація через PlayerAnimator
 * (PAL), той самий підхід, що дроп-анімація у снайперах.
 *
 * ── Чому PAL-шар, а не запис кутів у setupAnim ───────────────────────
 * Раніше кути з {@code downed_lie.json} вручну писались у {@code PlayerModel}
 * в {@code setupAnim TAIL}. Туди ж пишуть поза ліхтарика, ванільна локомоція,
 * анімація пробудження тощо — тож поза «плавала» і накладалась на інші.
 * PAL-шар з високим пріоритетом веде ВСЕ тіло (голова, тулуб, руки, ноги)
 * окремим шаром: ніщо ванільне його не перебиває.
 *
 * ── Коли грає ────────────────────────────────────────────────────────
 * Лише поки гравець впав ({@link ClientMatchState#isFallen}, тобто
 * {@code CRAWLING}). Непритомний повзе ванільною постаттю повзання.
 * Кожен тік клієнт звіряє всіх гравців світу (свого й чужих) і вмикає /
 * вимикає їхній шар. Кліп грається один раз і застигає на останньому
 * кадрі ({@code "loop": "hold_on_last_frame"} у JSON).
 *
 * Файл: {@code assets/maniacmod/player_animation/downed_lie.json}
 * (папка в однині — саме її сканує PAL).
 *
 * Чисто косметика: клас нічого не вирішує в матчі.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DownedPoseAnimation {

    public static final ResourceLocation CLIP_ID =
        new ResourceLocation(ManiacMod.MOD_ID, "downed_lie");

    /** Високий пріоритет — шар лягає над усіма іншими PAL-шарами. */
    private static final int LAYER_PRIORITY = 5000;

    /** Шар конкретної СУТНОСТІ гравця (після респавну сутність нова, стек — теж). */
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

    private DownedPoseAnimation() {}

    /** Лишено для сумісності. */
    public static void registerActions() {}

    private static KeyframeAnimation clip() {
        try {
            return PlayerAnimationRegistry.getAnimation(CLIP_ID);
        } catch (Throwable t) {
            ManiacMod.LOGGER.warn("[ManiacMod] downed_lie: PAL недоступний: {}", t.toString());
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
        if (anim == null) return; // спробуємо знову наступного тіку
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
                if (ClientMatchState.isFallen(p.getUUID())) start(p);
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
            ManiacMod.LOGGER.warn("[ManiacMod] downed_lie tick: {}", t.toString());
        }
    }

    /** Скидання при виході з матчу — див. {@code ClientMatchState#reset}. */
    public static void reset() {
        for (Entry e : ENTRIES.values()) e.layer.setAnimation(null);
        ENTRIES.clear();
    }
}
