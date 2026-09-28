package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.items.FlashlightItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Клієнтське життя ліхтарика — той самий принцип, що
 * {@link GeneratorFuelClientSoundHandler}: щотік перевіряє ЛОКАЛЬНИЙ
 * критерій "чи в руці зараз {@link FlashlightItem}" і сам
 * вмикає/вимикає незалежні наслідки, доки критерій true. Жодного
 * стану на сервері — предмет суто косметичний (див.
 * {@link FlashlightItem} клас-докстрінг).
 *
 * ── Наслідки, поки ліхтарик у руці ─────────────────────────────────────
 * <p>
 * Поза руки БІЛЬШЕ не тут: третя особа —
 * {@code MixinFlashlightHoldUpThirdPerson} (пряма правка
 * {@code PlayerModel.setupAnim}, 1:1 з NotEnoughAnimations, без
 * PAL/shaurma-lib playerlib-шару), перша особа — окремий
 * {@code MixinHoldFlashlightPose} на {@code ItemInHandRenderer}. Обидва
 * міксі самі перевіряють {@code FlashlightItem.isFlashlight(...)}
 * щокадру — жодної координації з цим класом не потрібно.
 * <ol>
 *   <li><b>Скасування темної зони.</b> {@code DarkZoneEffect} з
 *       shaurma-lib керується за КЛЮЧЕМ джерела ({@code "maniac_darkzone"}
 *       — той самий, яким пише {@code ClientPacketHandler.onDarkZoneState}).
 *       Замість другого, незалежного ключа (який довелось би якось
 *       комбінувати з першим — контракт суми кількох активних ключів
 *       не видно з боку мода) хендлер тимчасово ПЕРЕВИЗНАЧАЄ той самий
 *       ключ на {@code active=false, intensity=0}, поки ліхтарик у
 *       руці, а при знятті ліхтарика — одразу повертає останній стан,
 *       що прийшов від сервера ({@link #lastServerDarkZoneActive}/
 *       {@link #lastServerDarkZoneSpec}), а не просто "false": якщо
 *       гравець фізично й досі в темній зоні, ефект повертається одразу
 *       ж, без чекання наступного мережевого пакета (пакет прийде лише
 *       на СЛІДУЮЧІЙ зміні стану зони — {@code DarkZoneModule} шле його
 *       лише "на межі", див. той клас-докстрінг).</li>
 *   <li><b>Динамічне світло.</b> Ведеться цілком зовнішнім
 *       LambDynamicLights/Sodium Dynamic Lights через
 *       {@link FlashlightDynamicLight#registerIfPresent()} (реєструє
 *       Java-хендлер luminance для {@code EntityType.PLAYER} — покриває
 *       і власника ліхтарика, і будь-якого іншого гравця, якого ти
 *       бачиш), яка викликається щотік із {@link #onClientTick}
 *       (ідемпотентно, дешево після першої вдалої реєстрації). Лише
 *       КОСМЕТИКА клієнта — сервер про це не знає, тому мобів це не
 *       лякає й не приваблює (спавн і поведінка мобів рахуються на
 *       сервері за справжнім light level).</li>
 * </ol>
 *
 * ── Чому одразу і hold-поза, і light, а не два окремі хендлери ────────
 * Обидва наслідки мають ІДЕНТИЧНИЙ критерій активності ("тримаю
 * ліхтарик у руці, зараз, локально") — розділяти їх на два
 * {@code @SubscribeEvent}-класи означало б дублювати перевірку
 * {@link #isHoldingFlashlight}. TODO(рефактор), якщо колись з'явиться
 * третій предмет із такою ж механікою "поки в руці" — тоді вартий
 * узагальнений {@code HeldItemEffect}-реєстр за зразком
 * {@link com.log_to_kot.maniacmod.items.ItemRegistry}.
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FlashlightClientHandler {

    // ── Динамічне світло ─────────────────────────────────────────────────
    //
    // Реального рендеру світла тут більше немає — все веде
    // FlashlightDynamicLight.registerIfPresent() (LambDynamicLights/
    // Sodium Dynamic Lights хендлер для EntityType.PLAYER, покриває і
    // власника ліхтарика, і будь-якого іншого гравця з ним у руці),
    // яку викликає onClientTick нижче. Раніше тут стояв ручний хак
    // форсованого LightLayer.BLOCK по сфері навколо гравця — прибраний:
    // він і засмічував чергу перебудов рендер-секцій, і був зайвим, бо
    // dynamic-light мод уже й так робить те саме коректніше (плавне
    // згасання, сумісність із Sodium/Embeddium "з коробки", без
    // ручного checkBlock/setBlocksDirty). А сам FlashlightDynamicLight
    // раніше існував, але ніде не викликався — реєстрація не
    // відбувалась НІКОЛИ, тому й не було жодного динамічного світла.

    /** true, поки в поточному стані ліхтарик активний — щоб end-переходи (руку опущено) відпрацювали рівно один раз. */
    private static boolean wasHolding = false;

    // ── Скасування темної зони ──────────────────────────────────────────

    /** Останній стан "гравець у темній зоні", що прийшов із сервера — див. клас-докстрінг, пункт 2. */
    private static boolean lastServerDarkZoneActive = false;
    private static dev.shaurmalib.common.overlay.DarkZoneSpec lastServerDarkZoneSpec = null;

    /** true, поки хендлер сам тримає ключ {@code "maniac_darkzone"} примусово вимкненим. */
    private static boolean darkZoneForcedOff = false;

    private FlashlightClientHandler() {}

    /**
     * Викликати ЗАМІСТЬ прямого {@code DarkZoneEffect.setActive(...)} з
     * {@link com.log_to_kot.maniacmod.client.ClientPacketHandler#onDarkZoneState}
     * — хендлер мусить знати РЕАЛЬНИЙ серверний стан, щоб коректно
     * повернути його при знятті ліхтарика (див. клас-докстрінг, пункт 2).
     * Якщо ліхтарик зараз НЕ в руці — пакет застосовується як завжди,
     * без жодної різниці в поведінці.
     */
    public static void onServerDarkZoneState(boolean active, dev.shaurmalib.common.overlay.DarkZoneSpec spec) {
        lastServerDarkZoneActive = active;
        lastServerDarkZoneSpec = spec;
        if (!darkZoneForcedOff) {
            dev.shaurmalib.forge.overlay.DarkZoneEffect.setActive(DARKZONE_KEY, active, spec);
        }
        // Якщо зараз forced-off — НІЧОГО клієнту не шлемо: ліхтарик
        // навмисно тримає ключ вимкненим, справжній стан застосується
        // сам, щойно onClientTick побачить "руку опущено".
    }

    private static final String DARKZONE_KEY = "maniac_darkzone";

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        // Реєстрація хендлера LambDynamicLights/Sodium Dynamic Lights для
        // сутності-гравця (світло ліхтарика в руці ІНШОГО гравця, якого ти
        // бачиш) — раніше цей клас існував, але ніде не викликався, тож
        // реєстрація не відбувалась ніколи. registerIfPresent сам
        // ідемпотентний і дешевий (isPresent — кешоване поле), тому
        // виклик щотік безпечний.
        FlashlightDynamicLight.registerIfPresent();

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        boolean holding = player != null && mc.level != null && isHoldingFlashlight(player);

        if (holding == wasHolding) {
            return;
        }
        wasHolding = holding;

        if (holding) {
            darkZoneForcedOff = true;
            // lastServerDarkZoneSpec ще може бути null, якщо сервер жодного разу
            // не надсилав стан темної зони (гравець узяв ліхтарик одразу після
            // заходу у світ) — раніше це передавало null у DarkZoneEffect.setActive,
            // яке зберігало null у channel.spec, і наступний клієнтський тік падав з NPE
            // на channel.spec.transitionTicks(). Тут active=false, тож сам spec для
            // цільового intensity не використовується — досить будь-якого валідного
            // дефолту, аби channel.spec ніколи не був null.
            dev.shaurmalib.forge.overlay.DarkZoneEffect.setActive(DARKZONE_KEY, false,
                lastServerDarkZoneSpec != null
                    ? lastServerDarkZoneSpec
                    : dev.shaurmalib.common.overlay.DarkZoneSpec.defaults());
        } else {
            darkZoneForcedOff = false;
            // Повертаємо СПРАВЖНІЙ, останній відомий від сервера стан —
            // не "false" — саме заради випадку "гравець і досі фізично
            // стоїть у темній зоні, просто прибрав ліхтарик у слот".
            if (lastServerDarkZoneSpec != null) {
                dev.shaurmalib.forge.overlay.DarkZoneEffect.setActive(
                    DARKZONE_KEY, lastServerDarkZoneActive, lastServerDarkZoneSpec);
            }
        }
    }

    /** true, якщо в основній чи офф-руці зараз лежить {@link FlashlightItem} — той самий критерій, що {@code GeneratorFuelClientSoundHandler.hasChargedCanister}. */
    private static boolean isHoldingFlashlight(LocalPlayer player) {
        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        return FlashlightItem.isFlashlight(main) || FlashlightItem.isFlashlight(off);
    }

}
