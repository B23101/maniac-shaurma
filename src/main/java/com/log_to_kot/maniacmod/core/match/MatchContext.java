package com.log_to_kot.maniacmod.core.match;

import com.log_to_kot.maniacmod.maniacs.ManiacArchetype;
import com.log_to_kot.maniacmod.map.MapManager;
import com.log_to_kot.maniacmod.spawn.SpawnPoint;
import com.log_to_kot.maniacmod.survivors.SurvivorRole;
import com.log_to_kot.maniacmod.survivors.SurvivorState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Увесь стан ОДНОГО матчу в одному об'єкті.
 *
 * ── Чому це найважливіший клас міграції ──────────────────────────────
 * У v3 весь цей стан був статичними полями ManiacGameManager:
 *   static UUID maniacUUID; static Map survivorDataMap;
 *   static List generators, exits, bearTraps, electricWires,
 *              ropeBinds, mines, itemZones, escapeZones; ...
 * Наслідки, які й давали більшість багів:
 *   1. resetGame() мусив вручну не забути очистити КОЖЕН список —
 *      і, наприклад, mines у startGame() справді не чистились,
 *      а corpses чистились у двох різних місцях по-різному.
 *   2. Будь-який клас, що хотів дізнатися одну річ (чи я маньяк),
 *      тягнув за собою весь менеджер із пастками й боєм.
 *   3. Стан переживав перезавантаження світу, бо статика живе
 *      стільки, скільки JVM.
 *
 * Тепер новий матч = НОВИЙ MatchContext. Скидання — це не двадцять
 * .clear(), а присвоєння нового об'єкта: забути щось фізично
 * неможливо.
 *
 * Клас навмисно "тупий": лише дані й тривіальні запити. Уся логіка
 * (хто кого вдарив, коли міняється фаза) — у MatchOrchestrator і в
 * модулях.
 *
 * ── Чому цей клас НЕ public ──────────────────────────────────────────
 * До версії з фасадом клас був public, і за кілька кроків міграції
 * `match.context()` почали викликати напряму з чотирьох різних
 * пакетів (blocks/, items/, server/) — кожен читав і писав ту саму
 * мапу здоров'я чи станів по-своєму. Це рівно той шлях, яким
 * "чиста" архітектура повертається до God Object: не через одну
 * велику помилку, а через двадцять маленьких прямих звернень, кожне
 * з яких виглядало виправданим у моменті.
 *
 * Тому клас package-private. Єдиний спосіб дістатись до матчу ззовні
 * пакету `core.match` — вузькі, іменовані за призначенням методи на
 * {@link MatchOrchestrator} (наприклад {@code isManiac(uuid)},
 * {@code healSurvivor(uuid, amount)}). Якщо для нової механіки не
 * вистачає методу на оркестраторі — це сигнал додати ЙОГО там, а не
 * пробивати приватність рефлексією чи переносити клас назад у public.
 * Див. `core/match/README.md` і кореневий `AI_CODE_GUIDE.md`.
 */
final class MatchContext {

    // ── Ролі ─────────────────────────────────────────────────────────────

    private UUID maniacUUID;
    private ManiacArchetype maniacArchetype;

    /** UUID виживого → його роль (зараз усі DEFAULT, структура готова до інших). */
    private final Map<UUID, SurvivorRole> survivorRoles = new LinkedHashMap<>();

    /** UUID виживого → поточний стан (HEALTHY / BROKEN_LEG / CRAWLING / UNCONSCIOUS). */
    private final Map<UUID, SurvivorState> survivorStates = new HashMap<>();

    /**
     * UUID виживого → здоров'я. Тримається тут, а не в SurvivorRole:
     * роль — це незмінна «картка характеристик», спільна для всіх
     * гравців з цією роллю; хп — стан конкретного гравця в конкретному
     * матчі. У v3 обидва жили в одному SurvivorData, тому змінити
     * характеристики ролі означало чіпати клас, який тримає стан.
     */
    private final Map<UUID, Integer> survivorHp = new HashMap<>();

    /** Хто вже втік — щоб не рахувати їх ні живими, ні мертвими. */
    private final List<UUID> escaped = new ArrayList<>();

    /** Хто вибув остаточно (маньяк добив непритомного). */
    private final List<UUID> eliminated = new ArrayList<>();

    /**
     * Ім'я й термінальний стан (ESCAPED/ELIMINATED) гравців, яких уже
     * прибрано з {@link #survivorRoles} — щоб таб і підсумковий екран
     * і далі бачили рядок "хто це був і чим скінчив", а не просто
     * зникле ім'я. {@code isSurvivor(uuid)} на них навмисно повертає
     * false: вони більше не беруть участь у ремонті/русі/урону.
     */
    private final Map<UUID, String> terminalDisplayNames = new LinkedHashMap<>();
    private final Map<UUID, SurvivorState> terminalStates = new HashMap<>();

    // ── Карта ────────────────────────────────────────────────────────────

    private final MapManager map = new MapManager();

    /** Розмітка точок (спавни, лут, генератори, виходи) цього матчу. */
    private final List<SpawnPoint> spawnPoints = new ArrayList<>();

    /** Кому яка точка дісталась — знадобиться для реконекту гравця. */
    private final Map<UUID, SpawnPoint> assignedSpawns = new HashMap<>();

    /** Що вже зроблено в матчі — на цьому тримаються переходи фаз. */
    private final MatchObjectives objectives = new MatchObjectives();

    public MatchObjectives objectives() { return objectives; }

    // ── Лічильники ───────────────────────────────────────────────────────

    private long matchTick = 0;

    // ── Ролі: запис ──────────────────────────────────────────────────────

    public void assignManiac(UUID uuid, ManiacArchetype archetype) {
        this.maniacUUID = uuid;
        this.maniacArchetype = archetype;
    }

    public void addSurvivor(UUID uuid, SurvivorRole role) {
        survivorRoles.put(uuid, role);
        survivorStates.put(uuid, SurvivorState.HEALTHY);
        survivorHp.put(uuid, role.maxHp());
    }

    public void setSurvivorState(UUID uuid, SurvivorState state) {
        if (survivorRoles.containsKey(uuid)) survivorStates.put(uuid, state);
    }

    public void markEscaped(UUID uuid, String displayName) {
        if (survivorRoles.remove(uuid) != null) {
            objectives.complete(MatchObjectives.Objective.SOMEONE_ESCAPED);
            survivorStates.remove(uuid);
            survivorHp.remove(uuid);
            escaped.add(uuid);
            terminalDisplayNames.put(uuid, displayName);
            terminalStates.put(uuid, SurvivorState.ESCAPED);
        }
    }

    /**
     * Маньяк добив непритомного. На відміну від {@code removeSurvivor}
     * (вихід із сервера) це остаточний ігровий підсумок — гравець
     * лишається в {@link #terminalDisplayNames}/{@link #terminalStates}
     * для табу й фіналу, а не просто зникає з мап.
     */
    public void markEliminated(UUID uuid, String displayName) {
        if (survivorRoles.remove(uuid) != null) {
            objectives.complete(MatchObjectives.Objective.FIRST_BLOOD);
            survivorStates.remove(uuid);
            survivorHp.remove(uuid);
            eliminated.add(uuid);
            terminalDisplayNames.put(uuid, displayName);
            terminalStates.put(uuid, SurvivorState.ELIMINATED);
        }
    }

    public void removeSurvivor(UUID uuid) {
        survivorRoles.remove(uuid);
        survivorStates.remove(uuid);
        survivorHp.remove(uuid);
    }

    // ── Здоров'я ─────────────────────────────────────────────────────────

    /** Поточне хп. -1, якщо гравець не виживий цього матчу. */
    public int hpOf(UUID uuid) {
        return survivorHp.getOrDefault(uuid, -1);
    }

    /** Максимум хп за роллю. -1, якщо гравець не виживий. */
    public int maxHpOf(UUID uuid) {
        SurvivorRole role = survivorRoles.get(uuid);
        return role == null ? -1 : role.maxHp();
    }

    /**
     * Лікує. Повертає, скільки хп реально відновлено (0, якщо вже
     * повне або гравець не виживий) — викликач за цим розуміє, чи
     * витрачати предмет.
     */
    public int heal(UUID uuid, int amount) {
        SurvivorRole role = survivorRoles.get(uuid);
        if (role == null || amount <= 0) return 0;

        int current = survivorHp.getOrDefault(uuid, 0);
        int healed = Math.min(role.maxHp(), current + amount) - current;
        if (healed > 0) survivorHp.put(uuid, current + healed);
        return healed;
    }

    /**
     * Знімає хп. Повертає true, якщо гравець дійшов до 0 — рішення про
     * непритомність приймає модуль виживих, не цей клас.
     */
    public boolean damage(UUID uuid, int amount) {
        if (!survivorRoles.containsKey(uuid) || amount <= 0) return false;

        int next = Math.max(0, survivorHp.getOrDefault(uuid, 0) - amount);
        survivorHp.put(uuid, next);
        return next == 0;
    }

    /** Знімає роль (маньяка або виживого) з гравця. Для дебаг-команди morph. */
    public void clearRole(UUID uuid) {
        if (uuid.equals(maniacUUID)) {
            maniacUUID = null;
            maniacArchetype = null;
        }
        survivorRoles.remove(uuid);
        survivorStates.remove(uuid);
        survivorHp.remove(uuid);
    }

    // ── Ролі: читання ────────────────────────────────────────────────────

    public boolean isManiac(UUID uuid)    { return maniacUUID != null && maniacUUID.equals(uuid); }
    public boolean isSurvivor(UUID uuid)  { return survivorRoles.containsKey(uuid); }
    public UUID maniacUUID()              { return maniacUUID; }
    public ManiacArchetype maniacArchetype() { return maniacArchetype; }

    public SurvivorRole roleOf(UUID uuid)   { return survivorRoles.get(uuid); }
    public SurvivorState stateOf(UUID uuid) { return survivorStates.get(uuid); }

    public List<UUID> survivorIds()       { return List.copyOf(survivorRoles.keySet()); }
    public List<UUID> escapedIds()        { return List.copyOf(escaped); }
    public List<UUID> eliminatedIds()     { return List.copyOf(eliminated); }
    public int aliveSurvivorCount()       { return survivorRoles.size(); }

    /** Усі, хто вибув остаточно — втекли або загинули (для табу/фіналу). */
    public List<UUID> terminalIds()       { return List.copyOf(terminalDisplayNames.keySet()); }

    /** Ім'я гравця, що вже пішов з {@link #survivorRoles} (втік/загинув). null, якщо такого немає. */
    public String terminalDisplayNameOf(UUID uuid) { return terminalDisplayNames.get(uuid); }

    /** ESCAPED або ELIMINATED для гравця, якого вже нема серед активних виживих. null, якщо такого немає. */
    public SurvivorState terminalStateOf(UUID uuid) { return terminalStates.get(uuid); }

    /** Скільки виживих ще здатні щось робити (не непритомні). */
    public int activeSurvivorCount() {
        return (int) survivorStates.values().stream()
            .filter(s -> s != SurvivorState.UNCONSCIOUS)
            .count();
    }

    // ── Карта ────────────────────────────────────────────────────────────

    public MapManager map()                    { return map; }
    public List<SpawnPoint> spawnPoints()      { return spawnPoints; }
    public void addSpawnPoint(SpawnPoint p)    { spawnPoints.add(p); }

    public void rememberSpawn(UUID uuid, SpawnPoint point) { assignedSpawns.put(uuid, point); }
    public SpawnPoint spawnOf(UUID uuid)                   { return assignedSpawns.get(uuid); }
    public void clearAssignedSpawns()                      { assignedSpawns.clear(); }

    // ── Час ──────────────────────────────────────────────────────────────

    public long matchTick()  { return matchTick; }
    public void tick()       { matchTick++; }
}
