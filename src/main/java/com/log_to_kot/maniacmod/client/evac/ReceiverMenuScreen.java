package com.log_to_kot.maniacmod.client.evac;

import com.log_to_kot.maniacmod.client.style.CircleFx;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.UiFx;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.c2s.intent.ReceiverCallPacket;
import com.log_to_kot.maniacmod.net.s2c.evac.EvacStatusPacket.Step;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Меню приймача (ПКМ по приймачу): виглядає як польова рація, а не як інформаційна панель —
 * металевий корпус із гвинтами й антеною, динамік-решітка, фосфорний екран (частота, сила
 * сигналу, осцилограма, статус), ручка настройки, чотири сигнальні лампи кроків евакуації
 * (світло → виклик → ракета → вертоліт) і фізична клавіша «Викликати вертоліт». Екран НЕ ставить
 * гру на паузу (мультиплеєр, світ іде далі) — про це нагадує гравіювання внизу корпусу.
 *
 * <p>Клавіша активна лише при стані 1; сервер при натисканні однаково перевіряє все заново
 * ({@code EvacModule.onReceiverCall}). Поточний крок береться зі стану меню і
 * {@link ClientEvac#step()} — клієнт нічого не вирішує.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class ReceiverMenuScreen extends Screen {

    private static final int W = 300, H = 214;
    private static final int GREEN = 0xFF7CFC9A, AMBER = 0xFFE0A030, GREY = 0xFF5B6470, SILVER = 0xFFC2C9D6;
    // Корпус польової військової рації: оливково-сірий метал, чорні вставки, фосфорний екран.
    private static final int CASE = 0xFF2C3128, CASE_LIGHT = 0xFF3C4336, CASE_DARK = 0xFF171A14, CASE_EDGE = 0xFF0C0E0A;
    private static final int LCD_BG = 0xFF08130C, LCD_DIM = 0xFF2E6B45, LCD_ON = 0xFF86F7A8;
    private static final int RED = 0xFFE5483C;

    private static final String[] STEP_KEYS = { "power", "call", "flare", "heli" };

    private final int state;
    private final long openedMs = System.currentTimeMillis();
    /** 0..3 — індекс поточного кроку; 4 — усе виконано; -1 — недоступно. */
    private final int currentStep;
    private CallButton call;

    private ReceiverMenuScreen(int state) {
        super(Component.translatable("maniacmod.evac.menu.title"));
        this.state = state;
        this.currentStep = resolveCurrentStep(state);
    }

    public static void open(int state) {
        Minecraft.getInstance().setScreen(new ReceiverMenuScreen(state));
    }

    private static int resolveCurrentStep(int state) {
        return switch (state) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> switch (ClientEvac.step()) {
                case Step.CALLED -> 2;
                case Step.INBOUND, Step.ROPE_DROP -> 3;
                case Step.CLIMB, Step.DEPARTING -> 4;
                default -> 2;
            };
            default -> -1;
        };
    }

    @Override
    protected void init() {
        int x = (width - W) / 2, y = (height - H) / 2 + 8;
        call = new CallButton(x + 82, y + H - 56, W - 96, 30,
            Component.translatable("maniacmod.evac.menu.call"), () -> {
                ModNetwork.toServer(new ReceiverCallPacket());
                onClose();
            });
        call.active = state == 1;
        addRenderableWidget(call);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        long now = System.currentTimeMillis();
        float open = UiAnim.easeOutBack(UiAnim.clamp01((now - openedMs) / 380f), 0.9f);
        int x = (width - W) / 2, y = (height - H) / 2 + 8;   // +8: зверху місце під антену

        g.pose().pushPose();
        float cx = width / 2f, cy = height / 2f;
        g.pose().translate(cx, cy + (1f - open) * 18f, 0f);
        float sc = 0.9f + 0.1f * open;
        g.pose().scale(sc, sc, 1f);
        g.pose().translate(-cx, -cy, 0f);

        drawAntenna(g, x, y, now);
        drawChassis(g, x, y);
        drawSpeaker(g, x + 14, y + 14, 58, 82, now);
        drawDisplay(g, x + 82, y + 14, W - 96, 82, now);
        drawLamps(g, x + 14, y + 106, W - 28, now);
        drawDial(g, x + 14 + 20, y + H - 38, now);

        super.render(g, mx, my, pt);                       // кнопка виклику

        // Гравіювання на корпусі — як трафарет на справжній рації.
        g.drawCenteredString(font, Component.translatable("maniacmod.evac.menu.warn"),
            x + W / 2 + 22, y + H - 11, 0xFF6F7A62);
        g.pose().popPose();
    }

    // ── Частини ──────────────────────────────────────────────────────────

    /** Штирьова антена зверху корпусу; на живому каналі від кінчика розходяться хвилі. */
    private void drawAntenna(GuiGraphics g, int x, int y, long now) {
        int ax = x + W - 36;
        boolean live = state == 1 || state == 2;
        int col = state == 1 ? GREEN : state == 2 ? AMBER : GREY;
        g.fill(ax - 3, y - 6, ax + 3, y + 1, CASE_DARK);          // основа
        g.fill(ax - 1, y - 30, ax + 1, y - 5, SILVER);             // штир
        CircleFx.disc(g, ax, y - 31, 2.4f, GeneratorFuelUiTheme.alpha(col, 1f), GeneratorFuelUiTheme.alpha(col, 0.9f));
        if (live) {
            for (int i = 0; i < 3; i++) {
                float t = ((now % 1800L) / 1800f + i / 3f) % 1f;
                float r = 4f + t * 16f;
                CircleFx.ring(g, ax, y - 31, r, r + 1.1f, GeneratorFuelUiTheme.alpha(col, (1f - t) * 0.7f));
            }
        }
    }

    /** Металевий корпус: фаска, вставка-панель, гвинти по кутах. */
    private void drawChassis(GuiGraphics g, int x, int y) {
        g.fill(x - 1, y - 1, x + W + 1, y + H + 1, CASE_EDGE);
        g.fill(x, y, x + W, y + H, CASE);
        g.fill(x, y, x + W, y + 1, CASE_LIGHT);                    // світло зверху
        g.fill(x, y, x + 1, y + H, CASE_LIGHT);
        g.fill(x, y + H - 1, x + W, y + H, CASE_DARK);             // тінь знизу
        g.fill(x + W - 1, y, x + W, y + H, CASE_DARK);
        int[][] screws = { {x + 6, y + 6}, {x + W - 7, y + 6}, {x + 6, y + H - 7}, {x + W - 7, y + H - 7} };
        for (int[] sp : screws) {
            CircleFx.disc(g, sp[0], sp[1], 2.4f, 0xFF59604F, 0xFF20241C);
            g.fill(sp[0] - 1, sp[1], sp[0] + 2, sp[1] + 1, 0xFF14160F);
        }
    }

    /** Динамік: решітка з вертикальних прорізів; на живому каналі ледь «дихає». */
    private void drawSpeaker(GuiGraphics g, int x, int y, int w, int h, long now) {
        g.fill(x, y, x + w, y + h, CASE_DARK);
        ManiacUiTheme.border1px(g, x, y, w, h, CASE_EDGE);
        boolean live = state == 1 || state == 2;
        for (int i = 0; i < 7; i++) {
            int sy = y + 6 + i * 10;
            float amp = live ? 0.5f + 0.5f * Mth.sin(now / 160f + i * 0.9f) : 0f;
            int c = GeneratorFuelUiTheme.mix(0xFF0A0B08, 0xFF30382A, 0.4f + 0.6f * amp);
            g.fill(x + 6, sy, x + w - 6, sy + 4, c);
            g.fill(x + 6, sy + 4, x + w - 6, sy + 5, 0xFF1F241A);
        }
    }

    /** Фосфорний екран: канал, сила сигналу, частота, осцилограма, статус. */
    private void drawDisplay(GuiGraphics g, int x, int y, int w, int h, long now) {
        boolean live = state == 1 || state == 2;
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, CASE_EDGE);
        g.fill(x, y, x + w, y + h, LCD_BG);
        int on = live ? LCD_ON : LCD_DIM;

        // Верхній рядок: канал + шкала сигналу
        g.drawString(font, "CH 07", x + 6, y + 5, LCD_DIM, false);
        int bars = switch (state) { case 1 -> 3; case 2 -> 4; default -> 0; };
        for (int i = 0; i < 4; i++) {
            int bh = 3 + i * 2;
            int bx = x + w - 28 + i * 6;
            g.fill(bx, y + 14 - bh, bx + 4, y + 14, i < bars ? on : 0xFF123020);
        }

        // Частота — великими цифрами; у живому каналі остання цифра «пливе».
        String freq = live ? String.format("121.%02d", 50 + (int) ((now / 700L) % 3)) : "---.--";
        g.pose().pushPose();
        g.pose().translate(x + 6, y + 19, 0f);
        g.pose().scale(2f, 2f, 1f);
        g.drawString(font, freq, 0, 0, on, false);
        g.pose().popPose();
        g.drawString(font, "MHz", x + 6 + font.width(freq) * 2 + 4, y + 30, LCD_DIM, false);

        // Статус (до двох рядків)
        var lines = font.split(Component.translatable("maniacmod.evac.menu.state." + state), w - 12);
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            g.drawString(font, lines.get(i), x + 6, y + 40 + i * 10, state == 1 ? LCD_ON : state == 2 ? AMBER : LCD_DIM, false);
        }

        // Осцилограма: рівна — без світла, синус — зв'язок є, модульована — виклик іде.
        int wy = y + h - 11, ww = w - 12;
        g.fill(x + 6, wy, x + 6 + ww, wy + 1, 0xFF123020);
        for (int i = 0; i < ww; i++) {
            float ph = i * 0.22f - now / 120f;
            float amp = switch (state) {
                case 1 -> 4f * Mth.sin(ph);
                case 2 -> 5f * Mth.sin(ph) * (0.5f + 0.5f * Mth.sin(i * 0.05f + now / 400f));
                default -> ((i * 31 + now / 90) % 5 == 0 ? 1f : 0f);
            };
            g.fill(x + 6 + i, wy - Math.round(amp) , x + 7 + i, wy - Math.round(amp) + 1, on);
        }
        // Скляна відблиск
        g.fill(x, y, x + w, y + 1, 0x22FFFFFF);
        UiFx.scanBeam(g, x, y, w, h, UiAnim.clamp01((now - openedMs) / 1000f), 18, on & 0x00FFFFFF, 30);
    }

    /** Чотири кроки як сигнальні лампи на панелі: зелена — зроблено, бурштинова — зараз, темна — далі. */
    private void drawLamps(GuiGraphics g, int x, int y, int w, long now) {
        int cell = w / STEP_KEYS.length;
        for (int i = 0; i < STEP_KEYS.length; i++) {
            boolean done = currentStep >= 0 && i < currentStep;
            boolean cur = currentStep == i;
            int lx = x + i * cell + cell / 2;
            g.fill(lx - 8, y - 2, lx + 8, y + 12, CASE_DARK);       // «ніша» лампи
            if (done) {
                CircleFx.glow(g, lx, y + 5, 4f, 3f, GeneratorFuelUiTheme.alpha(GREEN, 0.35f));
                CircleFx.disc(g, lx, y + 5, 4f, GREEN, GeneratorFuelUiTheme.alpha(GREEN, 0.85f));
            } else if (cur) {
                float p = pulse(now, 700f);
                CircleFx.glow(g, lx, y + 5, 4f, 4f, GeneratorFuelUiTheme.alpha(AMBER, 0.3f + 0.45f * p));
                CircleFx.disc(g, lx, y + 5, 4f, GeneratorFuelUiTheme.alpha(AMBER, 0.6f + 0.4f * p), AMBER);
            } else {
                CircleFx.disc(g, lx, y + 5, 4f, 0xFF1A1E16, 0xFF0C0E0A);
            }
            Component label = Component.translatable("maniacmod.evac.menu.step." + STEP_KEYS[i]);
            var lines = font.split(label, cell - 4);
            for (int k = 0; k < Math.min(2, lines.size()); k++) {
                int tw = font.width(lines.get(k));
                g.drawString(font, lines.get(k), lx - tw / 2, y + 16 + k * 9,
                    cur ? GeneratorFuelUiTheme.TEXT : done ? 0xFFA8B59A : 0xFF6F7A62, false);
            }
        }
    }

    /** Ручка настройки: стрілка повільно гуляє, поки канал живий. */
    private void drawDial(GuiGraphics g, float cx, float cy, long now) {
        boolean live = state == 1 || state == 2;
        for (int i = 0; i <= 8; i++) {                               // шкала навколо
            float a = (-225f + i * 33.75f) * Mth.DEG_TO_RAD;
            g.fill(Math.round(cx + Mth.cos(a) * 19f), Math.round(cy + Mth.sin(a) * 19f),
                   Math.round(cx + Mth.cos(a) * 19f) + 1, Math.round(cy + Mth.sin(a) * 19f) + 1, 0xFF8A9580);
        }
        CircleFx.disc(g, cx, cy, 13f, 0xFF3A4034, 0xFF14160F);
        CircleFx.ring(g, cx, cy, 13f, 14f, 0xFF0C0E0A);
        float ang = (live ? -90f + 40f * Mth.sin(now / 900f) : -135f) * Mth.DEG_TO_RAD;
        for (int r = 3; r <= 11; r++) {                              // стрілка
            g.fill(Math.round(cx + Mth.cos(ang) * r), Math.round(cy + Mth.sin(ang) * r),
                   Math.round(cx + Mth.cos(ang) * r) + 2, Math.round(cy + Mth.sin(ang) * r) + 2, live ? RED : 0xFF59604F);
        }
    }

    private static float pulse(long now, float periodMs) {
        return 0.5f + 0.5f * Mth.sin(now / periodMs * Mth.TWO_PI);
    }

    // ── Кнопка ───────────────────────────────────────────────────────────

    /** Фізична клавіша на корпусі: опукла, при наведенні підсвічується, «тисне» вниз при натисканні. */
    private static final class CallButton extends AbstractButton {
        private final Runnable action;
        private float hover = 0f;
        private long lastMs = System.currentTimeMillis();

        CallButton(int x, int y, int w, int h, Component message, Runnable action) {
            super(x, y, w, h, message);
            this.action = action;
        }

        @Override
        public void onPress() { action.run(); }

        @Override
        public void updateWidgetNarration(NarrationElementOutput out) { defaultButtonNarrationText(out); }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            long now = System.currentTimeMillis();
            float dt = Math.min(0.1f, (now - lastMs) / 1000f);
            lastMs = now;
            hover = UiAnim.approach(hover, active && isHoveredOrFocused() ? 1f : 0f, 14f, dt);

            int x = getX(), y = getY();
            g.fill(x - 1, y - 1, x + width + 1, y + height + 1, 0xFF0C0E0A);         // гніздо
            int press = hover > 0.5f ? 1 : 0;
            int face = active ? GeneratorFuelUiTheme.mix(0xFF2F5A3C, 0xFF3F8A55, hover) : 0xFF30352B;
            g.fill(x + press, y + press, x + width - 1 + press, y + height - 1 + press, face);
            g.fill(x + press, y + press, x + width - 1 + press, y + 1 + press,        // блік зверху
                active ? 0x66FFFFFF : 0x22FFFFFF);
            g.fill(x + press, y + height - 3 + press, x + width - 1 + press, y + height - 1 + press, // тінь знизу
                active ? 0x66000000 : 0x55000000);
            if (active) {
                float p = 0.5f + 0.5f * Mth.sin(now / 420f);
                UiFx.glow(g, x, y, width, height, GREEN, 0.12f + 0.2f * p + 0.25f * hover, 3);
            }
            Minecraft mc = Minecraft.getInstance();
            g.drawCenteredString(mc.font, getMessage(), x + width / 2 + press, y + (height - 8) / 2 + press,
                active ? 0xFFFFFFFF : 0xFF6F7A62);
        }
    }
}
