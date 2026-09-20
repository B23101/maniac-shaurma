package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.items.FuelCanisterItem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.IItemDecorator;

/**
 * Малює на іконці каністри число — ЗАЛИШОК ЗАРЯДУ у відсотках ("73%") —
 * у правому нижньому куті слота, там само, де ванілька показує
 * кількість предметів у стеку.
 *
 * ── Чому саме IItemDecorator ─────────────────────────────────────────
 * Каністра має {@code stacksTo(1)}, тому ванільне число кількості для
 * неї не малюється зовсім — місце вільне. {@link IItemDecorator}
 * (реєструється через {@code RegisterItemDecorationsEvent}) — офіційний
 * Forge-механізм для такого "лічильника поверх іконки": його викликає
 * сам {@code GuiGraphics.renderItemDecorations}, тобто він спрацьовує
 * скрізь, де малюється іконка предмета, — у ванільних слотах інвентаря
 * і в кастомному хотбарі мода ({@code ManiacHotbarOverlay.renderItem}
 * теж викликає {@code renderItemDecorations}, у масштабованій системі
 * координат, яку цей клас не має знати).
 *
 * ── Колір ────────────────────────────────────────────────────────────
 * Палітра {@link ManiacUiTheme}, та сама, що в прогрес-барах: нормально
 * (нейтральний) → мало (попереджувальний) → майже порожньо (небезпека).
 * Пороги: понад 50% — нейтральний, 20-50% — попередження, менше 20% —
 * небезпека. Порожня каністра (0%) теж червона, щоб її було видно в руці.
 *
 * ── Z-позиція ────────────────────────────────────────────────────────
 * Ванільна кількість малюється на z+200 поверх іконки. Число заряду
 * піднімається на те саме зміщення, інакше воно опинилось би під
 * 3D-моделлю каністри (вона рендериться через GeckoLib
 * {@code GeoItemRenderer} у власному шарі).
 */
@OnlyIn(Dist.CLIENT)
public final class FuelCanisterChargeDecorator implements IItemDecorator {

    /** Верхня межа "нормального" заряду — вище неї колір нейтральний. */
    private static final int WARN_ABOVE_PERCENT = 50;

    /** Нижче цього — колір небезпеки. */
    private static final int DANGER_BELOW_PERCENT = 20;

    /** Той самий z-зсув, що ванільне число кількості. */
    private static final float TEXT_Z = 200f;

    /**
     * Масштаб тексту. "100%" — 4 символи (≈ 24 пікселі при масштабі 1),
     * а іконка 16 пікселів, тож без зменшення число вилізло б за
     * лівий край іконки.
     */
    private static final float TEXT_SCALE = 0.65f;

    @Override
    public boolean render(GuiGraphics graphics, Font font, ItemStack stack, int xOffset, int yOffset) {
        // Декоратор зареєстрований на предмет, тому стек завжди каністра,
        // але перевірка дешева й захищає від зміни реєстрації в майбутньому.
        if (!(stack.getItem() instanceof FuelCanisterItem)) return false;

        int charge = FuelCanisterItem.getCharge(stack);
        String text = charge + "%";

        int textWidth = font.width(text);

        // Правий нижній кут іконки 16x16 (як ванільна кількість): текст
        // вирівняний вправо, з відступом 1 піксель від краю. Масштаб
        // застосовується навколо цієї точки, тож координати задаємо в
        // "пікселях іконки", а не після зменшення.
        float right = xOffset + 16f;
        float bottom = yOffset + 16f;

        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(right, bottom, TEXT_Z);
        pose.scale(TEXT_SCALE, TEXT_SCALE, 1f);
        // Після translate/scale початок координат — у правому нижньому
        // куті іконки; зсуваємо текст вліво на його ширину й вгору на
        // висоту рядка, щоб він лежав У куті, а не за ним.
        graphics.drawString(font, text, -textWidth - 1, -font.lineHeight, colorFor(charge), true);
        pose.popPose();

        // Ми змінювали лише матрицю (повернули popPose) і не чіпали
        // GL-стан напряму — скидати стан для інших декораторів не треба.
        return false;
    }

    private static int colorFor(int charge) {
        if (charge < DANGER_BELOW_PERCENT) return ManiacUiTheme.BAR_FILL_DANGER;
        if (charge <= WARN_ABOVE_PERCENT) return ManiacUiTheme.BAR_FILL_WARN;
        return ManiacUiTheme.BAR_FILL_NEUTRAL;
    }
}
