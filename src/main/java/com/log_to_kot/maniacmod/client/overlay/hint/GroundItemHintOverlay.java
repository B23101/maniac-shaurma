package com.log_to_kot.maniacmod.client.overlay.hint;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
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
 * <h3>Коли НЕ показуємо «ПКМ — взяти» (лише назву або «підійдіть ближче»)</h3>
 * <ul>
 *   <li>Гравець ЗАДАЛЕКО за реальним серверним лімітом підбору
 *       ({@code ConfigSchema.GROUND_ITEM_PICKUP_RANGE}, донесеним
 *       {@code GroundItemVisualSettingsPacket}) — БАГФІКС: ванільний
 *       рейкаст ловить сутність суттєво далі (~3 блоки), ніж сервер
 *       готовий підтвердити підбір (типово 2 блоки); без цієї
 *       перевірки гравець бачив підказку в тому проміжку й отримував
 *       мовчазну відмову сервера. Тепер у цьому проміжку показується
 *       «підійдіть ближче» замість обіцянки дії.</li>
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

    // ── Анімаційний стан ─────────────────────────────────────────────────
    // appear      : плавна поява тексту (alpha + підйом), 0 → 1.
    // lastTargetId: id сутності, для якої текст уже прозвучав — звук лише
    //               коли погляд перескочив на НОВИЙ предмет, а не кожен кадр.
    private static final long[] lastFrameNanos = new long[1];
    private static float appear = 0f;
    private static boolean wasVisible = false;
    private static int lastTargetId = -1;

    public static void render(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        HitResult hit = mc.player == null ? null : mc.hitResult;
        GroundItemEntity item = (mc.screen == null
            && hit instanceof EntityHitResult entityHit
            && entityHit.getEntity() instanceof GroundItemEntity g
            && g.hasStack()) ? g : null;

        if (item == null) {
            if (wasVisible) {
                wasVisible = false;
            }
            appear = 0f;
            lastFrameNanos[0] = 0L;
            lastTargetId = -1;
            return;
        }

        int targetId = item.getId();
        if (targetId != lastTargetId) {
            lastTargetId = targetId;
        }
        if (!wasVisible) wasVisible = true;

        float dt = UiAnim.frameDt(lastFrameNanos);
        appear = UiAnim.approach(appear, 1f, 13f, dt);
        float ease = UiAnim.easeOutCubic(UiAnim.clamp01(appear));

        boolean inRange = mc.player.distanceTo(item) <= ClientMatchState.groundItemPickupRangeBlocks();
        boolean canPickUp = inRange && canOfferPickup(mc);
        String name = item.displayName().getString();
        String action = Component.translatable("maniacmod.hud.ground_item.pickup_hint").getString();
        String tooFar = Component.translatable("maniacmod.hud.ground_item.too_far_hint").getString();

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int centerX = screenW / 2;
        // Підйом: непрозорий текст «спливає» на місце, а не виникає раптово.
        int anchorY = screenH / 2 - ABOVE_CROSSHAIR_GAP + Math.round((1f - ease) * 6f);

        Font font = mc.font;
        if (canPickUp) {
            drawScaledCentered(graphics, font, name, centerX, anchorY - (font.lineHeight + LINE_GAP), TITLE_COLOR, ease);
            drawScaledCentered(graphics, font, action, centerX, anchorY, ACTION_COLOR, ease);
        } else if (!inRange) {
            // БАГФІКС: раніше тут показувалась сама назва без пояснення —
            // ванільний рейкаст ловить сутність аж до ~3 блоків, а сервер
            // (GroundItemEntity.interact) відмовляв уже на 2 блоках
            // (ConfigSchema.GROUND_ITEM_PICKUP_RANGE). Гравець бачив назву
            // і тиснув ПКМ у проміжку між цими дистанціями — і отримував
            // мовчазну відмову сервера без жодного пояснення. Тепер
            // клієнт знає той самий ліміт (див. GroundItemVisualSettingsPacket)
            // і чесно каже «підійдіть ближче», а не обіцяє дію, якої
            // сервер за мить не підтвердить.
            drawScaledCentered(graphics, font, name, centerX, anchorY - (font.lineHeight + LINE_GAP), TITLE_COLOR, ease);
            drawScaledCentered(graphics, font, tooFar, centerX, anchorY, ACTION_COLOR, ease);
        } else {
            drawScaledCentered(graphics, font, name, centerX, anchorY, TITLE_COLOR, ease);
        }
    }

    /**
     * Один рядок тексту з тінню, центрований по X, збільшений
     * {@link #TEXT_SCALE} навколо своєї власної базової лінії — сусідній
     * рядок (заданий окремим {@code baselineY}) масштаб не зсуває.
     */
    private static void drawScaledCentered(GuiGraphics g, Font font, String text,
                                            int centerX, int baselineY, int color, float alpha) {
        // Прозорість самої появи: текст проявляється, а не спалахує.
        // Колір задано як 0xFFRRGGBB, тож старший байт замінюємо на
        // поточну альфу — RGB лишається тим самим.
        int alphaByte = Math.round(UiAnim.clamp01(alpha) * 255f);
        int shaded = (alphaByte << 24) | (color & 0x00FFFFFF);

        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(centerX, baselineY, 0);
        pose.scale(TEXT_SCALE, TEXT_SCALE, 1f);
        int width = font.width(text);
        g.drawString(font, text, -width / 2, -font.lineHeight / 2, shaded, true);
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
