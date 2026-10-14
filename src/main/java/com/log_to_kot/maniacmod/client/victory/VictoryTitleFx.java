package com.log_to_kot.maniacmod.client.victory;

import com.log_to_kot.maniacmod.client.style.BloodFx;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.overlay.WorldToScreen;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Locale;

/**
 * Титул сцени перемоги: чіткий 2D-напис (шрифт гри, крупно, з товстим контуром — читається
 * з першого погляду), «прив'язаний» до світу.
 *
 * <p>Коли камера доїхала до кінцевої точки, перед нею на {@link #DISTANCE} блоків ставиться
 * світова опора. Кожен кадр опора проєктується на екран ({@code WorldToScreen}), і напис малюється
 * в тій точці, тож рух камери (тряска, дрейф) зсуває його разом із фоном — як елемент сцени, а не
 * плашка на екрані. Окремо від напису сипляться світові частинки навколо опори (іскри для гравців,
 * багряні спори для маніяка).</p>
 *
 * <p>Малювання навмисно просте заради читабельності: темний контур, основний колір, світліший
 * верхній край. Без світіння та розмитих ореолів (на великому масштабі вони перетворювалися на
 * кашу). Літери падають зверху по одній із відскоком, потім легко гойдаються; на виході злітають.</p>
 */
@OnlyIn(Dist.CLIENT)
final class VictoryTitleFx {

    private static final float DISTANCE = 7.0f;
    private static final int LETTER_STEP_MS = 60;
    private static final int DROP_MS = 600;
    private static final int SUB_DELAY_MS = 1500;
    private static final float DROP_HEIGHT_PX = 46f;

    private VictoryTitleFx() {}

    private record Theme(int main, int light, int outline, int glow, ParticleOptions particle, ParticleOptions particle2) {}

    private static final Theme SURVIVORS = new Theme(
        0xFF3FD21F, 0xFFB6FF6A, 0xFF0B3A06, 0xFF5BFF3A, ParticleTypes.END_ROD, ParticleTypes.HAPPY_VILLAGER);
    private static final Theme MANIAC = new Theme(
        BloodFx.BRIGHT, 0xFFFF6A72, 0xFF1A0003, BloodFx.HOT, ParticleTypes.CRIMSON_SPORE, ParticleTypes.SMOKE);

    // ── Прибивання до світу ──────────────────────────────────────────────
    private static boolean pinned;
    private static Vec3 pinPos;
    private static Quaternionf pinRot;
    private static float worldScale;      // блоків на піксель шрифту
    private static float blockWidthBlocks;
    private static float blockHeightBlocks;

    static void reset() {
        pinned = false;
        pinPos = null;
        pinRot = null;
    }

    private static String[] lines(boolean survivorsWin) {
        String key = survivorsWin ? "maniacmod.victory.players.title" : "maniacmod.victory.title";
        String text = Component.translatable(key).getString().toUpperCase(Locale.ROOT);
        int i = text.indexOf(' ');
        return i < 0 ? new String[] {text} : new String[] {text.substring(0, i), text.substring(i + 1)};
    }

    /** Прибити титул до світу перед поточною камерою (один раз за сцену). */
    private static void pin(Minecraft mc, boolean survivorsWin) {
        Camera cam = mc.gameRenderer.getMainCamera();
        if (!cam.isInitialized()) return;
        Font font = mc.font;
        String[] ls = lines(survivorsWin);
        int maxPx = 1;
        for (String l : ls) maxPx = Math.max(maxPx, font.width(l));

        double fov = Math.toRadians(mc.options.fov().get());
        float visibleH = (float) (2.0 * DISTANCE * Math.tan(fov / 2.0));
        float aspect = mc.getWindow().getWidth() / (float) Math.max(1, mc.getWindow().getHeight());
        float visibleW = visibleH * aspect;

        float targetW = Math.min(visibleW * 0.58f, visibleH * 1.25f);
        worldScale = targetW / maxPx;
        blockWidthBlocks = maxPx * worldScale;
        blockHeightBlocks = ls.length * 9f * worldScale;

        Vector3f look = new Vector3f(cam.getLookVector());
        Vector3f up = new Vector3f(cam.getUpVector());
        Vec3 pos = cam.getPosition();
        // Центр блока трохи вище середини кадру, щоб не лягати на смугу знизу.
        pinPos = pos.add(look.x * DISTANCE, look.y * DISTANCE, look.z * DISTANCE)
            .add(up.x * visibleH * 0.10f, up.y * visibleH * 0.10f, up.z * visibleH * 0.10f);
        pinRot = new Quaternionf(cam.rotation());
        pinned = true;
    }

    /** Клієнтський тік: прибити титул і сипати частинки сцени навколо нього. */
    static void tick(Minecraft mc, long lt, int titleMs, int fadeOutMs, boolean survivorsWin) {
        if (lt < 0 || lt >= titleMs + fadeOutMs || mc.level == null) return;
        if (!pinned) {
            pin(mc, survivorsWin);
            if (!pinned) return;
        }
        if (lt < 500) return;
        Theme th = survivorsWin ? SURVIVORS : MANIAC;
        Vector3f right = pinRot.transform(new Vector3f(1f, 0f, 0f));
        Vector3f up = pinRot.transform(new Vector3f(0f, 1f, 0f));
        var rnd = mc.level.random;
        for (int i = 0; i < 2; i++) {
            float rx = (rnd.nextFloat() - 0.5f) * blockWidthBlocks;
            float ry = (rnd.nextFloat() - 0.5f) * blockHeightBlocks;
            double x = pinPos.x + right.x * rx + up.x * ry;
            double y = pinPos.y + right.y * rx + up.y * ry;
            double z = pinPos.z + right.z * rx + up.z * ry;
            if (survivorsWin) {
                mc.level.addParticle(i == 0 ? th.particle() : th.particle2(), x, y, z, 0.0, 0.03, 0.0);
            } else {
                mc.level.addParticle(i == 0 ? th.particle() : th.particle2(), x, y - blockHeightBlocks * 0.4f, z,
                    0.0, -0.01, 0.0);
            }
        }
    }

    // ── Малювання (2D, у проєкції опори) ─────────────────────────────────

    static void draw(GuiGraphics g, int w, int h, long lt, int titleMs, int fadeOutMs, boolean survivorsWin) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        Theme th = survivorsWin ? SURVIVORS : MANIAC;
        long now = System.currentTimeMillis();

        float out = 1f - UiAnim.clamp01((lt - titleMs) / (float) fadeOutMs);
        if (out <= 0f) return;

        String[] ls = lines(survivorsWin);
        int maxPx = 1;
        for (String l : ls) maxPx = Math.max(maxPx, font.width(l));
        // Крупно: ~60% ширини екрана, але блок не вище третини висоти.
        float sc = Math.min(w * 0.60f / maxPx, h * 0.34f / (ls.length * 9f));
        sc = Math.max(sc, 2f);
        float lineH = 9f * sc;

        // Опора у світі → екран; якщо проєкція недоступна — центр трохи вище середини.
        float ax = w / 2f;
        float ay = h * 0.40f;
        if (pinned && pinPos != null) {
            WorldToScreen.Point p = WorldToScreen.project(pinPos.x, pinPos.y, pinPos.z, w, h, 0);
            if (p != null) {
                ax = p.x();
                ay = p.y();
            }
        }

        int index = 0;
        boolean glitch = !survivorsWin && lt > 1500 && (now / 100) % 9 == 0;
        for (int li = 0; li < ls.length; li++) {
            String line = ls[li];
            float x = ax - font.width(line) * sc / 2f;
            float yTop = ay + (li - (ls.length - 1) / 2f) * lineH - lineH / 2f;
            for (int ci = 0; ci < line.length(); ci++, index++) {
                String ch = String.valueOf(line.charAt(ci));
                float cw = font.width(ch) * sc;
                float p = (lt - index * LETTER_STEP_MS) / (float) DROP_MS;
                if (p > 0f) {
                    float t = UiAnim.clamp01(p);
                    float fall = 1f - UiAnim.easeOutBack(t, 1.2f);
                    float yy = yTop - fall * h * 0.30f - (1f - out) * h * 0.10f;
                    float s = sc * (1f + 0.6f * (1f - UiAnim.easeOutCubic(t)));
                    float wave = t >= 1f ? (float) Math.sin(now / 280.0 + index * 0.55) * sc * 0.35f : 0f;
                    float jit = glitch ? (BloodFx.hash01(now / 100 + index) - 0.5f) * sc * 1.5f : 0f;
                    float a = UiAnim.clamp01(t * 3f) * out;
                    letter(g, font, ch, x + cw / 2f + jit, yy + wave + 4f * sc, s, a, th, glitch);
                }
                x += cw;
            }
        }

        // Підзаголовок — під блоком, проявляється пізніше.
        String sub = Component.translatable(survivorsWin
            ? "maniacmod.victory.players.subtitle" : "maniacmod.victory.subtitle").getString();
        float sa = out * UiAnim.clamp01((lt - SUB_DELAY_MS) / 700f);
        int col = BloodFx.a(0xFFF2F2F2, sa);
        if (((col >>> 24) & 0xFF) >= 8) {
            float subSc = Math.max(1.4f, sc * 0.30f);
            float sy = ay + ls.length * lineH / 2f + subSc * 5f;
            g.pose().pushPose();
            g.pose().translate(ax, sy, 0f);
            g.pose().scale(subSc, subSc, 1f);
            int sx = Math.round(-font.width(sub) / 2f);
            g.drawString(font, sub, sx + 1, 1, BloodFx.a(0xFF000000, sa), false);
            g.drawString(font, sub, sx, 0, col, false);
            g.pose().popPose();
        }
    }

    /** Одна літера: товстий темний контур → основний колір → світліша верхня половина. Центр — (cx, cy). */
    private static void letter(GuiGraphics g, Font font, String ch, float cx, float cy, float s, float alpha,
                               Theme th, boolean glitch) {
        if (alpha <= 0.02f) return;
        float cw = font.width(ch);
        int outline = BloodFx.a(th.outline(), alpha);
        int main = BloodFx.a(th.main(), alpha);
        int light = BloodFx.a(th.light(), alpha);

        g.pose().pushPose();
        g.pose().translate(cx, cy, 0f);
        g.pose().scale(s, s, 1f);
        int px = Math.round(-cw / 2f);
        int py = -4;
        if (glitch) {
            g.drawString(font, ch, px - 1, py, BloodFx.a(0xFF00E5FF, alpha * 0.6f), false);
            g.drawString(font, ch, px + 1, py, BloodFx.a(0xFFFF0050, alpha * 0.6f), false);
        }
        // Контур у цілі пікселі шрифту (8 сусідів) + падаюча тінь — суцільна обводка без дір.
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
            if (dx == 0 && dy == 0) continue;
            g.drawString(font, ch, px + dx, py + dy, outline, false);
        }
        g.drawString(font, ch, px + 1, py + 2, outline, false);
        g.drawString(font, ch, px, py, main, false);
        g.pose().popPose();

        // Світліша верхня половина (scissor у екранних координатах).
        int x0 = Math.round(cx - cw * s / 2f);
        int x1 = Math.round(cx + cw * s / 2f) + 1;
        int y0 = Math.round(cy - 4f * s);
        int y1 = Math.round(cy - 0.5f * s);
        if (x1 > x0 && y1 > y0) {
            g.enableScissor(x0, y0, x1, y1);
            g.pose().pushPose();
            g.pose().translate(cx, cy, 0f);
            g.pose().scale(s, s, 1f);
            g.drawString(font, ch, px, py, light, false);
            g.pose().popPose();
            g.disableScissor();
        }
    }
}
