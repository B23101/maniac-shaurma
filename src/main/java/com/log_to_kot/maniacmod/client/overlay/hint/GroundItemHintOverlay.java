package com.log_to_kot.maniacmod.client.overlay.hint;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.entity.GroundItemEntity;
import dev.shaurmalib.forge.network.packets.InventorySlotAllocationPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Підказка над прицілом, коли гравець дивиться на предмет на землі:
 * назва предмета і «ПКМ — взяти».
 *
 * <h3>Чому суто клієнтський клас, без пакетів</h3>
 * Усе потрібне вже на клієнті: приціл ({@code mc.hitResult}), стек
 * сутності (синхронізується {@code SynchedEntityData}) і те, чи є в
 * гравця хоч один дозволений слот. Той самий підхід, що
 * {@link GeneratorHintOverlay}.
 *
 * <h3>Коли НЕ показуємо</h3>
 * <ul>
 *   <li>Фаза не дозволяє предмети ({@link PhaseRule#ITEM_USE}) — сервер
 *       відмовить у підборі, обіцяти його не можна.</li>
 *   <li>У гравця немає жодного дозволеного слота (маньяк, глядач) —
 *       сервер відповів би «немає вільних слотів» на кожен клік; замість
 *       обіцянки, яка завжди брехня, показуємо лише назву.</li>
 *   <li>Гравець присів (Shift): ПКМ із Shift не підбирає, це ремонт
 *       генератора.</li>
 * </ul>
 * Точну відповідь «слотів немає» клієнт знати не може (інвентар
 * дзеркалиться, але вибір слота вирішує сервер), тому тут перевіряється
 * лише те, що гарантовано: чи слоти існують. Вичерпані слоти покаже
 * серверне повідомлення в action bar при спробі.
 */
@OnlyIn(Dist.CLIENT)
public final class GroundItemHintOverlay {

    private static final int PANEL_WIDTH = 220;
    private static final int PANEL_HEIGHT_NAME_ONLY = 24;
    private static final int PANEL_HEIGHT_WITH_ACTION = 36;
    private static final int ABOVE_CROSSHAIR_GAP = 46;
    private static final int LINE_GAP = 2;

    /** Колір другого рядка («ПКМ — взяти»). Світліший за тіло, темніший за назву. */
    private static final int ACTION_COLOR = 0xFFB8B8B8;

    private GroundItemHintOverlay() {}

    public static void render(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;

        HitResult hit = mc.hitResult;
        if (!(hit instanceof EntityHitResult entityHit)) return;
        if (!(entityHit.getEntity() instanceof GroundItemEntity item)) return;
        if (!item.hasStack()) return;

        boolean canPickUp = canOfferPickup(mc);
        Component name = item.displayName();
        Component action = Component.translatable("maniacmod.hud.ground_item.pickup_hint");

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int panelH = canPickUp ? PANEL_HEIGHT_WITH_ACTION : PANEL_HEIGHT_NAME_ONLY;
        int panelX = screenW / 2 - PANEL_WIDTH / 2;
        int panelY = screenH / 2 - ABOVE_CROSSHAIR_GAP - panelH / 2;

        Font font = mc.font;
        if (canPickUp) {
            drawTwoLines(graphics, font, name.getString(), action.getString(),
                panelX, panelY, PANEL_WIDTH, panelH);
        } else {
            ManiacUiTheme.drawHintBar(graphics, font, name.getString(),
                panelX, panelY, PANEL_WIDTH, panelH);
        }
    }

    /**
     * Чи можна чесно пообіцяти «ПКМ — взяти». Умови зібрані в одному
     * місці, щоб сервер і підказка не розходились: вони дзеркалять
     * перевірки {@code GroundItemEntity.interact}, які клієнт знає.
     */
    private static boolean canOfferPickup(Minecraft mc) {
        if (!ClientMatchState.allows(PhaseRule.ITEM_USE)) return false;
        if (mc.player.isShiftKeyDown()) return false;
        // Дзеркалить GroundItemEntity.interact(): лежачий/непритомний
        // виживий не підбирає — обіцяти «ПКМ — взяти» тут було б брехнею.
        if (ClientMatchState.isSurvivor() && ClientMatchState.survivorState().isCrawlOnly()) return false;
        return hasAnyAllowedSlot();
    }

    /**
     * Чи є в гравця хоч один слот хотбару, доступний за розподілом
     * слотів. Гравець без обмежень (креатив за політикою EXEMPT, лобі
     * до призначення ролі) → усі 9 слотів доступні.
     */
    private static boolean hasAnyAllowedSlot() {
        if (!InventorySlotAllocationPacket.ClientHandler.isRestricted()) return true;
        for (int slot = 0; slot < 9; slot++) {
            if (InventorySlotAllocationPacket.ClientHandler.isAllowed(slot)) return true;
        }
        return false;
    }

    /**
     * Панель у стилі {@link ManiacUiTheme#drawHintBar}, але з двома
     * рядками. Окремий метод теми не додаємо: це єдине місце, де
     * потрібні два рядки, а тема лишається чистою. Рамка й фон беруться
     * викликом {@code drawHintBar} з порожнім текстом — тоді малюється
     * лише панель, а текст ми кладемо самі.
     */
    private static void drawTwoLines(GuiGraphics g, Font font, String title, String action,
                                     int x, int y, int w, int h) {
        ManiacUiTheme.drawHintBar(g, font, "", x, y, w, h);

        int totalTextHeight = font.lineHeight * 2 + LINE_GAP;
        int top = y + (h - totalTextHeight) / 2;
        g.drawCenteredString(font, title, x + w / 2, top, 0xFFFFFFFF);
        g.drawCenteredString(font, action, x + w / 2, top + font.lineHeight + LINE_GAP, ACTION_COLOR);
    }
}
