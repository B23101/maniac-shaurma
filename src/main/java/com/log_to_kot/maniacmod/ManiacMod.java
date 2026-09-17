package com.log_to_kot.maniacmod;

import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.config.MapPointConfigs;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.match.MatchRuntimeRegistry;
import com.log_to_kot.maniacmod.core.match.MatchBlockRegistry;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.registry.ModBlocks;
import com.log_to_kot.maniacmod.registry.ModCreativeTab;
import com.log_to_kot.maniacmod.registry.ModEntityTypes;
import com.log_to_kot.maniacmod.registry.ModItems;
import com.log_to_kot.maniacmod.registry.ModSoundCues;
import com.log_to_kot.maniacmod.registry.ModSounds;
import com.log_to_kot.maniacmod.server.ServerHooks;
import dev.shaurmalib.common.damage.DamageInterceptorRegistry;
import dev.shaurmalib.common.lifecycle.DisconnectPolicy;
import dev.shaurmalib.common.lifecycle.JoinPolicy;
import dev.shaurmalib.common.lifecycle.MatchLifecycleState;
import dev.shaurmalib.common.sound.SoundCategoryRegistry;
import dev.shaurmalib.common.sound.SoundCueRegistry;
import dev.shaurmalib.forge.ShaurmaLib;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.bernie.geckolib.GeckoLib;

import java.nio.file.Path;

/**
 * Головний клас мода.
 *
 * ── Що змінилось відносно v3 ─────────────────────────────────────────
 * v3 реєстрував два обробники подій прямо в конструкторі:
 *   MinecraftForge.EVENT_BUS.register(new ServerEventHandler());
 *   MinecraftForge.EVENT_BUS.register(new ManiacGameManager());
 * Тобто менеджер матчу був ще й Forge-слухачем — і його статичний стан
 * жив довше за сервер. Тепер матч — звичайний об'єкт, створений на
 * старті сервера і знищений на його зупинці.
 *
 * ── Порядок ініціалізації (він важливий) ─────────────────────────────
 *   1. Конструктор @Mod — лише реєстри контенту (предмети, блоки,
 *      сутності, звуки). Світу ще немає, конфіг читати нічим.
 *   2. FMLCommonSetupEvent — мережевий канал.
 *   3. ServerAboutToStartEvent — тут з'являється worldRoot, тому саме
 *      тут піднімається shaurma-lib, конфіг і сам матч.
 *   4. ServerStoppingEvent — матч знімається, статичні посилання
 *      звільняються (інакше наступний світ у тій самій JVM успадкує
 *      стан попереднього — v3-баг).
 */
@Mod(ManiacMod.MOD_ID)
public class ManiacMod {

    public static final String MOD_ID = "maniacmod";
    public static final Logger LOGGER = LogManager.getLogger("ManiacMod");

    /** Ідентифікатор scoreboard-команди для приховування нікнеймів у лобі. */
    private static final String NAMETAG_HIDE_TEAM = "maniac_nametag_hide";

    private static ShaurmaLib.Handle lib;
    private static MatchOrchestrator match;

    private final IEventBus modEventBus;

    public ManiacMod() {
        modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // ── Контент. Нічого, крім реєстрації: жодної логіки в конструкторі ──
        ModItems.ITEMS.register(modEventBus);
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlocks.BLOCK_ITEMS.register(modEventBus);
        ModEntityTypes.ENTITY_TYPES.register(modEventBus);
        ModSounds.SOUNDS.register(modEventBus);
        ModCreativeTab.TABS.register(modEventBus);

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::onRegisterOverlays);

        // Серверний lifecycle — на forge-шині, не на mod-шині.
        MinecraftForge.EVENT_BUS.addListener(this::onServerAboutToStart);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
        MinecraftForge.EVENT_BUS.register(new ServerHooks());

        GeckoLib.initialize();
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(ModNetwork::register);
        LOGGER.info("[ManiacMod] мережевий канал зареєстровано");
    }

    /**
     * worldRoot доступний лише тут — тому весь ланцюжок shaurma-lib,
     * конфіг і матч піднімаються саме в цій точці, а не в конструкторі.
     */
    private void onServerAboutToStart(final ServerAboutToStartEvent event) {
        Path worldRoot = event.getServer()
            .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);

        match = new MatchOrchestrator();
        match.attachServer(event.getServer());
        MatchRuntimeRegistry.cleanup(event.getServer());
        MatchBlockRegistry.restoreAll(event.getServer());

        lib = ShaurmaLib.init(MOD_ID, modEventBus)
            .withConfig(worldRoot, MOD_ID, ManiacMod.class::getResourceAsStream)
            .withTeleport()
            // Заморозка гравців на CINEMATIC/SCATTER — замість ручного
            // скидання швидкості, як робив v3.
            .withPlayerFreeze()
            // Блокування дій за причиною: технічна фаза, перезарядка
            // удару, гравець у пастці, гравець непритомний. Кілька
            // причин одночасно не конфліктують і знімаються незалежно.
            .withInteractionLock()
            // Ванільний урон у цьому режимі не діє: шкода рахується
            // модулем удару. Гвардія блокує все решту одним реєстром
            // причин замість обробника на кожну причину.
            .withDamageGuard()
            .withLifecycle(ConfigSchema.MIN_PLAYERS.defaultValue())
            // Хто зайшов посеред матчу — глядач; хто повернувся після
            // виходу — теж, бо його роль уже віддано або завершена.
            .withPlayerLifecycle(JoinPolicy.SPECTATE, null,
                DisconnectPolicy.RESET_TO_SPECTATOR, null)
            .withLobby(NAMETAG_HIDE_TEAM, match::currentLobbySpawn)
            // Слоти інвентаря: 4 у виживого, 0 у маньяка. Саме це
            // звільняє клавіші 1-4 маньяку і 5 виживому.
            .withInventorySlotAllocation()
            // Дефолт вимкнений (active=false): без ролі (лобі, глядач)
            // стаміна нікому не потрібна взагалі. SurvivorModule
            // вмикає її явно (StaminaService.setRules з active=true
            // і реальними числами з survivors.yml) лише виживим на
            // ROLE_REVEAL і вимикає назад на виході з ігрової фази.
            .withStamina(dev.shaurmalib.forge.stamina.StaminaRules.builder()
                .active(false)
                .build())
            // Спостереження за живим гравцем після вибуття.
            .withSpectator()
            // Пози повзання й непритомності.
            .withPlayerAnim()
            // Камера вступного ролика (фаза CINEMATIC).
            .withFreeCamera()
            // Затемнення на переходах фаз.
            .withScreenEffects()
            // Анімації використання предметів.
            .withAnimatedItems()
            // Генератор — анімований GeckoLib-блок.
            .withAnimatedBlocks()
            .withSound()
            .withOverlays()
            .withActionBarMessages()
            .withAnimatedCountdown()
            .build();

        // Конфіг піднімається одразу після lib: усе, що йде нижче,
        // вже читає справжні значення, а не дефолти.
        ManiacConfigs.init(lib.configModule());
        MapPointConfigs.init(ManiacConfigs.namespaceDirectory());
        match.reloadConfiguredMap();

        // Звуки описуються один раз — далі будь-де досить SoundCenter.play(...).
        ModSoundCues.register();

        // Ванільний урон вимкнено на весь час матчу: шкоду наносить
        // лише удар маньяка через власний модуль. Одна причина в
        // реєстрі замість обробника LivingHurtEvent у моді.
        DamageInterceptorRegistry.register(MOD_ID + ":match_damage",
            (victim, source, amount, ctx) -> match != null && match.phases().isGameplay());

        // Фази дзеркаляться в грубий стан бібліотеки одним місцем.
        // v3 тримав для цього окремий enum GameState і switch — тепер
        // кожна фаза сама знає свій LifecycleMirror.
        match.attachLifecycle(state -> lib.lifecycleModule().lifecycleBus()
            .transitionTo(switch (state) {
                case IDLE   -> MatchLifecycleState.IDLE;
                case ACTIVE -> MatchLifecycleState.ACTIVE;
                case ENDING -> MatchLifecycleState.ENDING;
            }));

        // ── Реєстрація ігрових модулів ──────────────────────────────────
        // Кожен модуль — PhaseListener. Додати механіку = додати рядок
        // сюди; нічого іншого в моді не змінюється.
        // Генератори й удар маньяка реєструє сам MatchOrchestrator —
        // вони потрібні йому напряму (блок генератора, хук атаки).
        // match.registerModule(new TrapModule());
        // match.registerModule(new SurvivorModule());
        // match.registerModule(new LootModule());
        // match.registerModule(new CinematicModule());

        LOGGER.info("[ManiacMod] shaurma-lib підключено, матч готовий (фаза {})",
            match.phases().current());
    }

    /**
     * Знімаємо матч разом із сервером. Без цього статичне посилання в
     * Phases пережило б вихід у головне меню — і наступний світ у тій
     * самій JVM почав би з чужою фазою.
     */
    private void onServerStopping(final ServerStoppingEvent event) {
        if (match != null) {
            match.shutdown();
            match = null;
        }
        // Реєстри бібліотеки статичні — без очищення наступний світ у
        // тій самій JVM успадкував би причини блокування від попереднього.
        DamageInterceptorRegistry.clear();
        SoundCueRegistry.clear();
        SoundCategoryRegistry.clear();
        lib = null;
        LOGGER.info("[ManiacMod] матч знято, стан очищено");
    }

    private void onRegisterOverlays(final RegisterGuiOverlaysEvent event) {
        ShaurmaLib.attachOverlayEngine(event);
    }

    // ── Доступ ───────────────────────────────────────────────────────────

    /** Матч поточного сервера. null між зупинкою і наступним стартом. */
    public static MatchOrchestrator match() {
        return match;
    }

    /** Те саме, але з голосною помилкою — для коду, який без матчу не має сенсу. */
    public static MatchOrchestrator requireMatch() {
        if (match == null) throw new IllegalStateException(
            "Матч ще не створений — доступний лише після ServerAboutToStartEvent.");
        return match;
    }

    public static ShaurmaLib.Handle lib() {
        if (lib == null) throw new IllegalStateException(
            "ShaurmaLib.Handle ще не готовий — доступний лише після ServerAboutToStartEvent.");
        return lib;
    }
}
