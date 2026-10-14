package com.log_to_kot.maniacmod.client.barricade;

import com.log_to_kot.maniacmod.barricade.BarricadeScene;
import com.log_to_kot.maniacmod.barricade.BoardSpec;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.UiFx;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.c2s.hold.BarricadeNailHoldPacket;
import com.log_to_kot.maniacmod.net.c2s.intent.BarricadeGameExitPacket;
import com.log_to_kot.maniacmod.net.s2c.barricade.BarricadeGameClosePacket;
import com.log_to_kot.maniacmod.net.s2c.barricade.BarricadeGameOpenPacket;
import com.log_to_kot.maniacmod.net.s2c.barricade.BarricadeNailPacket;
import com.log_to_kot.maniacmod.registry.ModSounds;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.BitSet;
import java.util.List;
import java.util.Locale;

/**
 * Міні-гра «Зняти барикаду»: ПКМ ломом по барикаді відкриває цей екран.
 *
 * <h3>Що на екрані</h3>
 * «Дірка» (прохід) у формі барикади, а в ній — ТІ САМІ дошки, що стоять у світі: той самий розмір,
 * поворот, текстура блока. Фіксована барикада (двері) малюється з моделей блока, гнучка — з
 * {@link BoardSpec} з урахуванням напрямку, з якого дивиться гравець ({@link BarricadeScene}).
 * На кожній дошці по цвяху з кожного кінця: цвяхів вдвічі більше, ніж дощок.
 *
 * <h3>Гра</h3>
 * Навести на цвях і ЗАТИСНУТИ ЛКМ (або Пробіл): лом тягне цвях, зі скрипом, що росте в тоні.
 * Відпустив — цвях осідає назад. Вийшов один цвях — дошка провисає на другому; вийшли обидва —
 * падає. Цвях під іншою дошкою закритий: знімай зверху вниз. Усі дошки зняті — барикада
 * розвалюється.
 *
 * <h3>Логіка</h3>
 * Екран нічого не вирішує: прогрес і «вийшов» приходять із сервера ({@link BarricadeNailPacket}),
 * клієнт шле лише «тримаю цвях №N» / «відпустив» і «закрив екран». Звуки скрипу/виліту/падіння
 * грає сервер у світі (їх чує і маньяк); тут — лише локальний «хват».
 */
@OnlyIn(Dist.CLIENT)
public final class BarricadeGameScreen extends Screen {

    private static final int PAD = 12;
    private static final int HINT_H = 26;
    private static final int HINT_GAP = 8;
    private static final long INTRO_MS = 320;
    private static final float FINALE_SECONDS = 2.0f;

    /** Туторіал показується сам лише при першому відкритті за запуск гри; далі — за клавішею H. */
    private static boolean helpSeen;

    private final String key;
    private final BarricadeScene scene;
    private BarricadeSceneView view;
    private int required;

    private int heldMine = -1;
    private boolean holdBySpace;
    private float lastRate = 1f;

    private boolean showHelp;
    private Demo demo;
    private float finaleT = -1f;
    private boolean serverClosed;
    private boolean exitSent;

    private String toast;
    private long toastUntil;

    private final long openedAt = System.currentTimeMillis();
    private final long[] frameClock = new long[1];
    private float time;

    // Розкладка (перераховується щокадру, бо розмір вікна може змінитись).
    private int panelX, panelY, panelW, panelH, sideW;
    private int vx, vy, vw, vh;
    private int helpBtnX, helpBtnY, helpBtnW;
    private int startBtnX, startBtnY, startBtnW, startBtnH;

    public BarricadeGameScreen(BarricadeGameOpenPacket packet) {
        super(Component.translatable("maniacmod.barricade.game.title"));
        this.key = packet.key();
        this.scene = packet.fixed()
            ? BarricadeScene.fixed()
            : BarricadeScene.flexible(packet.boards(), packet.lookX(), packet.lookZ());
        this.view = new BarricadeSceneView(scene);
        applySnapshot(packet);
        if (!helpSeen) {
            helpSeen = true;
            openHelp();
        }
    }

    String key() { return key; }

    @Override
    public boolean isPauseScreen() { return false; }

    // ── Мережа ───────────────────────────────────────────────────────────

    /** Початковий стан (або повторне оновлення): які цвяхи вже вийшли й які розхитані. */
    void applySnapshot(BarricadeGameOpenPacket p) {
        this.required = Math.max(1, p.nailTicks());
        BitSet removed = BitSet.valueOf(p.removed());
        for (int i = removed.nextSetBit(0); i >= 0 && i < scene.nailCount(); i = removed.nextSetBit(i + 1)) {
            view.markRemoved(i, false);
        }
        for (int i = 0; i < p.looseNails().length && i < p.looseProgress().length; i++) {
            view.setPull(p.looseNails()[i], p.looseProgress()[i] / (float) required, false, 1f);
        }
    }

    void onNail(BarricadeNailPacket p) {
        if (p.nail() < 0 || p.nail() >= scene.nailCount()) return;
        this.required = Math.max(1, p.required());
        if (p.progress() >= p.required()) {
            view.markRemoved(p.nail(), true);
            if (p.nail() == heldMine) heldMine = -1;
            return;
        }
        view.setPull(p.nail(), p.progress() / (float) required, p.held(), p.rate());
        if (p.nail() == heldMine && p.held()) lastRate = p.rate();
    }

    void onServerClose(byte reason) {
        serverClosed = true;
        if (reason == BarricadeGameClosePacket.DONE) {
            if (finaleT < 0f) finaleT = 0f;
            return;
        }
        Minecraft.getInstance().setScreen(null);
    }

    private void sendHold(int nail) {
        ModNetwork.toServer(new BarricadeNailHoldPacket(nail));
    }

    // ── Введення ─────────────────────────────────────────────────────────

    private void grab(int nail) {
        if (nail < 0 || nail == heldMine || finaleT >= 0f) return;
        heldMine = nail;
        sendHold(nail);
        Minecraft.getInstance().getSoundManager().play(
            SimpleSoundInstance.forUI(ModSounds.NAIL_GRAB.get(), 0.9f + 0.2f * (nail % 3) / 2f, 0.8f));
    }

    private void release() {
        if (heldMine >= 0) {
            heldMine = -1;
            sendHold(-1);
        }
        holdBySpace = false;
    }

    private void openHelp() {
        showHelp = true;
        demo = new Demo();
        release();
    }

    private void closeHelp() {
        showHelp = false;
        demo = null;
        UiAnim.playClick();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 && button != 1) return true;   // ЛКМ і ПКМ — однаково
        if (showHelp) {
            if (button == 0 && inside(mx, my, startBtnX, startBtnY, startBtnW, startBtnH)) closeHelp();
            return true;
        }
        if (button == 0 && inside(mx, my, helpBtnX, helpBtnY, helpBtnW, 14)) {
            UiAnim.playClick();
            openHelp();
            return true;
        }
        if (finaleT >= 0f) return true;
        int nail = view.pick(mx, my, 1.0f);
        if (nail >= 0) {
            grab(nail);
        } else if (view.nearCovered(mx, my)) {
            showToast(Component.translatable("maniacmod.barricade.game.covered").getString());
            UiAnim.playMenuWarn();
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if ((button == 0 || button == 1) && !holdBySpace) release();
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && showHelp) {
            closeHelp();
            return true;
        }
        if (showHelp && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_SPACE
            || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            closeHelp();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_H) {
            if (showHelp) closeHelp(); else openHelp();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_SPACE && !showHelp && finaleT < 0f && heldMine < 0) {
            Minecraft mc = Minecraft.getInstance();
            double mx = mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth();
            double my = mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight();
            int nail = view.pick(mx, my, 1.4f);
            if (nail >= 0) {
                holdBySpace = true;
                grab(nail);
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_SPACE && holdBySpace) {
            release();
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public void tick() {
        // Страховка: кнопку відпустили поза вікном, а подія не прийшла.
        if (heldMine >= 0) {
            Minecraft mc = Minecraft.getInstance();
            long win = mc.getWindow().getWindow();
            boolean mouse = mc.mouseHandler.isLeftPressed() || mc.mouseHandler.isRightPressed();
            boolean space = holdBySpace && InputConstants.isKeyDown(win, GLFW.GLFW_KEY_SPACE);
            if (!mouse && !space) release();
        }
        if (finaleT >= FINALE_SECONDS) Minecraft.getInstance().setScreen(null);
    }

    @Override
    public void removed() {
        if (!exitSent && !serverClosed) {
            exitSent = true;
            ModNetwork.toServer(new BarricadeGameExitPacket());
        }
        super.removed();
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void showToast(String text) {
        toast = text;
        toastUntil = System.currentTimeMillis() + 2200;
    }

    // ── Малювання ────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Без renderBackground: світ лишається видно позаду, як в інших міні-іграх мода.
        long now = System.currentTimeMillis();
        float dt = UiAnim.frameDt(frameClock);
        time += dt;
        view.update(dt);
        if (demo != null) demo.update(dt);
        if (view.allGone() && finaleT < 0f) finaleT = 0f;
        if (finaleT >= 0f) finaleT += dt;

        computeLayout();
        float intro = UiAnim.clamp01((now - openedAt) / (float) INTRO_MS);
        float ease = UiAnim.easeOutCubic(intro);

        GeneratorFuelUiTheme.beginAppear(g, panelX + panelW / 2f, panelY + panelH / 2f, ease, 14f);
        ManiacUiTheme.drawPanel(g, panelX, panelY, panelW, panelH);
        int contentY = ManiacUiTheme.drawTitle(g, font, title, panelX, panelY, panelW);
        vy = contentY;
        vh = panelY + panelH - PAD - contentY;
        view.layout(vx, vy, vw, vh, 22f, 22f, 150f);

        int hover = showHelp || finaleT >= 0f ? -1 : view.pick(mouseX, mouseY, 1.0f);
        drawViewport(g, mouseX, mouseY, hover);
        drawSide(g, now);
        drawHelpButton(g, mouseX, mouseY);

        if (showHelp) drawHelp(g, mouseX, mouseY);
        if (intro < 1f) {
            UiFx.scanBeam(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2, ease, 14, 0xFFFFFF, 36);
        }

        int hintY = panelY + panelH + HINT_GAP;
        ManiacUiTheme.drawHintBar(g, font, Component.translatable("maniacmod.barricade.game.hint_bar").getString(),
            panelX, hintY, panelW, HINT_H);
        GeneratorFuelUiTheme.endAppear(g);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void computeLayout() {
        panelW = Mth.clamp(width - 30, 360, 620);
        panelH = Mth.clamp(height - 64, 250, 380);
        panelX = (width - panelW) / 2;
        panelY = Math.max(6, (height - panelH - HINT_H - HINT_GAP) / 2);
        sideW = panelW < 430 ? 118 : 146;
        vx = panelX + PAD;
        vw = panelW - PAD * 3 - sideW;
        helpBtnW = font.width(Component.translatable("maniacmod.barricade.game.help_btn").getString()) + 12;
        helpBtnX = panelX + panelW - PAD - helpBtnW;
        helpBtnY = panelY + 10;
    }

    private void drawHelpButton(GuiGraphics g, int mx, int my) {
        boolean hov = inside(mx, my, helpBtnX, helpBtnY, helpBtnW, 14) && !showHelp;
        GeneratorFuelUiTheme.drawSlot(g, helpBtnX, helpBtnY, helpBtnW, 14,
            hov ? GeneratorFuelUiTheme.HIGHLIGHT : ManiacUiTheme.BORDER);
        g.drawCenteredString(font, Component.translatable("maniacmod.barricade.game.help_btn"),
            helpBtnX + helpBtnW / 2, helpBtnY + 3, hov ? ManiacUiTheme.TEXT_ACCENT : ManiacUiTheme.TEXT_BODY);
    }

    // ── Область гри ──────────────────────────────────────────────────────

    private void drawViewport(GuiGraphics g, int mx, int my, int hover) {
        g.fill(vx, vy, vx + vw, vy + vh, GeneratorFuelUiTheme.INSET_BG);
        // Легкий «стінний» шум: вертикальне потемніння до країв.
        g.fillGradient(vx, vy, vx + vw, vy + vh, 0x00000000, 0x55000000);
        ManiacUiTheme.border1px(g, vx, vy, vw, vh, ManiacUiTheme.BORDER);

        g.enableScissor(vx + 1, vy + 1, vx + vw - 1, vy + vh - 1);
        view.render(g, hover, heldMine, helpSeen && view.nailsRemoved() == 0);
        if (finaleT >= 0f) drawFinale(g);
        g.disableScissor();

        // Лом: до курсора, а коли тягнемо — упирається в цвях і б'ється в такт прогресу.
        if (!showHelp && finaleT < 0f && (heldMine >= 0 || inside(mx, my, vx, vy, vw, vh))) {
            if (heldMine >= 0) {
                // Лом чіпляє ГОЛОВКУ цвяха й тягне важелем: ручка поступово хилиться, тримаючи цвях,
                // а не крутиться туди-сюди (це й виглядало як відкручування).
                float pull = view.nailPull(heldMine);
                float tremor = (float) Math.sin(time * (30f + 20f * pull)) * (0.006f + 0.016f * pull);
                drawCrowbar(g, view.nailHeadX(heldMine), view.nailHeadY(heldMine) + 1.5f, 0.55f * pull + tremor);
            } else {
                drawCrowbar(g, mx, my, -0.05f + 0.03f * (float) Math.sin(time * 2f));
            }
        }

        if (toast != null && System.currentTimeMillis() < toastUntil) {
            String t = toast;
            int tw = font.width(t) + 14;
            int tx = vx + vw / 2 - tw / 2, ty = vy + vh - 24;
            GeneratorFuelUiTheme.drawSlot(g, tx, ty, tw, 16, GeneratorFuelUiTheme.FILL_WARN);
            g.drawCenteredString(font, t, vx + vw / 2, ty + 4, 0xFFFFE2A0);
        }
    }

    private void drawFinale(GuiGraphics g) {
        float t = Math.min(1f, finaleT / 0.6f);
        float fade = finaleT > FINALE_SECONDS - 0.5f ? Math.max(0f, (FINALE_SECONDS - finaleT) / 0.5f) : 1f;
        g.fill(vx, vy, vx + vw, vy + vh, GeneratorFuelUiTheme.alpha(0xFFF2F4F8, 0.16f * (1f - t) * fade));
        String text = Component.translatable("maniacmod.barricade.game.done").getString().toUpperCase(Locale.ROOT);
        float sc = 1.6f + 0.3f * (1f - t);
        g.pose().pushPose();
        g.pose().translate(vx + vw / 2f, vy + vh / 2f, 0f);
        g.pose().scale(sc, sc, 1f);
        int w = font.width(text);
        g.fill(-w / 2 - 8, -9, w / 2 + 8, 10, GeneratorFuelUiTheme.alpha(0xFF000000, 0.55f * t * fade));
        g.drawString(font, text, -w / 2, -4, GeneratorFuelUiTheme.alpha(0xFFF2F4F8, t * fade), false);
        g.pose().popPose();
        UiFx.scanBeam(g, vx + 1, vy + 1, vw - 2, vh - 2, Math.min(1f, finaleT / 1.0f), 22, 0xFFFFFF, 40);
    }

    /** Лом: кінець (кіготь) — у (tx,ty), ручка йде вгору-праворуч. */
    private void drawCrowbar(GuiGraphics g, float tx, float ty, float rock) {
        g.pose().pushPose();
        g.pose().translate(tx, ty, 0f);
        g.pose().mulPose(Axis.ZP.rotation(rock));
        float ex = 31f, ey = -40f;
        GuiShapes.line(g, 1.5f, 1.5f, ex + 1.5f, ey + 1.5f, 6f, 0x55000000);
        GuiShapes.line(g, 0f, 0f, ex, ey, 5.2f, 0xFF23272D);
        GuiShapes.line(g, 0f, 0f, ex * 0.64f, ey * 0.64f, 3.4f, 0xFF8E97A5);
        GuiShapes.line(g, ex * 0.64f, ey * 0.64f, ex, ey, 3.4f, 0xFFB0242F);
        GuiShapes.line(g, -0.6f, -0.6f, ex * 0.6f - 0.6f, ey * 0.6f - 0.6f, 1f, 0x88FFFFFF);
        GuiShapes.line(g, 0f, 0f, -7f, 2.5f, 3.6f, 0xFF8E97A5);
        GuiShapes.line(g, -7f, 2.5f, -9.5f, 8f, 3.2f, 0xFF8E97A5);
        g.pose().popPose();
    }

    // ── Бокова панель ────────────────────────────────────────────────────

    private void drawSide(GuiGraphics g, long now) {
        int x = vx + vw + PAD;
        int y = vy;
        int w = sideW;
        int total = scene.plankCount();
        int gone = view.planksGone();
        int nailsAll = scene.nailCount();
        int nailsGone = view.nailsRemoved();

        y = statCard(g, x, y, w, Component.translatable("maniacmod.barricade.game.planks").getString(),
            gone + " / " + total, total == 0 ? 0f : gone / (float) total);
        y = statCard(g, x, y + 6, w, Component.translatable("maniacmod.barricade.game.nails").getString(),
            nailsGone + " / " + nailsAll, nailsAll == 0 ? 0f : nailsGone / (float) nailsAll);

        y += 6;

        // Пам'ятка по клавішах — нижче, скільки лишилось місця.
        int left = vy + vh - y - 6;
        if (left >= 40) {
            int ty = y + 10;
            String[] keys = {"maniacmod.barricade.game.k_hold", "maniacmod.barricade.game.k_help", "maniacmod.barricade.game.k_exit"};
            for (String k : keys) {
                for (FormattedCharSequence line : font.split(Component.translatable(k), w)) {
                    if (ty + font.lineHeight > vy + vh) return;
                    g.drawString(font, line, x, ty, ManiacUiTheme.TEXT_DIM, false);
                    ty += font.lineHeight + 1;
                }
                ty += 3;
            }
        }
    }

    private int statCard(GuiGraphics g, int x, int y, int w, String label, String value, float frac) {
        int h = 44;
        GeneratorFuelUiTheme.drawSlot(g, x, y, w, h, ManiacUiTheme.BORDER);
        g.drawString(font, label, x + 7, y + 6, ManiacUiTheme.TEXT_DIM, false);
        g.pose().pushPose();
        g.pose().translate(x + 7, y + 17, 0f);
        g.pose().scale(1.5f, 1.5f, 1f);
        g.drawString(font, value, 0, 0, ManiacUiTheme.TEXT_ACCENT, false);
        g.pose().popPose();
        ManiacUiTheme.drawProgressBar(g, x + 7, y + h - 9, w - 14, 5, frac, ManiacUiTheme.BAR_FILL_NEUTRAL);
        return y + h;
    }

    // ── Туторіал ─────────────────────────────────────────────────────────

    private void drawHelp(GuiGraphics g, int mx, int my) {
        int x = panelX + PAD, y = vy, w = panelW - PAD * 2, h = vh;
        g.fill(x, y, x + w, y + h, 0xF4080A0C);
        ManiacUiTheme.border1px(g, x, y, w, h, ManiacUiTheme.BORDER_BRIGHT);
        g.fill(x, y, x + 24, y + 2, GeneratorFuelUiTheme.HIGHLIGHT);

        g.drawString(font, Component.translatable("maniacmod.barricade.game.help.title").getString().toUpperCase(Locale.ROOT),
            x + 12, y + 9, ManiacUiTheme.TEXT_TITLE, false);
        g.fill(x + 12, y + 21, x + w - 12, y + 22, ManiacUiTheme.DIVIDER);

        // Демо ліворуч.
        int demoW = Math.min(190, w * 2 / 5);
        int demoH = Math.min(120, h - 70);
        int dx = x + 12, dy = y + 30;
        g.fill(dx, dy, dx + demoW, dy + demoH, GeneratorFuelUiTheme.INSET_BG);
        ManiacUiTheme.border1px(g, dx, dy, demoW, demoH, ManiacUiTheme.BORDER);
        if (demo != null) {
            g.enableScissor(dx + 1, dy + 1, dx + demoW - 1, dy + demoH - 1);
            demo.render(g, dx, dy, demoW, demoH);
            g.disableScissor();
        }
        String cap = Component.translatable("maniacmod.barricade.game.help.demo_caption").getString();
        for (FormattedCharSequence line : font.split(Component.literal(cap), demoW)) {
            g.drawString(font, line, dx, dy + demoH + 5, ManiacUiTheme.TEXT_DIM, false);
            dy += font.lineHeight + 1;
        }

        // Кроки праворуч.
        int sx = dx + demoW + 18;
        int sw = x + w - 12 - sx;
        int sy = y + 30;
        for (int i = 1; i <= 5; i++) {
            List<FormattedCharSequence> lines = font.split(
                Component.translatable("maniacmod.barricade.game.help.step" + i), sw - 20);
            GeneratorFuelUiTheme.drawSlot(g, sx, sy - 1, 14, 14, ManiacUiTheme.BORDER_BRIGHT);
            g.drawCenteredString(font, String.valueOf(i), sx + 7, sy + 2, ManiacUiTheme.TEXT_ACCENT);
            int ty = sy;
            for (FormattedCharSequence line : lines) {
                g.drawString(font, line, sx + 20, ty, i == 5 ? 0xFFE9B96A : ManiacUiTheme.TEXT_BODY, false);
                ty += font.lineHeight + 1;
            }
            sy = Math.max(ty, sy + 14) + 5;
        }

        // Кнопка.
        startBtnW = 150;
        startBtnH = 20;
        startBtnX = x + w / 2 - startBtnW / 2;
        startBtnY = y + h - startBtnH - 10;
        boolean hov = inside(mx, my, startBtnX, startBtnY, startBtnW, startBtnH);
        float pulse = 0.5f + 0.5f * (float) Math.sin(time * 4f);
        if (hov) UiFx.glow(g, startBtnX, startBtnY, startBtnW, startBtnH, 0xC2C9D6, 0.5f, 3);
        GeneratorFuelUiTheme.drawSlot(g, startBtnX, startBtnY, startBtnW, startBtnH,
            hov ? GeneratorFuelUiTheme.HIGHLIGHT : GeneratorFuelUiTheme.mix(ManiacUiTheme.BORDER, ManiacUiTheme.BORDER_BRIGHT, 0.4f * pulse));
        g.drawCenteredString(font, Component.translatable("maniacmod.barricade.game.help.start"),
            startBtnX + startBtnW / 2, startBtnY + 6, hov ? ManiacUiTheme.TEXT_ACCENT : ManiacUiTheme.TEXT_BODY);
    }

    /**
     * Живе демо в туторіалі: дві дошки хрестом, на лівому кінці нижньої лежить верхня. Скрипт сам
     * «грає»: знімає цвяхи верхньої (вона падає), потім відкритої нижньої — показує правило
     * «знімай зверху вниз» і весь цикл дії.
     */
    private static final class Demo {
        private static final double[][] STEPS = {   // старт, тривалість, цвях
            {0.7, 1.0, 2}, {2.3, 1.0, 3}, {4.2, 1.0, 0}, {5.8, 1.0, 1}};
        private static final float LOOP = 8.6f;

        private final BarricadeScene demoScene;
        private BarricadeSceneView demoView;
        private float t;
        private float prevT;

        Demo() {
            ResourceLocation oak = new ResourceLocation("minecraft", "oak_planks");
            Vec3 n = new Vec3(0, 0, 1);
            demoScene = BarricadeScene.flexible(List.of(
                new BoardSpec(new Vec3(0, 0, 0), new Vec3(1.8, 0, 0), n, oak),
                new BoardSpec(new Vec3(-0.12, -0.5, 0.2), new Vec3(0.12, 0.5, 0.2), n, oak)), 0, -1);
            demoView = new BarricadeSceneView(demoScene);
        }

        void update(float dt) {
            prevT = t;
            t += Math.min(dt, 0.05f);
            if (t >= LOOP) {
                t = 0f;
                prevT = 0f;
                demoView = new BarricadeSceneView(demoScene);
            }
            demoView.update(dt);
            for (double[] s : STEPS) {
                int nail = (int) s[2];
                if (t < s[0] || demoView.isRemoved(nail)) continue;
                float f = (float) ((t - s[0]) / s[1]);
                if (f >= 1f) demoView.markRemoved(nail, true);
                else demoView.setPull(nail, f, true, 1f);
            }
        }

        void render(GuiGraphics g, int x, int y, int w, int h) {
            demoView.layout(x, y, w, h, 14f, 20f, 90f);
            int active = -1;
            for (double[] s : STEPS) {
                if (t >= s[0] && t < s[0] + s[1]) active = (int) s[2];
            }
            demoView.render(g, -1, active, false);

            // Курсор: їде до наступного цвяха й «тисне» ЛКМ на час тяги.
            float cx = x + w * 0.55f, cy = y + h * 0.85f;
            float px = cx, py = cy;
            boolean pressed = false;
            for (int i = 0; i < STEPS.length; i++) {
                double[] s = STEPS[i];
                float tx = demoView.nailX((int) s[2]), ty = demoView.nailY((int) s[2]);
                float fromX = i == 0 ? cx : demoView.nailX((int) STEPS[i - 1][2]);
                float fromY = i == 0 ? cy : demoView.nailY((int) STEPS[i - 1][2]);
                if (t >= s[0] - 0.55) {
                    float k = (float) Mth.clamp((t - (s[0] - 0.55)) / 0.5, 0.0, 1.0);
                    k = k * k * (3f - 2f * k);
                    px = Mth.lerp(k, fromX, tx);
                    py = Mth.lerp(k, fromY, ty);
                    pressed = t >= s[0] && t < s[0] + s[1];
                }
            }
            // Стрілка-курсор.
            g.pose().pushPose();
            g.pose().translate(px + 2, py + 2, 0f);
            GuiShapes.fan(g, 3f, 8f, 0xFFFFFFFF,
                List.of(new float[] {0f, 0f}, new float[] {0f, 14f}, new float[] {9f, 10f}), 0xFFE6E9EF);
            g.pose().popPose();
            // Піктограма миші з підсвіченою ЛКМ.
            int mx0 = x + w - 22, my0 = y + 8;
            g.fill(mx0, my0, mx0 + 14, my0 + 20, 0xFF23272D);
            ManiacUiTheme.border1px(g, mx0, my0, 14, 20, 0xFF8E97A5);
            g.fill(mx0 + 1, my0 + 1, mx0 + 7, my0 + 9, pressed ? 0xFFE0A030 : 0xFF3A414B);
            g.fill(mx0 + 7, my0 + 1, mx0 + 8, my0 + 9, 0xFF8E97A5);
            g.fill(mx0 + 8, my0 + 1, mx0 + 13, my0 + 9, 0xFF3A414B);
        }
    }
}
