package com.log_to_kot.maniacmod.client.overlay.hint;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.ManiacKeybinds;
import com.log_to_kot.maniacmod.client.overlay.actionprogress.GeneratorProgressOverlay;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
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
    private static final int MIN_PANEL_WIDTH = 250;
    private static final int PADDING = 10;
    private static final int TEXT_ROW_HEIGHT = 10;
    private static final int GAP_BEFORE_BAR = 8;
    private static final int BAR_HEIGHT = 8;
    private static final int PANEL_HEIGHT = PADDING * 2 + TEXT_ROW_HEIGHT + GAP_BEFORE_BAR + BAR_HEIGHT;
    private static final int ABOVE_CROSSHAIR_GAP = 46;

    private GeneratorHintOverlay() {}

    // ── Анімаційний стан ─────────────────────────────────────────────────
    // appear: плавна поява панелі (підйом + масштаб), 0 → 1.
    private static final long[] lastFrameNanos = new long[1];
    private static float appear = 0f;
    private static boolean wasVisible = false;

    public static void render(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        HitResult hit = mc.hitResult;
        GeneratorEntity generator = (hit instanceof EntityHitResult entityHit
            && entityHit.getEntity() instanceof GeneratorEntity g) ? g : null;

        // Усі умови показу зібрані в одному місці — так само, як раніше
        // окремі ранні return, лише тепер дозволяють розрізнити «показати»
        // від «сховати» й анімувати появу замість миттєвого стрибка.
        boolean visible = ClientMatchState.isSurvivor()
            && ClientMatchState.allows(PhaseRule.GENERATOR_REPAIR)
            // Утримання вже триває — прогрес-панель сама показує статус,
            // друга підказка тут була б зайвою.
            && !GeneratorProgressOverlay.isVisible()
            // Утримання почато, але ще не долетів перший пакет з сервера
            // (рідкісний проміжок в один-два тіки) — теж не дублюємо хінт.
            && !ManiacKeybinds.isRepairHeld()
            && generator != null
            // БАГФІКС: mc.player буває null у короткому вікні (спавн/
            // деспавн, вихід зі світу, спектатор без сутності) — без
            // цієї перевірки fuelHintText(mc) нижче падав з NPE на
            // mc.player.getItemInHand(...), і трест вилазив прямо на
            // екран у момент, коли приціл встиг зловити генератор
            // раніше, ніж клієнтський гравець "живий".
            && mc.player != null
            // DONE — саботажний ПКМ маньяка інший потік, тут виживому нічого тримати
            && !generator.isActive();

        if (!visible) {
            if (wasVisible) {
                wasVisible = false;
            }
            appear = 0f;
            lastFrameNanos[0] = 0L;
            return;
        }
        if (!wasVisible) {
            wasVisible = true;
        }

        float dt = UiAnim.frameDt(lastFrameNanos);
        appear = UiAnim.approach(appear, 1f, 11f, dt);
        float ease = UiAnim.easeOutCubic(UiAnim.clamp01(appear));

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

        drawHint(graphics, mc.font, text.getString(), fraction, ease);
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

    private static void drawHint(GuiGraphics graphics, Font font, String text, float fraction, float ease) {
        Minecraft mc = Minecraft.getInstance();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        // БАГФІКС: ширина панелі раніше була жорсткою константою
        // (250px), а текст підказки береться з перекладу і може бути
        // довшим за неї (напр. український "Утримуй Shift+ПКМ, щоб
        // ремонтувати генератор") — рядок вилазив за праву межу панелі
        // замість того, щоб панель підлаштувалась під нього. Тепер
        // ширина рахується від РЕАЛЬНОЇ ширини тексту (font.width),
        // з мінімумом MIN_PANEL_WIDTH — короткі підказки й далі
        // виглядають так само, як раніше.
        int textWidth = font.width(text);
        int panelWidth = Math.max(MIN_PANEL_WIDTH, textWidth + PADDING * 2);

        int panelX = screenW / 2 - panelWidth / 2;
        int panelY = screenH / 2 - ABOVE_CROSSHAIR_GAP - PANEL_HEIGHT / 2;

        // Плавна поява: підйом знизу + легкий масштаб навколо центру.
        // Малюємо в зсунутій системі координат, тож усі внутрішні
        // обчислення (panelX/panelY) лишаються незмінними.
        graphics.pose().pushPose();
        float cx = panelX + panelWidth / 2f;
        float cy = panelY + PANEL_HEIGHT / 2f;
        graphics.pose().translate(cx, cy + (1f - ease) * 10f, 0f);
        float scale = 0.94f + 0.06f * ease;
        graphics.pose().scale(scale, scale, 1f);
        graphics.pose().translate(-cx, -cy, 0f);

        GeneratorFuelUiTheme.drawPanel(graphics, panelX, panelY, panelWidth, PANEL_HEIGHT);

        int textX = panelX + PADDING;
        int textY = panelY + PADDING;
        GeneratorFuelUiTheme.drawTitleLeft(graphics, font, text, textX, textY);

        int barY = textY + TEXT_ROW_HEIGHT + GAP_BEFORE_BAR;
        int barX = panelX + PADDING;
        int barWidth = panelWidth - PADDING * 2;
        // БАГФІКС: раніше тут завжди був жорсткий 0f — бар підказки не
        // показував реальний прогрес заливу/ремонту. Тепер малює те, що
        // фактично вже накопичено на генераторі (той самий заглиблений
        // трек, що на прогрес-панелі під час утримання).
        GeneratorFuelUiTheme.drawBar(graphics, barX, barY, barWidth, BAR_HEIGHT, fraction);

        graphics.pose().popPose();
    }
}
