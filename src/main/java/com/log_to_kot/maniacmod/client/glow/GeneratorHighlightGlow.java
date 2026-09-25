package com.log_to_kot.maniacmod.client.glow;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.entity.GeneratorEntity;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.GeneratorHighlightPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Підсвітка генераторів для ВИЖИВОГО (клавіша 5) — реальний КОНТУР
 * сутності генератора крізь стіни, видимий лише тому, хто натиснув.
 *
 * ── Що це замінює ────────────────────────────────────────────────────
 * Раніше на екрані малювався ромб-маркер (екранна проєкція точки), а не
 * сама сутність. Тепер сервер, як і раніше, шле список позицій і станів
 * ОДНОМУ гравцю ({@code GeneratorHighlightPacket}), а клієнт лише
 * проєктує їх на завантажені сутності генераторів і передає в
 * {@link ClientEntityGlow} — далі контур малює ванільний рендер.
 *
 * ── Чому через id сутності, а не через позицію у рендері ─────────────
 * Міксин {@code EntityGlowMixin} працює з {@code Entity.getId()}, тож
 * єдиний місток «позиція з пакета → сутність» — саме тут. Позиція є
 * стабільним ключем (генератор не рухається), тому шукаємо сутність за
 * {@code generatorPos()}, а не змушуємо сервер смикати id у пакеті.
 *
 * ── Час ──────────────────────────────────────────────────────────────
 * Від {@code System.currentTimeMillis()}, а не від тіків клієнта: таймер
 * має згаснути вчасно навіть при відкритому меню, коли тіки не йдуть
 * (той самий принцип, що був у старому маркері).
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GeneratorHighlightGlow {

    /** Момент (мс), коли підсвітка гасне. 0 — не активна. */
    private static long activeUntilMs = 0;

    private static List<GeneratorHighlightPacket.Entry> entries = List.of();

    private GeneratorHighlightGlow() {}

    /** Викликається з {@code ClientPacketHandler} на кожен {@code GeneratorHighlightPacket}. */
    public static void show(List<GeneratorHighlightPacket.Entry> newEntries, int durationTicks) {
        entries = List.copyOf(newEntries);
        activeUntilMs = System.currentTimeMillis() + durationTicks * 50L;
        sync();
    }

    /** Кінець матчу / дисконект — гасить і дані, і сам контур. */
    public static void reset() {
        activeUntilMs = 0;
        entries = List.of();
        ClientEntityGlow.clear();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        if (activeUntilMs != 0 && System.currentTimeMillis() >= activeUntilMs) {
            reset();
            return;
        }
        if (activeUntilMs == 0) return;
        sync();
    }

    private static void sync() {
        Minecraft mc = Minecraft.getInstance();
        // Роль могла змінитись, поки підсвітка висіла (морф/конець матчу) —
        // чужій ролі генератори не світимо.
        if (mc.level == null || !ClientMatchState.isSurvivor()) {
            ClientEntityGlow.clear();
            return;
        }

        Map<Integer, Integer> next = new HashMap<>();
        for (GeneratorHighlightPacket.Entry entry : entries) {
            GeneratorEntity entity = findAt(mc, entry.pos());
            if (entity == null) continue; // чанк не завантажений клієнтом — світити нічого
            next.put(entity.getId(), ClientMatchState.highlightColor(entry.state()) & 0x00FFFFFF);
        }
        ClientEntityGlow.replace(next);
    }

    private static GeneratorEntity findAt(Minecraft mc, BlockPos pos) {
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof GeneratorEntity generator && generator.generatorPos().equals(pos)) {
                return generator;
            }
        }
        return null;
    }
}
