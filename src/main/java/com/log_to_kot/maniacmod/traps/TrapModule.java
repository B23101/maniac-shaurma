package com.log_to_kot.maniacmod.traps;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.core.phase.PhaseListener;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.entity.BearTrapEntity;
import com.log_to_kot.maniacmod.maniacs.ManiacArchetype;
import com.log_to_kot.maniacmod.map.minigame.TargetMinigameSpec;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.AbilityCooldownPacket;
import com.log_to_kot.maniacmod.net.s2c.minigame.RepairMinigameProgressPacket;
import com.log_to_kot.maniacmod.net.s2c.minigame.RepairMinigameResultPacket;
import com.log_to_kot.maniacmod.net.s2c.minigame.TargetMinigameOpenPacket;
import com.log_to_kot.maniacmod.net.s2c.notify.ActionBarPacket;
import com.log_to_kot.maniacmod.net.s2c.traps.TrapCatalogPacket;
import com.log_to_kot.maniacmod.net.s2c.traps.TrapLoadoutPacket;
import com.log_to_kot.maniacmod.registry.ModSounds;
import com.log_to_kot.maniacmod.sound.OggSoundLength;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import dev.shaurmalib.common.overlay.ActionBarMessageType;
import dev.shaurmalib.common.lock.LockType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Пастки маньяка: розміщення, спрацювання, звільнення.
 *
 * ── Один модуль на ВСІ пастки ────────────────────────────────────────
 * Життєвий цикл однаковий для будь-якої пастки (див.
 * {@link TrapArchetype}), тому він живе тут ОДИН раз. Конкретна пастка
 * додає лише «де ставити / що з'являється / що робить із жертвою».
 * Сутність-тіло ({@link BearTrapEntity}) не тримає ігрового стану —
 * лише показує модель і питає модуль у свій тік.
 *
 * ── Що модуль тримає ─────────────────────────────────────────────────
 *   • {@link #placed}      — усі пастки матчу (для тіку й очищення);
 *   • {@link #victims}     — хто зараз у пастці і в якій;
 *   • {@link #slotCooldown} — перезарядка розміщення по слотах;
 *   • {@link #immunity}    — короткий імунітет жертви після звільнення.
 * Усе це стирається на виході з ігрових фаз. Сама сутність прибирається
 * {@code MatchRuntimeRegistry.cleanup}, тому світ не засмічується.
 *
 * ── Довіра клієнту ───────────────────────────────────────────────────
 * Клієнт надсилає лише «слот N, підтверджую». Куди дивиться маньяк,
 * дальність, фазу, кулдаун і валідність місця сервер перераховує сам.
 *
 * ── Жертва й ЛКМ ─────────────────────────────────────────────────────
 * Жертві ставиться лише {@link LockType#MOVEMENT}. {@code ATTACK}
 * навмисно НЕ ставиться: звільнення — це ЛКМ ломом по капкану, і
 * заблокований удар зробив би пастку безвихідною.
 *
 * ── Саморятунок: міні-гра «попади в ціль» ─────────────────────────────
 * У момент захлопування жертві відкривається міні-гра
 * {@link TrapEscapeMinigame}: набрати {@code trapEscapeHitsRequired}
 * влучань (5), промах знімає {@code trapEscapeMissPenalty} (2), на всю
 * спробу — {@code trapEscapeTicks}. Встиг — вибрався сам (капкан
 * розкривається й зникає, як після удару ломом); не встиг — лишається
 * в капкані й чекає на товариша. Спроба одна на захлопування.
 *
 * ── Кого пастка НЕ чіпає ─────────────────────────────────────────────
 * Лежачих (CRAWLING/UNCONSCIOUS) і термінальних — див.
 * {@link #canCatch}. Перевірка стоїть ОДНИМ місцем на всі типи пасток
 * ({@link #tickTrap}), а не в кожній архетипі: інакше нова пастка
 * мусила б пам'ятати про це правило, і колись його забула б.
 */
public final class TrapModule implements PhaseListener {

    private static final String LOCK_REASON = "trap_victim";

    /** Імунітет жертви після звільнення, у тіках (3 с): щоб не потрапити в той самий капкан миттєво. */
    private static final int RELEASE_IMMUNITY_TICKS = 60;

    private final Supplier<MatchOrchestrator> matchSupplier;

    /** Усі пастки матчу за UUID сутності. */
    private final Map<UUID, PlacedTrap> placed = new HashMap<>();

    /** UUID жертви → UUID капкана, у якому вона. */
    private final Map<UUID, UUID> victims = new HashMap<>();

    /** Слот 0..2 → скільки тіків до дозволу поставити ще раз. */
    private final int[] slotCooldown = new int[ManiacArchetype.MAX_TRAP_SLOTS];

    /** UUID гравця → скільки тіків він ще не спрацьовує пастки. */
    private final Map<UUID, Integer> immunity = new HashMap<>();

    /**
     * UUID жертви → її спроба вибратися з капкана. Запису немає = жертва
     * вже не має шансу вибратися сама (спроба використана) і чекає на
     * товариша з ломом.
     */
    private final Map<UUID, TrapEscapeMinigame> escapes = new HashMap<>();

    /** Лише для розкладки міні-гри (позиція цілі, seed) — ігровий стан не тримає. */
    private final Random rng = new Random();

    /**
     * UUID капкана → тіків до наступного звуку боротьби жертви.
     *
     * ── Чому період = довжині файлу ─────────────────────────────────
     * Боротьба — це не одна подія, а ТРИВАЛИЙ стан, тож звук
     * повторюється. Коли інтервал дорівнює довжині самого ogg, наступне
     * програвання стартує тієї ж миті, коли скінчилось попереднє: ні
     * шва, ні паузи, і нічого не треба тримати в конфігу — заміна файла
     * сама змінить темп. Довжину читає {@code OggSoundLength}.
     *
     * Порожній осередок = 0 = «грати зараз», тому перший звук
     * виставляється в {@link #trigger} на повну довжину: капкан спершу
     * клацає, і лише потім починається боротьба.
     */
    private final Map<UUID, Integer> struggleCooldown = new HashMap<>();

    /** Тривалість звуку боротьби в тіках; 0 — файл недоступний, звук не граємо. */
    private final int struggleSoundTicks = OggSoundLength.ticksOf("trap_struggle");

    /** Що маньяк обрав у меню на початку матчу: індекси в {@link ManiacArchetype#traps()}. */
    private final List<Integer> chosenSlots = new ArrayList<>();

    private record PlacedTrap(TrapArchetype type, UUID entityId) {}

    public TrapModule(Supplier<MatchOrchestrator> matchSupplier) {
        this.matchSupplier = matchSupplier;
    }

    private MatchOrchestrator match() {
        return matchSupplier.get();
    }

    @Override
    public String id() {
        return "traps";
    }

    // ── Життєвий цикл фаз ────────────────────────────────────────────────

    @Override
    public void onPhaseEnter(GamePhase phase, List<ServerPlayer> players) {
        // ROLE_REVEAL: якщо в маньяка вже є архетип (RANDOM/FIXED — для
        // MENU архетип прийде пізніше через onTrapsChosen/morphManiac,
        // і тоді каталог піде звідти) і є з чого обирати — пропонуємо
        // вибір. Якщо каталог не більший за ліміт, вибирати нема що:
        // TrapModule сам візьме всі при вході в ігрову фазу нижче,
        // TrapCatalogPacket не шлеться, екран вибору не відкриється.
        if (phase == GamePhase.ROLE_REVEAL) {
            MatchOrchestrator m = match();
            ManiacArchetype archetype = m == null ? null : m.maniacArchetype();
            if (archetype != null) {
                for (ServerPlayer p : players) {
                    if (m.isManiac(p.getUUID())) syncCatalogIfChoiceNeeded(p, archetype);
                }
            }
        }

        // Вхід в ігрову фазу. Якщо маньяк не встиг обрати пастки в меню
        // (дебаг, старт командою, вибір відкладено чи не був потрібен) —
        // беремо всі, до ліміту: маньяк не має лишитись без пасток лише
        // через те, що не було кому натиснути. Клієнту одразу шлемо набір.
        if (phase.allows(PhaseRule.TRAPS)) {
            MatchOrchestrator m = match();
            if (m != null && chosenSlots.isEmpty()) chooseAll(m.maniacArchetype());
            for (ServerPlayer p : players) {
                if (m != null && m.isManiac(p.getUUID())) syncLoadout(p);
            }
            return;
        }

        // Стан пасток живе лише в ігрових фазах. Вихід у неігрову
        // (кінець, скидання, лобі) обов'язково розморожує жертв.
        clearLoadoutOnClient(players);
        releaseEveryone(players);
        placed.clear();
        // Разом із пастками зникає й їхній звуковий стан: у новому матчі
        // ті самі UUID не повторяться, але тримати мертві записи нема сенсу
        // (і саме цей виклик робить скидання повним, а не частковим).
        struggleCooldown.clear();
        victims.clear();
        immunity.clear();
        closeAllEscapes(players);
        java.util.Arrays.fill(slotCooldown, 0);
        // chosenSlots чистимо лише при поверненні в LOBBY/RESET: вибір у
        // меню робиться ДО ігрової фази (ROLE_REVEAL — теж неігрова), і
        // стирати його на вході в ROLE_REVEAL означало б знищити вибір,
        // який щойно зроблено.
        if (phase == GamePhase.LOBBY || phase == GamePhase.RESET) resetChoice();
    }

    @Override
    public void onPhaseTick(GamePhase phase, List<ServerPlayer> players, long ticksInPhase) {
        if (!phase.allows(PhaseRule.TRAPS)) return;

        tickSlotCooldowns(players);
        tickImmunity();
        // Не звільнений, але вибулий / від'єднаний / більше не виживий — знімаємо лок.
        releaseInvalidVictims(players);
        tickEscapes(players);
    }

    // ── Вибір пасток у меню ──────────────────────────────────────────────

    /**
     * Маньяк обрав пастки в меню. Приймаємо лише реальні індекси зі
     * списку його архетипу, без дублів і не більше {@code trapsPerMatch}.
     * Усе інше мовчки відкидається: модифікований клієнт не має способу
     * взяти пастку, якої в маньяка немає, чи більше, ніж дозволено.
     */
    public void onTrapsChosen(ServerPlayer maniac, List<Integer> indices) {
        MatchOrchestrator m = match();
        if (m == null || !m.isManiac(maniac.getUUID())) return;
        ManiacArchetype archetype = m.maniacArchetype();
        if (archetype == null) return;

        int limit = Math.min(ManiacConfigs.get(ConfigSchema.TRAPS_PER_MATCH),
                             ManiacArchetype.MAX_TRAP_SLOTS);
        Set<Integer> seen = new HashSet<>();
        chosenSlots.clear();
        for (int index : indices) {
            if (chosenSlots.size() >= limit) break;
            if (index < 0 || index >= archetype.traps().size()) continue;
            if (!seen.add(index)) continue;
            chosenSlots.add(index);
        }
    }

    /**
     * Автовибір: усі пастки архетипу до ліміту. Викликається, коли меню
     * не показувалось (дебаг-режим, старт командою) — маньяк не має
     * лишитись без пасток лише через те, що не було кому натиснути.
     */
    public void chooseAll(ManiacArchetype archetype) {
        chosenSlots.clear();
        if (archetype == null) return;
        int limit = Math.min(ManiacConfigs.get(ConfigSchema.TRAPS_PER_MATCH),
                             ManiacArchetype.MAX_TRAP_SLOTS);
        for (int i = 0; i < archetype.traps().size() && chosenSlots.size() < limit; i++) {
            chosenSlots.add(i);
        }
    }

    /** Порожній вибір — наступний матч починається без застарілих пасток. */
    public void resetChoice() {
        chosenSlots.clear();
    }

    /**
     * ДЕБАГ: примусово шле {@link TrapCatalogPacket} маньяку, ігноруючи
     * перевірку {@code all.size() <= limit} з {@link #syncCatalogIfChoiceNeeded}.
     * Використовується лише {@code /maniac debug traps} для UI-тесту
     * {@code TrapChooseScreen}, коли в реєстрі замало пасток, щоб екран
     * відкрився природним шляхом. Ігрову логіку вибору не чіпає: після
     * закриття екрана {@code chooseAll}/{@code onTrapsChosen} відпрацюють
     * як завжди.
     *
     * @return скільки пасток було в надісланому каталозі (0, якщо в
     *         архетипу взагалі немає жодної — пакет тоді не шлеться)
     */
    public int debugForceCatalog(ServerPlayer maniac, ManiacArchetype archetype) {
        List<TrapArchetype> all = archetype.traps();
        if (all.isEmpty()) return 0;

        int limit = Math.min(ManiacConfigs.get(ConfigSchema.TRAPS_PER_MATCH),
                             ManiacArchetype.MAX_TRAP_SLOTS);
        List<String> ids = new ArrayList<>();
        for (TrapArchetype t : all) ids.add(t.id());
        ModNetwork.toPlayer(maniac, new TrapCatalogPacket(ids, limit));
        return ids.size();
    }

    /**
     * Шле маньяку його набір пасток і дальність розміщення — з цього
     * клієнт малює панель зліва й квадрат-підказку. Порядок id = порядок
     * клавіш 5/6/7. Викликається після вибору в меню, при вході в гру й
     * при перезаході маньяка.
     */
    public void syncLoadout(ServerPlayer maniac) {
        List<String> ids = new ArrayList<>();
        for (int slot = 0; slot < chosenSlots.size(); slot++) {
            TrapArchetype type = trapForSlot(slot);
            if (type != null) ids.add(type.id());
        }
        ModNetwork.toPlayer(maniac, new TrapLoadoutPacket(ids,
            ManiacConfigs.get(ConfigSchema.TRAP_PLACE_RANGE_BLOCKS)));
    }

    /**
     * Якщо каталог архетипу більший за ліміт — шле {@link TrapCatalogPacket}
     * (сигнал відкрити екран вибору). Якщо не більший — вибирати нема
     * що, пакет НЕ шлеться: маньяк однаково отримає всі пастки через
     * автовибір при вході в ігрову фазу.
     *
     * Викликається з трьох місць, де архетип щойно стає відомим:
     * вхід у ROLE_REVEAL (RANDOM/FIXED), вибір у меню маньяка
     * (onTrapsChosen — ні, туди НЕ треба: там вибір уже ЗРОБЛЕНО) і
     * дебаг-morph. Насправді виклик потрібен рівно там, де архетип
     * ПРИЗНАЧАЄТЬСЯ, а не де пастки вже обираються — тому дивись
     * виклики в {@code MatchOrchestrator.onManiacChosen}/{@code morphManiac}
     * і в {@link #onPhaseEnter} вище.
     */
    public void syncCatalogIfChoiceNeeded(ServerPlayer maniac, ManiacArchetype archetype) {
        int limit = Math.min(ManiacConfigs.get(ConfigSchema.TRAPS_PER_MATCH),
                             ManiacArchetype.MAX_TRAP_SLOTS);
        List<TrapArchetype> all = archetype.traps();
        if (all.size() <= limit) return;

        List<String> ids = new ArrayList<>();
        for (TrapArchetype t : all) ids.add(t.id());
        ModNetwork.toPlayer(maniac, new TrapCatalogPacket(ids, limit));
    }

    /** Порожній набір — клієнт ховає панель і виходить із режиму розміщення. */
    private void clearLoadoutOnClient(List<ServerPlayer> players) {
        MatchOrchestrator m = match();
        if (m == null) return;
        for (ServerPlayer p : players) {
            if (m.isManiac(p.getUUID())) ModNetwork.toPlayer(p, TrapLoadoutPacket.empty());
        }
    }

    /** Що обрано: слот клавіші (0..2) → індекс у списку пасток архетипу. Для HUD. */
    public List<Integer> chosenSlots() {
        return List.copyOf(chosenSlots);
    }

    /** Пастка на клавіші слота, або null, якщо не обрана. */
    public TrapArchetype trapForSlot(int slot) {
        MatchOrchestrator m = match();
        if (m == null || slot < 0 || slot >= chosenSlots.size()) return null;
        ManiacArchetype archetype = m.maniacArchetype();
        return archetype == null ? null : archetype.trapAt(chosenSlots.get(slot));
    }

    // ── Розміщення ───────────────────────────────────────────────────────

    /**
     * Перевірка місця без розміщення — для червоного/зеленого квадрата.
     * Той самий шлях, що й реальне розміщення, тож клієнт ніколи не
     * покаже зелений там, де сервер відмовить.
     */
    public TrapArchetype.PlacementResult check(ServerPlayer maniac, int slot, BlockPos floor) {
        TrapArchetype type = trapForSlot(slot);
        if (type == null) return TrapArchetype.PlacementResult.UNAVAILABLE;
        if (slotCooldown[slot] > 0) return TrapArchetype.PlacementResult.UNAVAILABLE;

        ServerLevel level = maniac.serverLevel();
        TrapArchetype.PlacementResult shared = TrapPlacementRules.check(level, floor, maniac);
        if (!shared.ok()) return shared;

        TrapArchetype.PlacementResult own = type.validatePlacement(level, floor);
        if (!own.ok()) return own;

        if (isOccupied(level, floor)) return TrapArchetype.PlacementResult.OCCUPIED;
        return TrapArchetype.PlacementResult.OK;
    }

    /**
     * Маньяк підтвердив розміщення (ПКМ у режимі розміщення).
     *
     * Куди ставити, рахується ТУТ за поглядом маньяка, а не береться з
     * клієнта. Блок-підлога — той, у верхню грань якого він дивиться.
     *
     * @return результат: {@code OK} — поставлено; інше — причина відмови для повідомлення
     */
    public TrapArchetype.PlacementResult onPlaceConfirmed(ServerPlayer maniac, int slot) {
        MatchOrchestrator m = match();
        if (m == null || !m.isManiac(maniac.getUUID())) return TrapArchetype.PlacementResult.UNAVAILABLE;
        if (!m.phases().allows(PhaseRule.TRAPS)) return TrapArchetype.PlacementResult.UNAVAILABLE;

        BlockPos floor = TrapAiming.targetFloor(maniac.level(), maniac,
            ManiacConfigs.get(ConfigSchema.TRAP_PLACE_RANGE_BLOCKS));
        if (floor == null) return TrapArchetype.PlacementResult.TOO_FAR;

        TrapArchetype.PlacementResult result = check(maniac, slot, floor);
        if (!result.ok()) {
            notify(maniac, ActionBarMessageType.ERROR, "maniacmod.trap.place_" + result.name().toLowerCase());
            return result;
        }

        TrapArchetype type = trapForSlot(slot);
        Entity body = type.spawn(maniac.serverLevel(), floor, maniac);
        if (body == null) return TrapArchetype.PlacementResult.NO_ROOM_ABOVE;

        placed.put(body.getUUID(), new PlacedTrap(type, body.getUUID()));

        int cooldown = type.placementCooldownTicks();
        slotCooldown[slot] = cooldown;
        ModNetwork.toPlayer(maniac,
            new AbilityCooldownPacket(AbilityCooldownPacket.trapId(slot), cooldown));

        // Звук установки — з МІСЦЯ ПАСТКИ, а не з маньяка: чутно тому, хто
        // поруч, і саме там, де щось поставили. Ванільний позиційний
        // playSound, а не SoundCenter — подія серверна й має бути чутна
        // всім поблизу, а не лише тому, хто її викликав.
        maniac.serverLevel().playSound(null, floor,
            ModSounds.TRAP_PLACE.get(), SoundSource.BLOCKS, 1.0f, 1.0f);
        return TrapArchetype.PlacementResult.OK;
    }

    /** Чи на цьому блоці вже стоїть пастка (будь-якого типу). */
    private boolean isOccupied(ServerLevel level, BlockPos floor) {
        AABB box = new AABB(floor.above());
        for (Entity e : level.getEntities((Entity) null, box.inflate(0.3), x -> x instanceof BearTrapEntity)) {
            if (isRegistered(e)) return true;
        }
        return false;
    }

    // ── Реєстр сутностей ─────────────────────────────────────────────────

    /** Чи модуль знає цю сутність. Сирота (пережила матч) — ні, і сама себе прибирає. */
    public boolean isRegistered(Entity entity) {
        return placed.containsKey(entity.getUUID());
    }

    // ── Тік капкана (викликається з BearTrapEntity.tick) ─────────────────

    /**
     * Перевіряє, чи хтось наступив на цю пастку. Захлопнутий капкан
     * не спрацьовує знову, доки жертву не звільнено.
     */
    public void tickTrap(BearTrapEntity trap) {
        PlacedTrap info = placed.get(trap.getUUID());
        if (info == null) return;
        if (!match().phases().allows(PhaseRule.TRAPS)) return;
        if (trap.isSnapped()) {
            // Захлопнутий капкан не спрацьовує вдруге, але він не «мовчить»:
            // поки тримає жертву — вона смикається, і це чути.
            tickStruggle(trap);
            return;
        }

        AABB zone = trap.getBoundingBox();
        for (ServerPlayer candidate : trap.level().getEntitiesOfClass(ServerPlayer.class, zone)) {
            if (immunity.containsKey(candidate.getUUID())) continue;
            if (victims.containsKey(candidate.getUUID())) continue;
            // Лежачого пастка не ловить — і це перевіряється ОДИН раз тут,
            // на всі типи пасток, а не в кожній архетипі (див. canCatch).
            if (!canCatch(candidate)) continue;
            if (!info.type().shouldTrigger(candidate)) continue;
            trigger(trap, info, candidate);
            return;
        }
    }

    /**
     * Звук боротьби в захлопнутому капкані — раз на {@code struggleSoundTicks}
     * тіків, доки він когось тримає.
     *
     * ── Чому це тікається з сутності, а не з фази ────────────────────
     * Капкан і так тікає себе сам ({@code BearTrapEntity.tick} → {@link #tickTrap}),
     * тож звук приходить рівно тоді, коли капкан існує. Окремий тік у
     * {@code onPhaseTick} вимагав би пошуку сутності за UUID на кожен
     * тік — той самий результат дорожче й з зайвим станом у модулі.
     *
     * Смикання чути ВСІМ поблизу, а не лише жертві: це і є сенс звуку —
     * тімейт має почути, що когось узяло, навіть коли не бачить.
     */
    private void tickStruggle(BearTrapEntity trap) {
        if (struggleSoundTicks <= 0) return;

        int left = struggleCooldown.getOrDefault(trap.getUUID(), 0);
        if (left > 0) {
            struggleCooldown.put(trap.getUUID(), left - 1);
            return;
        }

        trap.level().playSound(null, trap.blockPosition(),
            ModSounds.TRAP_STRUGGLE.get(), SoundSource.BLOCKS, 1.0f, 1.0f);
        // -1, а не повна довжина: тік, у якому ми граємо, теж витрачається
        // на відлік, тож наступне програвання стане рівно за один період
        // від цього — без шва на стику двох файлів.
        struggleCooldown.put(trap.getUUID(), Math.max(1, struggleSoundTicks - 1));
    }

    /**
     * Чи може пастка ЗАХЛОПНУТИ цього гравця: живий виживий на ногах.
     *
     * ── Чому лежачих відсікаємо тут, а не в архетипах ────────────────
     * Правило спільне для ВСІХ пасток (і для майбутніх): пастка ловить
     * того, хто НАСТУПАЄ на неї. Гравець, що вже лежить (повзе чи без
     * свідомості), фізично не робить кроку — капкан під ним спрацював би
     * вже в момент падіння, і лежачий у капкані означав би «збитий з ніг
     * ще раз, без жодної спроби вирватися». Термінальний стан (вибув чи
     * втік) — теж ні: він уже не бореться за життя.
     *
     * Архетип пастки може додати СВОЇ умови ({@link TrapArchetype#shouldTrigger}),
     * але обійти це правило не може — воно стоїть вище в
     * {@link #tickTrap}.
     */
    private boolean canCatch(ServerPlayer candidate) {
        MatchOrchestrator m = match();
        if (m == null || !m.isSurvivor(candidate.getUUID())) return false;
        return standsUp(m.survivorStateOf(candidate.getUUID()));
    }

    /** Чи стан гравця дозволяє тримати його в пастці (див. {@link #canCatch}). */
    private static boolean standsUp(SurvivorState state) {
        return state != null && !state.isCrawlOnly() && !state.isTerminal();
    }

    private void trigger(BearTrapEntity trap, PlacedTrap info, ServerPlayer victim) {
        trap.setSnapped(true);
        victims.put(victim.getUUID(), trap.getUUID());
        // Клацок капкана чути з його місця — це головний звук-підказка
        // для тих, хто ще не бачить, що сталося. Боротьба почнеться після
        // нього, а не в один тік із ним (див. tickStruggle).
        trap.level().playSound(null, trap.blockPosition(),
            ModSounds.TRAP_SNAP.get(), SoundSource.BLOCKS, 1.0f, 1.0f);
        struggleCooldown.put(trap.getUUID(), Math.max(1, struggleSoundTicks));

        // Жертву притягуємо до центру капкана: нога має опинитись «в зубах»,
        // а не збоку від моделі.
        victim.teleportTo(trap.getX(), trap.getY(), trap.getZ());
        ManiacMod.lib().interactionLockModule()
            .lock(victim.getUUID(), LockType.MOVEMENT, LOCK_REASON);

        info.type().applyEffect(victim, trap.blockPosition());

        // Форс-знімок HUD одразу, а не на наступному throttled тіку:
        // клієнтський блок руху (MixinKeyboardInputTrapped) читає поле
        // trapped із SurvivorVitalsPacket і має спрацювати в той самий
        // момент, що серверний лок, інакше жертва встигає на кадр-два
        // зрушити з місця до прильоту пакета.
        MatchOrchestrator m = match();
        if (m != null) m.survivors().forceVitalsRefresh(victim);

        // А вже потім — міні-гра: клієнт вибирає заголовок екрана за
        // власним станом «я в пастці», тож знімок HUD мусить прийти
        // раніше за пакет відкриття (інакше на секунду блимнув би
        // заголовок генератора).
        startEscapeMinigame(victim, trap);
    }

    // ── Саморятунок: міні-гра «попади в ціль» ─────────────────────────────

    /**
     * Відкриває жертві спробу вибратися з капкана.
     *
     * Спільна з генератором геометрія ({@link TargetMinigameSpec}), але
     * СВОЇ значення з конфігу — капкан і ремонт настроюються окремо.
     * Ціль не по центру й не впритул до країв: та сама причина, що в
     * генератора — повзунок мусить мати місце пробігти повз неї, інакше
     * вона була б «безкоштовною» на розвороті хвилі.
     */
    private void startEscapeMinigame(ServerPlayer victim, BearTrapEntity trap) {
        double cursorSpeed = ManiacConfigs.get(ConfigSchema.TRAP_ESCAPE_CURSOR_SPEED);
        double hitZoneWidth = ManiacConfigs.get(ConfigSchema.TRAP_ESCAPE_HIT_ZONE_WIDTH);
        int hitsRequired = ManiacConfigs.get(ConfigSchema.TRAP_ESCAPE_HITS_REQUIRED);
        double targetPosition = 0.15 + rng.nextDouble() * 0.70;

        TargetMinigameSpec spec = new TargetMinigameSpec(
            rng.nextLong(), cursorSpeed, hitZoneWidth, targetPosition, hitsRequired);
        escapes.put(victim.getUUID(), new TrapEscapeMinigame(trap.getUUID(), spec,
            ManiacConfigs.get(ConfigSchema.TRAP_ESCAPE_MISS_PENALTY),
            ManiacConfigs.get(ConfigSchema.TRAP_ESCAPE_TICKS)));

        // Клієнт тримає ЛИШЕ ОДИН екран міні-гри. Якщо в гравця була
        // відкрита міні-гра ремонта генератора, її треба закрити ДО
        // відкриття цієї: інакше пізніший «результат» генератора закрив
        // би вже екран капкана, і гравець лишився б у пастці без нього.
        MatchOrchestrator m = match();
        if (m != null) m.generatorModule().abandonMinigame(victim);

        ModNetwork.toPlayer(victim, new TargetMinigameOpenPacket(
            trap.blockPosition(), spec.seed(), spec.cursorSpeed(), spec.hitZoneWidth(),
            spec.targetPosition(), spec.hitsRequired()));
    }

    /**
     * Клік у міні-грі визволення — приходить із {@code ServerPacketHandler}
     * (той самий пакет кліку, що й у міні-гри генератора: на клієнті все
     * одно відкритий лише один екран).
     *
     * @return true, якщо клік належав спробі визволення цього гравця —
     *         тоді викликач більше нічого не пробує; false — у гравця
     *         жодної спроби немає
     */
    public boolean onEscapeAttempt(ServerPlayer player, double cursorPosition) {
        TrapEscapeMinigame game = escapes.get(player.getUUID());
        if (game == null) return false;

        // Допуск затримки мережі — спільний на всі міні-гри з повзунком:
        // це властивість КАНАЛУ, а не механіки, тож окремий ключ лише
        // розійшовся б із генераторним без причини.
        switch (game.attempt(cursorPosition,
                ManiacConfigs.get(ConfigSchema.TARGET_MINIGAME_LAG_TOLERANCE_MS))) {
            case HIT, MISS -> ModNetwork.toPlayer(player,
                RepairMinigameProgressPacket.attempt(game.hits()));
            case ESCAPED -> {
                escapes.remove(player.getUUID());
                ModNetwork.toPlayer(player, new RepairMinigameResultPacket(true));
                escapeSuccess(player, game);
            }
        }
        return true;
    }

    /**
     * Гравець вибрався з капкана сам — той самий наслідок, що удар ломом
     * по капкану: лок знято, капкан розкритий і прибраний.
     * Імунітет тут потрібен так само: гравець стоїть НА тому місці, де
     * щойно був капкан.
     */
    private void escapeSuccess(ServerPlayer victim, TrapEscapeMinigame game) {
        BearTrapEntity trap = trapEntity(game.trapId(), victim);
        if (trap != null) {
            release(victim, trap);
            trap.discard();
            placed.remove(trap.getUUID());
            struggleCooldown.remove(trap.getUUID());
        }
        notify(victim, ActionBarMessageType.SUCCESS, "maniacmod.trap.escape_success");
    }

    /**
     * Раз на тік для кожної активної спроби визволення. Час вийшов —
     * екран закривається, гравець лишається в капкані (спроба
     * витрачена, чекає на товариша з ломом). Зниклих чи невиживих
     * закриває {@link #releaseInvalidVictims} — тут вони лише
     * прибираються без пакета.
     */
    private void tickEscapes(List<ServerPlayer> players) {
        for (Map.Entry<UUID, TrapEscapeMinigame> entry : new ArrayList<>(escapes.entrySet())) {
            ServerPlayer player = onlinePlayer(entry.getKey());
            if (player == null) {
                escapes.remove(entry.getKey());
                continue;
            }

            TrapEscapeMinigame game = entry.getValue();
            game.tick();
            if (!game.isExpired()) continue;

            escapes.remove(entry.getKey());
            ModNetwork.toPlayer(player, new RepairMinigameResultPacket(false));
            notify(player, ActionBarMessageType.COOLDOWN, "maniacmod.trap.escape_timeout");
        }
    }

    /** Закриває спробу визволення цього гравця, якщо вона є (напр. товариш зірвав капкан ломом). */
    private void closeEscape(ServerPlayer player) {
        if (escapes.remove(player.getUUID()) == null) return;
        ModNetwork.toPlayer(player, new RepairMinigameResultPacket(false));
    }

    /**
     * Закриває УСІ відкриті спроби — вихід із ігрової фази чи кінець
     * матчу. Без цього екран міні-гри лишився б висіти на екрані гравця
     * (сервер уже нічого не тікає, отже й не надішле результат).
     */
    private void closeAllEscapes(List<ServerPlayer> players) {
        for (UUID id : new ArrayList<>(escapes.keySet())) {
            ServerPlayer player = onlinePlayer(id);
            if (player != null) ModNetwork.toPlayer(player, new RepairMinigameResultPacket(false));
        }
        escapes.clear();
    }

    /** Сутність капкана за UUID, якщо вона зараз завантажена у світі гравця. */
    private BearTrapEntity trapEntity(UUID trapId, ServerPlayer context) {
        if (trapId == null || context == null) return null;
        if (!(context.level() instanceof ServerLevel level)) return null;
        return level.getEntities().get(trapId) instanceof BearTrapEntity trap ? trap : null;
    }

    // ── Звільнення ───────────────────────────────────────────────────────

    /**
     * Виживий вдарив по капкану ломом (ЛКМ). Викликається з хука
     * {@code AttackEntityEvent}.
     *
     * Що робить удар залежить від стану капкана: якщо він захлопнув
     * жертву — звільняє її; якщо порожній — просто знешкоджує (зникає).
     * Це і «звільнити себе/тімейта», і «позбутись, поки не закрився».
     *
     * ── Хто платить за удар ──────────────────────────────────────────
     * Міцність і перезарядку списує САМЕ ЦЕЙ метод, і лише коли удар
     * зараховано: промах повз дальність чи удар ломом на перезарядці
     * нічого не коштує. Викликач (хук) лише передає лом із руки.
     *
     * @return true, якщо удар зараховано — хук тоді скасовує ванільний
     *         {@code attack}; false — не наш випадок, подію не чіпаємо
     */
    public boolean onCrowbarHit(ServerPlayer hitter, BearTrapEntity trap, net.minecraft.world.item.ItemStack crowbar) {
        if (!(crowbar.getItem() instanceof com.log_to_kot.maniacmod.items.CrowbarItem)) return false;
        if (!placed.containsKey(trap.getUUID())) return false;
        MatchOrchestrator m = match();
        if (m == null || !m.phases().allows(PhaseRule.TRAPS)) return false;
        if (!m.isSurvivor(hitter.getUUID())) return false;

        // Зламаний лом і лом на перезарядці не працюють. Повідомлення
        // даємо тут: гравець має зрозуміти, ЧОМУ клік нічого не зробив.
        if (com.log_to_kot.maniacmod.items.CrowbarItem.isBroken(crowbar)) {
            notify(hitter, ActionBarMessageType.ERROR, "maniacmod.trap.crowbar_broken");
            return true;
        }
        if (hitter.getCooldowns().isOnCooldown(crowbar.getItem())) {
            notify(hitter, ActionBarMessageType.COOLDOWN, "maniacmod.trap.crowbar_cooldown");
            return true;
        }

        double range = ManiacConfigs.get(ConfigSchema.CROWBAR_RANGE_BLOCKS);
        if (hitter.getEyePosition().distanceToSqr(trap.position().add(0, 0.2, 0)) > range * range) return false;

        // Удар зараховано (дальність, перезарядка й міцність уже
        // перевірені) — звук із місця капкана, чутний обом сторонам.
        hitter.level().playSound(null, trap.blockPosition(),
            ModSounds.CROWBAR_HIT.get(), SoundSource.PLAYERS, 1.0f, 1.0f);

        // "Жива" анімація удару тілом гравця (playerlib) замість
        // GeckoLib-тригера на самому предметі — див. CrowbarItem
        // клас-докстрінг щодо синхронізації руки/предмета.
        com.log_to_kot.maniacmod.items.CrowbarItem.playHitLive(hitter);

        UUID trappedId = victimOf(trap.getUUID());
        if (trappedId != null) {
            ServerPlayer victim = onlinePlayer(trappedId);
            if (victim != null) release(victim, trap);
        }
        trap.discard();
        placed.remove(trap.getUUID());
        struggleCooldown.remove(trap.getUUID());

        int left = com.log_to_kot.maniacmod.items.CrowbarItem.wear(crowbar);
        hitter.getCooldowns().addCooldown(crowbar.getItem(),
            ManiacConfigs.get(ConfigSchema.CROWBAR_COOLDOWN_TICKS));
        notify(hitter, left > 0 ? ActionBarMessageType.INFO : ActionBarMessageType.ERROR,
            left > 0 ? "maniacmod.trap.crowbar_used" : "maniacmod.trap.crowbar_broke",
            String.valueOf(left));
        return true;
    }

    /** Знімає лок із жертви, дає короткий імунітет. Сам капкан лишається на розсуд викликача. */
    private void release(ServerPlayer victim, BearTrapEntity trap) {
        victims.remove(victim.getUUID());
        // Спроба визволення, якщо була, теж закінчена: лок знято, екран
        // має закритись (це шлях звільнення ломом; успіх міні-гри шле свій
        // результат ДО виклику release).
        closeEscape(victim);
        ManiacMod.lib().interactionLockModule()
            .unlock(victim.getUUID(), LockType.MOVEMENT, LOCK_REASON);
        immunity.put(victim.getUUID(), RELEASE_IMMUNITY_TICKS);

        // Той самий форс-знімок, що в trigger(): trapped має злетіти на
        // клієнті миттєво, звільнений гравець рухається одразу після удару
        // ломом, а не через кадр-два.
        MatchOrchestrator m = match();
        if (m != null) m.survivors().forceVitalsRefresh(victim);
    }

    private UUID victimOf(UUID trapId) {
        for (Map.Entry<UUID, UUID> e : victims.entrySet()) {
            if (e.getValue().equals(trapId)) return e.getKey();
        }
        return null;
    }

    /**
     * Звільняє тих, кого капкан уже НЕ має тримати: тих, хто вийшов із
     * сервера, втратив роль виживого, або збитий з ніг / вибув.
     *
     * ── Чому лежачого капкан відпускає ─────────────────────────────────
     * Те саме правило, що {@link #canCatch}, лише з іншого боку: пастка
     * тримає того, хто СТОЇТЬ у ній. Гравець, що втратив свідомість,
     * лежить — тримати його «в зубах» означало б забороняти йому навіть
     * повзти, доки не скінчиться таймер смерті. Тому капкан
     * розкривається (знову бойовий, але без жертви), а лок знімається.
     * Імунітет НЕ виставляємо: лежачий і без нього не спрацьовує пастки.
     */
    private void releaseInvalidVictims(List<ServerPlayer> players) {
        MatchOrchestrator m = match();
        for (UUID id : new ArrayList<>(victims.keySet())) {
            ServerPlayer p = onlinePlayer(id);
            boolean stillTrapWorthy = p != null && m.isSurvivor(id)
                && standsUp(m.survivorStateOf(id));
            if (stillTrapWorthy) continue;

            UUID trapId = victims.remove(id);
            if (p == null) continue;

            ManiacMod.lib().interactionLockModule()
                .unlock(id, LockType.MOVEMENT, LOCK_REASON);
            closeEscape(p);

            BearTrapEntity trap = trapEntity(trapId, p);
            if (trap != null) trap.setSnapped(false);
        }
    }

    private void releaseEveryone(List<ServerPlayer> players) {
        for (UUID id : new ArrayList<>(victims.keySet())) {
            ManiacMod.lib().interactionLockModule().unlock(id, LockType.MOVEMENT, LOCK_REASON);
        }
        victims.clear();
    }

    /** Гравець вийшов із сервера — не лишаємо замок і запис у мапах. */
    public void onPlayerLeft(ServerPlayer player) {
        UUID id = player.getUUID();
        if (victims.remove(id) != null) {
            ManiacMod.lib().interactionLockModule().unlock(id, LockType.MOVEMENT, LOCK_REASON);
        }
        immunity.remove(id);
        // Спроба визволення померла разом із сесією: пакет шукати нема кому.
        escapes.remove(id);
    }

    // ── Запити ───────────────────────────────────────────────────────────

    /** Хто зараз у пастці. Для SurvivorModule: у пастці нога не гоїться. */
    public Set<UUID> victimIds() {
        return Set.copyOf(victims.keySet());
    }

    /** Чи гравець зараз у пастці. */
    public boolean isTrapped(UUID playerId) {
        return victims.containsKey(playerId);
    }

    // ── Тікання ──────────────────────────────────────────────────────────

    /** Коротке повідомлення гравцю (action bar). Ключі — у lang. */
    private void notify(ServerPlayer player, ActionBarMessageType type, String key, String... args) {
        ModNetwork.toPlayer(player, new ActionBarPacket(type, key, args));
    }

    private void tickSlotCooldowns(List<ServerPlayer> players) {
        for (int i = 0; i < slotCooldown.length; i++) {
            if (slotCooldown[i] > 0) slotCooldown[i]--;
        }
    }

    private void tickImmunity() {
        immunity.replaceAll((id, ticks) -> ticks - 1);
        immunity.values().removeIf(ticks -> ticks <= 0);
    }

    private ServerPlayer onlinePlayer(UUID id) {
        MatchOrchestrator m = match();
        return m == null ? null : m.onlinePlayer(id);
    }
}
