package com.log_to_kot.maniacmod.client.overlay.hotbar;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.ManiacKeybinds;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.UiFx;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.AbilityCooldownPacket;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/**
 * Панель пасток маньяка — вертикальна колонка слотів у ЛІВОМУ НИЖНЬОМУ
 * куті екрана (клавіші 5/6/7), той самий відступ від низу, що хотбар
 * маньяка ({@link ManiacHotbarOverlay#MARGIN_BOTTOM}) — раніше стояла
 * по центру вертикалі, заважаючи огляду й прицілюванню.
 *
 * ── Чому окремо від {@link ManiacHotbarOverlay} ──────────────────────
 * Хотбар-оверлей малює слоти, які lib видає з {@code InventorySlotAllocation};
 * у маньяка їх 0 ({@code ManiacArchetype.HOTBAR_SLOTS}), тож він для
 * маньяка не малює нічого. Пастки — не предмети інвентаря, а дії з
 * власним кулдауном, і показуються самостійною панеллю.
 *
 * ── Вигляд ───────────────────────────────────────────────────────────
 * Той самий стиль, що слот сили виживого ({@code SurvivorVitalsOverlay}):
 * темний фон, рамка, іконка по центру, назва клавіші зверху зліва,
 * темна завіса кулдауну зверху вниз із секундами. Гравець читає обидві
 * ролі однаково.
 *
 * Стани слота:
 *   • готовий            — золота рамка, яскрава іконка;
 *   • режим розміщення   — жовтогаряча товста рамка (слот «в руці»);
 *   • перезарядка        — тьмяна іконка, завіса, залишок у секундах.
 *
 * Малюються лише ті слоти, які маньяк узяв у матч
 * ({@link ClientMatchState#trapIds()}), без порожніх.
 */
@OnlyIn(Dist.CLIENT)
public final class TrapPanelOverlay {

    private static final int SLOT = 40;
    private static final int ICON = 28;
    private static final int GAP = 6;
    private static final int MARGIN_LEFT = 14;
    /** Той самий відступ від низу екрана, що {@code ManiacHotbarOverlay.MARGIN_BOTTOM}. */
    private static final int MARGIN_BOTTOM = 14;

    /** Активний слот — той самий жовтогарячий акцент, що й пастки в хотбарі маньяка. */
    private static final int ACTIVE_BORDER = GeneratorFuelUiTheme.HIGHLIGHT;
    private static final int TRAP_STRIP = GeneratorFuelUiTheme.FILL_WARN;
    private static final float[] readyFlash = new float[8];
    private static final boolean[] wasCooling = new boolean[8];

    // ── Анімаційний стан (статичний — клас без інстансу, як і хотбар) ────
    // entrance       : плавний в'їзд колонки зліва, 0 → 1.
    // selectionPulse : короткий спалах рамки активного слота при зміні.
    // lastActiveSlot : попередній активний слот — звук/пульс рівно на ЗМІНІ.
    // lastReadyMask  : бітова маска готових слотів — «тіньк» на відкаті таймера.
    private static final long[] lastFrameNanos = new long[1];
    private static float entrance = 0f;
    private static float selectionPulse = 0f;
    private static final int NO_SLOT = Integer.MIN_VALUE;
    private static int lastActiveSlot = NO_SLOT;
    private static int lastReadyMask = 0;
    private static boolean readyMaskInit = false;

    private static final float ENTRANCE_SPEED = 8f;
    private static final float PULSE_DECAY_SPEED = 6f;

    private TrapPanelOverlay() {}

    /** Скидає анімаційний стан, коли панель невидима — наступна поява знову плавна. */
    private static void resetAnims() {
        lastFrameNanos[0] = 0L;
        entrance = 0f;
        selectionPulse = 0f;
        lastActiveSlot = NO_SLOT;
        lastReadyMask = 0;
        readyMaskInit = false;
    }

    public static void render(GuiGraphics graphics) {
        if (!ClientMatchState.isManiac()) { resetAnims(); return; }
        if (!ClientMatchState.allows(PhaseRule.HUD)) { resetAnims(); return; }

        List<String> ids = ClientMatchState.trapIds();
        if (ids.isEmpty()) { resetAnims(); return; }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.isSpectator()) { resetAnims(); return; }

        float dt = UiAnim.frameDt(lastFrameNanos);
        entrance = UiAnim.approach(entrance, 1f, ENTRANCE_SPEED, dt);
        selectionPulse = UiAnim.approach(selectionPulse, 0f, PULSE_DECAY_SPEED, dt);

        // Зміна активного слота («в руці») — клік і пружний спалах рамки.
        int activeSlot = ClientMatchState.activeTrapSlot();
        if (activeSlot != lastActiveSlot) {
            if (lastActiveSlot != NO_SLOT) {
                selectionPulse = 1f;
            }
            lastActiveSlot = activeSlot;
        }

        int screenH = mc.getWindow().getGuiScaledHeight();
        int total = ids.size() * SLOT + (ids.size() - 1) * GAP;
        int top = screenH - MARGIN_BOTTOM - total;
        long tick = mc.level != null ? mc.level.getGameTime() : 0L;

        // Готовність слотів — маска, щоб озвучити «тіньк» рівно тоді, коли
        // пастка вийшла з відкату (а не щокадру).
        int readyMask = 0;
        for (int slot = 0; slot < ids.size(); slot++) {
            String cooldownId = AbilityCooldownPacket.trapId(slot);
            if (ClientMatchState.abilityCooldownFraction(cooldownId, tick) <= 0f) readyMask |= (1 << slot);
        }
        readyMaskInit = true;
        lastReadyMask = readyMask;

        // Плавний в'їзд колонки зліва-знизу: поки entrance росте 0→1,
        // панель піднімається на місце (ease-out — гальмує в кінці).
        // Каскад: слоти в'їжджають зліва по черзі, з легким overshoot.
        for (int slot = 0; slot < ids.size(); slot++) {
            float local = UiAnim.clamp01(entrance * 1.4f - slot * 0.12f);
            float e = UiAnim.easeOutBack(local, 0.9f);
            int x = MARGIN_LEFT - Math.round((1f - e) * (SLOT + MARGIN_LEFT + 6));
            renderSlot(graphics, mc, slot, ids.get(slot), x, top + slot * (SLOT + GAP), tick, selectionPulse);
        }
    }

    private static void renderSlot(GuiGraphics graphics, Minecraft mc, int slot, String trapId,
                                   int x, int y, long tick, float pulse) {
        String cooldownId = AbilityCooldownPacket.trapId(slot);
        float remaining = ClientMatchState.abilityCooldownFraction(cooldownId, tick);
        boolean ready = remaining <= 0f;
        boolean active = ClientMatchState.activeTrapSlot() == slot;

        // Слот у стилі панелей генератора.
        int border = active ? ACTIVE_BORDER : GeneratorFuelUiTheme.PANEL_BORDER;
        GeneratorFuelUiTheme.drawSlot(graphics, x, y, SLOT, SLOT, border);
        if (active) {
            float breathe = 0.7f + 0.3f * (float) Math.sin(System.currentTimeMillis() / 280.0);
            UiFx.glow(graphics, x, y, SLOT, SLOT, ACTIVE_BORDER, 0.30f * breathe + pulse * 0.5f, 4);
            ManiacBorder.draw(graphics, x + 1, y + 1, SLOT - 2, SLOT - 2, withAlpha(ACTIVE_BORDER, breathe));
            UiFx.brackets(graphics, x, y, SLOT, SLOT, withAlpha(ACTIVE_BORDER, 0.95f), 6,
                0.35f + 0.65f * pulse, 4);
            // Ковзний блик по активному слоту.
            UiFx.scanBeam(graphics, x + 1, y + 1, SLOT - 2, SLOT - 2,
                (System.currentTimeMillis() % 2400L) / 2400f, 6, 0xFFFFFF, 26);
        }
        // Смужка типу (пастка) зверху.
        graphics.fill(x + 1, y + 1, x + SLOT - 1, y + 3, withAlpha(TRAP_STRIP, ready ? 0.9f : 0.4f));

        // Спалах готовності: коли кулдаун щойно скінчився.
        int fi = Math.min(slot, readyFlash.length - 1);
        if (wasCooling[fi] && ready) readyFlash[fi] = 1f;
        wasCooling[fi] = !ready;
        readyFlash[fi] = UiAnim.approach(readyFlash[fi], 0f, 4.5f, 0.016f);

        // Пружний спалах довкола активного слота — затухає за PULSE_DECAY_SPEED.
        if (active && pulse > 0.03f) {
            GeneratorFuelUiTheme.pulseRing(graphics, x, y, SLOT, SLOT, ACTIVE_BORDER, 1f - pulse, 9);
        }

        drawIcon(graphics, trapId, x, y, ready ? 1.0f : 0.40f);

        if (!ready) {
            int curtain = Math.round((SLOT - 2) * remaining);
            graphics.fill(x + 1, y + 1, x + SLOT - 1, y + 1 + curtain, 0xB0000000);
            GeneratorFuelUiTheme.drawBarFx(graphics, x + 4, y + SLOT - 7, SLOT - 8, 4,
                1f - remaining, 1f - remaining, TRAP_STRIP, 0f);

            // Округлення вгору: «0с» ніколи не показується, поки пастка не готова.
            int seconds = (int) Math.ceil(
                ClientMatchState.abilityCooldownTicksLeft(cooldownId, tick) / 20.0);
            String text = String.valueOf(seconds);
            graphics.drawString(mc.font, text,
                x + (SLOT - mc.font.width(text)) / 2,
                y + (SLOT - mc.font.lineHeight) / 2 - 1,
                GeneratorFuelUiTheme.TEXT, true);
        }
        if (readyFlash[fi] > 0.02f) {
            graphics.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1,
                GeneratorFuelUiTheme.alpha(0xFFFFFFFF, readyFlash[fi] * 0.5f));
            GeneratorFuelUiTheme.pulseRing(graphics, x, y, SLOT, SLOT, TRAP_STRIP, 1f - readyFlash[fi], 8);
        }

        // Справжня назва прив'язки: перепризначення клавіші видно одразу.
        String key = ManiacKeybinds.TRAPS[slot].getTranslatedKeyMessage().getString();
        graphics.drawString(mc.font, key, x + 4, y + 5,
            active ? ACTIVE_BORDER : ready ? GeneratorFuelUiTheme.HIGHLIGHT : GeneratorFuelUiTheme.HIT_ZONE, true);
    }

    /** Локальний хелпер 1px рамки (щоб не тягнути стару тему). */
    private static final class ManiacBorder {
        static void draw(GuiGraphics g, int x, int y, int w, int h, int c) {
            g.fill(x, y, x + w, y + 1, c);
            g.fill(x, y + h - 1, x + w, y + h, c);
            g.fill(x, y, x + 1, y + h, c);
            g.fill(x + w - 1, y, x + w, y + h, c);
        }
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.round(UiAnim.clamp01(alpha) * 255f);
        return (a << 24) | (color & 0x00FFFFFF);
    }

    /**
     * Іконка з {@code textures/gui/traps/<id>.png} (32×32, біла лінійна),
     * тонована під стан. Відсутня текстура — просто чорний квадрат Minecraft
     * (видно одразу, що іконку забули), не падіння.
     */
    private static void drawIcon(GuiGraphics graphics, String trapId, int x, int y, float tint) {
        ResourceLocation tex = new ResourceLocation(ManiacMod.MOD_ID, "textures/gui/traps/" + trapId + ".png");
        int ix = x + (SLOT - ICON) / 2;
        int iy = y + (SLOT - ICON) / 2 + 2; // трохи нижче центру: угорі місце під клавішу
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(tint, tint, tint, 1.0f);
        graphics.blit(tex, ix, iy, ICON, ICON, 0f, 0f, 32, 32, 32, 32);
        // Множник ОБОВ'ЯЗКОВО назад до білого: інакше решта GUI лишилась би тонованою.
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }
}
