package com.log_to_kot.maniacmod.client.overlay.hint;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.ManiacKeybinds;
import com.log_to_kot.maniacmod.client.overlay.actionprogress.GeneratorProgressOverlay;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.entity.GeneratorEntity;
import com.log_to_kot.maniacmod.items.FuelCanisterItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Підказка "як почати" ремонт/залив генератора — маленька панель-хінт
 * по центру екрана, ЩО НАД ПРИЦІЛОМ, доки гравець просто ДИВИТЬСЯ на
 * генератор (ще не тримає утримання).
 *
 * ── Навіщо окремо від GeneratorProgressOverlay ─────────────────────────
 * Раніше текст "Утримуй Shift+ПКМ, щоб ремонтувати генератор" малювався
 * як ЗАГОЛОВОК прогрес-панелі, яка сама з'являється лише ПІСЛЯ того, як
 * утримання вже почалось — тобто підказка "як почати дію" показувалась
 * у момент, коли дія вже триває, а до початку утримання не показувалось
 * НІЧОГО. Цей клас закриває саме той проміжок: рахує щотік той самий
 * рейкаст, що {@code ClientInputHandler.handleGeneratorRepair}, і поки
 * ({@code hitResult} — генератор) І (утримання ще НЕ триває), малює
 * маленьку панель-хінт того самого стилю {@link GeneratorFuelUiTheme}, що й
 * прогрес-панель ремонту (текст-рядок + заглиблений бар, поки що порожній —
 * прогресу ще нема). Щойно утримання почалось —
 * {@link GeneratorProgressOverlay#isVisible()} стає {@code true}, і цей
 * клас перестає малювати: дві підказки одна над одною ніколи не видно.
 *
 * ── Дві стадії, різний текст ────────────────────────────────────────────
 * REPAIR — підказка завжди однакова ("Утримуй Shift+ПКМ, щоб
 * ремонтувати"). FUEL — потрібна каністра {@link FuelCanisterItem} в
 * одній із рук, і вона має бути ЗАРЯДЖЕНА (див.
 * {@link FuelCanisterItem#isEmpty}): якщо каністри немає, текст
 * замінюється на "Потрібна каністра з бензином", а якщо є лише порожня —
 * на "Каністра порожня" замість заклику тримати кнопку. Гравцю відразу
 * видно, ЧОГО йому бракує, а не просто "нічого не відбувається" при
 * спробі заливу голими руками чи порожньою каністрою. Стадію беремо напряму з
 * {@link GeneratorEntity#isFuelStage()} — той самий синхронізований
 * прапор, що {@code ACTIVE}, а не вгадуємо її локально.
 *
 * ── Чому суто клієнтський клас, без пакетів ────────────────────────────
 * Увесь потрібний стан (рейкаст, чи гравець виживий, чи фаза дозволяє
 * ремонт, стадія генератора через {@link GeneratorEntity#isFuelStage()}/
 * {@link GeneratorEntity#isActive()}, і зміст руки гравця) уже
 * доступний на клієнті без сервера — так само, як ванільна підсвітка
 * блоку під прицілом рахується локально.
 */
@OnlyIn(Dist.CLIENT)
public final class GeneratorHintOverlay {

    // БАГФІКС: та сама уніфікація масштабу, що GeneratorProgressOverlay —
    // панель була вдвічі більшою за решту HUD мода.
    private static final int PANEL_WIDTH = 250;
    private static final int PADDING = 10;
    private static final int TEXT_ROW_HEIGHT = 10;
    private static final int GAP_BEFORE_BAR = 8;
    private static final int BAR_HEIGHT = 8;
    private static final int PANEL_HEIGHT = PADDING * 2 + TEXT_ROW_HEIGHT + GAP_BEFORE_BAR + BAR_HEIGHT;
    private static final int ABOVE_CROSSHAIR_GAP = 46;

    private GeneratorHintOverlay() {}

    public static void render(GuiGraphics graphics) {
        if (!ClientMatchState.isSurvivor()) return;
        if (!ClientMatchState.allows(PhaseRule.GENERATOR_REPAIR)) return;

        // Утримання вже триває — прогрес-панель сама показує статус,
        // друга підказка тут була б зайвою.
        if (GeneratorProgressOverlay.isVisible()) return;

        // Утримання почато, але ще не долетів перший пакет з сервера
        // (рідкісний проміжок в один-два тіки) — теж не дублюємо хінт.
        if (ManiacKeybinds.isRepairHeld()) return;

        Minecraft mc = Minecraft.getInstance();
        HitResult hit = mc.hitResult;
        if (!(hit instanceof EntityHitResult entityHit)) return;
        if (!(entityHit.getEntity() instanceof GeneratorEntity generator)) return;
        if (generator.isActive()) return; // DONE — саботажний ПКМ маньяка інший потік, тут виживому нічого тримати

        boolean fuelStage = generator.isFuelStage();
        Component text = fuelStage
            ? fuelHintText(mc)
            : Component.translatable("maniacmod.hud.generator.hold_hint");

        // БАГФІКС: бар підказки раніше завжди малювався порожнім (0%),
        // навіть коли генератор — особливо стадія FUEL — уже частково
        // залитий (кимось іншим чи цим же гравцем до того, як відпустив
        // ПКМ). Тепер беремо реальний накопичений прогрес поточної стадії
        // напряму із сутності (синхронізований GeneratorEntity.stagePercent(),
        // те саме значення, що бачить прогрес-панель під час утримання).
        float fraction = generator.stagePercent() / 100f;

        drawHint(graphics, mc.font, text.getString(), fraction);
    }

    /**
     * Текст підказки для стадії FUEL. Та сама логіка вибору руки, що на
     * сервері ({@code GeneratorModule.canisterInHand}): заряджена
     * каністра в БУДЬ-ЯКІЙ руці має перевагу над порожньою, інакше
     * підказка б казала "порожня", коли сервер уже заливає з іншої руки.
     *
     * Три випадки: є заряджена — заклик тримати кнопку; є лише порожня —
     * "каністра порожня"; жодної — "потрібна каністра".
     */
    private static Component fuelHintText(Minecraft mc) {
        ItemStack main = mc.player.getItemInHand(InteractionHand.MAIN_HAND);
        ItemStack off = mc.player.getItemInHand(InteractionHand.OFF_HAND);

        boolean mainIsCanister = main.getItem() instanceof FuelCanisterItem;
        boolean offIsCanister = off.getItem() instanceof FuelCanisterItem;

        boolean hasCharged = (mainIsCanister && !FuelCanisterItem.isEmpty(main))
            || (offIsCanister && !FuelCanisterItem.isEmpty(off));
        if (hasCharged) {
            return Component.translatable("maniacmod.hud.generator.fuel_hint");
        }
        if (mainIsCanister || offIsCanister) {
            return Component.translatable("maniacmod.hud.generator.fuel_hint_empty_canister");
        }
        return Component.translatable("maniacmod.hud.generator.fuel_hint_no_canister");
    }

    private static void drawHint(GuiGraphics graphics, Font font, String text, float fraction) {
        Minecraft mc = Minecraft.getInstance();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        int panelX = screenW / 2 - PANEL_WIDTH / 2;
        int panelY = screenH / 2 - ABOVE_CROSSHAIR_GAP - PANEL_HEIGHT / 2;

        GeneratorFuelUiTheme.drawPanel(graphics, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT);

        int textX = panelX + PADDING;
        int textY = panelY + PADDING;
        GeneratorFuelUiTheme.drawTitleLeft(graphics, font, text, textX, textY);

        int barY = textY + TEXT_ROW_HEIGHT + GAP_BEFORE_BAR;
        int barX = panelX + PADDING;
        int barWidth = PANEL_WIDTH - PADDING * 2;
        // БАГФІКС: раніше тут завжди був жорсткий 0f — бар підказки не
        // показував реальний прогрес заливу/ремонту. Тепер малює те, що
        // фактично вже накопичено на генераторі (той самий заглиблений
        // трек, що на прогрес-панелі під час утримання).
        GeneratorFuelUiTheme.drawBar(graphics, barX, barY, barWidth, BAR_HEIGHT, fraction);
    }
}
