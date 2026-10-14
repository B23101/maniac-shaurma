package com.log_to_kot.maniacmod.vr;

import com.log_to_kot.maniacmod.ManiacMod;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;

/**
 * ЄДИНЕ місце, що торкається публічного API Vivecraft ({@code org.vivecraft.api}) —
 * через рефлексію, а не compile-time залежність.
 *
 * ── Чому рефлексія ───────────────────────────────────────────────────
 *  • Збірка мода НЕ залежить від Vivecraft: немає jar у libs/, немає
 *    publishToMavenLocal, немає питань SRG-vs-official маплінгів. Хто VR не
 *    потребує — збирає й запускає мод так само, як і раніше.
 *  • Інтерфейс API стабільний (версіонований), використано 8 методів. Якщо
 *    щось змінилось у версії Vivecraft — відповідна частина тихо вимикається,
 *    мод працює далі.
 *
 * ── Безпека ──────────────────────────────────────────────────────────
 * Викликається лише коли Vivecraft ЗАВАНТАЖЕНИЙ (перевіряють фасади
 * {@link VrServerCompat} і {@code VrCompat}). Клієнтська й спільна частини API
 * резолвляться незалежно: збій однієї не вимикає іншу. Помилка виклику не
 * вимикає міст назавжди — лише повертає значення за замовчуванням.
 */
final class VrApiReflect {

    private VrApiReflect() {}

    // ── Клієнтський API (VRClientAPI) ────────────────────────────────

    private static boolean clientResolved;
    private static Object clientApi;
    private static Method isVRActive;
    private static Method triggerHaptic;
    private static Method postTickWorldPose;
    private static Object partMain;
    private static Object partOff;

    // ── Спільний API (VRAPI) ─────────────────────────────────────────

    private static boolean commonResolved;
    private static Object commonApi;
    private static Method isVRPlayer;
    private static Method getVRPose;

    // ── Структури поз ────────────────────────────────────────────────

    private static boolean poseResolved;
    private static Method poseMainHand;
    private static Method partPos;
    private static Method partDir;

    // ── Поточний прохід рендеру (внутрішнє поле Vivecraft, не публічний API) ──

    private static boolean passResolved;
    private static Object passHolder;
    private static java.lang.reflect.Field passField;

    private static boolean warned;

    private static synchronized boolean client() {
        if (clientResolved) return clientApi != null;
        clientResolved = true;
        try {
            Class<?> apiClass = Class.forName("org.vivecraft.api.client.VRClientAPI");
            Class<?> partClass = Class.forName("org.vivecraft.api.data.VRBodyPart");
            Object api = apiClass.getMethod("instance").invoke(null);
            isVRActive = apiClass.getMethod("isVRActive");
            triggerHaptic = apiClass.getMethod("triggerHapticPulse",
                partClass, float.class, float.class, float.class, float.class);
            postTickWorldPose = apiClass.getMethod("getPostTickWorldPose");
            partMain = enumConstant(partClass, "MAIN_HAND");
            partOff = enumConstant(partClass, "OFF_HAND");
            clientApi = api;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            warnOnce("VRClientAPI", e);
            clientApi = null;
        }
        return clientApi != null;
    }

    private static synchronized boolean common() {
        if (commonResolved) return commonApi != null;
        commonResolved = true;
        try {
            Class<?> apiClass = Class.forName("org.vivecraft.api.VRAPI");
            Object api = apiClass.getMethod("instance").invoke(null);
            isVRPlayer = apiClass.getMethod("isVRPlayer", Player.class);
            getVRPose = apiClass.getMethod("getVRPose", Player.class);
            commonApi = api;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            warnOnce("VRAPI", e);
            commonApi = null;
        }
        return commonApi != null;
    }

    private static synchronized boolean pose() {
        if (poseResolved) return poseMainHand != null;
        poseResolved = true;
        try {
            Class<?> poseClass = Class.forName("org.vivecraft.api.data.VRPose");
            Class<?> dataClass = Class.forName("org.vivecraft.api.data.VRBodyPartData");
            partPos = dataClass.getMethod("getPos");
            partDir = dataClass.getMethod("getDir");
            poseMainHand = poseClass.getMethod("getMainHand");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            warnOnce("VRPose", e);
            poseMainHand = null;
        }
        return poseMainHand != null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(Class<?> enumClass, String name) {
        return Enum.valueOf((Class<Enum>) enumClass, name);
    }

    // ── Клієнт ───────────────────────────────────────────────────────

    static boolean isVrActive() {
        if (!client()) return false;
        try {
            return (Boolean) isVRActive.invoke(clientApi);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    static void haptic(boolean mainHand, float seconds, float amplitude) {
        if (!client() || !isVrActive()) return;
        try {
            triggerHaptic.invoke(clientApi, mainHand ? partMain : partOff, seconds, 160F, amplitude, 0F);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // вібрація — косметика; не ламаємо гру
        }
    }

    static Vec3 clientMainHandPos() { return clientHand(true); }
    static Vec3 clientMainHandDir() { return clientHand(false); }

    private static Vec3 clientHand(boolean position) {
        if (!client() || !pose() || !isVrActive()) return null;
        try {
            Object worldPose = postTickWorldPose.invoke(clientApi);
            return handVec(worldPose, position);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Чи зараз малюється вигляд ОЧЕЙ (LEFT/RIGHT/CENTER), а не дзеркало, третя особа чи
     * камера. Публічний API Vivecraft цього не віддає, тож читаємо його внутрішнє поле
     * {@code ClientDataHolderVR.currentPass}. Якщо поле не знайшлось — {@code true}
     * (малюємо як раніше, без фільтра).
     */
    static synchronized boolean isFirstPersonPass() {
        if (!passResolved) {
            passResolved = true;
            try {
                Class<?> holder = Class.forName("org.vivecraft.client_vr.ClientDataHolderVR");
                passHolder = holder.getMethod("getInstance").invoke(null);
                passField = holder.getField("currentPass");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                passHolder = null;
                passField = null;
            }
        }
        if (passField == null || passHolder == null) return true;
        try {
            Object pass = passField.get(passHolder);
            if (!(pass instanceof Enum<?> e)) return true;
            String n = e.name();
            return n.equals("LEFT") || n.equals("RIGHT") || n.equals("CENTER");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    // ── Спільна (сервер + клієнт) частина ────────────────────────────

    static boolean isVrPlayer(Player player) {
        if (!common()) return false;
        try {
            return (Boolean) isVRPlayer.invoke(commonApi, player);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    static Vec3 playerHandPos(Player player) { return playerHand(player, true); }
    static Vec3 playerHandDir(Player player) { return playerHand(player, false); }

    private static Vec3 playerHand(Player player, boolean position) {
        if (!common() || !pose() || !isVrPlayer(player)) return null;
        try {
            return handVec(getVRPose.invoke(commonApi, player), position);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** VRPose → головна рука → позиція/напрямок; null на будь-якій відсутній ланці. */
    private static Vec3 handVec(Object vrPose, boolean position) throws ReflectiveOperationException {
        if (vrPose == null) return null;
        Object hand = poseMainHand.invoke(vrPose);
        if (hand == null) return null;
        return (Vec3) (position ? partPos : partDir).invoke(hand);
    }

    private static void warnOnce(String what, Throwable e) {
        if (warned) return;
        warned = true;
        ManiacMod.LOGGER.warn("[ManiacMod] VR: API Vivecraft ({}) недоступний — VR-функції цієї частини вимкнено", what, e);
    }
}
