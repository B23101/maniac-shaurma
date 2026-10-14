package com.log_to_kot.maniacmod.client.screen.menu;

import com.log_to_kot.maniacmod.client.style.BloodFx;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.storage.LevelSummary;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Картки рядків списків серверів і світів.
 *
 * ── Як це влаштовано ─────────────────────────────────────────────────
 * Викликається з міксинів у КІНЦІ ванільного {@code render} рядка. Ванільний
 * рядок спершу робить усе своє (стартує пінг, оновлює іконку, виставляє
 * тултіпи пінгу й списку гравців), а ми зверху поверх нього зафарбовуємо
 * праву частину рядка непрозорою панеллю й малюємо свій вміст.
 *
 * Чому не {@code cancellable} на початку: пінг сервера стартує саме всередині
 * ванільного {@code OnlineServerEntry.render} (прапорець {@code pinged}). Якщо
 * скасувати {@code render} на вході — пінг не запуститься ніколи.
 *
 * ── Геометрія збігається з ванільною ─────────────────────────────────
 * Іконка 32×32 ліворуч лишається ванільною (разом із її стрілками наведення —
 * вони ж відповідають за кліки «зайти / вгору / вниз»). Смужка пінгу — у тій
 * самій зоні 10×8 на {@code left + width - 15}, текст статусу закінчується на
 * {@code left + width - 17}: ванільні тултіпи («N мс», список гравців) спрацьовують
 * там, де ми малюємо.
 */
@OnlyIn(Dist.CLIENT)
public final class ManiacMenuCards {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy  HH:mm");
    /** Висоти п'яти стовпчиків смужки пінгу. */
    private static final int[] BAR_H = {2, 4, 5, 6, 8};

    private ManiacMenuCards() {}

    // ── Дані світу без @Shadow ─────────────────────────────────────────
    // Приватне поле LevelSummary у WorldListEntry шукаємо за ТИПОМ: назва поля в різних
    // мапінгах (official / SRG) різна, і @Shadow із неточною назвою валить цілий міксин.

    private static final Map<Class<?>, Field> SUMMARY_FIELDS = new HashMap<>();

    public static synchronized LevelSummary summaryOf(Object entry) {
        Class<?> c = entry.getClass();
        if (!SUMMARY_FIELDS.containsKey(c)) {
            Field found = null;
            for (Field f : c.getDeclaredFields()) {
                if (LevelSummary.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        found = f;
                    } catch (RuntimeException ignored) {
                        // немає доступу — світ лишиться ванільним
                    }
                    break;
                }
            }
            SUMMARY_FIELDS.put(c, found);
        }
        Field f = SUMMARY_FIELDS.get(c);
        if (f == null) return null;
        try {
            return (LevelSummary) f.get(entry);
        } catch (IllegalAccessException | RuntimeException e) {
            return null;
        }
    }

    // ── Сервер ──────────────────────────────────────────────────────────

    public static void renderServer(GuiGraphics g, ServerData sd, int top, int left, int width,
                                    int height, boolean hovering, boolean selected, int mx, int my) {
        Font font = Minecraft.getInstance().font;
        long now = Util.getMillis();
        boolean compatible = sd.protocol == SharedConstants.getCurrentVersion().getProtocolVersion();

        int x0 = left + 32;
        int x1 = left + width;
        panel(g, left, top, x0, x1, height, hovering, selected);

        int textX = x0 + 8;
        int rightEdge = x1 - 17;

        // статус праворуч: «гравці» або, якщо версія не підходить, версія сервера червоним
        Component status = compatible ? sd.status : sd.version.copy().withStyle(ChatFormatting.RED);
        int statusW = status == null ? 0 : font.width(status);
        if (status != null) {
            g.drawString(font, status, rightEdge - statusW, top + 3, ManiacMenuTheme.DIM, false);
        }

        // назва
        int nameMax = Math.max(20, rightEdge - statusW - 8 - textX);
        String name = font.plainSubstrByWidth(sd.name == null ? "" : sd.name, nameMax);
        g.drawString(font, name, textX, top + 3, hovering || selected ? 0xFFFFFFFF : ManiacMenuTheme.SILVER, false);

        // опис (MOTD) у два рядки; поки пінг іде — підпис «Опитування…»
        boolean waiting = !sd.pinged || sd.ping == -2L;
        boolean emptyMotd = sd.motd == null || sd.motd.getString().isEmpty();
        if (waiting && emptyMotd) {
            String dots = ".".repeat((int) ((now / 350L) % 4L));
            g.drawString(font, I18n.get("maniacmod.menu.pinging") + dots, textX, top + 14,
                ManiacMenuTheme.DIM, false);
        } else if (sd.motd != null) {
            List<FormattedCharSequence> lines = font.split(sd.motd, Math.max(40, x1 - textX - 8));
            for (int i = 0; i < Math.min(2, lines.size()); i++) {
                g.drawString(font, lines.get(i), textX, top + 13 + 10 * i, ManiacMenuTheme.BODY, false);
            }
        }

        pingBars(g, sd, compatible, left + width - 15, top + 1, now);

        // ванільні стрілки на іконці вимкнені (див. MixinOnlineServerEntryManiac) — малюємо свої
        // у ТИХ САМИХ зонах кліку: права половина — зайти, ліва — вгору/вниз
        if (hovering) iconOverlay(g, left, top, mx, my, compatible);
    }

    /** Накладка на іконку сервера при наведенні: «зайти» праворуч, «вгору/вниз» ліворуч. */
    private static void iconOverlay(GuiGraphics g, int left, int top, int mx, int my, boolean canJoin) {
        g.fill(left, top, left + 32, top + 32, BloodFx.a(ManiacMenuTheme.SHADE, 0.74f));
        int dx = mx - left;
        int dy = my - top;
        boolean inIcon = dx >= 0 && dx < 32 && dy >= 0 && dy < 32;

        if (canJoin) {
            boolean hot = inIcon && dx > 16;
            g.fill(left + 16, top, left + 32, top + 32, BloodFx.a(BloodFx.BRIGHT, hot ? 0.92f : 0.55f));
            g.fill(left + 16, top, left + 17, top + 32, BloodFx.a(BloodFx.HOT, hot ? 0.9f : 0.4f));
            triangle(g, left + 23, top + 16, 3, 0, hot ? 0xFFFFFFFF : 0xFFE6E6E6);
        } else {
            g.fill(left + 16, top, left + 32, top + 32, BloodFx.a(0xFF2A2F31, 0.8f));
        }
        boolean hotUp = inIcon && dx < 16 && dy < 16;
        boolean hotDown = inIcon && dx < 16 && dy >= 16;
        triangle(g, left + 8, top + 8, 3, 3, hotUp ? 0xFFFFFFFF : 0xFF8A9096);
        triangle(g, left + 8, top + 24, 3, 1, hotDown ? 0xFFFFFFFF : 0xFF8A9096);
        g.fill(left + 3, top + 16, left + 14, top + 17, 0x30FFFFFF);
    }

    /**
     * Накладка на іконку світу при наведенні — у стилі іконки сервера. У ванілі вся іконка
     * (32 px) є кнопкою «грати», тож і підсвічуємо її цілком; зони кліків не чіпаємо.
     */
    public static void worldIconOverlay(GuiGraphics g, int left, int top, int mx, int my) {
        boolean hot = mx >= left && mx < left + 32 && my >= top && my < top + 32;
        g.fill(left, top, left + 32, top + 32, BloodFx.a(ManiacMenuTheme.SHADE, 0.80f));
        g.fill(left, top, left + 32, top + 32, BloodFx.a(BloodFx.BRIGHT, hot ? 0.90f : 0.45f));
        g.fill(left + 31, top, left + 32, top + 32, BloodFx.a(BloodFx.HOT, hot ? 0.90f : 0.40f));
        triangle(g, left + 17, top + 16, 5, 0, hot ? 0xFFFFFFFF : 0xFFE6E6E6);
        BloodFx.border1(g, left, top, 32, 32, hot ? BloodFx.HOT : BloodFx.MID);
    }

    /** Маленький трикутник-стрілка. dir: 0 — праворуч, 1 — вниз, 2 — ліворуч, 3 — вгору. */
    private static void triangle(GuiGraphics g, int cx, int cy, int r, int dir, int argb) {
        for (int i = 0; i <= r; i++) {
            int half = r - i;
            switch (dir) {
                case 0 -> g.fill(cx - r + i, cy - half, cx - r + i + 1, cy + half + 1, argb);
                case 2 -> g.fill(cx + r - i, cy - half, cx + r - i + 1, cy + half + 1, argb);
                case 1 -> g.fill(cx - half, cy - r + i, cx + half + 1, cy - r + i + 1, argb);
                default -> g.fill(cx - half, cy + r - i, cx + half + 1, cy + r - i + 1, argb);
            }
        }
    }

    /** Смужка якості зв'язку: зелена / жовта / червона, під час пінгу — біжить хвиля. */
    private static void pingBars(GuiGraphics g, ServerData sd, boolean compatible, int x, int y, long now) {
        boolean waiting = !sd.pinged || sd.ping == -2L;
        int lit;
        int color;
        boolean cross = false;
        if (!compatible) {
            lit = 0;
            color = BloodFx.BRIGHT;
            cross = true;
        } else if (waiting) {
            lit = (int) ((now / 140L) % 6L);
            color = 0xFF9AA0A6;
        } else if (sd.ping < 0L) {
            lit = 0;
            color = BloodFx.BRIGHT;
            cross = true;
        } else {
            long p = sd.ping;
            lit = p < 150L ? 5 : p < 300L ? 4 : p < 600L ? 3 : p < 1000L ? 2 : 1;
            color = p < 300L ? ManiacMenuTheme.GOOD : p < 1000L ? ManiacMenuTheme.WARN : BloodFx.BRIGHT;
        }
        for (int i = 0; i < 5; i++) {
            int bh = BAR_H[i];
            int bx = x + i * 2;
            g.fill(bx, y + 8 - bh, bx + 1, y + 8, i < lit ? color : 0xFF2A2F31);
        }
        if (cross) {
            Font font = Minecraft.getInstance().font;
            g.drawString(font, "×", x + 1, y - 1, BloodFx.BRIGHT, false);
        }
    }

    // ── Світ ────────────────────────────────────────────────────────────

    public static void renderWorld(GuiGraphics g, LevelSummary s, int index, int top, int left,
                                   int width, int height, boolean hovering, boolean selected) {
        if (s == null) return;
        Font font = Minecraft.getInstance().font;
        int x0 = left + 32;
        int x1 = left + width;
        panel(g, left, top, x0, x1, height, hovering, selected);

        int textX = x0 + 8;

        // мітки праворуч (від краю до центру)
        int chipRight = x1 - 6;
        if (s.isLocked()) chipRight = chip(g, font, I18n.get("maniacmod.menu.chip.locked"), chipRight, top + 3, BloodFx.BRIGHT);
        if (s.isHardcore()) chipRight = chip(g, font, I18n.get("maniacmod.menu.chip.hardcore"), chipRight, top + 3, BloodFx.BRIGHT);
        if (s.hasCheats()) chipRight = chip(g, font, I18n.get("maniacmod.menu.chip.cheats"), chipRight, top + 3, ManiacMenuTheme.WARN);

        String name = s.getLevelName();
        if (name == null || name.isEmpty()) name = I18n.get("selectWorld.world") + " " + (index + 1);
        int nameMax = Math.max(20, chipRight - 6 - textX);
        g.drawString(font, font.plainSubstrByWidth(name, nameMax), textX, top + 3,
            hovering || selected ? 0xFFFFFFFF : ManiacMenuTheme.SILVER, false);

        // «папка · дата останньої гри»
        String sub = s.getLevelId();
        long played = s.getLastPlayed();
        if (played > 0L) {
            sub += "  ·  " + DATE.format(Instant.ofEpochMilli(played).atZone(ZoneId.systemDefault()));
        }
        g.drawString(font, font.plainSubstrByWidth(sub, Math.max(40, x1 - textX - 8)), textX, top + 13,
            ManiacMenuTheme.DIM, false);

        // режим / версія — готовий ванільний опис (вже локалізований)
        Component info = s.getInfo();
        if (info != null) {
            List<FormattedCharSequence> lines = font.split(info, Math.max(40, x1 - textX - 8));
            if (!lines.isEmpty()) g.drawString(font, lines.get(0), textX, top + 23, ManiacMenuTheme.BODY, false);
        }
    }

    /** Мітка-«плашка». Повертає нову праву межу для наступної мітки. */
    private static int chip(GuiGraphics g, Font font, String text, int right, int y, int color) {
        float scale = 0.75f;
        int tw = Math.round(font.width(text) * scale);
        int w = tw + 8;
        int x = right - w;
        g.fill(x, y, x + w, y + 10, BloodFx.a(color, 0.16f));
        BloodFx.border1(g, x, y, w, 10, BloodFx.a(color, 0.75f));
        ManiacMenuTheme.text(g, font, text, x + 4, y + 2, scale, color, false);
        return x - 4;
    }

    // ── Спільна панель ──────────────────────────────────────────────────

    /** Непрозора панель праворуч від іконки + рамка іконки; акцент — «лезо» зліва. */
    private static void panel(GuiGraphics g, int left, int top, int x0, int x1, int h,
                              boolean hovering, boolean selected) {
        int fill = selected ? 0xFF171B1D : (hovering ? 0xFF121517 : ManiacMenuTheme.PANEL);
        g.fill(x0, top, x1, top + h, fill);
        int border = selected ? BloodFx.MID : (hovering ? 0xFF3A3F47 : ManiacMenuTheme.BORDER);
        BloodFx.border1(g, x0, top, x1 - x0, h, border);
        int accent = selected ? BloodFx.BRIGHT : (hovering ? BloodFx.MID : 0xFF2A2F31);
        g.fill(x0, top, x0 + 2, top + h, accent);
        BloodFx.border1(g, left, top, 32, 32, border);
    }
}
