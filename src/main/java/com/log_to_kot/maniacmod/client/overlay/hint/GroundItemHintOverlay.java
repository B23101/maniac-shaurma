package com.log_to_kot.maniacmod.client.overlay.hint;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.entity.GroundItemEntity;
import com.mojang.blaze3d.vertex.PoseStack;
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
 *
 * <h3>Без фону, збільшений текст</h3>
 * На відміну від панельних підказок генератора, ця підказка навмисно
 * БЕЗ панелі: жодного фону чи рамки {@link ManiacUiTheme#drawHintBar}
 * тепер немає — лише текст із тінню (як ванільна назва предмета над
 * хотбаром), піднятий приблизно вдвічі ({@link #TEXT_SCALE}) через
 * {@code pose().scale}, щоб читатись здалеку так само легко, як досі
 * читалась ціла панель. Фон на предметі, що лежить просто в траві чи на
 * підлозі, лише перекривав огляд — сама сутність унизу вже достатній
 * візуальний якір для тексту над нею.
 */
@OnlyIn(Dist.CLIENT)
public final class GroundItemHintOverlay {

    private static final int ABOVE_CROSSHAIR_GAP = 46;
    private static final int LINE_GAP = 3;

    /** Масштаб тексту підказки відносно звичайного шрифту — приблизно вдвічі більший. */
    private static final float TEXT_SCALE = 1.6f;

    /** Колір назви предмета (перший, більший рядок). */
    private static final int TITLE_COLOR = 0xFFFFFFFF;

    /** Колір другого рядка («ПКМ — взяти»). Світліший за тіло, темніший за назву. */
    private static final int ACTION_COLOR = 0xFFD0D0D0;

    private GroundItemHintOverlay() {}

    public static void render(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;

        HitResult hit = mc.hitResult;
        if (!(hit instanceof EntityHitResult entityHit)) return;
        if (!(entityHit.getEntity() instanceof GroundItemEntity item)) return;
        if (!item.hasStack()) return;

        boolean canPickUp = canOfferPickup(mc);
        String name = item.displayName().getString();
        String action = Component.translatable("maniacmod.hud.ground_item.pickup_hint").getString();

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int centerX = screenW / 2;
        int anchorY = screenH / 2 - ABOVE_CROSSHAIR_GAP;

        Font font = mc.font;
        if (canPickUp) {
            drawScaledCentered(graphics, font, name, centerX, anchorY - (font.lineHeight + LINE_GAP), TITLE_COLOR);
            drawScaledCentered(graphics, font, action, centerX, anchorY, ACTION_COLOR);
        } else {
            drawScaledCentered(graphics, font, name, centerX, anchorY, TITLE_COLOR);
        }
    }

    /**
     * Один рядок тексту з тінню, центрований по X, збільшений
     * {@link #TEXT_SCALE} навколо своєї власної базової лінії — сусідній
     * рядок (заданий окремим {@code baselineY}) масштаб не зсуває.
     */
    private static void drawScaledCentered(GuiGraphics g, Font font, String text,
                                            int centerX, int baselineY, int color) {
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(centerX, baselineY, 0);
        pose.scale(TEXT_SCALE, TEXT_SCALE, 1f);
        int width = font.width(text);
        g.drawString(font, text, -width / 2, -font.lineHeight / 2, color, true);
        pose.popPose();
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
}
