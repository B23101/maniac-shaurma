package com.log_to_kot.maniacmod.client.overlay.hotbar;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.ManiacKeybinds;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
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

    private TrapPanelOverlay() {}

    public static void render(GuiGraphics graphics) {
        if (!ClientMatchState.isManiac()) return;
        if (!ClientMatchState.allows(PhaseRule.HUD)) return;

        List<String> ids = ClientMatchState.trapIds();
        if (ids.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.isSpectator()) return;

        int screenH = mc.getWindow().getGuiScaledHeight();
        int total = ids.size() * SLOT + (ids.size() - 1) * GAP;
        // Знизу зліва (той самий MARGIN_BOTTOM, що хотбар маньяка), не по
        // центру вертикалі: панель пасток — периферійний HUD-елемент, а не
        // те, на що дивляться постійно, і центр екрана заважав приціленню.
        int top = screenH - MARGIN_BOTTOM - total;
        long tick = mc.level != null ? mc.level.getGameTime() : 0L;

        for (int slot = 0; slot < ids.size(); slot++) {
            renderSlot(graphics, mc, slot, ids.get(slot), MARGIN_LEFT, top + slot * (SLOT + GAP), tick);
        }
    }

    private static void renderSlot(GuiGraphics graphics, Minecraft mc, int slot, String trapId,
                                   int x, int y, long tick) {
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
