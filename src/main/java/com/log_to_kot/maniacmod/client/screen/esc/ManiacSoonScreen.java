package com.log_to_kot.maniacmod.client.screen.esc;

import com.log_to_kot.maniacmod.client.style.BloodFx;
import com.log_to_kot.maniacmod.client.style.HorrorBackdrop;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/**
 * Заглушка розділу ESC-меню («Профіль», «Лідерборди»), доки в моді
 * немає збору статистики гравців: у мода поки що немає ні сховища
 * показників (вбивства, втечі, ігри), ні пакета, що їх віддає клієнту.
 * Так само у снайперів «Вікіпедія» відкривається через {@code ComingSoonScreen}.
 *
 * Стиль — той самий фон {@link HorrorBackdrop}. Esc або кнопка
 * «Назад» повертає в ESC-меню.
 */
@OnlyIn(Dist.CLIENT)
public final class ManiacSoonScreen extends Screen {

    private static final int W = 340;
    private static final int H = 150;
    private static final int BTN_H = 24;

    private static final int SILVER = 0xFFF2F4F8;
    private static final int BODY = 0xFFB9B9B9;
    private static final int PANEL = 0xFF0B0D0E;
    private static final int BORDER = 0xFF292D2E;

    private final String titleKey;
    private final String descKey;
    private final HorrorBackdrop backdrop = new HorrorBackdrop();
    private final long openedAt = Util.getMillis();
    private final long[] clock = new long[1];
    private float hoverBack;

    public ManiacSoonScreen(String titleKey, String descKey) {
        super(Component.translatable(titleKey));
        this.titleKey = titleKey;
        this.descKey = descKey;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    @Override
    public void onClose() { back(); }

    private void back() {
        minecraft.setScreen(new ManiacEscMenuScreen(true));
    }

    private int px() { return (width - W) / 2; }
    private int py() { return (height - H) / 2; }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        long now = Util.getMillis();
        float dt = UiAnim.frameDt(clock);
        float a = UiAnim.easeOutCubic((now - openedAt) / 350f);
        backdrop.render(g, width, height, 1f);

        int x = px();
        int y = py();
        g.fill(x, y, x + W, y + H, BloodFx.a(PANEL, 0.94f * a));
        BloodFx.border1(g, x, y, W, H, BloodFx.a(BORDER, a));
        g.fill(x, y, x + W, y + 2, BloodFx.a(BloodFx.BRIGHT, a));

        String title = I18n.get(titleKey).toUpperCase(Locale.ROOT);
        float ts = 1.6f;
        g.pose().pushPose();
        g.pose().translate(x + (W - this.font.width(title) * ts) / 2f, y + 22, 0f);
        g.pose().scale(ts, ts, 1f);
        if (a > 0.02f) g.drawString(this.font, title, 0, 0, BloodFx.a(SILVER, a), false);
        g.pose().popPose();

        String soon = I18n.get("maniacmod.soon.tag");
        int sw = this.font.width(soon) + 14;
        int sx = x + (W - sw) / 2;
        g.fill(sx, y + 46, sx + sw, y + 60, BloodFx.a(0xFF3A0A0E, a));
        BloodFx.border1(g, sx, y + 46, sw, 14, BloodFx.a(BloodFx.BRIGHT, a));
        g.drawString(this.font, soon, sx + 7, y + 50, BloodFx.a(0xFFFF6A70, a), false);

        String desc = I18n.get(descKey);
        int ly = y + 72;
        for (var line : this.font.split(Component.literal(desc), W - 40)) {
            g.drawString(this.font, line, x + 20, ly, BloodFx.a(BODY, a), false);
            ly += this.font.lineHeight + 2;
        }

        int bx = x + 20;
        int bw = W - 40;
        int by = y + H - BTN_H - 16;
        boolean hov = mx >= bx && mx < bx + bw && my >= by && my < by + BTN_H;
        hoverBack = UiAnim.approach(hoverBack, hov ? 1f : 0f, 16f, dt);
        g.fill(bx, by, bx + bw, by + BTN_H, BloodFx.a(0xFF0A0B0C, 0.92f * a));
        int blade = Math.round(bw * UiAnim.easeOutCubic(hoverBack));
        if (blade > 0) {
            g.fill(bx, by, bx + blade, by + BTN_H, BloodFx.a(BloodFx.MID, 0.9f * a));
            g.fill(bx + Math.max(0, blade - 3), by, bx + blade, by + BTN_H, BloodFx.a(BloodFx.BRIGHT, 0.8f * a));
        }
        BloodFx.border1(g, bx, by, bw, BTN_H, BloodFx.a(BloodFx.mix(BORDER, BloodFx.HOT, hoverBack), a));
        String label = I18n.get("maniacmod.soon.back");
        g.drawString(this.font, label, bx + (bw - this.font.width(label)) / 2, by + (BTN_H - 8) / 2,
            BloodFx.a(BloodFx.mix(BODY, 0xFFFFFFFF, hoverBack), a), true);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int bx = px() + 20;
        int by = py() + H - BTN_H - 16;
        if (button == 0 && mx >= bx && mx < bx + W - 40 && my >= by && my < by + BTN_H) {
            UiAnim.playClick();
            back();
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            back();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
