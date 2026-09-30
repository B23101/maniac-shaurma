package com.log_to_kot.maniacmod.client.overlay.vitals;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.HeartbeatClock;
import com.log_to_kot.maniacmod.client.ManiacKeybinds;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.UiFx;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.AbilityCooldownPacket;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * HUD виживого — у стилі панелей генератора ({@link GeneratorFuelUiTheme}),
 * але панель і шкали — ТЕКСТУРИ:
 * <ul>
 *   <li>{@code vitals_panel_bg.png} — фон панелі (метал, фаска, слот-медальйон, роздільники);</li>
 *   <li>{@code vitals_panel_overlay.png} — шар ПОВЕРХ вмісту (кромка-блиск, кутові скоби);</li>
 *   <li>{@code hp_bar_empty/full.png}, {@code stamina_bar_empty/full.png} — трек і заливка
 *       шкал 146×10. Заливка — нейтральний срібний метал, тому змінює колір
 *       множником (червоний при малому хп, бурштин при втомі).</li>
 * </ul>
 * Динаміка (блик, «привид», спалахи, іскри, ЕКГ) малюється поверх текстур.
 *
 * ── Що анімується ────────────────────────────────────────────────────
 *  • поява: панель в'їжджає зліва з пружним overshoot, шкали ЗАПОВНЮЮТЬСЯ по черзі;
 *  • втрата хп: «привид», білий спалах, тряска, RGB-глітч панелі, червоні уламки;
 *  • лікування: спалах і срібні іскри, що здіймаються від краю шкали;
 *  • мало хп: червоне світіння панелі й ЕКГ в такт, шкала пульсує червоним;
 *  • серце: пульс у такт близькості маньяка + кільце + ЕКГ у медальйоні;
 *  • стаміна: бурштин при виснаженні, при поламаній нозі блокується;
 *  • слот підсвітки: спалах готовності, штора кулдауну, скоби;
 *  • шкала вставання: пружний «поштовх» і іскри на кожне натискання пробілу.
 */
@OnlyIn(Dist.CLIENT)
public final class SurvivorVitalsOverlay {

    private SurvivorVitalsOverlay() {}

    // ── Геометрія ────────────────────────────────────────────────────────
    private static final int PANEL_W = 214;
    private static final int PANEL_H = 64;
    private static final int MARGIN = 12;
    private static final int PAD = 8;
    private static final int MEDALLION = 44;

    // Текстури панелі та шкал (розміри = розміри PNG, 1 texel = 1 GUI px).
    private static final ResourceLocation TEX_PANEL_BG =
        new ResourceLocation("maniacmod", "textures/gui/vitals/vitals_panel_bg.png");
    private static final ResourceLocation TEX_PANEL_OVERLAY =
        new ResourceLocation("maniacmod", "textures/gui/vitals/vitals_panel_overlay.png");
    private static final ResourceLocation TEX_HP_EMPTY =
        new ResourceLocation("maniacmod", "textures/gui/vitals/hp_bar_empty.png");
    private static final ResourceLocation TEX_HP_FULL =
        new ResourceLocation("maniacmod", "textures/gui/vitals/hp_bar_full.png");
    private static final ResourceLocation TEX_STA_EMPTY =
        new ResourceLocation("maniacmod", "textures/gui/vitals/stamina_bar_empty.png");
    private static final ResourceLocation TEX_STA_FULL =
        new ResourceLocation("maniacmod", "textures/gui/vitals/stamina_bar_full.png");

    private static final int BAR_TEX_W = 146;
    private static final int BAR_TEX_H = 10;
    /** Позиції в координатах панелі (збігаються з тим, що «запечено» у vitals_panel_bg.png). */
    private static final int BAR_X = 60;
    private static final int HP_BAR_Y = 17;
    private static final int STA_BAR_Y = 46;
    private static final int HP_LABEL_Y = 6;
    private static final int STA_LABEL_Y = 35;
    private static final int MED_X = 8;
    private static final int MED_Y = 10;

    private static final int BAR_TINT_NEUTRAL = 0xFFFFFF;

    private static final int HEART_ICON_SIZE = 42;
    private static final int HEART_BASE_DRAW_SIZE = 26;
    private static final ResourceLocation TEX_HEART =
        new ResourceLocation("maniacmod", "textures/gui/vitals/heart_icon.png");
    /** Червоне гало силуету — малюється ПІД серцем, пульсує в такт ударам. */
    private static final ResourceLocation TEX_HEART_GLOW =
        new ResourceLocation("maniacmod", "textures/gui/vitals/heart_glow.png");

    // ── Стан анімацій ───────────────────────────────────────────────────
    private static final long[] lastFrameNanos = new long[1];
    private static float entrance = 0f;
    private static float shownHp = -1f;
    private static float ghostHp = -1f;
    private static float shownStamina = -1f;
    private static float lastHpTarget = -1f;
    private static float hitFlash = 0f;
    private static float healFlash = 0f;
    private static float shake = 0f;
    private static boolean wasReady = true;
    private static float readyFlash = 0f;
    private static int lastPresses = -1;
    private static float standUpPulse = 0f;
    private static float standUpShown = 0f;
    private static float standUpGhost = 0f;
    private static float introTime = 0f;
    private static float ecgPhase = 0f;
    private static float glitch = 0f;
    private static final UiFx.Sparks SPARKS = new UiFx.Sparks(64);
    private static final UiFx.Sparks STAND_SPARKS = new UiFx.Sparks(48);

    private static void resetAnims() {
        lastFrameNanos[0] = 0L;
        entrance = 0f;
        shownHp = -1f;
        ghostHp = -1f;
        shownStamina = -1f;
        lastHpTarget = -1f;
        hitFlash = 0f;
        healFlash = 0f;
        shake = 0f;
        wasReady = true;
        readyFlash = 0f;
        lastPresses = -1;
        standUpPulse = 0f;
        standUpShown = 0f;
        standUpGhost = 0f;
        introTime = 0f;
        ecgPhase = 0f;
        glitch = 0f;
        SPARKS.clear();
        STAND_SPARKS.clear();
    }

    public static void render(GuiGraphics graphics) {
        if (!ClientMatchState.isSurvivor()) { resetAnims(); return; }
        // LOBBY — виняток: /maniac morph survivor призначає роль у лобі для дебагу HUD.
        boolean hudPhase = ClientMatchState.allows(PhaseRule.HUD)
            || ClientMatchState.phase() == GamePhase.LOBBY;
        if (!hudPhase) { resetAnims(); return; }

        int hp = ClientMatchState.hp();
        int maxHp = ClientMatchState.maxHp();
        if (maxHp <= 0) { resetAnims(); return; } // vitals ще не приходили

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int screenH = mc.getWindow().getGuiScaledHeight();
        SurvivorState state = ClientMatchState.survivorState();

        float dt = UiAnim.frameDt(lastFrameNanos);
        introTime += dt;
        // Пружний в'їзд зліва: overshoot дає «вискок і осідання».
        entrance = UiAnim.approach(entrance, 1f, 6f, dt);
        float appear = UiAnim.easeOutCubic(UiAnim.clamp01(entrance));
        float slideT = UiAnim.easeOutBack(UiAnim.clamp01(introTime / 0.55f), 0.9f);

        // ── цілі та згладжування ───────────────────────────────────────
        float hpTarget = UiAnim.clamp01((float) hp / maxHp);
        boolean staminaLocked = state == SurvivorState.BROKEN_LEG;
        float staminaTarget = staminaLocked ? 0f : UiAnim.clamp01(ClientMatchState.stamina());

        // Вступне наповнення: шкали стартують з нуля й по черзі «заливаються».
        boolean hpIntro = introTime < 0.30f;
        boolean staIntro = introTime < 0.50f;
        if (shownHp < 0f) { shownHp = 0f; ghostHp = 0f; lastHpTarget = hpTarget; }
        if (shownStamina < 0f) shownStamina = 0f;
        float hpGoal = hpIntro ? 0f : hpTarget;
        float staGoal = staIntro ? 0f : staminaTarget;

        if (!hpIntro) {
            if (hpTarget < lastHpTarget - 0.001f) {
                hitFlash = 1f; shake = 1f; glitch = 1f;
                SPARKS.burst(BAR_X + Math.max(2f, BAR_TEX_W * shownHp), HP_BAR_Y + 5f, 14, 70f, -0.6f, 0.5f, 0.6f, 0xFF3A44);
            } else if (hpTarget > lastHpTarget + 0.001f) {
                healFlash = 1f;
                SPARKS.burst(BAR_X + Math.max(2f, BAR_TEX_W * shownHp), HP_BAR_Y + 5f, 12, 46f, 0.1f, -1f, 0.8f, 0xE4EAF6);
            }
            lastHpTarget = hpTarget;
        }

        shownHp = UiAnim.approach(shownHp, hpGoal, hpIntro ? 6f : 12f, dt);
        // Слід: тьмяний «привид» доганяє повільно при втраті, ніколи не менший за заливку.
        ghostHp = Math.max(shownHp, UiAnim.approach(ghostHp, hpGoal, 1.6f, dt));
        shownStamina = UiAnim.approach(shownStamina, staGoal, staIntro ? 6f : 12f, dt);
        hitFlash = UiAnim.approach(hitFlash, 0f, 7f, dt);
        healFlash = UiAnim.approach(healFlash, 0f, 5f, dt);
        shake = UiAnim.approach(shake, 0f, 8f, dt);
        glitch = UiAnim.approach(glitch, 0f, 6.5f, dt);

        long now = System.currentTimeMillis();
        boolean low = hpTarget < 0.30f;
        float lowIntensity = low ? 1f - hpTarget / 0.30f : 0f;
        float lowPulse = low
            ? 0.5f + 0.5f * (float) Math.sin(now / (260.0 - 150.0 * lowIntensity))
            : 0f;

        int panelLeft = MARGIN;
        int panelTop = screenH - MARGIN - PANEL_H;
        int shakeX = shake > 0.02f ? Math.round((float) Math.sin(now / 22.0) * 3f * shake) : 0;
        // Зсув в'їзду: панель приїжджає зліва (overshoot через easeOutBack).
        float slideX = (1f - slideT) * -(PANEL_W + MARGIN);

        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, appear);
        GeneratorFuelUiTheme.beginAppear(graphics, panelLeft + PANEL_W / 2f, panelTop + PANEL_H / 2f, appear, 8f);
        graphics.pose().translate(shakeX + slideX, 0f, 0f);

        // Світіння ЗОВНІ панелі: тривожне червоне при малому хп, срібне «дихання» в нормі.
        if (low) {
            UiFx.glow(graphics, panelLeft, panelTop, PANEL_W, PANEL_H, 0xE0303A, 0.35f + 0.65f * lowPulse, 5);
        } else {
            UiFx.glow(graphics, panelLeft, panelTop, PANEL_W, PANEL_H, 0xC2C9D6,
                0.10f + 0.06f * (float) Math.sin(now / 900.0), 3);
        }

        // ── фон-ТЕКСТУРА (+ RGB-глітч при ударі: червоний/блакитний двійники) ─
        if (glitch > 0.05f) {
            int gx = Math.round(glitch * 4f);
            UiFx.blitTinted(graphics, TEX_PANEL_BG, panelLeft - gx, panelTop, 0f, 0f, PANEL_W, PANEL_H, PANEL_W, PANEL_H,
                0xFF2A36, glitch * 0.55f);
            UiFx.blitTinted(graphics, TEX_PANEL_BG, panelLeft + gx, panelTop, 0f, 0f, PANEL_W, PANEL_H, PANEL_W, PANEL_H,
                0x30D8FF, glitch * 0.40f);
        }
        UiFx.blitTinted(graphics, TEX_PANEL_BG, panelLeft, panelTop, 0f, 0f, PANEL_W, PANEL_H, PANEL_W, PANEL_H,
            0xFFFFFF, 1f);
        if (low) {
            int c = GeneratorFuelUiTheme.alpha(GeneratorFuelUiTheme.FILL_DANGER, 0.35f + 0.65f * lowPulse);
            ManiacUiTheme.border1px(graphics, panelLeft, panelTop, PANEL_W, PANEL_H, c);
        }
        if (hitFlash > 0.02f) {
            graphics.fill(panelLeft + 1, panelTop + 1, panelLeft + PANEL_W - 1, panelTop + PANEL_H - 1,
                GeneratorFuelUiTheme.alpha(GeneratorFuelUiTheme.FILL_DANGER, hitFlash * 0.30f));
        }
        if (healFlash > 0.02f) {
            UiFx.innerGlow(graphics, panelLeft, panelTop, PANEL_W, PANEL_H, 0xE4EAF6, healFlash, 6);
        }

        // ── медальйон: ЕКГ + серце ─────────────────────────────────────
        int medX = panelLeft + MED_X;
        int medY = panelTop + MED_Y;
        // Ритм веде ЄДИНИЙ годинник (HeartbeatClock) — той самий, що озвучує серце:
        // фаза інтегрується покадрово (без «перескоку» при зміні періоду), а форма
        // пульсу — два удари «тук-ТУК» з м'яким підйомом і спадом.
        float heartbeat = HeartbeatClock.level();
        float phase = HeartbeatClock.phase();
        float pulse = HeartbeatClock.pulse();

        // ЕКГ біжить у такт серцебиттю; червоніє з тривогою. Зсув 0.525 —
        // щоб QRS-зубець доходив до яскравого правого краю рівно на першому ударі.
        ecgPhase = (phase + 0.525f) % 1f;
        int ecgRgb = GeneratorFuelUiTheme.mix(0x8E97A5, 0xE0303A,
            Math.min(1f, heartbeat * 0.8f + lowIntensity)) & 0xFFFFFF;
        UiFx.ecg(graphics, medX + 3, medY + 3, MEDALLION - 6, MEDALLION - 6, ecgPhase, ecgRgb,
            0.55f + 0.45f * Math.max(heartbeat, lowIntensity), 0.55f);

        int medBorder = GeneratorFuelUiTheme.mix(GeneratorFuelUiTheme.PANEL_BORDER,
            GeneratorFuelUiTheme.FILL_DANGER, Math.min(1f, pulse * (0.35f + heartbeat) + lowPulse * 0.5f));
        ManiacUiTheme.border1px(graphics, medX, medY, MEDALLION, MEDALLION, medBorder);
        renderHeart(graphics, medX + MEDALLION / 2, medY + MEDALLION / 2, pulse, heartbeat);
        // Кільце розходиться від слота на кожен ПЕРШИЙ удар серця (чим ближче маньяк — тим помітніше).
        float ringT = HeartbeatClock.msSinceLub() / 380f;
        if (ringT < 1f && heartbeat > 0.05f) {
            GeneratorFuelUiTheme.pulseRing(graphics, medX, medY, MEDALLION, MEDALLION,
                GeneratorFuelUiTheme.FILL_DANGER, ringT, 9);
        }

        // ── підписи + текстурні шкали ───────────────────────────────────
        String hpLabel = Component.translatable("maniacmod.hud.vitals.hp").getString()
            .toUpperCase(java.util.Locale.ROOT);
        String hpValue = hp + "/" + maxHp;
        int colX = panelLeft + BAR_X;
        int colW = BAR_TEX_W;
        int hpValueColor = GeneratorFuelUiTheme.mix(GeneratorFuelUiTheme.TEXT, GeneratorFuelUiTheme.FILL_DANGER,
            Math.max(hitFlash, lowIntensity * lowPulse));
        graphics.drawString(font, hpLabel, colX, panelTop + HP_LABEL_Y, ManiacUiTheme.TEXT_BODY, false);
        graphics.drawString(font, hpValue, colX + colW - font.width(hpValue), panelTop + HP_LABEL_Y, hpValueColor, false);

        int hpColor = GeneratorFuelUiTheme.mix(BAR_TINT_NEUTRAL, 0xFF3540,
            Math.min(1f, lowIntensity * (0.55f + 0.45f * lowPulse))) & 0xFFFFFF;
        float hpFlash = Math.max(hitFlash, healFlash * 0.6f);
        drawTexBar(graphics, TEX_HP_EMPTY, TEX_HP_FULL, colX, panelTop + HP_BAR_Y,
            shownHp, ghostHp, hpColor, hpFlash, 0f);

        String staLabel = Component.translatable("maniacmod.hud.vitals.stamina").getString()
            .toUpperCase(java.util.Locale.ROOT);
        String staValue = staminaLocked ? "0%" : Math.round(shownStamina * 100f) + "%";
        boolean tired = !staminaLocked && shownStamina < 0.20f;
        float tiredBlink = tired ? 0.55f + 0.45f * (float) Math.sin(now / 150.0) : 0f;
        int staValueColor = staminaLocked ? GeneratorFuelUiTheme.GHOST
            : tired ? GeneratorFuelUiTheme.mix(GeneratorFuelUiTheme.TEXT, GeneratorFuelUiTheme.FILL_WARN, tiredBlink)
            : GeneratorFuelUiTheme.TEXT;
        graphics.drawString(font, staLabel, colX, panelTop + STA_LABEL_Y, ManiacUiTheme.TEXT_BODY, false);
        graphics.drawString(font, staValue, colX + colW - font.width(staValue), panelTop + STA_LABEL_Y, staValueColor, false);

        int staColor = staminaLocked ? 0x56606E
            : tired ? (GeneratorFuelUiTheme.mix(BAR_TINT_NEUTRAL, 0xFFB030, 0.6f + 0.4f * tiredBlink) & 0xFFFFFF)
            : BAR_TINT_NEUTRAL;
        drawTexBar(graphics, TEX_STA_EMPTY, TEX_STA_FULL, colX, panelTop + STA_BAR_Y,
            shownStamina, shownStamina, staColor, 0f, 0.35f);

        // ── шар-overlay (кромка, скоби) + ковзний блиск по верхній грані ───
        UiFx.blitTinted(graphics, TEX_PANEL_OVERLAY, panelLeft, panelTop, 0f, 0f, PANEL_W, PANEL_H, PANEL_W, PANEL_H,
            0xFFFFFF, 1f);
        GeneratorFuelUiTheme.topSheen(graphics, panelLeft, panelTop, PANEL_W, 0f);
        // Промінь-скан раз на кілька секунд — «живий» HUD.
        float scanT = ((now % 6500L) / 6500f) * 1.35f;
        if (scanT < 1f) {
            UiFx.scanBeam(graphics, panelLeft + 1, panelTop + 1, PANEL_W - 2, PANEL_H - 2, scanT, 14, 0xFFFFFF, 20);
        }

        // ── іскри (у локальних координатах панелі) ─────────────────────────
        graphics.pose().pushPose();
        graphics.pose().translate(panelLeft, panelTop, 0f);
        SPARKS.tickAndRender(graphics, dt, 90f);
        graphics.pose().popPose();

        // ── ряд над панеллю: слот сили + чіпи станів ─────────────────────
        int rowY = panelTop - 5 - HL_SLOT;
        renderHighlightSlot(graphics, mc, panelLeft, rowY, dt);
        if (state != SurvivorState.HEALTHY) {
            renderStatusChip(graphics, font, panelLeft + HL_SLOT + 6, rowY + (HL_SLOT - CHIP_H) / 2, state, now);
        }

        GeneratorFuelUiTheme.endAppear(graphics);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        if (state == SurvivorState.CRAWLING) {
            renderStandUpBar(graphics, mc, dt);
        } else {
            lastPresses = -1;
            standUpShown = 0f;
            standUpGhost = 0f;
            standUpPulse = 0f;
            STAND_SPARKS.clear();
        }
    }

    /**
     * Текстурна шкала: {@code *_empty.png} (трек) → «привид» (заливка, тонована
     * у сірий) → {@code *_full.png} (заливка, тонована {@code rgb}) → блик,
     * голівка, спалах. Заливка обрізається по ширині ЧЕРЕЗ uWidth blit-а
     * (без scissor), внутрішня зона треку — x 1..145, y 1..9.
     */
    private static void drawTexBar(GuiGraphics g, ResourceLocation empty, ResourceLocation full,
                                   int x, int y, float fraction, float ghost, int rgb, float flash, float glintOffset) {
        int inner = BAR_TEX_W - 2;
        int filled = Math.round(inner * UiAnim.clamp01(fraction));
        int ghostW = Math.round(inner * UiAnim.clamp01(ghost));

        UiFx.blitTinted(g, empty, x, y, 0f, 0f, BAR_TEX_W, BAR_TEX_H, BAR_TEX_W, BAR_TEX_H, 0xFFFFFF, 1f);

        int x0 = x + 1, y0 = y + 1, innerH = BAR_TEX_H - 2;
        if (ghostW > filled) {
            UiFx.blitTinted(g, full, x0 + filled, y0, 1f + filled, 1f, ghostW - filled, innerH,
                BAR_TEX_W, BAR_TEX_H, 0x56606E, 0.85f);
        }
        if (filled <= 0) return;

        UiFx.blitTinted(g, full, x0, y0, 1f, 1f, filled, innerH, BAR_TEX_W, BAR_TEX_H, rgb, 1f);
        UiFx.barGlint(g, x0, y0, filled, innerH, 1800L, glintOffset);
        UiFx.barHead(g, x0, y0, filled, innerH, rgb, 0.6f + 0.4f * flash);
        if (flash > 0.01f) {
            g.fill(x0, y0, x0 + filled, y0 + innerH, GeneratorFuelUiTheme.alpha(0xFFFFFFFF, flash * 0.75f));
        }
    }

    // ── Серце ────────────────────────────────────────────────────────────

    /**
     * Серце по ЦЕНТРУ медальйона. Раніше тут був зсув {@code -3} по Y, а розмір
     * округлявся до цілого і ділився навпіл — на непарних розмірах серце
     * «стрибало» на піксель під час пульсації й стояло вище рамки. Тепер:
     * без ручного зсуву, а масштаб іде через матрицю (дробовий), з якорем у
     * центрі непрозорої частини текстури — серце не пливе при пульсації.
     */
    private static void renderHeart(GuiGraphics graphics, int cx, int cy, float pulse, float heartbeat) {
        float amplitude = 0.08f + 0.20f * heartbeat;
        float size = HEART_BASE_DRAW_SIZE * (1f + pulse * amplitude);
        // Гало більше за серце вдвічі-півтора й яскравішає на піку удару.
        float glowSize = size * (1.55f + 0.25f * pulse);
        float glowA = 0.30f + 0.70f * pulse * (0.5f + 0.5f * heartbeat);
        drawCentered(graphics, TEX_HEART_GLOW, cx, cy, glowSize, HEART_GLOW_ANCHOR_X, HEART_GLOW_ANCHOR_Y,
            0xFFFFFF, glowA);
        // Легке «вибухання» до білого на піку — серце спалахує при ударі.
        drawCentered(graphics, TEX_HEART, cx, cy, size, HEART_ANCHOR_X, HEART_ANCHOR_Y,
            GeneratorFuelUiTheme.mix(0xFFFFFF, 0xFFD0D0, 1f - pulse) & 0xFFFFFF, 1f);
    }

    /** Центр непрозорої частини heart_icon.png (42x42, анатомічне серце): bbox 7..37 x 2..37. */
    private static final float HEART_ANCHOR_X = 22f;
    private static final float HEART_ANCHOR_Y = 19.5f;
    /** Те саме для heart_glow.png: розмитий силует того ж серця, bbox 8..35 x 3..36. */
    private static final float HEART_GLOW_ANCHOR_X = 21.5f;
    private static final float HEART_GLOW_ANCHOR_Y = 19.5f;

    /**
     * Малює квадратну текстуру {@link #HEART_ICON_SIZE} так, щоб точка-якір
     * (в пікселях текстури) опинилась рівно в ({@code cx}, {@code cy}), а сторона
     * стала {@code size} пікселів (дробове).
     */
    private static void drawCentered(GuiGraphics graphics, ResourceLocation tex, int cx, int cy, float size,
                                     float anchorX, float anchorY, int rgb, float alpha) {
        float scale = size / HEART_ICON_SIZE;
        graphics.pose().pushPose();
        graphics.pose().translate(cx, cy, 0f);
        graphics.pose().scale(scale, scale, 1f);
        graphics.pose().translate(-anchorX, -anchorY, 0f);
        UiFx.blitTinted(graphics, tex, 0, 0, 0f, 0f, HEART_ICON_SIZE, HEART_ICON_SIZE,
            HEART_ICON_SIZE, HEART_ICON_SIZE, rgb, alpha);
        graphics.pose().popPose();
    }

    // ── Чіп стану (нога / повзання / непритомність) ───────────────────────

    private static final int CHIP_H = 18;

    private static void renderStatusChip(GuiGraphics graphics, Font font, int x, int y,
                                          SurvivorState state, long now) {
        String key = switch (state) {
            case BROKEN_LEG -> "maniacmod.hud.status.broken_leg";
            case CRAWLING -> "maniacmod.hud.status.crawling";
            case UNCONSCIOUS -> "maniacmod.hud.status.unconscious";
            default -> null;
        };
        if (key == null) return;
        String text = Component.translatable(key).getString();
        int w = font.width(text) + 16;
        float blink = 0.5f + 0.5f * (float) Math.sin(now / 240.0);
        int border = GeneratorFuelUiTheme.mix(GeneratorFuelUiTheme.PANEL_BORDER,
            state == SurvivorState.BROKEN_LEG ? GeneratorFuelUiTheme.FILL_WARN : GeneratorFuelUiTheme.FILL_DANGER,
            0.35f + 0.65f * blink);
        UiFx.glow(graphics, x, y, w, CHIP_H, border & 0xFFFFFF, 0.25f + 0.45f * blink, 3);
        GeneratorFuelUiTheme.drawSlot(graphics, x, y, w, CHIP_H, border);
        // Маркер-квадрат зліва блимає в такт рамці.
        graphics.fill(x + 5, y + CHIP_H / 2 - 2, x + 9, y + CHIP_H / 2 + 2,
            GeneratorFuelUiTheme.alpha(border, 0.6f + 0.4f * blink) | 0xFF000000);
        graphics.drawString(font, text, x + 13, y + (CHIP_H - font.lineHeight) / 2 + 1,
            GeneratorFuelUiTheme.TEXT, false);
    }

    // ── Сила підсвітки генераторів (клавіша 5) ────────────────────────────

    private static final int HL_SLOT = 36;
    private static final int HL_ICON = 24;
    private static final ResourceLocation TEX_HIGHLIGHT_ICON =
        new ResourceLocation("maniacmod", "textures/gui/vitals/highlight_icon.png");

    private static void renderHighlightSlot(GuiGraphics graphics, Minecraft mc, int x, int y, float dt) {
        long tick = mc.level != null ? mc.level.getGameTime() : 0L;
        float remaining = ClientMatchState.abilityCooldownFraction(AbilityCooldownPacket.HIGHLIGHT_ID, tick);
        boolean ready = remaining <= 0f;

        if (ready && !wasReady) readyFlash = 1f;
        wasReady = ready;
        readyFlash = UiAnim.approach(readyFlash, 0f, 4f, dt);

        long now = System.currentTimeMillis();
        int border = ready
            ? GeneratorFuelUiTheme.mix(GeneratorFuelUiTheme.PANEL_BORDER, GeneratorFuelUiTheme.HIGHLIGHT,
                0.75f + 0.25f * (float) Math.sin(now / 420.0))
            : GeneratorFuelUiTheme.PANEL_BORDER;
        if (ready) {
            UiFx.brackets(graphics, x, y, HL_SLOT, HL_SLOT,
                GeneratorFuelUiTheme.alpha(GeneratorFuelUiTheme.HIGHLIGHT, 0.8f), 4,
                0.5f + 0.5f * (float) Math.sin(now / 380.0), 3);
        }
        GeneratorFuelUiTheme.drawSlot(graphics, x, y, HL_SLOT, HL_SLOT, border);

        int iconX = x + (HL_SLOT - HL_ICON) / 2;
        int iconY = y + (HL_SLOT - HL_ICON) / 2 + 2;
        float tint = ready ? 1.0f : 0.40f;
        RenderSystem.enableBlend();
        float a = RenderSystem.getShaderColor()[3];
        RenderSystem.setShaderColor(tint, tint, tint, a);
        graphics.blit(TEX_HIGHLIGHT_ICON, iconX, iconY, HL_ICON, HL_ICON, 0f, 0f, 32, 32, 32, 32);
        RenderSystem.setShaderColor(1f, 1f, 1f, a);

        if (!ready) {
            int curtain = Math.round((HL_SLOT - 2) * remaining);
            graphics.fill(x + 1, y + 1, x + HL_SLOT - 1, y + 1 + curtain, 0xB0000000);
            // Тонкий бар готовності знизу слота.
            GeneratorFuelUiTheme.drawBarFx(graphics, x + 3, y + HL_SLOT - 6, HL_SLOT - 6, 4,
                1f - remaining, 1f - remaining, GeneratorFuelUiTheme.FILL, 0f);
            int seconds = (int) Math.ceil(
                ClientMatchState.abilityCooldownTicksLeft(AbilityCooldownPacket.HIGHLIGHT_ID, tick) / 20.0);
            String text = String.valueOf(seconds);
            graphics.drawString(mc.font, text, x + (HL_SLOT - mc.font.width(text)) / 2,
                y + (HL_SLOT - mc.font.lineHeight) / 2 - 1, GeneratorFuelUiTheme.TEXT, true);
        }
        if (readyFlash > 0.02f) {
            graphics.fill(x + 1, y + 1, x + HL_SLOT - 1, y + HL_SLOT - 1,
                GeneratorFuelUiTheme.alpha(0xFFFFFFFF, readyFlash * 0.55f));
            GeneratorFuelUiTheme.pulseRing(graphics, x, y, HL_SLOT, HL_SLOT, GeneratorFuelUiTheme.HIGHLIGHT,
                1f - readyFlash, 8);
        }

        String key = ManiacKeybinds.HIGHLIGHT.getTranslatedKeyMessage().getString();
        graphics.drawString(mc.font, key, x + 4, y + 3,
            ready ? GeneratorFuelUiTheme.HIGHLIGHT : GeneratorFuelUiTheme.HIT_ZONE, true);
    }

    // ── Шкала вставання (CRAWLING) — вертикальна, знизу вгору ─────────────

    private static final int STAND_UP_BAR_WIDTH = 12;
    private static final int STAND_UP_BAR_HEIGHT = 90;
    private static final int STAND_UP_PANEL_PADDING = 6;
    private static final int STAND_UP_OFFSET_FROM_CENTER = 26;
    private static final int STAND_UP_TEXT_GAP = 4;

    private static void renderStandUpBar(GuiGraphics graphics, Minecraft mc, float dt) {
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        int presses = ClientMatchState.standUpPresses();
        int required = ClientMatchState.standUpRequired();
        float target = required > 0 ? UiAnim.clamp01((float) presses / required) : 0f;

        // Кожне натискання (прогрес виріс) — пружний поштовх панелі й спалах.
        if (lastPresses >= 0 && presses > lastPresses) {
            standUpPulse = 1f;
            // Іскри від голівки шкали на кожне натискання.
            int pw = STAND_UP_BAR_WIDTH + STAND_UP_PANEL_PADDING * 2;
            int ph = STAND_UP_BAR_HEIGHT + STAND_UP_PANEL_PADDING * 2;
            float headY = (screenH / 2f + ph / 2f) - STAND_UP_PANEL_PADDING - STAND_UP_BAR_HEIGHT * standUpShown;
            STAND_SPARKS.burst(screenW / 2f + STAND_UP_OFFSET_FROM_CENTER + pw / 2f, headY, 8, 60f, 1f, -0.4f, 0.5f, 0xE4EAF6);
        }
        lastPresses = presses;
        standUpPulse = UiAnim.approach(standUpPulse, 0f, 9f, dt);
        standUpShown = UiAnim.approach(standUpShown, target, 16f, dt);
        standUpGhost = target > standUpGhost ? target : UiAnim.approach(standUpGhost, target, 2.2f, dt);

        int panelW = STAND_UP_BAR_WIDTH + STAND_UP_PANEL_PADDING * 2;
        int panelH = STAND_UP_BAR_HEIGHT + STAND_UP_PANEL_PADDING * 2;
        int panelX = screenW / 2 + STAND_UP_OFFSET_FROM_CENTER;
        int panelY = screenH / 2 - panelH / 2;

        float cx = panelX + panelW / 2f;
        float cy = panelY + panelH / 2f;
        graphics.pose().pushPose();
        graphics.pose().translate(cx, cy, 0f);
        float sc = 1f + 0.08f * standUpPulse;
        graphics.pose().scale(sc, sc, 1f);
        graphics.pose().translate(-cx, -cy, 0f);

        UiFx.glow(graphics, panelX, panelY, panelW, panelH, 0xC2C9D6, 0.25f + 0.55f * standUpPulse, 4);
        GeneratorFuelUiTheme.drawPanel(graphics, panelX, panelY, panelW, panelH);
        UiFx.brackets(graphics, panelX, panelY, panelW, panelH, GeneratorFuelUiTheme.alpha(0xFFF2F4F8, 0.85f),
            5, 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 260.0), 3);
        GeneratorFuelUiTheme.drawBarVFx(graphics, panelX + STAND_UP_PANEL_PADDING, panelY + STAND_UP_PANEL_PADDING,
            STAND_UP_BAR_WIDTH, STAND_UP_BAR_HEIGHT, standUpShown, standUpGhost,
            GeneratorFuelUiTheme.FILL, standUpPulse);
        graphics.pose().popPose();
        STAND_SPARKS.tickAndRender(graphics, dt, 70f);

        String percentText = Math.round(standUpShown * 100f) + "%";
        String prompt = Component.translatable("maniacmod.hud.stand_up_prompt").getString();
        int centerX = panelX + panelW / 2;
        graphics.drawString(mc.font, percentText, centerX - mc.font.width(percentText) / 2, panelY - 10,
            GeneratorFuelUiTheme.TEXT, true);

        // Підказка «дихає» — заохочує спамити пробіл.
        float breathe = 0.65f + 0.35f * (float) Math.sin(System.currentTimeMillis() / 180.0);
        graphics.drawString(mc.font, prompt, centerX - mc.font.width(prompt) / 2,
            panelY + panelH + STAND_UP_TEXT_GAP,
            GeneratorFuelUiTheme.alpha(GeneratorFuelUiTheme.TEXT, breathe), true);
    }
}
