package com.log_to_kot.maniacmod.client.anim;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.items.FlashlightItem;
import com.log_to_kot.maniacmod.vr.VrServerCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Кістки {@code right_item} / {@code left_item} Blockbench-поз: де стоїть, як повернутий і якого
 * розміру предмет у руці гравця (вид від третьої особи).
 *
 * <h3>Чому кістка предмета «взагалі не працювала»</h3>
 * Пози рук цього мода пишуться прямо в {@code PlayerModel.setupAnim} (див.
 * {@code MixinGeneratorWorkThirdPerson}, {@code MixinFlashlightHoldUpThirdPerson}) — не через PAL.
 * А кістки предмета читав лише PAL-міксин на {@code ItemInHandLayer}, до якого ці пози не доходять:
 * {@code right_item}/{@code left_item} з файлів ніхто не застосовував. Окрім того, самі файли були
 * зіпсовані:
 * <ul>
 *   <li>{@code generator_repair.json}: {@code right_item}/{@code left_item} зі <b>scale [0,0,0]</b> —
 *       нульовий масштаб ховав би предмет у руці;</li>
 *   <li>{@code flashlight_idle.json}: та сама кістка під двома іменами ({@code rightItem} і
 *       {@code right_item}); завантажувач брав останню й <b>перезаписував</b> позицію/нахил першої
 *       статичним {@code [0,4,12]} (12 px у бік плеча) — предмет їхав у обличчя.</li>
 * </ul>
 *
 * <h3>Що робить тепер</h3>
 * {@link #forHand} питає, який кліп зараз веде руку цього гравця (ремонт / залив генератора, ліхтарик
 * у руці), бере в нього кістку предмета потрібної руки й повертає зсув. Його додає до матриці
 * предмета {@code MixinHeldItemBones}. Правила:
 * <ul>
 *   <li><b>Відносно, а не абсолютно.</b> Зсув/поворот/масштаб додаються до ванільного положення
 *       предмета в кулаку. Кістка без треку = «не чіпати» (а не «обнулити»).</li>
 *   <li><b>Нульовий масштаб ігнорується</b> (компонент &lt; {@value #MIN_SCALE} → 1) — випадковий
 *       ключ scale 0 більше не може сховати предмет. Попередження про нього — в лозі при читанні.</li>
 *   <li>Осі — як у Blockbench для руки, що тримає предмет перед собою: x — вправо, y — вгору,
 *       z — НАЗАД (мінус z = вперед, як у позицій решти кісток), px. Поворот — градуси, порядок ZYX.</li>
 * </ul>
 * Нові пози з кістками предмета підключаються додаванням рядка в {@link #clipFor}.
 */
@OnlyIn(Dist.CLIENT)
public final class HeldItemBones {

    /** Менший масштаб компонента вважається помилкою й замінюється на 1. */
    public static final float MIN_SCALE = 0.05f;

    /** Готовий зсув предмета: px, градуси, множники. */
    public static final class Transform {
        public float px, py, pz;
        public float rx, ry, rz;
        public float sx = 1f, sy = 1f, sz = 1f;
        public boolean any;
    }

    private static final ResourceLocation REPAIR_FILE = file("generator_repair");
    private static final ResourceLocation REFUEL_FILE = file("generator_refuel");
    private static final ResourceLocation FLASHLIGHT_FILE = file("flashlight_idle");

    private static final Map<ResourceLocation, Optional<BoneClip>> CLIPS = new HashMap<>();
    private static final Transform OUT = new Transform();
    private static final float[] V = new float[3];

    private HeldItemBones() {}

    private static ResourceLocation file(String name) {
        return new ResourceLocation(ManiacMod.MOD_ID, "player_animations/" + name + ".json");
    }

    /** Який кліп зараз веде предмет у руці цього гравця; {@code null} — жоден. */
    private static ResourceLocation clipFor(Player player, ItemStack stack) {
        int action = ClientMatchState.generatorInteractionOf(player.getUUID());
        if (action == ClientMatchState.GENERATOR_ACTION_REPAIR) return REPAIR_FILE;
        if (action == ClientMatchState.GENERATOR_ACTION_REFUEL) return REFUEL_FILE;
        if (FlashlightItem.isFlashlight(stack)) return FLASHLIGHT_FILE;
        return null;
    }

    /**
     * Зсув предмета в цій руці цього гравця зараз, або {@code null}, якщо кліпи кістку не ведуть
     * (тоді предмет лежить у руці як у ванілі). Повертає ОДИН спільний об'єкт — не зберігайте його.
     */
    public static Transform forHand(Player player, HumanoidArm arm, ItemStack stack, float partialTick) {
        if (stack.isEmpty() || VrServerCompat.isVrPlayer(player)) return null;
        ResourceLocation file = clipFor(player, stack);
        if (file == null) return null;
        BoneClip clip = clip(file);
        if (clip == null) return null;

        String bone = arm == HumanoidArm.RIGHT ? "right_item" : "left_item";
        if (!clip.hasBone(bone)) return null;

        float t = (player.tickCount + partialTick) / 20f;
        Transform o = OUT;
        o.px = o.py = o.pz = o.rx = o.ry = o.rz = 0f;
        o.sx = o.sy = o.sz = 1f;
        o.any = false;

        if (clip.sample(bone, BoneClip.Channel.POSITION, t, V)) {
            o.px = V[0]; o.py = V[1]; o.pz = V[2];
            o.any = true;
        }
        if (clip.sample(bone, BoneClip.Channel.ROTATION, t, V)) {
            o.rx = V[0]; o.ry = V[1]; o.rz = V[2];
            o.any = true;
        }
        if (clip.sample(bone, BoneClip.Channel.SCALE, t, V)) {
            o.sx = safeScale(V[0]); o.sy = safeScale(V[1]); o.sz = safeScale(V[2]);
            o.any = true;
        }
        return o.any ? o : null;
    }

    private static float safeScale(float s) {
        return Math.abs(s) < MIN_SCALE ? 1f : s;
    }

    private static BoneClip clip(ResourceLocation file) {
        Optional<BoneClip> cached = CLIPS.get(file);
        if (cached != null) return cached.orElse(null);
        BoneClip loaded = null;
        try {
            Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(file);
            if (res.isPresent()) {
                try (Reader reader = res.get().openAsReader()) {
                    loaded = BoneClipLoader.load(reader, null,
                        w -> ManiacMod.LOGGER.warn("[ManiacMod] {}: {}", file.getPath(), w));
                }
            }
        } catch (IOException | RuntimeException e) {
            ManiacMod.LOGGER.error("[ManiacMod] не вдалося прочитати {}: {}", file, e.toString());
        }
        CLIPS.put(file, Optional.ofNullable(loaded));
        return loaded;
    }

    /** F3+T: перечитати файли поз без перезапуску гри. */
    @Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                            bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Reload {
        private Reload() {}

        @SubscribeEvent
        public static void onRegister(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((ResourceManagerReloadListener) rm -> CLIPS.clear());
        }
    }
}
