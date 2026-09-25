package com.log_to_kot.maniacmod.sound;

import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Звук у світі, який грає СЕРВЕР — у своєму радіусі чутності.
 *
 * ── Чому не {@code Level#playSound} ──────────────────────────────────
 * Ванільний метод не має параметра радіуса. Він розсилає звук за
 * жорстким правилом {@code volume > 1 ? 16 * volume : 16} блоків, тож
 * найменша зона — 16 блоків, і зробити звук чутним ЛИШЕ на 10 чи 5
 * блоків через нього неможливо. Тут звук іде тим самим ванільним
 * пакетом {@link ClientboundSoundPacket} (клієнт бачить його як
 * ЗВИЧАЙНИЙ звук світу — з загасанням за відстанню й панорамою), але
 * розсилається через {@code PlayerList#broadcast}, у якого радіус —
 * параметр.
 *
 * ── Чому не {@code SoundCenter} із shaurma-lib ───────────────────────
 * SoundCenter грає звук ЛОКАЛЬНО, на клієнті, який його викликав. Для
 * звуку генератора це неправильно: він мусить бути чутний усім, хто
 * поруч (і маньяку теж), іти З ТОЧКИ генератора й загасати з
 * відстанню — тобто бути звуком світу, а не «подією в інтерфейсі
 * одного гравця». Тому джерело звуку — сервер.
 *
 * ── Чому {@code SoundSource} — параметр ──────────────────────────────
 * Категорія визначає, яким ванільним слайдером гучності гравець може
 * приглушити звук. Механізми (генератор) — це {@link SoundSource#BLOCKS},
 * тож гравець, якого дратує гул, зменшує «Блоки», а не вимикає всі
 * звуки гри.
 */
public final class WorldSound {

    private WorldSound() {}

    /**
     * Програти звук усім, хто зараз у радіусі {@code radiusBlocks} від
     * точки {@code at}, в тому самому вимірі.
     *
     * @param level       рівень сервера; {@code null} — тихо нічого не
     *                    робимо (викликачі беруть рівень з онлайн-гравця,
     *                    а матч може тікати й без них)
     * @param sound       подія з {@code ModSounds}
     * @param at          точка світу, З ЯКОЇ чути звук (центр генератора)
     * @param radiusBlocks радіус чутності у блоках — умикає/вимикає звук
     * @param volume      гучність у точці (1.0 = як звичайний звук блока)
     * @param pitch       висота; 1.0 — як у файлі
     */
    public static void playInRadius(ServerLevel level, SoundEvent sound, SoundSource source,
                                    Vec3 at, double radiusBlocks, float volume, float pitch) {
        if (level == null) return;

        // Холдер, а не SoundEvent: саме так звук їде в пакеті (клієнт
        // знаходить його за id у своєму реєстрі). Нема холдера — звук не
        // зареєстровано; це тиха відмова без крашу посеред матчу.
        Holder<SoundEvent> holder = ForgeRegistries.SOUND_EVENTS.getHolder(sound).orElse(null);
        if (holder == null) return;

        level.getServer().getPlayerList().broadcast(
            null, at.x, at.y, at.z, radiusBlocks, level.dimension(),
            new ClientboundSoundPacket(holder, source, at.x, at.y, at.z,
                volume, pitch, level.random.nextLong()));
    }

    /**
     * Обірвати вже граючий звук на всіх гравцях сервера.
     *
     * ── Чому це потрібно окремо від {@link #playInRadius} ────────────────
     * {@code playInRadius} лише припиняє СТАВИТИ нові програвання — те, що
     * вже летить по мережі (наприклад довгий {@code generator_loop}),
     * клієнт доспіває природньо до кінця файлу, бо звук — не «петля з
     * прапорцем», яку можна вимкнути, а вже відправлений одноразовий
     * пакет. Якщо генератор зникає (кінець матчу, RESET) саме ПІД ЧАС
     * такого доспівування, гравець у лобі чує гул генератора, якого вже
     * немає — секунди зайвого звуку, що читаються як баг.
     *
     * ── Чому всім гравцям, а не в радіусі ─────────────────────────────────
     * {@code ClientboundStopSoundPacket} не має параметра відстані — це
     * команда «якщо в тебе зараз грає САМЕ ЦЕЙ звук/категорія, зупини».
     * Гравець, що вже вийшов за {@code radiusBlocks} у момент стопу, теж
     * мусить отримати команду: інакше він єдиний лишиться з висячим
     * звуком, а адресувати повідомлення «тим, хто раніше був у радіусі»
     * сервер просто не має чим — той стан жив лише в {@link
     * com.log_to_kot.maniacmod.map.GeneratorSoundscape}, а не на клієнті.
     *
     * @param source null — зупинити звук БУДЬ-ЯКОЇ категорії з таким id
     *               (тут завжди передається конкретна категорія, бо мод
     *               завжди знає, з якою грав)
     */
    public static void stopForAll(ServerLevel level, SoundEvent sound, SoundSource source) {
        if (level == null) return;
        net.minecraft.resources.ResourceLocation id =
            ForgeRegistries.SOUND_EVENTS.getKey(sound);
        if (id == null) return;

        ClientboundStopSoundPacket packet = new ClientboundStopSoundPacket(id, source);
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            player.connection.send(packet);
        }
    }
}
