package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.maniacs.ManiacArchetype;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * Клавіші мода.
 *
 * ── Розкладка ────────────────────────────────────────────────────────
 * | Дія                       | Клавіша | Хто    | Звідки           |
 * |---------------------------|---------|--------|------------------|
 * | Удар                      | ЛКМ     | маньяк | ванільна         |
 * | Здібність 1 / 2 / 3       | 1 2 3   | маньяк | власні           |
 * | Пастка 1 / 2 / 3          | Z X C   | маньяк | власні           |
 * | Підсвітка генераторів     | 5       | виживі | власна           |
 * | Підвестися після падіння  | пробіл  | виживі | ванільна (jump)  |
 * | Підняти непритомного      | Shift   | виживі | ванільна (sneak) |
 * | Ремонт / залив / підбір   | ПКМ     | виживі | ванільна (use)   |
 * | Таб-екран (ростер)        | Tab     | усі    | ванільна (playerlist) |
 *
 * ── Чому цифри вільні ────────────────────────────────────────────────
 * У маньяка немає інвентаря взагалі (0 слотів hotbar), тому клавіші
 * 1–4 ванілі нема що вибирати — вони віддані здібностям. У виживого
 * слотів 4, тому вільна п'ята — вона й тримає підсвітку.
 *
 * Розподіл слотів робить {@code InventorySlotAllocation} з shaurma-lib:
 * він не дає вибрати заборонений слот, тому формальний конфлікт із
 * {@code key.hotbar.N} ні до чого не призводить.
 *
 * ⚠ Число здібностей і пасток — рівно по 3, бо клавіш рівно по три
 * (див. {@link ManiacArchetype#MAX_ABILITIES}). Це не налаштування:
 * четверту здібність нема на що повісити.
 *
 * ── Чому пробіл і Shift без власних KeyMapping ──────────────────────
 * Це навмисно ванільні клавіші. Власний KeyMapping дав би гравцю з
 * перепризначеним стрибком дві різні клавіші для «стрибнути» й
 * «встати».
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ManiacKeybinds {

    private static final String CATEGORY = "key.categories.maniacmod";

    /** Здібності: клавіші 1, 2, 3. Індекс масиву = номер слота. */
    public static final KeyMapping[] ABILITIES = {
        ability(1, GLFW.GLFW_KEY_1),
        ability(2, GLFW.GLFW_KEY_2),
        ability(3, GLFW.GLFW_KEY_3),
    };

    /** Пастки: клавіші Z, X, C. Індекс масиву = номер слота. */
    public static final KeyMapping[] TRAPS = {
        trap(1, GLFW.GLFW_KEY_Z),
        trap(2, GLFW.GLFW_KEY_X),
        trap(3, GLFW.GLFW_KEY_C),
    };

    /** Підсвітка генераторів та їхніх станів. */
    public static final KeyMapping HIGHLIGHT = mapping("highlight", GLFW.GLFW_KEY_5);

    private ManiacKeybinds() {}

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        for (KeyMapping key : ABILITIES) event.register(key);
        for (KeyMapping key : TRAPS) event.register(key);
        event.register(HIGHLIGHT);
    }

    // ── Ванільні клавіші, перевикористані модом ──────────────────────────

    /** Пробіл: підвестися після падіння. */
    public static boolean isStandUpDown() {
        return Minecraft.getInstance().options.keyJump.isDown();
    }

    /** Shift: утримання для підняття непритомного. */
    public static boolean isRescueHeld() {
        return Minecraft.getInstance().options.keyShift.isDown();
    }

    /** Tab: утримання для таб-екрану (живі/маньяк/спостерігачі). Ванільна клавіша списку гравців. */
    public static boolean isRosterHeld() {
        return Minecraft.getInstance().options.keyPlayerList.isDown();
    }

    // ── Внутрішнє ────────────────────────────────────────────────────────

    private static KeyMapping ability(int number, int glfwKey) {
        return mapping("ability_" + number, glfwKey);
    }

    private static KeyMapping trap(int number, int glfwKey) {
        return mapping("trap_" + number, glfwKey);
    }

    private static KeyMapping mapping(String name, int glfwKey) {
        return new KeyMapping("key.maniacmod." + name, KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM.getOrCreate(glfwKey), CATEGORY);
    }

    static {
        // Розкладка й межі архетипу мусять збігатися, інакше третя
        // здібність мовчки лишилась би без клавіші.
        if (ABILITIES.length != ManiacArchetype.MAX_ABILITIES
            || TRAPS.length != ManiacArchetype.MAX_TRAP_SLOTS) {
            throw new IllegalStateException(
                "Кількість клавіш не збігається з межами ManiacArchetype.");
        }
    }
}
