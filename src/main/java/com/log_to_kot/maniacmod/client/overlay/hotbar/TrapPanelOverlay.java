package com.log_to_kot.maniacmod.client.overlay.hotbar;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.ManiacKeybinds;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
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
    private static final int ACTIVE_BORDER = 0xFFFF9838;

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
        int slide = Math.round((1f - UiAnim.easeOutCubic(UiAnim.clamp01(entrance))) * (SLOT + MARGIN_LEFT));
        int x = MARGIN_LEFT - slide;
        int yShift = Math.round((1f - UiAnim.easeOutCubic(UiAnim.clamp01(entrance))) * MARGIN_BOTTOM * 0.5f);

        for (int slot = 0; slot < ids.size(); slot++) {
            renderSlot(graphics, mc, slot, ids.get(slot), x, top + slot * (SLOT + GAP) + yShift, tick, selectionPulse);
        }
    }

    private static void renderSlot(GuiGraphics graphics, Minecraft mc, int slot, String trapId,
                                   int x, int y, long tick, float pulse) {
        String cooldownId = AbilityCooldownPacket.trapId(slot);
        float remaining = ClientMatchState.abilityCooldownFraction(cooldownId, tick);
        boolean ready = remaining <= 0f;
        boolean active = ClientMatchState.activeTrapSlot() == slot;

        graphics.fill(x, y, x + SLOT, y + SLOT, ManiacUiTheme.SLOT_FILL);

        // Рамка: активний слот — товстіша й жовтогаряча (він «в руці»).
        int border = active ? ACTIVE_BORDER
            : ready ? ManiacUiTheme.MENU_ACCENT_GOLD : ManiacUiTheme.BORDER;
        ManiacUiTheme.border1px(graphics, x, y, SLOT, SLOT, border);
        if (active) ManiacUiTheme.border1px(graphics, x + 1, y + 1, SLOT - 2, SLOT - 2, border);

        // Пружний спалах довкола активного слота — затухає за PULSE_DECAY_SPEED.
        if (active && pulse > 0.03f) {
            ManiacUiTheme.border1px(graphics, x - 1, y - 1, SLOT + 2, SLOT + 2, withAlpha(ACTIVE_BORDER, pulse));
        }

        drawIcon(graphics, trapId, x, y, ready ? 1.0f : 0.40f);

        if (!ready) {
            int curtain = Math.round((SLOT - 2) * remaining);
            graphics.fill(x + 1, y + 1, x + SLOT - 1, y + 1 + curtain, 0xB0000000);

            // Округлення вгору: «0с» ніколи не показується, поки пастка не готова.
            int seconds = (int) Math.ceil(
                ClientMatchState.abilityCooldownTicksLeft(cooldownId, tick) / 20.0);
            String text = String.valueOf(seconds);
            graphics.drawString(mc.font, text,
                x + (SLOT - mc.font.width(text)) / 2,
                y + (SLOT - mc.font.lineHeight) / 2 + 2,
                ManiacUiTheme.TEXT_TITLE, true);
        }

        // Справжня назва прив'язки: перепризначення клавіші видно одразу.
        String key = ManiacKeybinds.TRAPS[slot].getTranslatedKeyMessage().getString();
        graphics.drawString(mc.font, key, x + 4, y + 3,
            active ? ACTIVE_BORDER : ready ? ManiacUiTheme.TEXT_ACCENT : ManiacUiTheme.TEXT_BODY, true);
    }

    /** Накладає прозорість на колір 0xFFRRGGBB, зберігаючи його RGB. */
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
