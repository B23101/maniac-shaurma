package com.log_to_kot.maniacmod.client.vr;

import com.log_to_kot.maniacmod.vr.VrClientConfig;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;

/**
 * VR: HUD (слоти, панелі, підказки, оверлеї) — «на екрані», а не на руці.
 *
 * ── Чому так грубо ───────────────────────────────────────────────────
 * Vivecraft малює ВЕСЬ 2D-інтерфейс (ваніль + усі Forge-оверлеї мода) в ОДИН
 * буфер і показує його однією площиною, яка за замовчуванням прив'язана до
 * зап'ястя (WRIST). Окремо розмістити один оверлей через його API неможливо.
 * Єдиний важіль — глобальний режим {@code vrHudLockMode}: WRIST / HAND / HEAD.
 * HEAD = площина висить перед обличчям (віртуальний «екран»).
 *
 * Поки йде матч (фаза не LOBBY/RESET), форсуємо HEAD; на виході з матчу,
 * з VR чи зі світу повертаємо те, що обрав гравець.
 *
 * ── Крихкість ────────────────────────────────────────────────────────
 * Це ВНУТРІШНІ класи Vivecraft ({@code ClientDataHolderVR}, {@code VRSettings}),
 * не публічний API: назви можуть змінитися між версіями. Тому все через
 * рефлексію з тихим відкатом — якщо поле не знайшлось, нічого не ламається,
 * просто HUD лишається там, де його поставив гравець. Налаштування на диск
 * ми не пишемо, але якщо гравець змінить будь-яку VR-опцію ПІД ЧАС матчу,
 * Vivecraft збереже й наш HEAD — на це варто пам'ятати.
 *
 * Рефлексія замість compileOnly на повний Vivecraft: публічний артефакт
 * містить лише API-jar, а не клієнтські класи.
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class VrHudLock {

    private static boolean resolved;
    private static boolean available;
    private static Object settings;          // VRSettings
    private static Field lockField;          // VRSettings#vrHudLockMode
    private static Object headValue;         // VRSettings.HUDLock.HEAD

    /** Що було до нас; null = ми зараз нічого не підмінюємо. */
    private static Object savedMode;

    private VrHudLock() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!VrCompat.isInstalled()) return;

        boolean want = shouldForceHead();
        if (want == (savedMode != null)) return; // нічого міняти

        if (!resolve()) return;
        try {
            if (want) {
                savedMode = lockField.get(settings);
                if (savedMode == null) return;
                lockField.set(settings, headValue);
            } else {
                lockField.set(settings, savedMode);
                savedMode = null;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            ManiacMod.LOGGER.warn("VR HUD lock: не вдалось змінити режим HUD", e);
            savedMode = null;
            available = false;
        }
    }

    private static boolean shouldForceHead() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return false;
        if (!VrCompat.isVrActive() || !VrClientConfig.hudHeadLock()) return false;
        GamePhase p = ClientMatchState.phase();
        return p != GamePhase.LOBBY && p != GamePhase.RESET;
    }

    /** Знаходить внутрішні поля Vivecraft один раз. */
    private static boolean resolve() {
        if (resolved) return available;
        resolved = true;
        try {
            Class<?> holder = Class.forName("org.vivecraft.client_vr.ClientDataHolderVR");
            Object instance = holder.getMethod("getInstance").invoke(null);
            settings = holder.getField("vrSettings").get(instance);
            lockField = settings.getClass().getField("vrHudLockMode");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object head = Enum.valueOf((Class<Enum>) lockField.getType(), "HEAD");
            headValue = head;
            available = settings != null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            ManiacMod.LOGGER.warn("VR HUD lock: внутрішній API Vivecraft не знайдено — HUD лишається "
                + "там, де його поставив гравець", e);
            available = false;
        }
        return available;
    }
}
