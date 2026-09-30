package com.log_to_kot.maniacmod.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

/**
 * Не дає гравцю СТРИБНУТИ, коли міні-гра закрилась, а він ще тримає пробіл.
 *
 * ── Звідки баг ───────────────────────────────────────────────────────
 * Міні-гра керується пробілом. Останнє влучання гравець робить пробілом
 * і ще секунду-дві тримає його затиснутим. Сервер відповідає, екран
 * закривається — і {@code Minecraft.setScreen(null)} викликає
 * {@code KeyMapping.setAll()}, який перечитує РЕАЛЬНИЙ стан клавіш.
 * Пробіл усе ще натиснутий → {@code keyJump.isDown()} стає true →
 * персонаж стрибає одразу після перемоги.
 *
 * ── Що робить клас ───────────────────────────────────────────────────
 * {@link #armIfSpaceHeld()} викликається із {@code TargetMinigameScreen#removed}.
 * Якщо пробіл у цю мить затиснуто, вмикається «вартовий»: поки пробіл не
 * ВІДПУЩЕНО, {@code MixinKeyboardInputMinigameJump} глушить стрибок
 * ({@link #shouldSuppressJump()}). Відпустив — вартовий сам вимикається,
 * наступне натискання стрибає як завжди. Запобіжник {@link #MAX_MS}
 * знімає блок, навіть якщо клавіша «залипла».
 *
 * Чисто клієнтська зручність, нічого не вирішує в матчі.
 */
@OnlyIn(Dist.CLIENT)
public final class MinigameJumpGuard {

    /** Максимум, скільки блок може тривати, мс (запобіжник від залипання). */
    private static final long MAX_MS = 3000;

    private static boolean armed = false;
    private static long armedAtMillis = 0;

    private MinigameJumpGuard() {}

    /** Викликати в момент закриття міні-гри. */
    public static void armIfSpaceHeld() {
        if (isSpaceDown()) {
            armed = true;
            armedAtMillis = System.currentTimeMillis();
        }
    }

    /** true — цього тіку стрибок треба заглушити. Сам знімає блок, коли пробіл відпущено. */
    public static boolean shouldSuppressJump() {
        if (!armed) return false;
        if (!isSpaceDown() || System.currentTimeMillis() - armedAtMillis > MAX_MS) {
            armed = false;
            return false;
        }
        return true;
    }

    private static boolean isSpaceDown() {
        long window = Minecraft.getInstance().getWindow().getWindow();
        return GLFW.glfwGetKey(window, GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS;
    }
}
