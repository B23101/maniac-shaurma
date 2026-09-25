package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Клієнтська частина «оглушений маньяк»: поворот камери — ДРУГИЙ,
 * незалежний шар (перший — {@link MixinMouseHandlerManiacStunned}).
 *
 * ── Навіщо другий шар ────────────────────────────────────────────────
 * {@link MixinMouseHandlerManiacStunned} скасовує ванільний
 * {@code MouseHandler.turnPlayer()} — але жоден міксин не вічний:
 * мінімальна зміна назви методу чи порядку викликів у новій версії
 * гри лишає гравця з камерою, що крутиться, під час оглушення. Другий
 * шар стоїть на самій ДІЇ, а не на обробнику вводу:
 * {@code Entity.turn(yRot, xRot)} — єдина точка, де поворот гравця
 * взагалі записується (її викликає і миша, і будь-який інший код у
 * майбутньому).
 *
 * ── Чому {@code Entity}, а не {@code LocalPlayer} ────────────────────
 * Метод оголошений саме в {@code Entity}, і Mixin, якому вказати
 * успадкований метод, застосовує інжект до КЛАСУ-оголошення — тобто до
 * {@code Entity}, а не до {@code LocalPlayer}. Це нормально й навіть
 * краще: міксин у явному вигляді чіпає рівно те, що має — тому нижче
 * стоїть перевірка «це мій власний гравець». Без неї ми б блокували
 * поворот УСІМ сутностям у світі (мобів крутить сервер, і клієнтський
 * код їхнього повороту не чіпає, але покладатись на «і так не чіпає»
 * тут безглуздо).
 *
 * ⚠ Зручність, а не захист — та сама межа, що в решті клієнтських
 * шарів стану: справжній захист від «маньяк під час оглушення» — це
 * серверні локи {@code ManiacStunModule} (рух і удар). Поворот камери
 * сервер перевірити не може в принципі (це не мережевий стан у
 * класичному розумінні), тому тут свідомо лише клієнт.
 */
@Mixin(Entity.class)
public abstract class MixinEntityTurnManiacStunned {

    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void maniacmod$blockOwnTurnWhenStunned(double yRot, double xRot, CallbackInfo ci) {
        if (!ClientMatchState.isManiacStunned()) return;
        // Лише СВІЙ гравець: у чужих сутностей поворот приходить із
        // сервера, і глушити його означало б розсинхронізувати модель.
        if ((Object) this != Minecraft.getInstance().player) return;
        ci.cancel();
    }
}
