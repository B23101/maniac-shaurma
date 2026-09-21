package com.log_to_kot.maniacmod.server;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.abilities.Ability;
import com.log_to_kot.maniacmod.maniacs.ManiacArchetype;
import com.log_to_kot.maniacmod.maniacs.ManiacRegistry;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import net.minecraft.server.level.ServerPlayer;

/**
 * Обробка намірів, надісланих клієнтом.
 *
 * ── Головне правило ──────────────────────────────────────────────────
 * Клієнт надсилає «я натиснув», а не «я зробив». Кожен метод тут
 * заново перевіряє роль, фазу й стан — навіть якщо клієнт уже
 * перевірив те саме. Клієнт може бути модифікований; сервер — ні.
 *
 * Перевірки навмисно однакової форми: спершу матч, потім роль, потім
 * дозвіл фази, потім стан. Якщо колись знадобиться лог «чому дію
 * відхилено», його додають в одному місці, а не в п'яти.
 */
public final class ServerPacketHandler {

    private ServerPacketHandler() {}

    // ── Маньяк ───────────────────────────────────────────────────────────

    /**
     * Здібність за номером клавіші 1–3.
     *
     * Слот поза межами — модифікований клієнт. Мовчки ігноруємо:
     * кидати виняток означало б дати спосіб засмічувати лог сервера.
     */
    public static void onAbilityActivate(ServerPlayer player, int slot) {
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;
        if (slot < 0 || slot >= ManiacArchetype.MAX_ABILITIES) return;
        if (!match.isManiac(player.getUUID())) return;
        if (!match.phases().allows(PhaseRule.ABILITIES)) return;

        ManiacArchetype archetype = match.maniacArchetype();
        if (archetype == null) return;

        Ability ability = archetype.abilityAt(slot);
        if (ability == null) return; // слот порожній — у маньяка менше здібностей

        // TODO(міграція abilities): перевірити кулдаун слота й викликати
        // ability.activate(player); кулдаун відповісти
        // AbilityCooldownPacket.abilityId(slot) ОДИН раз.
    }

    /** Пастка за номером слота Z/X/C. */
    public static void onTrapPlace(ServerPlayer player, int slot) {
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;
        if (slot < 0 || slot >= ManiacArchetype.MAX_TRAP_SLOTS) return;
        if (!match.isManiac(player.getUUID())) return;
        if (!match.phases().allows(PhaseRule.TRAPS)) return;

        ManiacArchetype archetype = match.maniacArchetype();
        if (archetype == null) return;
        if (archetype.trapAt(slot) == null) return;

        // TODO(міграція traps): порахувати позицію по погляду гравця,
        // перевірити TrapPlacementRules і кулдаун слота.
        // Позиція навмисно рахується тут, а не приходить у пакеті.
    }

    public static void onManiacSelected(ServerPlayer player, String maniacId) {
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;

        // Невідомий id — клієнт модифікований або застарів. Мовчки
        // ігноруємо: кидати виняток тут означало б дати будь-кому
        // спосіб засмічувати лог сервера.
        if (!ManiacRegistry.exists(maniacId)) return;

        match.onManiacChosen(player, ManiacRegistry.get(maniacId));
        // Таб мусить показати ЯКОГО САМЕ маньяка обрано одразу, не
        // чекаючи наступного переходу фази.
        ServerHooks.broadcastRoster(player.getServer().getPlayerList().getPlayers());
    }

    // ── Виживі ───────────────────────────────────────────────────────────

    public static void onHighlightRequest(ServerPlayer player) {
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;
        if (!match.isSurvivor(player.getUUID())) return;
        if (!match.phases().allows(PhaseRule.HUD)) return;

        // Кулдаун (30 с за замовчуванням) належить ролі виживого, а не
        // генераторам — тому запуск іде через SurvivorModule, який сам
        // вирішує, готова сила чи ні, і лише потім просить генератори
        // показати стан. Відмова мовчазна: клієнт і так не слатиме пакет,
        // поки бачить кулдаун на власному HUD.
        match.survivors().tryUseHighlight(player);
    }

    public static void onStandUpAttempt(ServerPlayer player) {
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;

        if (!match.isSurvivor(player.getUUID())) return;
        if (!match.phases().allows(PhaseRule.MOVEMENT)) return;
        // Поза станом «збитий з ніг» пакет безглуздий — і саме тут
        // гаситься спам пробілом.
        if (match.survivorStateOf(player.getUUID()) != SurvivorState.CRAWLING) return;

        match.survivors().onStandUpAttempt(player);
    }

    public static void onRescueHold(ServerPlayer player, boolean holding) {
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;
        if (!match.isSurvivor(player.getUUID())) return;
        if (!match.phases().allows(PhaseRule.RESCUE)) return;

        match.survivors().onRescueHold(player, holding);
    }

    /**
     * ПКМ утримується/відпущено над генератором (стадія REPAIR).
     *
     * Позицію приймаємо від клієнта (рейкаст на момент натискання), але
     * НЕ довіряємо їй сліпо — {@code GeneratorModule.refreshRepair}
     * сам звіряє, що генератор існує саме там; а дистанцію до нього
     * додатково не перевіряємо тут, бо ремонт, на відміну від
     * міні-ігор, не має власного окремого захисту від "телепортного"
     * ремонту — це той самий рівень довіри, що вже був у моделі
     * блок+use() до цієї зміни.
     */
    public static void onGeneratorRepairHold(ServerPlayer player, boolean holding,
                                              net.minecraft.core.BlockPos pos) {
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;
        if (!match.isSurvivor(player.getUUID())) return;

        if (!holding) {
            match.generatorModule().stopRepair(player);
            return;
        }

        if (!match.phases().allows(PhaseRule.GENERATOR_REPAIR)) return;
        com.log_to_kot.maniacmod.map.zones.GeneratorPoi poi = match.generatorAt(pos);
        if (poi == null || poi.stage() == com.log_to_kot.maniacmod.map.zones.GeneratorPoi.Stage.DONE) return;

        match.generatorModule().refreshRepair(player, pos);
    }

    // ── Міні-ігри ремонту ────────────────────────────────────────────────

    /**
     * Клік у міні-грі "ціль". Перевірка ролі/фази тут не потрібна —
     * {@link com.log_to_kot.maniacmod.map.GeneratorModule} сам мовчки
     * ігнорує клік, якщо в цього гравця немає активної TARGET-міні-гри
     * (а її й не могло бути в маньяка чи поза REPAIR-фазою).
     */
    public static void onTargetMinigameClick(ServerPlayer player, double cursorPosition) {
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;
        match.generatorModule().onTargetMinigameClick(player, cursorPosition);
    }

    /** Дріт перетягнуто в міні-грі "дроти". Та сама логіка ігнорування, що й вище. */
    public static void onWireMinigameDrop(ServerPlayer player, int leftSlot, int rightSlot) {
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;
        match.generatorModule().onWireMinigameDrop(player, leftSlot, rightSlot);
    }

    // ── Налаштування (GUI-меню) ─────────────────────────────────────────

    /**
     * Відкриття {@code SettingsMenuScreen} — спільна точка для команди
     * {@code /maniac settings} ({@link ManiacCommand#openSettings}) і
     * кнопки "⚙ Налаштування гри" у вікні чату
     * ({@link com.log_to_kot.maniacmod.net.c2s.settings.OpenSettingsMenuRequestPacket}).
     *
     * Один метод, а не дві копії тієї самої перевірки прав і того
     * самого виклику {@code SettingsSnapshot.collect()} — інакше зміна
     * правила доступу (наприклад підняти поріг прав) довелося б
     * пам'ятати внести у двох місцях, і рано чи пізно вони розійшлися б
     * (див. {@code AI_CODE_GUIDE.md}, розділ 0).
     *
     * {@code hasPermission(2)} — той самий рівень, що вимагає корінь
     * команди {@code /maniac} у {@link ManiacCommand#register}. Клієнт
     * ховає кнопку від гравців без прав лише косметично: справжня межа
     * — тут, бо пакет можна надіслати й модифікованим клієнтом без
     * жодної кнопки.
     */
    public static void onOpenSettingsMenuRequest(ServerPlayer player) {
        if (!player.hasPermissions(2)) return;

        com.log_to_kot.maniacmod.net.ModNetwork.toPlayer(player,
            new com.log_to_kot.maniacmod.net.s2c.settings.OpenSettingsMenuPacket(
                com.log_to_kot.maniacmod.config.SettingsSnapshot.collect()));
    }

    /**
     * Зміна одного значення з {@code SettingsMenuScreen}.
     *
     * Права перевіряються ТУТ, а не лише тим, що клієнту хтось
     * дозволив відкрити екран — сам пакет можна надіслати й без
     * екрана, модифікованим клієнтом. {@code hasPermission(2)} — той
     * самий рівень, що вимагає корінь команди {@code /maniac} у
     * {@link ManiacCommand#register}.
     */
    public static void onSettingsChange(ServerPlayer player, String block, String key, String value) {
        if (!player.hasPermissions(2)) return;

        String error = com.log_to_kot.maniacmod.config.ManiacConfigs.set(block, key, value);
        if (error != null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c" + error));
            return;
        }

        // БАГ (GUI "змінює" роль дебагу, а старт усе одно бере стару):
        // /maniac debug role <...> пише в DebugMode.runtimeRole (пам'ять
        // процесу), і DebugMode.role() ЗАВЖДИ читає runtimeRole першим,
        // якщо він хоч раз був виставлений за цей запуск сервера —
        // конфіг після цього ігнорується назавжди, аж до рестарту.
        // Тому правка debugRole саме тут, у GUI-меню, тихо нічого не
        // міняла: конфіг-файл оновлювався, а старт матчу й далі брав
        // застряглий runtimeRole з попередньої команди. Правка через
        // GUI має бути таким самим "джерелом правди", як і команда —
        // тому вона так само скидає runtime-override.
        if ("game".equals(block) && "debugRole".equals(key)) {
            com.log_to_kot.maniacmod.core.match.DebugMode.clearRuntimeRole();
        }

        // Відповідь несе ФАКТИЧНО записане значення (після clamp/fallback
        // усередині ManiacConfigs.set), не те, що гравець ввів — див.
        // докстрінг OpenSettingsMenuPacket.
        com.log_to_kot.maniacmod.net.ModNetwork.toPlayer(player,
            new com.log_to_kot.maniacmod.net.s2c.settings.OpenSettingsMenuPacket(
                com.log_to_kot.maniacmod.config.SettingsSnapshot.collect()));
    }
}
