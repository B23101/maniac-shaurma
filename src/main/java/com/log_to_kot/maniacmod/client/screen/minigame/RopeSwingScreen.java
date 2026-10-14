package com.log_to_kot.maniacmod.client.screen.minigame;

import com.log_to_kot.maniacmod.client.vr.VrCompat;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.rope.RopeHangClient;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.UiFx;
import com.log_to_kot.maniacmod.entity.RopeTrapEntity;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.c2s.intent.RopeSwingPacket;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Міні-гра «Розкачайся» (відкривається по F) — у стилі решти меню мода.
 *
 * ── Вигляд ───────────────────────────────────────────────────────────
 * Те саме, що в міні-грі капкана: темна панель {@link ManiacUiTheme}
 * (ігровий світ ПОЗАДУ, без ванільного фону), заголовок капсом +
 * роздільник, поява «підйом + масштаб», окрема панель-підказка знизу.
 * Ліворуч — КАРТИНКА підвішеного персонажа на нитці
 * ({@code textures/gui/rope/hanging_player.png}, 32×64, вертикальна
 * нитка зверху): її просто гойдає кут із сервера. Праворуч — смужка
 * розкачування й дві клавіші A / D, між якими блимає та, що потрібна зараз.
 *
 * ── Логіка ───────────────────────────────────────────────────────────
 * Екран нічого не вирішує: кут і межа розриву приходять із сервера
 * через {@link RopeTrapEntity}, клавіші лише шлються пакетом. Автоповтор
 * утриманої клавіші ігнорується (інакше його б рахувало як спам).
 * Закривається сам, коли гравця звільнено; ESC — просто сховати меню.
 */
@OnlyIn(Dist.CLIENT)
public final class RopeSwingScreen extends Screen {

    private static final ResourceLocation CHARACTER =
        new ResourceLocation(ManiacMod.MOD_ID, "textures/gui/rope/hanging_player.png");
    private static final int TEX_W = 32, TEX_H = 64, SCALE = 2;

    private static final int PANEL_W = 312;
    private static final int PANEL_H = 206;
    private static final int HINT_H = 26;
    private static final int HINT_GAP = 10;
    private static final int PAD = 16;
    private static final int LEFT_W = 112;

    private static final long INTRO_MS = 320;

    private final long openedAt = System.currentTimeMillis();
    private final long[] frameClock = new long[1];

    private float lastTheta;
    private float velocity;
    private float flashA, flashD;       // 0..1, спалах клавіші після натискання
    private boolean heldA, heldD;
    private int notHungTicks;

    public RopeSwingScreen() {
        super(Component.translatable("maniacmod.rope.title"));
    }

    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof RopeSwingScreen) return;
        if (!RopeHangClient.isLocalHung()) return;
        mc.setScreen(new RopeSwingScreen());
        UiAnim.playAppear();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        // Грейс 10 тіків: сутність могла ще не доїхати до клієнта.
        notHungTicks = RopeHangClient.isLocalHung() ? 0 : notHungTicks + 1;
        if (notHungTicks > 10) onClose();
    }

    // ── Введення ─────────────────────────────────────────────────────────

    private boolean isLeft(int key, int scan) {
        return Minecraft.getInstance().options.keyLeft.matches(key, scan) || key == GLFW.GLFW_KEY_LEFT;
    }

    private boolean isRight(int key, int scan) {
        return Minecraft.getInstance().options.keyRight.matches(key, scan) || key == GLFW.GLFW_KEY_RIGHT;
    }

    private void pressLeft() {
        if (!heldA) {
            heldA = true;
            flashA = 1f;
            UiAnim.playClick();
            ModNetwork.toServer(new RopeSwingPacket((byte) -1));
        }
    }

    private void pressRight() {
        if (!heldD) {
            heldD = true;
            flashD = 1f;
            UiAnim.playClick();
            ModNetwork.toServer(new RopeSwingPacket((byte) 1));
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (isLeft(keyCode, scanCode)) {
            pressLeft();
            return true;
        }
        if (isRight(keyCode, scanCode)) {
            pressRight();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (isLeft(keyCode, scanCode)) { heldA = false; return true; }
        if (isRight(keyCode, scanCode)) { heldD = false; return true; }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    /** Клік не «провалюється» у світ під панеллю. */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // VR: A/D немає — клік лівою/правою половиною екрана = розкачування вліво/вправо.
        if (VrCompat.isVrActive() && button == 0) {
            if (mouseX < this.width / 2.0) pressLeft(); else pressRight();
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (VrCompat.isVrActive() && button == 0) { heldA = false; heldD = false; }
        return true;
    }

    // ── Малювання ────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Навмисно без renderBackground: світ лишається видно позаду, як у міні-грі капкана.
        long now = System.currentTimeMillis();
        float dt = UiAnim.frameDt(frameClock);

        RopeTrapEntity rope = RopeHangClient.localRope();
        float theta = rope == null ? 0f : rope.swing(partialTick);
        float limit = rope == null ? 0.8f : Math.max(0.1f, rope.breakAngle());
        float energy = Mth.clamp(Math.abs(theta) / limit, 0f, 1f);

        velocity = Mth.lerp(0.25f, velocity, theta - lastTheta);
        lastTheta = theta;
        flashA = UiAnim.approach(flashA, 0f, 7f, dt);
        flashD = UiAnim.approach(flashD, 0f, 7f, dt);

        int panelX = width / 2 - PANEL_W / 2;
        int panelY = height / 2 - PANEL_H / 2 - (HINT_H + HINT_GAP) / 2;

        float intro = UiAnim.clamp01((now - openedAt) / (float) INTRO_MS);
        float ease = UiAnim.easeOutCubic(intro);

        // Тремтіння панелі, коли нитка от-от порветься.
        float shake = 0f;
        if (energy > 0.85f) shake = (float) Math.sin(now / 38.0) * (energy - 0.85f) * 10f;

        g.pose().pushPose();
        g.pose().translate(shake, 0f, 0f);
        GeneratorFuelUiTheme.beginAppear(g, panelX + PANEL_W / 2f, panelY + PANEL_H / 2f, ease, 14f);

        ManiacUiTheme.drawPanel(g, panelX, panelY, PANEL_W, PANEL_H);
        if (energy > 0.7f) {
            float a = (energy - 0.7f) / 0.3f * (0.5f + 0.5f * (float) Math.sin(now / 140.0));
            UiFx.innerGlow(g, panelX, panelY, PANEL_W, PANEL_H, 0xB0242F, a, 6);
        }
        int contentY = ManiacUiTheme.drawTitle(g, font, title, panelX, panelY, PANEL_W);

        drawCharacter(g, panelX + PAD, contentY, theta, energy);
        int rx = panelX + PAD + LEFT_W + 14;
        int rw = panelX + PANEL_W - PAD - rx;
        drawRightColumn(g, rx, contentY, rw, energy, now);

        if (intro < 1f) {
            UiFx.scanBeam(g, panelX + 1, panelY + 1, PANEL_W - 2, PANEL_H - 2, ease, 14, 0xFFFFFF, 40);
        }

        // Панель-підказка під основною — ОДИН рядок, вміщений у ширину.
        int hintY = panelY + PANEL_H + HINT_GAP;
        String hint = Component.translatable("maniacmod.rope.hint_bar").getString();
        ManiacUiTheme.drawHintBar(g, font, hint, panelX, hintY, PANEL_W, HINT_H);

        GeneratorFuelUiTheme.endAppear(g);
        g.pose().popPose();
        super.render(g, mouseX, mouseY, partialTick);
    }

    /** Ліва колонка: балка, картинка персонажа на нитці (гойдається від кута). */
    private void drawCharacter(GuiGraphics g, int x, int y, float theta, float energy) {
        int cx = x + LEFT_W / 2;

        // Інсет-фон колонки.
        int top = y;
        int h = TEX_H * SCALE + 14;
        g.fill(x, top, x + LEFT_W, top + h, GeneratorFuelUiTheme.INSET_BG);
        ManiacUiTheme.border1px(g, x, top, LEFT_W, h, ManiacUiTheme.BORDER);

        // Балка, до якої прив'язана нитка.
        g.fill(cx - 22, top + 4, cx + 22, top + 8, 0xFF56606E);
        g.fill(cx - 22, top + 4, cx + 22, top + 5, 0xFF8E97A5);

        var pose = g.pose();
        pose.pushPose();
        pose.translate(cx, top + 8, 0f);
        pose.mulPose(Axis.ZP.rotation(-theta));          // кут із сервера: θ>0 — вправо
        pose.translate(-(TEX_W * SCALE) / 2f, 0f, 0f);
        g.blit(CHARACTER, 0, 0, TEX_W * SCALE, TEX_H * SCALE, 0, 0, TEX_W, TEX_H, TEX_W, TEX_H);
        pose.popPose();
    }

    /** Права колонка: опис, смужка розкачування, клавіші A/D, підказка про ножиці. */
    private void drawRightColumn(GuiGraphics g, int x, int y, int w, float energy, long now) {
        Font f = font;
        int cy = y;

        // Короткий опис (обережно: переноситься по ширині, без накладання).
        cy = wrapped(g, "maniacmod.rope.desc", x, cy, w, ManiacUiTheme.TEXT_BODY);
        cy += 8;

        // Смужка розкачування: назва зліва, відсоток справа — як у генераторі.
        String pct = Math.round(energy * 100f) + "%";
        GeneratorFuelUiTheme.drawTitleRow(g, f,
            Component.translatable("maniacmod.rope.energy").getString(), pct, x, cy, w);
        cy += f.lineHeight + 4;
        int fill = energy < 0.5f ? ManiacUiTheme.BAR_FILL_NEUTRAL
                 : energy < 0.85f ? ManiacUiTheme.BAR_FILL_WARN : ManiacUiTheme.BAR_FILL_DANGER;
        ManiacUiTheme.drawProgressBar(g, x, cy, w, 12, energy, fill);
        cy += 12 + 12;

        // Клавіші A / D. Підсвічується та, у бік якої зараз летить персонаж.
        boolean moving = Math.abs(velocity) > 0.0006f;
        boolean wantD = moving && velocity > 0;
        boolean wantA = moving && velocity < 0;
        int gap = 10;
        int kw = (w - gap) / 2;
        drawKey(g, x, cy, kw, 38, "A", false, wantA, flashA, now);
        drawKey(g, x + kw + gap, cy, kw, 38, "D", true, wantD, flashD, now);
        cy += 38 + 10;

        wrapped(g, "maniacmod.rope.ally", x, cy, w, ManiacUiTheme.TEXT_DIM);
    }

    private void drawKey(GuiGraphics g, int x, int y, int w, int h, String label, boolean right,
                         boolean wanted, float flash, long now) {
        float pulse = 0.5f + 0.5f * (float) Math.sin(now / 160.0);
        int border = wanted ? GeneratorFuelUiTheme.HIGHLIGHT : ManiacUiTheme.BORDER;
        if (wanted) {
            UiFx.glow(g, x, y, w, h, 0xC2C9D6, 0.35f + 0.35f * pulse, 4);
        }
        GeneratorFuelUiTheme.drawSlot(g, x, y, w, h, border);
        if (flash > 0.02f) {
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, GeneratorFuelUiTheme.alpha(0xFFF2F4F8, flash * 0.35f));
        }
        int textColor = wanted ? ManiacUiTheme.TEXT_ACCENT : ManiacUiTheme.TEXT_BODY;
        g.drawCenteredString(font, label, x + w / 2, y + 8, textColor);

        // Маленька стрілка-шеврон під літерою (малюється прямокутниками, без гліфів).
        int ax = x + w / 2;
        int ay = y + h - 13;
        int c = wanted ? 0xFFF2F4F8 : 0xFF56606E;
        int dir = right ? 1 : -1;
        for (int i = 0; i < 4; i++) {
            g.fill(ax + dir * i, ay + i, ax + dir * i + 1, ay + i + 1, c);
            g.fill(ax + dir * i, ay + 6 - i, ax + dir * i + 1, ay + 7 - i, c);
        }
        g.fill(ax - dir * 5, ay + 3, ax + dir * 3, ay + 4, c);
    }

    /** Малює переклад із переносом за шириною. Повертає Y під останнім рядком. */
    private int wrapped(GuiGraphics g, String key, int x, int y, int w, int color) {
        List<FormattedCharSequence> lines = font.split(Component.translatable(key), w);
        for (FormattedCharSequence line : lines) {
            g.drawString(font, line, x, y, color, false);
            y += font.lineHeight + 1;
        }
        return y;
    }
}
