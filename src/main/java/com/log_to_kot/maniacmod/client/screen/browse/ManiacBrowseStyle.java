package com.log_to_kot.maniacmod.client.screen.browse;

import com.log_to_kot.maniacmod.client.screen.mainmenu.ManiacMainMenuBackground;
import com.log_to_kot.maniacmod.client.style.BloodFx;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.gui.ModListScreen;

import java.util.List;

/**
 * Спільний «маніячний» стиль для екранів: вибір світу, список серверів,
 * додавання/редагування сервера, прямий вхід, список модів.
 *
 * Логіку екранів не чіпаємо: міксини (див. {@code mixin/browse}) лише
 * перехоплюють малювання й викликають методи звідси. Пінг, іконки, тултіпи,
 * кліки — ванільні.
 *
 * Фон — той самий, що в головному меню ({@link ManiacMainMenuBackground#renderShared}).
 */
@OnlyIn(Dist.CLIENT)
public final class ManiacBrowseStyle {

    public static final int SILVER = 0xFFF2F4F8;
    public static final int BODY = 0xFFB9B9B9;
    public static final int DIM = 0xFF7A7A7A;
    public static final int PANEL = 0xFF0B0D0E;
    public static final int CARD = 0xFF0B0D0E;
    public static final int CARD_HOVER = 0xFF14181A;
    public static final int BORDER = 0xFF292D2E;
    public static final int SHADE = 0xFF050607;

    private ManiacBrowseStyle() {}

    // ── Які екрани стилізуємо ───────────────────────────────────────────

    /** Серверів, світів і форм стосується {@code ManiacMenuTheme}; тут лише екран модів Forge. */
    public static boolean isStyled(Screen s) {
        return s instanceof ModListScreen;
    }

    /** Списки, у яких прибираємо ванільну «землю» (малювання самих екранів — у ManiacMenuTheme). */
    public static boolean hidesListDirt() {
        Screen s = Minecraft.getInstance().screen;
        return isStyled(s)
            || s instanceof net.minecraft.client.gui.screens.worldselection.SelectWorldScreen
            || s instanceof net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
    }

    public static boolean isStyled() {
        return isStyled(Minecraft.getInstance().screen);
    }

    // ── Фон ─────────────────────────────────────────────────────────────

    /** Фон головного меню + затемнення; для форм (додати/редагувати сервер, прямий вхід) — ще й панель. */
    public static void renderBackdrop(GuiGraphics g, Screen s) {
        int w = s.width;
        int h = s.height;
        if (!ManiacMainMenuBackground.renderShared(g, w, h)) {
            g.fillGradient(0, 0, w, h, 0xFF0B0809, 0xFF030304);
        }
        g.fill(0, 0, w, h, BloodFx.a(SHADE, 0.50f));

        int vh = (int) (h * 0.24f);
        g.fillGradient(0, 0, w, vh, BloodFx.a(0xFF020203, 0.60f), 0x00020203);
        g.fillGradient(0, h - vh, w, h, 0x00020203, BloodFx.a(0xFF020203, 0.70f));
        int fogH = (int) (h * 0.36f);
        g.fillGradient(0, h - fogH, w, h, 0x00500810, BloodFx.a(0xFF500810, 0.18f));

    }

    /** Червона цятка + підпис зверху по центру (над ванільним заголовком). */
    private static void overline(GuiGraphics g, Font font, int cx, String text) {
        float scale = 0.75f;
        int tw = Math.round(font.width(text) * scale);
        int x = cx - (tw + 10) / 2;
        BloodFx.blob(g, x + 2.5f, 8.5f, 2.2f, BloodFx.BRIGHT);
        g.pose().pushPose();
        g.pose().translate(x + 9, 5, 0);
        g.pose().scale(scale, scale, 1f);
        g.drawString(font, text, 0, 0, BODY, false);
        g.pose().popPose();
    }

    /** Панель за формою: межі беремо з реальних віджетів, а не з констант ванілі. */
    private static void formPanel(GuiGraphics g, Screen s) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (GuiEventListener l : s.children()) {
            if (l instanceof AbstractWidget wd) {
                minX = Math.min(minX, wd.getX());
                maxX = Math.max(maxX, wd.getX() + wd.getWidth());
                maxY = Math.max(maxY, wd.getY() + wd.getHeight());
            }
        }
        if (minX == Integer.MAX_VALUE) return;
        int px = minX - 16;
        int pw = (maxX - minX) + 32;
        int py = 3;
        int ph = (maxY + 14) - py;
        g.fill(px, py, px + pw, py + ph, BloodFx.a(PANEL, 0.94f));
        BloodFx.border1(g, px, py, pw, ph, BORDER);
        g.fill(px, py, px + pw, py + 2, BloodFx.BRIGHT);
        g.fill(px, py, px + 2, py + ph, BloodFx.MID);
    }

    /** Напівпрозора темна підкладка під ScrollPanel (інфо про мод). */
    public static void panelBackground(GuiGraphics g, int l, int t, int r, int b) {
        g.fill(l, t, r, b, BloodFx.a(PANEL, 0.82f));
        BloodFx.border1(g, l, t, r - l, b - t, BORDER);
    }

    // ── Кнопка ──────────────────────────────────────────────────────────

    public static void drawButton(GuiGraphics g, int x, int y, int w, int h, Component label,
                                  boolean active, float hover, long now) {
        Font font = Minecraft.getInstance().font;
        int slant = h >= 24 ? 5 : 3;
        float hv = active ? hover : 0f;

        slantFill(g, x, y, w - slant, h, slant, active ? 0xE00B0D0E : 0xA0090A0B);

        if (hv > 0.01f) {
            int bladeW = Math.round((w - slant) * (1f - (1f - hv) * (1f - hv) * (1f - hv)));
            slantFill(g, x, y, bladeW, h, slant, BloodFx.a(BloodFx.MID, 0.85f));
            slantFill(g, x + Math.max(0, bladeW - 3), y, Math.min(3, bladeW), h, slant,
                BloodFx.a(BloodFx.HOT, 0.75f * hv));
            float f = (now % 900L) / 900f;
            int sx = x + Math.round((w - slant - 14) * f);
            if (sx + 14 <= x + bladeW) slantFill(g, sx, y, 14, h, slant, BloodFx.a(0x30FFFFFF, hv));
        }

        int borderCol = BloodFx.mix(active ? BORDER : 0xFF1B1E20, BloodFx.HOT, hv * 0.9f);
        slantBorder(g, x, y, w - slant, h, slant, borderCol);
        slantFill(g, x, y, 2, h, slant, active ? (hv > 0.5f ? BloodFx.BRIGHT : 0xFF3A3F47) : 0xFF22262A);

        int col = !active ? 0xFF5A5A5A : (hv > 0.4f ? 0xFFFFFFFF : BODY);
        g.enableScissor(x + 4, y, x + w - 2, y + h);
        g.drawCenteredString(font, label, x + w / 2 + slant / 2, y + (h - 8) / 2, col);
        g.disableScissor();
    }

    private static void slantFill(GuiGraphics g, int x, int y, int w, int h, int slant, int argb) {
        if (w <= 0 || (argb >>> 24) == 0) return;
        for (int row = 0; row < h; row++) {
            int off = Math.round(slant * (h - 1 - row) / (float) Math.max(1, h - 1));
            g.fill(x + off, y + row, x + off + w, y + row + 1, argb);
        }
    }

    private static void slantBorder(GuiGraphics g, int x, int y, int w, int h, int slant, int argb) {
        for (int row = 0; row < h; row++) {
            int off = Math.round(slant * (h - 1 - row) / (float) Math.max(1, h - 1));
            g.fill(x + off, y + row, x + off + 1, y + row + 1, argb);
            g.fill(x + off + w - 1, y + row, x + off + w, y + row + 1, argb);
        }
        g.fill(x + slant, y, x + slant + w, y + 1, argb);
        g.fill(x, y + h - 1, x + w, y + h, argb);
    }

    // ── Поле вводу ──────────────────────────────────────────────────────

    /** Перефарбовує 1-пікселеву рамку ванільного EditBox (викликається ПІСЛЯ ванільного малювання). */
    public static void editBoxFrame(GuiGraphics g, int x, int y, int w, int h, boolean focused) {
        BloodFx.border1(g, x - 1, y - 1, w + 2, h + 2, focused ? BloodFx.BRIGHT : 0xFF3A3F47);
        if (focused) g.fill(x - 1, y - 1, x + 1, y + h + 1, BloodFx.HOT);
    }

    // ── Рядки списків ───────────────────────────────────────────────────

    /** Підкладка картки (малюється ДО ванільного вмісту рядка). */
    public static void cardBase(GuiGraphics g, int left, int top, int w, int h, boolean hover) {
        g.fill(left, top, left + w, top + h, hover ? CARD_HOVER : CARD);
    }

    /** Рамка й акцент картки (малюється ПІСЛЯ вмісту). */
    public static void cardFrame(GuiGraphics g, int left, int top, int w, int h, boolean hover) {
        BloodFx.border1(g, left, top, w, h, hover ? BloodFx.MID : BORDER);
        g.fill(left + w - 2, top, left + w, top + h, hover ? BloodFx.BRIGHT : 0xFF1E2224);
        // рамка навколо іконки 32×32
        BloodFx.border1(g, left, top, 32, Math.min(32, h), hover ? BloodFx.HOT : 0xFF3A3F47);
    }

    /** Рамка виділеного рядка (замість сіро-білої ванільної). */
    public static void selectionFrame(GuiGraphics g, int x0, int x1, int top, int h) {
        BloodFx.border1(g, x0, top - 2, x1 - x0, h + 4, BloodFx.BRIGHT);
    }

    // ── Картка сервера (поверх ванільного рядка після його render) ──────

    public static void serverCard(GuiGraphics g, ServerData sd, int index, int left, int top,
                                  int width, int height, boolean hover) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        long now = System.currentTimeMillis();

        int cx = left + 32;
        g.fill(cx, top, left + width, top + height, hover ? CARD_HOVER : CARD);

        boolean incompatible = sd.protocol != SharedConstants.getCurrentVersion().getProtocolVersion();
        boolean pinging = !sd.pinged || sd.ping == -2L;
        boolean unreachable = !pinging && sd.ping < 0L;

        // права колонка: гравці, пінг
        int rightEdge = left + width - 5;
        int playersW = 0;
        if (!pinging && !unreachable && !incompatible && sd.status != null) {
            playersW = font.width(sd.status);
            // той самий x, що й у ванілі — щоб ванільний тултіп гравців лишався над текстом
            g.drawString(font, sd.status, left + width - playersW - 15 - 2, top + 1, BODY, false);
        }
        drawPingBars(g, left + width - 15, top, sd.ping, pinging, unreachable || incompatible, index, now);

        if (!pinging && !unreachable) {
            String ms = I18n.get("maniacmod.browse.ping", sd.ping);
            g.drawString(font, ms, rightEdge - font.width(ms), top + 12, pingColor(sd.ping), false);
        }
        if (incompatible && sd.version != null) {
            Component v = sd.version;
            int vw = font.width(v);
            g.drawString(font, v, rightEdge - vw, top + 22, 0xFFE05A60, false);
        }

        // назва
        int nameMax = Math.max(20, width - 35 - Math.max(playersW + 20, 30));
        String name = font.plainSubstrByWidth(sd.name == null ? "" : sd.name, nameMax);
        g.drawString(font, name, left + 35, top + 2, hover ? 0xFFFFFFFF : SILVER, false);

        // motd: до двох рядків, не заходить у праву колонку
        if (sd.motd != null) {
            int motdW = Math.max(40, width - 35 - 60);
            List<FormattedCharSequence> lines = font.split(sd.motd, motdW);
            for (int i = 0; i < Math.min(2, lines.size()); i++) {
                g.drawString(font, lines.get(i), left + 35, top + 12 + i * 10, DIM, false);
            }
        }

        cardFrame(g, left, top, width, height, hover);
    }

    /** Картка LAN-сервера (вся ширина рядка). */
    public static void lanCard(GuiGraphics g, String title, String motd, String address,
                               int left, int top, int width, int height, boolean hover) {
        Font font = Minecraft.getInstance().font;
        g.fill(left, top, left + width, top + height, hover ? CARD_HOVER : CARD);
        BloodFx.blob(g, left + 16f, top + 16f, 3.2f, hover ? BloodFx.HOT : BloodFx.BRIGHT);
        int max = Math.max(20, width - 40);
        g.drawString(font, font.plainSubstrByWidth(title, max), left + 35, top + 2, SILVER, false);
        g.drawString(font, font.plainSubstrByWidth(motd == null ? "" : motd, max), left + 35, top + 12, DIM, false);
        g.drawString(font, font.plainSubstrByWidth(address == null ? "" : address, max), left + 35, top + 22, DIM, false);
        cardFrame(g, left, top, width, height, hover);
    }

    private static int pingColor(long ping) {
        if (ping < 150) return 0xFF6FCF7A;
        if (ping < 400) return 0xFFE0C050;
        return 0xFFE05A60;
    }

    /** 5 стовпчиків 10×8; червоний хрестик, якщо сервер недоступний/несумісний; біжить анімація, поки пінг іде. */
    private static void drawPingBars(GuiGraphics g, int x, int y, long ping, boolean pinging,
                                     boolean bad, int index, long now) {
        int lit;
        int col;
        if (bad) {
            lit = 0;
            col = 0xFFE05A60;
        } else if (pinging) {
            lit = (int) ((now / 160L + index) % 6L);
            col = BODY;
        } else {
            lit = ping < 150 ? 5 : ping < 300 ? 4 : ping < 600 ? 3 : ping < 1000 ? 2 : 1;
            col = pingColor(ping);
        }
        for (int i = 0; i < 5; i++) {
            int bh = 2 + i * 1 + (i > 2 ? 1 : 0);
            int bx = x + i * 2;
            g.fill(bx, y + 8 - bh, bx + 1, y + 8, i < lit ? col : 0xFF2A2E31);
        }
        if (bad) {
            g.fill(x + 1, y + 1, x + 9, y + 2, 0xFFE05A60);
            g.fill(x + 1, y + 6, x + 9, y + 7, 0xFFE05A60);
        }
    }
}
