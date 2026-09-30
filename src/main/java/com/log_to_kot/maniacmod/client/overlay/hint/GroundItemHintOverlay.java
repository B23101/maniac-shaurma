package com.log_to_kot.maniacmod.client.overlay.hint;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.entity.GroundItemEntity;
import com.log_to_kot.maniacmod.loot.GroundItemAim;
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
        // Ціль — по ПРОМЕНЮ погляду (GroundItemAim), а не по ванільному прицілу:
        // трава перед предметом перекривала приціл, і підказка зникала, хоча
        // взяти предмет можна. Інша сутність під прицілом (лежачий союзник,
        // генератор) має пріоритет — тоді підказки про предмет немає.
        GroundItemEntity item = null;
        if (mc.player != null && mc.screen == null) {
            HitResult hit = mc.hitResult;
            boolean otherEntity = hit instanceof EntityHitResult eh
                && !(eh.getEntity() instanceof GroundItemEntity);
            if (!otherEntity) {
                item = GroundItemAim.find(mc.player, GroundItemAim.CLIENT_MARGIN);
            }
        }

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
        String second = canPickUp ? action : (!inRange ? tooFar : null);
        int textW = Math.max(font.width(name), second == null ? 0 : font.width(second));
        int w = Math.max(160, textW + 24);
        int h = second == null ? 26 : 38;
        int x = centerX - w / 2;
        int y = anchorY - h / 2 - 10;

        GeneratorFuelUiTheme.beginAppear(graphics, centerX, y + h / 2f, ease, 8f);
        GeneratorFuelUiTheme.drawPanel(graphics, x, y, w, h);
        graphics.drawString(font, name, centerX - font.width(name) / 2, y + 8, GeneratorFuelUiTheme.TEXT, false);
        if (second != null) {
            GeneratorFuelUiTheme.divider(graphics, x, y + 19, w);
            int c = canPickUp ? GeneratorFuelUiTheme.HIGHLIGHT : GeneratorFuelUiTheme.HIT_ZONE;
            graphics.drawString(font, second, centerX - font.width(second) / 2, y + 24, c, false);
        }
        GeneratorFuelUiTheme.endAppear(graphics);
    }

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
