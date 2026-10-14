package com.log_to_kot.maniacmod.client.overlay.hotbar;

import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Анімації предметів у слотах хотбару: «додано» і «викинуто».
 *
 * <h3>Як визначається подія</h3>
 * Кожен кадр порівнюємо вміст дозволених слотів із попереднім:
 * <ul>
 *   <li>порожній → предмет: <b>додано</b>;</li>
 *   <li>предмет → порожній: <b>викинуто</b> (або витрачено);</li>
 *   <li>один предмет → інший (обмін із землею): викинуто старий + додано новий.</li>
 * </ul>
 * Зміна лише NBT (заряд, міцність) подією не вважається. Стан «що було» скидається, коли
 * змінився набір дозволених слотів або хотбар ще в'їжджає (початок матчу, видача слотів) —
 * тоді весь стартовий набір НЕ розлітається по екрану.
 *
 * <h3>Вигляд</h3>
 * <ul>
 *   <li><b>Додано</b>: іконка летить дугою з центру екрана (звідки гравець підбирав) у слот,
 *       лишаючи іскровий слід; у слоті вона «влітає» з пружним перельотом масштабу, слот
 *       спалахує, розходиться кільце й іскри;</li>
 *   <li><b>Викинуто</b>: іконка вилітає зі слота по дузі до центру екрана, обертаючись і
 *       зменшуючись; слот червонувато блимає, з нього здіймається пил.</li>
 * </ul>
 * Усе — чиста функція від віку анімації (секунди), стан лише таймери й попередній вміст слотів.
 */
final class HotbarItemFx {

    private static final int SLOTS = 9;
    private static final float FLY_SECONDS = 0.38f;
    private static final float ADD_SECONDS = 0.90f;
    private static final float DROP_SECONDS = 0.65f;

    private static final int COL_ADD = GeneratorFuelUiTheme.HIGHLIGHT;
    private static final int COL_DROP = 0xFFE0303A;

    private final ItemStack[] previous = new ItemStack[SLOTS];
    private final ItemStack[] dropped = new ItemStack[SLOTS];
    private final float[] addAge = new float[SLOTS];
    private final float[] dropAge = new float[SLOTS];
    private List<Integer> lastAllowed = List.of();

    HotbarItemFx() {
        java.util.Arrays.fill(addAge, -1f);
        java.util.Arrays.fill(dropAge, -1f);
        java.util.Arrays.fill(previous, ItemStack.EMPTY);
        java.util.Arrays.fill(dropped, ItemStack.EMPTY);
    }

    // ── Оновлення ────────────────────────────────────────────────────────

    /**
     * @param skipFirst слот на позиції 0 не чіпаємо (слот удару маньяка — предмета в ньому не видно)
     * @param entrance  прогрес в'їзду хотбару 0..1
     */
    void update(Minecraft mc, List<Integer> allowed, float dt, boolean skipFirst, float entrance) {
        if (mc.player == null) return;
        Inventory inv = mc.player.getInventory();

        if (!allowed.equals(lastAllowed) || entrance < 0.95f) {
            lastAllowed = List.copyOf(allowed);
            for (int s = 0; s < SLOTS; s++) {
                previous[s] = s < 36 ? inv.getItem(s).copy() : ItemStack.EMPTY;
                addAge[s] = -1f;
                dropAge[s] = -1f;
            }
            return;
        }

        for (int index = 0; index < allowed.size(); index++) {
            int s = allowed.get(index);
            if (s < 0 || s >= SLOTS) continue;
            if (skipFirst && index == 0) continue;

            ItemStack now = inv.getItem(s);
            ItemStack old = previous[s];
            boolean oldEmpty = old.isEmpty();
            boolean nowEmpty = now.isEmpty();

            if (oldEmpty && !nowEmpty) {
                addAge[s] = 0f;
                previous[s] = now.copy();
            } else if (!oldEmpty && nowEmpty) {
                dropped[s] = old;
                dropAge[s] = 0f;
                previous[s] = ItemStack.EMPTY;
            } else if (!oldEmpty && !ItemStack.isSameItem(old, now)) {
                dropped[s] = old;
                dropAge[s] = 0f;
                addAge[s] = 0f;
                previous[s] = now.copy();
            }
        }

        for (int s = 0; s < SLOTS; s++) {
            if (addAge[s] >= 0f) {
                addAge[s] += dt;
                if (addAge[s] > ADD_SECONDS) addAge[s] = -1f;
            }
            if (dropAge[s] >= 0f) {
                dropAge[s] += dt;
                if (dropAge[s] > DROP_SECONDS) {
                    dropAge[s] = -1f;
                    dropped[s] = ItemStack.EMPTY;
                }
            }
        }
    }

    // ── Запити від слота ─────────────────────────────────────────────────

    /** Предмет ще летить до слота — сам слот його поки не малює. */
    boolean isFlying(int slot) {
        return slot >= 0 && slot < SLOTS && addAge[slot] >= 0f && addAge[slot] < FLY_SECONDS;
    }

    /** Множник розміру іконки в слоті: пружне «влітання» після приземлення. */
    float popScale(int slot) {
        if (slot < 0 || slot >= SLOTS || addAge[slot] < FLY_SECONDS) return 1f;
        float k = UiAnim.clamp01((addAge[slot] - FLY_SECONDS) / 0.30f);
        return 0.55f + 0.45f * UiAnim.easeOutBack(k, 1.1f);
    }

    // ── Малювання ефектів (поверх слотів) ────────────────────────────────

    void render(GuiGraphics g, Minecraft mc, List<Integer> allowed, int left, int top,
                int slotSize, int gap, int screenW, int screenH) {
        if (mc.player == null) return;
        Inventory inv = mc.player.getInventory();
        for (int index = 0; index < allowed.size(); index++) {
            int s = allowed.get(index);
            if (s < 0 || s >= SLOTS) continue;
            int x = left + index * (slotSize + gap);
            float cx = x + slotSize / 2f;
            float cy = top + slotSize / 2f;

            if (dropAge[s] >= 0f) renderDrop(g, mc, s, x, top, slotSize, cx, cy, screenW, screenH);
            if (addAge[s] >= 0f) renderAdd(g, mc, inv.getItem(s), s, x, top, slotSize, cx, cy, screenW, screenH);
        }
    }

    private void renderAdd(GuiGraphics g, Minecraft mc, ItemStack stack, int slot, int x, int y, int size,
                           float cx, float cy, int sw, int sh) {
        float age = addAge[slot];
        float itemSize = size * 0.72f;

        if (age < FLY_SECONDS) {
            float t = age / FLY_SECONDS;
            float u = t * t * (3f - 2f * t);
            float sx = sw / 2f, sy = sh / 2f;
            float ctrlX = (sx + cx) / 2f, ctrlY = Math.min(sy, cy) - 90f;

            // Іскровий слід за іконкою.
            for (int k = 1; k <= 7; k++) {
                float uu = Math.max(0f, u - 0.045f * k);
                float px = bezier(sx, ctrlX, cx, uu), py = bezier(sy, ctrlY, cy, uu);
                float a = (1f - k / 8f) * 0.9f;
                int d = Math.max(1, 4 - k / 2);
                int c = (k & 1) == 0 ? 0xFFFFFFFF : COL_ADD;
                g.fill(Math.round(px - d / 2f), Math.round(py - d / 2f),
                    Math.round(px + d / 2f) + 1, Math.round(py + d / 2f) + 1, GeneratorFuelUiTheme.alpha(c, a));
            }

            float px = bezier(sx, ctrlX, cx, u), py = bezier(sy, ctrlY, cy, u);
            float scale = 0.7f + 0.45f * (float) Math.sin(Math.PI * u);
            drawItem(g, stack, px, py, itemSize * scale, (1f - u) * -28f);
            return;
        }

        // Приземлення: спалах, кільце, іскри.
        float k = UiAnim.clamp01((age - FLY_SECONDS) / (ADD_SECONDS - FLY_SECONDS));
        float inv = 1f - k;
        g.fill(x + 1, y + 1, x + size - 1, y + size - 1, GeneratorFuelUiTheme.alpha(0xFFFFFFFF, 0.45f * inv * inv * inv));
        GeneratorFuelUiTheme.pulseRing(g, x, y, size, size, COL_ADD, k, 12);
        burst(g, cx, cy, k, 12, 16f + 30f * UiAnim.easeOutCubic(k), 0xFFFFFFFF, COL_ADD, 0x5EED + slot * 131);
    }

    private void renderDrop(GuiGraphics g, Minecraft mc, int slot, int x, int y, int size,
                            float cx, float cy, int sw, int sh) {
        ItemStack stack = dropped[slot];
        if (stack.isEmpty()) return;
        float t = UiAnim.clamp01(dropAge[slot] / DROP_SECONDS);
        float itemSize = size * 0.72f;

        // Слот червонувато блимає й «димить» пилом.
        float flash = UiAnim.clamp01(1f - t * 3f);
        if (flash > 0f) {
            g.fill(x + 1, y + 1, x + size - 1, y + size - 1, GeneratorFuelUiTheme.alpha(COL_DROP, 0.40f * flash));
        }
        GeneratorFuelUiTheme.pulseRing(g, x, y, size, size, COL_DROP, UiAnim.clamp01(t * 1.6f), 8);
        burst(g, cx, cy + 6f, UiAnim.clamp01(t * 1.4f), 9, 10f + 22f * t, 0xFFC8CCD4, 0xFF8E97A5, 0xD0B + slot * 977);

        // Іконка вилітає дугою до центру екрана: вгору, потім важко падає; крутиться й тане.
        float towardCenter = (sw / 2f - cx) * 0.35f * t;
        float px = cx + towardCenter;
        float py = cy - 150f * t + 190f * t * t;
        float scale = (float) Math.pow(1f - t, 0.8) * 1.1f;
        if (scale > 0.08f) {
            float spin = (slot % 2 == 0 ? 1f : -1f) * 540f * t;
            // Слід із двох «привидів» позаду.
            for (int k = 2; k >= 1; k--) {
                float tt = Math.max(0f, t - 0.05f * k);
                float gx = cx + (sw / 2f - cx) * 0.35f * tt;
                float gy = cy - 150f * tt + 190f * tt * tt;
                float gs = (float) Math.pow(1f - tt, 0.8) * (1.1f - 0.25f * k);
                if (gs > 0.08f) drawItem(g, stack, gx, gy, itemSize * gs, (slot % 2 == 0 ? 1f : -1f) * 540f * tt);
            }
            drawItem(g, stack, px, py, itemSize * scale, spin);
        }
    }

    // ── Примітиви ────────────────────────────────────────────────────────

    /** Іконка предмета з центром у (cx, cy), розміром {@code size} (як у слоті: 24-піксельна сітка) і поворотом. */
    private static void drawItem(GuiGraphics g, ItemStack stack, float cx, float cy, float size, float rotDeg) {
        var pose = g.pose();
        pose.pushPose();
        pose.translate(cx, cy, 300f);
        pose.mulPose(Axis.ZP.rotationDegrees(rotDeg));
        float scale = size / 24f;
        pose.scale(scale, scale, 1f);
        pose.translate(-8f, -8f, 0f);
        g.renderItem(stack, 0, 0);
        pose.popPose();
    }

    private static float bezier(float p0, float p1, float p2, float t) {
        float inv = 1f - t;
        return inv * inv * p0 + 2f * inv * t * p1 + t * t * p2;
    }

    /** Віяло іскор із точки: детерміноване від {@code seed}, прогрес {@code k} 0..1. */
    private static void burst(GuiGraphics g, float cx, float cy, float k, int count, float radius,
                              int colorA, int colorB, int seed) {
        if (k >= 1f) return;
        float a = (1f - k) * (1f - k);
        for (int i = 0; i < count; i++) {
            int h = hash(seed + i * 2654435);
            float ang = (float) ((i / (float) count) * Math.PI * 2 + ((h & 255) / 255f - 0.5f) * 0.8f);
            float r = radius * (0.6f + 0.4f * (((h >> 8) & 255) / 255f));
            int px = Math.round(cx + (float) Math.cos(ang) * r);
            int py = Math.round(cy + (float) Math.sin(ang) * r);
            int d = ((h >> 16) & 3) == 0 ? 3 : 2;
            int c = ((h >> 18) & 1) == 0 ? colorA : colorB;
            g.fill(px, py, px + d, py + d, GeneratorFuelUiTheme.alpha(c, a));
        }
    }

    private static int hash(int v) {
        int h = v * 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return h & 0x7FFFFFFF;
    }
}
