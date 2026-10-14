package com.log_to_kot.maniacmod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Глушить ШТАТНІ іконки голосу Simple Voice Chat — значок мікрофона в HUD і ВСІ значки над головою
 * гравців (говорить / відключений / звук вимкнено / інша група). Над головою не лишається нічого;
 * замість штатного HUD-значка малюється власна панель ({@code client/voice/VoiceHudOverlay}).
 * Код самого SVC не змінюється: це міксин мода на його клас під час завантаження.
 *
 * ── Чому саме ці два методи ──────────────────────────────────────────
 * {@code renderIcon} і {@code renderPlayerIcon} у {@code RenderEvents} SVC лише малюють значок;
 * {@code onRenderName} — обробник нікнейма, єдина робота якого — вибрати й намалювати значок над головою
 * (глушимо і його, щоб значок не повернувся, якщо в іншій версії SVC перейменовано {@code renderPlayerIcon}).
 * Груповий HUD і все інше лишаються як є. Подій на кшталт {@code HudIconRenderEvent} у
 * SVC API для 1.20.1 нема (див. voice/README.md), тому через API це не зробити.
 *
 * ── Чому {@code @Pseudo} і {@code require = 0} ───────────────────────
 * SVC опційний: без нього клас-ціль відсутній, і міксин має мовчки не діяти. Так само, якщо в іншій
 * версії SVC методи названі інакше, міксин нічого не знайде й не покладе гру (тоді штатні іконки
 * просто залишаться поруч із нашими). Обробники без аргументів цілі — вони їм не потрібні, а
 * різниця сигнатур між версіями SVC не ламає навішування.
 */
@Pseudo
@Mixin(targets = "de.maxhenkel.voicechat.voice.client.RenderEvents", remap = false)
public abstract class MixinSvcRenderEventsIcons {

    @Inject(method = "renderIcon", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void maniacmod$noOwnHudIcon(CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "renderPlayerIcon", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void maniacmod$noHeadIcon(CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "onRenderName", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void maniacmod$noHeadIconHandler(CallbackInfo ci) {
        ci.cancel();
    }
}
