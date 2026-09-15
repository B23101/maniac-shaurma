package com.log_to_kot.maniacmod.client.overlay;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.ManiacClientState;
import com.log_to_kot.maniacmod.client.ManiacKeybinds;
import com.log_to_kot.maniacmod.entity.ManiacType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * HUD здібностей маньяка.
 *
 * Показується ТІЛЬКИ маньяку, внизу праворуч від хотбару.
 * Візуально — квадратна іконка з символом здібності та
 * підписом клавіші знизу, точно як на скріншоті гравця.
 *
 * Під час перезарядки іконка затемнюється і
 * заповнюється знизу вгору (як у Minecraft-cooldown).
 *
 * Структура (1 здібність):
 *
 *   ┌─────┐
 *   │  ☽  │   ← іконка здібності
 *   └─────┘
 *    [E]       ← клавіша
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT)
public class AbilityHudOverlay {

    /** Розміри клітинки */
    private static final int CELL_SIZE = 24;
    private static final int BORDER    = 1;
    private static final int KEY_H     = 10; // висота підпису клавіші
    private static final int GAP       = 4;  // відступ від хотбару
    private static final int PAD_RIGHT = 6;  // відступ від правого краю хотбару

    /** Поточний кулдаун з серверу (0.0–1.0, 1.0 = повна перезарядка) */
    private static volatile float cooldownFraction = 0f;

    public static void setCooldownFraction(float f) {
        cooldownFraction = Math.max(0f, Math.min(1f, f));
    }

    // ── Рендер ────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onRenderPost(RenderGuiOverlayEvent.Post event) {
        // Рендеримо ПІСЛЯ хотбару
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) return;
        if (!ManiacClientState.isLocalManiac()) return;
        ManiacType type = ManiacClientState.getLocalType();
        if (type == null) return;

        Minecraft mc = Minecraft.getInstance();
        int W = mc.getWindow().getGuiScaledWidth();
        int H = mc.getWindow().getGuiScaledHeight();

        net.minecraft.client.gui.GuiGraphics guiGraphics = event.getGuiGraphics();
        com.mojang.blaze3d.vertex.PoseStack ps = guiGraphics.pose();

        // Хотбар: ширина 182 пікселі, центр екрану
        int hotbarLeft  = W / 2 - 91;
        int hotbarRight = W / 2 + 91;
        int hotbarY     = H - 22; // верх хотбару (висота 22 пікселі)

        // Позиція клітинки здібності — праворуч від хотбару
        int cellX = hotbarRight + PAD_RIGHT;
        int cellY = hotbarY - (CELL_SIZE - 22) / 2; // вирівнювання по висоті

        // ── Фон клітинки ──────────────────────────────────────────────────────
        int bgColor = 0xCC000000;
        drawRect(ps, cellX,          cellY,          cellX + CELL_SIZE, cellY + CELL_SIZE, bgColor);

        // ── Overlay перезарядки (затемнення знизу вгору) ─────────────────────
        if (cooldownFraction > 0f) {
            int darkH   = (int)(CELL_SIZE * cooldownFraction);
            int darkY   = cellY + CELL_SIZE - darkH;
            drawRect(ps, cellX, darkY, cellX + CELL_SIZE, cellY + CELL_SIZE, 0x99000000);
        }

        // ── Рамка ─────────────────────────────────────────────────────────────
        int borderColor = cooldownFraction > 0 ? 0xFF555555 : 0xFFCC2222;
        // верх
        drawRect(ps, cellX,                      cellY,
                     cellX + CELL_SIZE,          cellY + BORDER, borderColor);
        // низ
        drawRect(ps, cellX,                      cellY + CELL_SIZE - BORDER,
                     cellX + CELL_SIZE,          cellY + CELL_SIZE, borderColor);
        // ліво
        drawRect(ps, cellX,                      cellY,
                     cellX + BORDER,             cellY + CELL_SIZE, borderColor);
        // право
        drawRect(ps, cellX + CELL_SIZE - BORDER, cellY,
                     cellX + CELL_SIZE,          cellY + CELL_SIZE, borderColor);

        // ── Іконка здібності (символ залежить від типу маньяка) ──────────────
        String icon = abilityIcon(type);
        int iconColor = cooldownFraction > 0 ? 0xFF666666 : 0xFFDDDDDD;
        var font = mc.font;
        guiGraphics.drawString(font, icon,
            (int)(cellX + CELL_SIZE / 2f - font.width(icon) / 2f),
            (int)(cellY + CELL_SIZE / 2f - 4f),
            iconColor, true);
        // ── Підпис клавіші знизу ─────────────────────────────────────────────
        String keyName = "[" + ManiacKeybinds.ABILITY.getKey().getDisplayName().getString() + "]";
        int keyBgY  = cellY + CELL_SIZE + 1;
        // Фон підпису
        drawRect(ps,
            cellX + CELL_SIZE / 2 - font.width(keyName) / 2 - 1,
            keyBgY - 1,
            cellX + CELL_SIZE / 2 + font.width(keyName) / 2 + 1,
            keyBgY + KEY_H - 1,
            0xAA000000);
        // Текст клавіші
        int keyColor = cooldownFraction > 0 ? 0xFF666666 : 0xFFFFFFFF;
        guiGraphics.drawString(font, keyName,
            (int)(cellX + CELL_SIZE / 2f - font.width(keyName) / 2f),
            keyBgY,
            keyColor, true);
    }

    // ── Іконки для кожного маньяка ────────────────────────────────────────────

    private static String abilityIcon(ManiacType type) {
        return switch (type) {
            case CHUCKY     -> "☽"; // місячний серп — лють Чакі
            case SLENDERMAN -> "※"; // статичний розряд Слендера
        };
    }

    // ── GL helper ─────────────────────────────────────────────────────────────

    private static void drawRect(PoseStack ps, int x1, int y1, int x2, int y2, int color) {
        if (x2 <= x1 || y2 <= y1) return;
        float a = ((color >> 24) & 0xFF) / 255f;
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >>  8) & 0xFF) / 255f;
        float b = ( color        & 0xFF) / 255f;

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();

        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        buf.vertex(ps.last().pose(), x1, y2, 0).color(r,g,b,a).endVertex();
        buf.vertex(ps.last().pose(), x2, y2, 0).color(r,g,b,a).endVertex();
        buf.vertex(ps.last().pose(), x2, y1, 0).color(r,g,b,a).endVertex();
        buf.vertex(ps.last().pose(), x1, y1, 0).color(r,g,b,a).endVertex();
        Tesselator.getInstance().end();

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }
}
