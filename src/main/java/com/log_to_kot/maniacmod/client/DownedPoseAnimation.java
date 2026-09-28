package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.ManiacMod;
import dev.shaurmalib.common.playeranim.PoseAction;
import dev.shaurmalib.common.playeranim.PoseActionRegistry;
import dev.shaurmalib.common.playeranim.PoseLayerId;
import dev.shaurmalib.common.playeranim.PoseSource;
import dev.shaurmalib.forge.playeranim.PlayerPoseController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Лежача поза виживого (playerlib) — візуальна частина стану «впав».
 *
 * ── Що тут і що НЕ тут ───────────────────────────────────────────────
 * ХІТБОКС лежачого гравця робить зовсім інший механізм —
 * {@code MixinPlayerDownedPose} (примусова {@code Pose.SWIMMING},
 * 0.6×0.6 замість 0.6×1.8). Цей клас хітбокс не чіпає взагалі: він
 * накладає поверх пози АНІМАЦІЮ тіла, щоб лежачий виглядав як
 * людина, що лежить (лікті вперті, голова піднята), а не як
 * витягнута по стійці «смирно» модель.
 *
 * ── Чому на КОЖНОГО лежачого, а не лише на себе ──────────────────────
 * PAL (PlayerAnimator) застосовує {@code AnimationStack} КОЖНОГО
 * {@code AbstractClientPlayer} під час рендера, але наповнити цей стек
 * може лише клієнт, який про позу ЗНАЄ. Клієнт дізнається про чужих
 * лежачих із {@code DownedSurvivorsPacket}/{@code RosterSyncPacket}
 * (див. {@link ClientMatchState#isProne}), тому запускати позу треба
 * для всіх, кого видно, — інакше тіло лежало б лише у себе на екрані, а
 * всі інші бачили б стоячого гравця, що «їде» по землі.
 *
 * ── Розбіжність із сервером неможлива ────────────────────────────────
 * Клас нічого не вирішує: він лише повторює те, що вже вирішив сервер
 * (стан виживого) і що вже прийшло пакетами. Це виключно косметика —
 * жодної ігрової переваги з неї не випливає, і вона безпечно зникає,
 * якщо PAL не встановлено (усі виклики {@code PlayerPoseController}
 * відмовостійкі й повертають {@code false}).
 *
 * ── Чому власний {@code Set}, а не «тригерити щотік» ─────────────────
 * {@code playAction} перезапускає кліп із fade-in. Викликати його щотік
 * означало б безкінечно рестартувати анімацію (виглядало б як судоми).
 * Тому запам'ятовуємо, кому вже граємо, і запускаємо рівно на переході
 * «встав → ліг».
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DownedPoseAnimation {

    /** Ім'я дії; файл — {@code assets/maniacmod/player_animations/downed_lie.json}. */
    public static final String LIE_ACTION = ManiacMod.MOD_ID + ":downed_lie";

    /** Кому зараз граємо позу. */
    private static final Set<UUID> posing = new HashSet<>();

    /**
     * Реєстрація дії. Викликати РІВНО ОДИН раз при старті клієнта
     * ({@code ClientSetup.onClientSetup}) — реєстр глобальний.
     *
     * Шар {@link PoseLayerId#DEATH_LIE}: він найвищий (1100), тобто
     * остаточно перекриває локомоцію на тих кістках, які є у файлі.
     * Для лежачого це саме те, що потрібно: у цьому стані немає жодної
     * іншої пози, з якою треба було б ділити тіло. Вищий шар для нього
     * не потрібен, а інші ({@code ITEM_ACTION} — ліхтарик/каністра)
     * свідомо нижчі: лежачий предметів не тримає.
     *
     * fade-in 6 тіків: падіння вже саме по собі різке, а мить, коли
     * гравець торкнувся землі, не має виглядати як «миттєво інша
     * людина». fade-out 8 — вставання теж плавне.
     */
    public static void registerActions() {
        PoseActionRegistry.register(PoseAction.of(
            LIE_ACTION,
            PoseSource.hold(ManiacMod.MOD_ID, "downed_lie").withFade(6, 8),
            PoseLayerId.DEATH_LIE));
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            // Вийшли зі світу: клієнтські записи однаково мертві, але
            // залишений UUID змусив би нас «думати», що поза ще грає,
            // і наступний матч почався б без неї. Скидаємо локально —
            // сам шар PAL чиститься на disconnect окремо.
            posing.clear();
            return;
        }

        // 1. Хто ПОВИНЕН лежати зараз.
        Set<UUID> shouldPose = new HashSet<>();
        List<AbstractClientPlayer> candidates = new ArrayList<>();
        for (Player player : level.players()) {
            if (!(player instanceof AbstractClientPlayer clientPlayer)) continue;
            if (!ClientMatchState.isProne(clientPlayer.getUUID())) continue;
            shouldPose.add(clientPlayer.getUUID());
            candidates.add(clientPlayer);
        }

        // 2. Почати тим, кому ще не граємо. `false` означає, що PAL ще
        //    не готовий (ресурси/реєстр) — у такому разі НЕ позначаємо
        //    гравця як «грає», щоб спробувати наступного тіку.
        for (AbstractClientPlayer player : candidates) {
            if (posing.contains(player.getUUID())) continue;
            if (PlayerPoseController.playAction(player, LIE_ACTION)) {
                posing.add(player.getUUID());
            }
        }

        // 3. Зупинити тим, хто більше не лежить (встав, помер, вибув).
        for (UUID id : new ArrayList<>(posing)) {
            if (shouldPose.contains(id)) continue;
            posing.remove(id);
            AbstractClientPlayer player = findPlayer(level, id);
            if (player != null) {
                PlayerPoseController.stopAction(player, LIE_ACTION);
            }
        }

        // Окремо прибирати "залишки" не потрібно: гравець, що зник із
        // ростеру, наступного тіка не потрапить у shouldPose і піде
        // звичайним шляхом кроку 3 (зупинка там безпечна навіть коли
        // сутності вже немає — PlayerPoseController просто нічого не знайде).
    }

    private static AbstractClientPlayer findPlayer(ClientLevel level, UUID id) {
        for (Player player : level.players()) {
            if (player.getUUID().equals(id) && player instanceof AbstractClientPlayer clientPlayer) {
                return clientPlayer;
            }
        }
        // Поза межами списку видимих гравців — спробувати дістати
        // сутність напряму (той самий прийом, що в ManiacStunWorldMarker).
        if (level.getPlayerByUUID(id) instanceof AbstractClientPlayer clientPlayer) {
            return clientPlayer;
        }
        return null;
    }

    /** Скидання при виході з матчу — див. {@code ClientMatchState#reset}. */
    public static void reset() {
        posing.clear();
    }
}
