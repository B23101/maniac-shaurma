package com.log_to_kot.maniacmod.client.overlay.notify;

import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * "Генератор N з M полагоджено" — короткий overlay ПО ЦЕНТРУ екрана,
 * показується УСІМ виживим одночасно, щойно {@code GeneratorModule}
 * зафіксує завершення будь-якого генератора (обидві стадії —
 * {@code GeneratorModule.onGeneratorCompleted}, надходить через
 * {@code GeneratorCompletedPacket}).
 *
 * ── Навіщо окремий overlay, а не ActionBarPacket/AlertNotificationSystem ───
 * {@code ActionBarPacket} малює текст ВНИЗУ екрана (той самий рядок,
 * що ванільний action bar) — цього досить для дрібних технічних
 * повідомлень, але прогрес по генераторах — командна подія, яку
 * задумано помітною: великий текст ПО ЦЕНТРУ, як
 * {@code GeneratorProgressOverlay}/{@code GeneratorHintOverlay} вище
 * приціла. {@code AlertNotificationSystem} з shaurma-lib — стекований
 * kill-feed у кутку екрана (розрахований на серію коротких записів, що
 * накопичуються один під одним) — не підходить для одноразового,
 * великого, короткочасного банера, тому тут власний простий клас без
 * стекування: лише ОДИН активний показ за раз (новий показ перериває
 * попередній, якщо два генератори завершено підряд за секунди — це
 * рідкісний випадок, і показ нового важливіше за доспоглядання
 * попереднього fade-out).
 *
 * ── Таймінг: fade in → hold → fade out ─────────────────────────────────
 * Простий трикутний профіль альфи, порахований від
 * {@code System.currentTimeMillis()} у момент {@link #show} — навмисно
 * НЕ клієнтський тік-лічильник (як міг би здатись природним поруч із
 * рештою HUD-класів мода): тіки чіпляються до
 * {@code TickEvent.ClientTickEvent}, який у {@code ClientInputHandler}
 * навмисно НЕ рахується, коли {@code mc.screen != null} чи гравець
 * поза світом — цьому ж класу, навпаки, треба однаково коректно
 * згасати незалежно від того, відкрите зараз якесь меню чи ні. Час
 * системної години не має такої залежності й не потребує окремої
 * підписки на подію тіку взагалі — {@link #render} просто звіряє
 * {@code System.currentTimeMillis()} щокадру.
 */
@OnlyIn(Dist.CLIENT)
public final class GeneratorCompletedOverlay {

    private static final long FADE_IN_MS = 250;
    private static final long HOLD_MS = 2500;
    private static final long FADE_OUT_MS = 750;
    private static final long TOTAL_MS = FADE_IN_MS + HOLD_MS + FADE_OUT_MS;

    private static final int PANEL_WIDTH = 320;
    private static final int PANEL_HEIGHT = 46;
    private static final int ABOVE_CENTER_OFFSET = 70;

    private static String text = null;
    private static long shownAtMs = -1;

    private GeneratorCompletedOverlay() {}

    /** Викликається з {@code ClientPacketHandler.onGeneratorCompleted}. */
    public static void show(int index, int total) {
        text = Component.translatable("maniacmod.hud.generator.completed_toast", index, total).getString();
        shownAtMs = System.currentTimeMillis();
        // Короткий «поп» появи — банер має не просто з'явитись, а «в'їхати» зі звуком.
        com.log_to_kot.maniacmod.client.style.UiAnim.playAppear();
    }

    public static void render(GuiGraphics graphics) {
        if (text == null) return;
        long age = System.currentTimeMillis() - shownAtMs;
        if (age >= TOTAL_MS) {
            text = null;
            return;
        }

        float alpha = alphaAt(age);
        if (alpha <= 0.004f) return;

        Minecraft mc = Minecraft.getInstance();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        int panelX = screenW / 2 - PANEL_WIDTH / 2;
        int panelY = screenH / 2 - ABOVE_CENTER_OFFSET - PANEL_HEIGHT / 2;

        // Плавна поява з легким «відскоком»: банер виростає з 0.86 до 1.0
        // (easeOutBack) — не миттєвий хлопок, а сучасний pop-in.
        float grow = UiAnim
                .easeOutBack(Math.min(1f, age / (float) FADE_IN_MS), 0.6f);
        float scale = 0.86f + 0.14f * grow;

        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(screenW / 2f, panelY + PANEL_HEIGHT / 2f, 0f);
        pose.scale(scale, scale, 1f);
        pose.translate(-screenW / 2f, -(panelY + PANEL_HEIGHT / 2f), 0f);

        // Панель у стилі генератора + бар «життя» банера, що спорожнює за час показу.
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
        GeneratorFuelUiTheme.drawPanel(graphics, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT);
        graphics.drawCenteredString(mc.font, text,
            panelX + PANEL_WIDTH / 2, panelY + 9, GeneratorFuelUiTheme.TEXT);
        float life = 1f - Math.min(1f, age / (float) TOTAL_MS);
        GeneratorFuelUiTheme.drawBarFx(graphics, panelX + 10, panelY + PANEL_HEIGHT - 12, PANEL_WIDTH - 20, 5,
            life, life, GeneratorFuelUiTheme.FILL, Math.max(0f, 1f - age / 300f));
        // Розбіжне кільце при появі.
        if (age < 500) {
            GeneratorFuelUiTheme.pulseRing(graphics, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT,
                GeneratorFuelUiTheme.HIGHLIGHT, age / 500f, 12);
        }
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        pose.popPose();
    }

    private static float alphaAt(long ageMs) {
        if (ageMs < FADE_IN_MS) {
            return ageMs / (float) FADE_IN_MS;
        }
        if (ageMs < FADE_IN_MS + HOLD_MS) {
            return 1f;
        }
        long intoFadeOut = ageMs - FADE_IN_MS - HOLD_MS;
        return 1f - Math.min(1f, intoFadeOut / (float) FADE_OUT_MS);
    }

    /** Той самий прийом кодування альфи в старший байт ARGB, що {@code OverlayPanelStyle.applyAlpha} в бібліотеці. */
    private static int applyAlpha(int argb, float alpha) {
        int baseAlpha = (argb >>> 24) & 0xFF;
        int newAlpha = Math.round(baseAlpha * alpha);
        return (newAlpha << 24) | (argb & 0x00FFFFFF);
    }
}
