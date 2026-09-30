package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.survivors.SurvivorState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Чи має камера ВЛАСНОГО гравця бути заморожена просто зараз.
 *
 * ── Навіщо окремий клас ──────────────────────────────────────────────
 * Заморозку камери роблять ДВА міксини ({@code MixinMouseHandlerManiacStunned}
 * — на ванільному {@code MouseHandler.turnPlayer} і
 * {@code MixinEntityTurnManiacStunned} — на самій дії
 * {@code Entity.turn}), і питання «кого зараз не пускати» в них мусить
 * бути ОДНЕ. Раніше умова була скопійована в обидва міксини; щойно
 * додався третій стан (робота з генератором, ТЗ 12), копіювання стало б
 * трьома місцями, де забути один стан = камера, що крутиться там, де не
 * мала б. Тепер умова одна, а міксини лише питають.
 *
 * ── Що тут зведено ───────────────────────────────────────────────────
 *   • оглушення маньяка ударом лома (рух, удар і огляд — «нічого не може»);
 *   • удар струмом від електричного дроту (ТЗ 5: «не може обертати камеру»);
 *   • підвіс на мотузці ({@code RopeHangClient}): камера заблокована, доки висить;
 *   • робота з генератором — ремонт або залив (ТЗ 12: «він не може
 *     обертати камеру», поки тримає кнопку);
 *   • падіння виживого ({@code CRAWLING}): лежить, доки не встане
 *     пробілом, і крутити камерою не може;
 *   • анімація пробудження після розкидання ({@link WakeUpAnimation}):
 *     камеру веде сама анімація, мишка з нею б боролась.
 *
 * ── Чому саме CRAWLING, а не весь {@code isCrawlOnly()} ─────────────
 * {@code UNCONSCIOUS} (0 хп) теж лежить, але він ПОВЗЕ: напрямок повзання
 * задає погляд, тож без камери він не зміг би обрати, куди повзти.
 * {@code CRAWLING} після падіння — навпаки, нерухомий (рух заморожений
 * {@code MixinKeyboardInputDownedMovement} і серверним локом), і йому
 * лишається лише тиснути пробіл. Пробіл читається напряму через
 * {@code keyJump.isDown()} ({@code ManiacKeybinds.isStandUpDown}), тож
 * заборона повороту на вставання не впливає.
 *
 * Капкан сюди НЕ входить свідомо: затиснутий у капкані не може зрушити з
 * місця, але роздивлятись навколо (і кликати ПКМ по капкану) йому ніхто
 * не забороняє — див. докстрінг {@code MixinKeyboardInputTrapped}.
 *
 * ── Джерела ──────────────────────────────────────────────────────────
 * Перші два — серверний стан, віддзеркалений пакетами. Третє — ЛОКАЛЬНЕ
 * утримання Shift+ПКМ: воно за визначенням стосується лише власного
 * гравця, а міксини нижче чіпають теж лише його, тож плутанини між
 * «моїм» і «чужим» тут виникнути не може.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientLookLock {

    private ClientLookLock() {}

    public static boolean isLocked() {
        return ClientMatchState.isManiacStunned()
            || ClientMatchState.isWireShocked()
            || com.log_to_kot.maniacmod.client.rope.RopeHangClient.isLocalHung()
            || ClientInputHandler.isRepairHeld()
            || isFallenSurvivor()
            || WakeUpAnimation.isPlaying()
            || com.log_to_kot.maniacmod.client.intro.IntroClient.isBlockingInput();
    }

    /**
     * Виживий, що впав і лежить нерухомо ({@code CRAWLING}). Перевірка
     * ролі обов'язкова: {@code survivorState} у {@link ClientMatchState}
     * скидається лише в {@code reset()}, а маньяк/глядач мають
     * {@code HEALTHY} за замовчуванням — але явна перевірка не лишає
     * місця для випадкового спрацювання на чужій ролі (той самий
     * захист, що в {@code MixinKeyboardInputDownedMovement}).
     */
    private static boolean isFallenSurvivor() {
        return ClientMatchState.isSurvivor()
            && ClientMatchState.survivorState() == SurvivorState.CRAWLING;
    }
}
