package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Лежачий виживий: не дає РЕНДЕРЕРУ покласти модель, бо це вже робить
 * повнотілова PAL-анімація {@code downed_lie} ({@code DownedPoseAnimation}).
 *
 * ── Що було не так ───────────────────────────────────────────────────
 * Хітбокс лежачого тримає {@code MixinPlayerDownedPose}: він ставить
 * {@code Pose.SWIMMING}. Але ця ж поза ВІЗУАЛЬНО змушує
 * {@code PlayerRenderer.setupRotations} покласти всю модель на живіт
 * (поворот на -90° по X і зсув униз-вперед). А {@code downed_lie.json}
 * малювався для ПРЯМОСТОЯЧОГО гравця: там {@code body} сам повернутий на
 * -87.86° і опущений на 10 пікселів. Обидва повороти накладались —
 * тіло переверталось «зайвий раз» і лежачої пози не було видно.
 *
 * ── Що робить цей міксин ─────────────────────────────────────────────
 * Для лежачого повертає {@code getSwimAmount == 0}: рендерер вважає, що
 * гравець не пливе, модель лишається вертикальною, а лягає вона рівно
 * так, як задумав художник у Blockbench. ХІТБОКС не змінюється — це
 * лише рендер, {@code Pose.SWIMMING} лишається.
 *
 * ── ⚠ Перемикач ──────────────────────────────────────────────────────
 * {@link #CANCEL_SWIM_LAYING}: якщо в грі лежачий виглядає «утоплений у
 * землю» чи лежить догори ногами — постав {@code false}, тоді анімація
 * малюватиметься поверх вже покладеної моделі (треба буде переробити
 * сам JSON під горизонтальну модель). Я не міг запустити гру, тому це
 * єдине місце, яке потрібно перевірити очима.
 *
 * ── require = 0 ──────────────────────────────────────────────────────
 * Якщо мапінг {@code setupRotations}/{@code getSwimAmount} розійдеться,
 * інжект мовчки не застосується (буде стара поведінка), а не впаде гра.
 */
@Mixin(PlayerRenderer.class)
public abstract class MixinPlayerRendererDownedUpright {

    /** false — повернути ванільне «плавальне» кладення моделі. */
    private static final boolean CANCEL_SWIM_LAYING = true;

    @Redirect(method = "setupRotations",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/player/AbstractClientPlayer;getSwimAmount(F)F"),
              require = 0)
    private float maniacmod$noSwimLayingWhenDowned(AbstractClientPlayer player, float partialTick) {
        if (CANCEL_SWIM_LAYING && ClientMatchState.isFallen(player.getUUID())) return 0.0f;
        return player.getSwimAmount(partialTick);
    }
}
