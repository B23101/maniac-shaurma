package com.log_to_kot.maniacmod.client.screen;

import com.log_to_kot.maniacmod.entity.ManiacType;
import com.log_to_kot.maniacmod.network.ModMessages;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class ManiacSelectScreen extends Screen {

    private static final int ICON_SIZE = 48;
    private static final int ICON_GAP  = 12;
    private static final int ICON_PAD  = 10;
    private static final int PANEL_W   = 180;

    private static final int C_BG         = 0xF0060606;
    private static final int C_ICON_NORMAL= 0xFF1C1C1C;
    private static final int C_ICON_HOVER = 0xFF2A0000;
    private static final int C_ICON_SEL   = 0xFF3D0000;
    private static final int C_ICON_BRD   = 0xFF6B0000;
    private static final int C_ICON_SEL_B = 0xFFDD2222;
    private static final int C_PANEL_BG   = 0xF2110000;
    private static final int C_PANEL_EDGE = 0xFFDD2222;
    private static final int C_TITLE      = 0xFFFF4444;
    private static final int C_TEXT       = 0xFFDDDDDD;
    private static final int C_SUBTEXT    = 0xFF888888;
    private static final int C_BTN        = 0xFF8B0000;
    private static final int C_BTN_H      = 0xFFAA0000;

    // Ключі маньяків — беруться з лангу
    private static final String[] KEYS  = { "chucky", "slenderman" };
    private static final int[]    COLORS = { 0xFFFF6666, 0xFF9999FF };
    private static final ManiacType[] TYPES = { ManiacType.CHUCKY, ManiacType.SLENDERMAN };

    private int   selectedIdx = -1;
    private int   hoveredIdx  = -1;
    private float panelX;
    private boolean btnHover  = false;

    public ManiacSelectScreen() {
        super(Component.translatable("maniacmod.screen.select.title"));
    }

    @Override public boolean isPauseScreen()    { return false; }
    @Override public boolean shouldCloseOnEsc() { return false; }

    @Override
    protected void init() {
        super.init();
        panelX = width;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mx, int my, float delta) {
        PoseStack ps = guiGraphics.pose();
        // Animate panel
        float target = selectedIdx >= 0 ? (width - PANEL_W) : width;
        panelX += (target - panelX) * 0.25f;
        if (Math.abs(panelX - target) < 0.5f) panelX = target;

        drawRect(ps, 0, 0, width, height, C_BG);
        drawRect(ps, 0, 0, width, 2, C_TITLE);
        drawRect(ps, 0, height - 2, width, height, C_TITLE);

        guiGraphics.drawCenteredString(font,
            I18n.get("maniacmod.screen.select.title"), width / 2, 14, C_TITLE);
        guiGraphics.drawCenteredString(font,
            I18n.get("maniacmod.screen.select.subtitle"), width / 2, 26, C_SUBTEXT);

        renderIcons(guiGraphics, ps, mx, my);

        if (selectedIdx >= 0 || (int) panelX < width)
            renderPanel(guiGraphics, ps, mx, my);

        super.render(guiGraphics, mx, my, delta);
    }

    private void renderIcons(GuiGraphics guiGraphics, PoseStack ps, int mx, int my) {
        int totalH = TYPES.length * ICON_SIZE + (TYPES.length - 1) * ICON_GAP;
        int startY = (height - totalH) / 2;
        hoveredIdx = -1;

        for (int i = 0; i < TYPES.length; i++) {
            int iy = startY + i * (ICON_SIZE + ICON_GAP);
            boolean hover = mx >= ICON_PAD && mx <= ICON_PAD + ICON_SIZE
                         && my >= iy       && my <= iy + ICON_SIZE;
            if (hover) hoveredIdx = i;
            boolean sel = selectedIdx == i;

            int bg  = sel ? C_ICON_SEL  : (hover ? C_ICON_HOVER : C_ICON_NORMAL);
            int brd = sel ? C_ICON_SEL_B : C_ICON_BRD;

            drawRect(ps, ICON_PAD,              iy,              ICON_PAD + ICON_SIZE, iy + ICON_SIZE,   bg);
            drawRect(ps, ICON_PAD,              iy,              ICON_PAD + ICON_SIZE, iy + 2,           brd);
            drawRect(ps, ICON_PAD,              iy + ICON_SIZE-2,ICON_PAD + ICON_SIZE, iy + ICON_SIZE,   brd);
            drawRect(ps, ICON_PAD,              iy,              ICON_PAD + 2,         iy + ICON_SIZE,   brd);
            drawRect(ps, ICON_PAD + ICON_SIZE-2,iy,              ICON_PAD + ICON_SIZE, iy + ICON_SIZE,   brd);

            if (sel) drawRect(ps, ICON_PAD + ICON_SIZE, iy + ICON_SIZE/2 - 1,
                                  ICON_PAD + ICON_SIZE + 8, iy + ICON_SIZE/2 + 1, C_ICON_SEL_B);

            // First letter of name as icon
            String name = I18n.get("maniacmod.maniac." + KEYS[i] + ".name");
            String letter = name.substring(0, 1);
            guiGraphics.drawString(font, letter,
                ICON_PAD + ICON_SIZE/2 - font.width(letter)/2,
                iy + ICON_SIZE/2 - 10, COLORS[i]);

            guiGraphics.drawString(font, name,
                ICON_PAD + ICON_SIZE/2 - font.width(name)/2,
                iy + ICON_SIZE - 10,
                sel ? 0xFFFF9999 : C_SUBTEXT);
        }
    }

    private void renderPanel(GuiGraphics guiGraphics, PoseStack ps, int mx, int my) {
        int px = (int) panelX;
        if (px >= width) return;

        drawRect(ps, px, 0, px + PANEL_W, height, C_PANEL_BG);
        drawRect(ps, px, 0, px + 2, height, C_PANEL_EDGE);

        if (selectedIdx < 0 || selectedIdx >= TYPES.length) return;

        String key = KEYS[selectedIdx];
        int cx = px + 12;
        int cw = PANEL_W - 24;
        int cy = 16;

        // Name + subtitle
        guiGraphics.drawString(font, I18n.get("maniacmod.maniac." + key + ".name"), cx, cy, C_TITLE);
        cy += 14;
        guiGraphics.drawString(font, I18n.get("maniacmod.maniac." + key + ".subtitle"), cx, cy, C_SUBTEXT);
        cy += 14;
        drawRect(ps, cx, cy, cx + cw, cy + 1, 0xFF330000);
        cy += 8;

        // Stats
        String[] statKeys = { "cooldown", "size", "weapon", "style" };
        for (String sk : statKeys) {
            String label = I18n.get("maniacmod.stat.label." + sk);
            String value = I18n.get("maniacmod.maniac." + key + ".stat." + sk);
            guiGraphics.drawString(font, label, cx,      cy, C_SUBTEXT);
            guiGraphics.drawString(font, value, cx + 70, cy, C_TEXT);
            cy += 12;
        }
        cy += 6;
        drawRect(ps, cx, cy, cx + cw, cy + 1, 0xFF330000);
        cy += 8;

        // Description
        String desc = I18n.get("maniacmod.maniac." + key + ".desc");
        for (String line : desc.split("\\\\n|\n")) {
            for (String wl : wrapText(line, cw)) {
                guiGraphics.drawString(font, wl, cx, cy, C_TEXT);
                cy += 11;
            }
        }

        // Confirm button
        int btnW = cw, btnH = 20;
        int btnX = cx, btnY = height - 40;
        btnHover = mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH;

        drawRect(ps, btnX, btnY, btnX + btnW, btnY + btnH, btnHover ? C_BTN_H : C_BTN);
        drawRect(ps, btnX, btnY, btnX + btnW, btnY + 1, C_PANEL_EDGE);
        drawRect(ps, btnX, btnY + btnH - 1, btnX + btnW, btnY + btnH, C_PANEL_EDGE);

        String btnLabel = I18n.get("maniacmod.screen.select.btn_confirm") + " " +
                          I18n.get("maniacmod.maniac." + key + ".name");
        guiGraphics.drawString(font, btnLabel, btnX + btnW/2 - font.width(btnLabel)/2, btnY + btnH/2 - 4, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);
        int totalH = TYPES.length * ICON_SIZE + (TYPES.length - 1) * ICON_GAP;
        int startY = (height - totalH) / 2;
        for (int i = 0; i < TYPES.length; i++) {
            int iy = startY + i * (ICON_SIZE + ICON_GAP);
            if (mx >= ICON_PAD && mx <= ICON_PAD + ICON_SIZE && my >= iy && my <= iy + ICON_SIZE) {
                selectedIdx = (selectedIdx == i) ? -1 : i;
                return true;
            }
        }
        if (selectedIdx >= 0 && btnHover) { confirm(); return true; }
        return super.mouseClicked(mx, my, button);
    }

    private void confirm() {
        if (selectedIdx < 0 || selectedIdx >= TYPES.length) return;
        ModMessages.sendManiacSelectResponse(TYPES[selectedIdx]);
        Minecraft.getInstance().setScreen(null);
    }

    private java.util.List<String> wrapText(String text, int maxW) {
        java.util.List<String> r = new java.util.ArrayList<>();
        if (text.isEmpty()) { r.add(""); return r; }
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String test = line.length() == 0 ? word : line + " " + word;
            if (font.width(test) <= maxW) line = new StringBuilder(test);
            else { if (line.length() > 0) r.add(line.toString()); line = new StringBuilder(word); }
        }
        if (line.length() > 0) r.add(line.toString());
        return r;
    }

    private static void drawRect(PoseStack ps, int x1, int y1, int x2, int y2, int color) {
        float a = ((color >> 24) & 0xFF) / 255f;
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >>  8) & 0xFF) / 255f;
        float b = ( color        & 0xFF) / 255f;
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc(); RenderSystem.disableDepthTest();
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        buf.vertex(ps.last().pose(), x1, y2, 0).color(r,g,b,a).endVertex();
        buf.vertex(ps.last().pose(), x2, y2, 0).color(r,g,b,a).endVertex();
        buf.vertex(ps.last().pose(), x2, y1, 0).color(r,g,b,a).endVertex();
        buf.vertex(ps.last().pose(), x1, y1, 0).color(r,g,b,a).endVertex();
        Tesselator.getInstance().end();
        RenderSystem.enableDepthTest(); RenderSystem.disableBlend();
    }
}
