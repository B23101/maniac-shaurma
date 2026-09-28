package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.entity.GeneratorEntity;
import com.log_to_kot.maniacmod.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Клієнтське життя звуку заливу пального — за тим самим принципом, що
 * {@link GeneratorLoopClientSoundHandler} для гулу генератора, тільки
 * джерело "чи зараз активно" тут не {@code isActive()} сутності, а
 * ЛОКАЛЬНЕ утримання Shift+ПКМ цього гравця
 * ({@link ClientInputHandler#isRepairHeld()} +
 * {@link ClientInputHandler#heldGenerator()}) НА СТАДІЇ FUEL, і до того
 * ж — реально заряджена каністра бензину в його руці
 * ({@link #hasChargedCanister}, той самий критерій, що серверний
 * {@code GeneratorModule.canisterInHand}) — саме заряджена каністра
 * вирішує, чи прогрес заливу дійсно йде, тож і звук грає лише тоді.
 * Залив чути тільки тому, хто сам його виконує, на відміну від гулу,
 * який чутно всім поруч.
 *
 * ── Чому не по всіх гравцях одразу, лише по собі ────────────────────────
 * У ванільному Minecraft клієнт не бачить чужого локального стану
 * утримання кнопки — лише свій. Звук заливу від ІНШОГО survivor'а тут
 * не грає нікому, крім нього самого (як і було задумано зі старим
 * серверним підходом — {@code FUEL_RADIUS_BLOCKS} обмежував дальність,
 * але кожен слухач усе одно чув лише коли САМ був поруч чужого заливу;
 * основний випадок використання — власний залив, тому спрощення до
 * "тільки моя дія" не міняє типової гри).
 *
 * ── Ланцюжок трьох семплів і плавний кінець ─────────────────────────────
 * Поки утримання триває, щойно поточний {@link GeneratorFuelClientSound}
 * долає мінімальну тривалість найкоротшого з трьох файлів
 * ({@code naturalLengthElapsed()} — той самий підхід через
 * {@code OggSoundLength}, що раніше мав серверний клас), заводиться
 * новий інстанс на ту саму подію {@code FUEL_FILL} — рушій сам обирає
 * інший випадковий варіант з {@code sounds.json}. Коли утримання
 * зникає, НАСТУПНИЙ інстанс не заводиться — натомість поточному
 * викликається {@link GeneratorFuelClientSound#release()}, і він сам
 * плавно згасає, не обриваючись.
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GeneratorFuelClientSoundHandler {

    /** Гучність заливу — фіксована, на відміну від гулу генератора: гравець і так впритул до генератора, поки заливає. */
    private static final float FILL_VOLUME = 1.0f;

    /** Поточний живий прогін (звучить або вже гасне), якщо є. */
    private static GeneratorFuelClientSound current = null;

    private GeneratorFuelClientSoundHandler() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        boolean holdingFuel = player != null
            && ClientInputHandler.isRepairHeld()
            && ClientInputHandler.heldGenerator() != null
            && ClientInputHandler.heldGenerator().isFuelStage()
            && hasChargedCanister(player);

        if (!holdingFuel) {
            // Утримання зникло (відпущено, каністра спорожніла, генератор
            // перейшов зі стадії FUEL) — плавно згасити те, що вже грає,
            // і НЕ заводити нічого нового, поки утримання не почнеться знову.
            if (current != null && !current.isReleasing()) {
                current.release();
            }
            cleanupIfFinished(mc);
            return;
        }

        GeneratorEntity generator = ClientInputHandler.heldGenerator();
        Vec3 origin = generator.position().add(0, generator.getBbHeight() * 0.5, 0);

        boolean needsNext = current == null
            || current.isStopped()
            || (!current.isReleasing() && current.naturalLengthElapsed());

        if (needsNext) {
            // Попередній прогін (якщо був) НЕ гаситься примусово — він сам
            // дограє свій файл до кінця природно (SoundManager сам його
            // прибере), просто хендлер більше за ним не стежить. Це і є
            // те саме навмисне коротке накладення двох семплів, про яке
            // сказано в докстрінгу класу вище.
            Holder<SoundEvent> holder = ForgeRegistries.SOUND_EVENTS
                .getHolder(ModSounds.FUEL_FILL.get()).orElse(null);
            if (holder != null) {
                current = new GeneratorFuelClientSound(holder, origin.x, origin.y, origin.z, FILL_VOLUME);
                mc.getSoundManager().play(current);
            }
        }
    }

    private static void cleanupIfFinished(Minecraft mc) {
        if (current != null && current.isStopped()) {
            current = null;
        }
    }

    /**
     * true, якщо в основній чи офф-руці гравця зараз лежить каністра
     * бензину з зарядом > 0 — той самий критерій, що серверний
     * {@code GeneratorModule.canisterInHand}/{@code tickFuel}, який
     * фактично вирішує, чи прогрес заливу піде. Без цієї перевірки
     * звук {@code fuel_fill} грав навіть тоді, коли гравець просто
     * тримає Shift+ПКМ по генераторі на стадії FUEL без каністри
     * (або з порожньою) в руці — прогрес не йде, а звук усе одно чути.
     */
    private static boolean hasChargedCanister(LocalPlayer player) {
        net.minecraft.world.item.ItemStack main = player.getMainHandItem();
        net.minecraft.world.item.ItemStack off = player.getOffhandItem();
        boolean mainOk = main.getItem() instanceof com.log_to_kot.maniacmod.items.FuelCanisterItem
            && !com.log_to_kot.maniacmod.items.FuelCanisterItem.isEmpty(main);
        boolean offOk = off.getItem() instanceof com.log_to_kot.maniacmod.items.FuelCanisterItem
            && !com.log_to_kot.maniacmod.items.FuelCanisterItem.isEmpty(off);
        return mainOk || offOk;
    }
}
