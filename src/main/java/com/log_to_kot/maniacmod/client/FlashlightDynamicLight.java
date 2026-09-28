package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.items.FlashlightItem;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Динамічне світло ліхтарика через API мода LambDynamicLights (у
 * мод-листі відомий гравцям як "Sodium Dynamic Lights" — Forge-порт
 * LambDynamicLights, оптимізований під Sodium/Embeddium, ті самі моди,
 * що вже стоять поруч у списку: {@code embeddium},
 * {@code sodiumdynamiclights}).
 * <p>
 * Замінює попередній ручний підхід (щотік форсоване підняття
 * {@code LightLayer.BLOCK} по сфері блоків навколо гравця через
 * {@code LightEngine.checkBlock} + {@code LevelRenderer.setBlocksDirty},
 * див. видалену логіку {@code FlashlightClientHandler#refreshLightIfMoved}) —
 * той підхід рахував і форсував тисячі блокових позицій щотік
 * (радіус 10 ⇒ ~4000+ позицій у сфері) і на кожному кроці позначав
 * чанки "брудними", змушуючи Sodium/Embeddium перебудовувати
 * рендер-секції набагато частіше, ніж потрібно. Хендлер
 * LambDynamicLights натомість лише повертає ЦІЛЬОВИЙ рівень
 * освітлення для сутності, а САМ рушій (уже сумісний із
 * Sodium/Embeddium "з коробки") вирішує, коли й що перебудувати.
 * <p>
 * ── Чому чиста рефлексія, без жодної compile-time залежності ─────────
 * Спроба підключити {@code compileOnly 'maven.modrinth:lambdynamiclights:...'}
 * не резолвиться в цьому середовищі складання (репозиторій/координати
 * недоступні), а жорстка залежність тут і не потрібна: весь сенс класу
 * — працювати однаково коректно й тоді, коли мода взагалі немає. Тому
 * тут НІДЕ немає {@code import dev.lambdaurora.lambdynlights.*} —
 * усі класи знаходяться через {@link Class#forName(String)}, метод
 * реєстрації викликається через {@link Method#invoke}, а сам
 * функціональний інтерфейс {@code DynamicLightHandler<T>} реалізується
 * динамічним {@link Proxy}, оскільки лямбду на інтерфейс, невідомий
 * компілятору, написати не можна. Якщо в майбутньому з'явиться робочий
 * maven-репозиторій з координатами артефакту, можна повернути
 * {@code compileOnly} заради зручності розробки (підказки IDE,
 * компайл-тайм перевірка сигнатур) — сам рантайм-контракт цього класу
 * від цього не зміниться.
 * <p>
 * Усі виклики огорнуті {@code try/catch(Throwable)}: якщо гравець не
 * має мода, або має несумісну версію API, клієнт просто лишається без
 * динамічного світла ліхтарика — ніколи не падає.
 */
public final class FlashlightDynamicLight {

    /** Luminance (0..15) гравця з ліхтариком у руці — той самий рівень, що й у {@code FlashlightItem} для факела/блока-максимуму (14 — не 15, щоб трохи відрізнялось від справжнього факела). */
    private static final int LIGHT_LEVEL = 14;

    private static final String HANDLERS_CLASS = "dev.lambdaurora.lambdynlights.api.DynamicLightHandlers";
    private static final String HANDLER_IFACE = "dev.lambdaurora.lambdynlights.api.DynamicLightHandler";

    /**
     * Modid-и, під якими може бути встановлений динамічний світло-мод.
     * {@code lambdynlights} — оригінальний LambDynamicLights;
     * {@code sodiumdynamiclights} — його Forge/NeoForge-форк (той, що
     * стоїть у списку гравця разом з Embeddium). Обидва тримають API в
     * тому самому пакеті {@code dev.lambdaurora.lambdynlights.api}, тож
     * код реєстрації нижче для них однаковий — різниця лише в modid,
     * який треба перевірити (раніше перевірявся ТІЛЬКИ
     * {@code lambdynlights}, тому у гравця з форком реєстрація ніколи не
     * виконувалась і ліхтарик не світив узагалі).
     */
    private static final String[] CANDIDATE_MODIDS = { "sodiumdynamiclights", "lambdynlights" };

    private static Boolean presentCache = null;
    private static boolean registered = false;

    private FlashlightDynamicLight() {}

    /** true, якщо будь-який із відомих dynamic-light модів встановлений. Кешується — modlist не міняється в рантаймі. */
    public static boolean isPresent() {
        if (presentCache == null) {
            boolean found = false;
            for (String modid : CANDIDATE_MODIDS) {
                if (ModList.get().isLoaded(modid)) {
                    found = true;
                    break;
                }
            }
            presentCache = found;
        }
        return presentCache;
    }

    /**
     * Реєструє хендлер один раз за життя клієнта — викликається лениво
     * з {@code FlashlightClientHandler#onClientTick}, щотік, поки не
     * зареєструється (чи не з'ясується, що API недоступне) — сама
     * перевірка {@link #isPresent()} дешева (кешоване поле).
     */
    public static void registerIfPresent() {
        if (registered || !isPresent()) return;
        registered = true;
        try {
            ClassLoader cl = FlashlightDynamicLight.class.getClassLoader();
            Class<?> handlersClass = Class.forName(HANDLERS_CLASS, true, cl);
            Class<?> handlerIface = Class.forName(HANDLER_IFACE, true, cl);

            Object handlerProxy = Proxy.newProxyInstance(
                cl,
                new Class<?>[]{handlerIface},
                new LuminanceInvocationHandler());

            Method register = handlersClass.getMethod(
                "registerDynamicLightHandler", EntityType.class, handlerIface);
            register.invoke(null, EntityType.PLAYER, handlerProxy);
        } catch (Throwable t) {
            // Мода немає, або несумісна версія API (метод/клас відсутній,
            // сигнатура інша тощо) — не валимо клієнта, просто без
            // динамічного світла. presentCache=false, щоб більше не
            // пробувати щотік марно ловити виняток.
            registered = false;
            presentCache = false;
        }
    }

    /** Рівень світла гравця = LIGHT_LEVEL, поки ліхтарик у руці, інакше 0 — рушій сам домішає це до вже наявних джерел (Math.max з іншими причинами світла). */
    private static int playerLuminance(Entity entity) {
        if (!(entity instanceof Player player)) return 0;
        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        boolean holding = FlashlightItem.isFlashlight(main) || FlashlightItem.isFlashlight(off);
        return holding ? LIGHT_LEVEL : 0;
    }

    /**
     * Реалізує {@code DynamicLightHandler<Player>} динамічно. Інтерфейс
     * мода (за докстрінгом API) має метод {@code getLuminance(T)} і,
     * можливо, {@code isWaterSensitive(T)} з дефолтною реалізацією —
     * тут не покладаємось на default-методи інтерфейсу (Proxy все одно
     * мусить сам обробити виклик, якщо метод абстрактний у конкретній
     * версії), тож явно відповідаємо на обидва за назвою методу.
     */
    private static final class LuminanceInvocationHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("getLuminance".equals(name) && args != null && args.length == 1 && args[0] instanceof Entity e) {
                return playerLuminance(e);
            }
            if ("isWaterSensitive".equals(name)) {
                return false;
            }
            // equals/hashCode/toString чи будь-що незнайоме з
            // default-методу інтерфейсу — безпечний no-op.
            Class<?> ret = method.getReturnType();
            if (ret == boolean.class) return false;
            if (ret == int.class) return 0;
            return null;
        }
    }
}
