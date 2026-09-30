package com.log_to_kot.maniacmod.client.overlay.roster;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.maniacs.ManiacArchetype;
import com.log_to_kot.maniacmod.maniacs.ManiacVisuals;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoObjectRenderer;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * Малює geo-моделі в tab-екрані БЕЗ будь-якої сутності у світі.
 *
 * ── Навіщо ───────────────────────────────────────────────────────────
 * Раніше картка маньяка брала живого {@code Player} зі світу клієнта, а
 * картка завдання — найближчий завантажений {@code GeneratorEntity}. Обидва
 * шляхи ламались, коли сутності поруч нема, вона невидима або її гравець
 * поза зоною завантаження: на місці моделі з'являвся фолбек. Тепер
 * моделлю володіє САМ оверлей: він бере {@code .geo.json} + текстуру +
 * анімацію з ассетів (архетип маньяка / ресурси генератора) і малює їх
 * через {@link GeoObjectRenderer} — присутність чи невидимість
 * справжньої сутності на це більше не впливає.
 *
 * ── Відмовостійкість ─────────────────────────────────────────────────
 * Перший виняток з рендера вимикає geo-вʼюпорт до перезапуску гри
 * ({@link #available()}); картки тоді переходять на плоский фолбек,
 * Tab лишається робочим. Той самий принцип, що й у {@link TabModel3d}.
 */
@OnlyIn(Dist.CLIENT)
final class TabGeoView {

    private TabGeoView() {}

    private static boolean broken = false;

    /** Один рендерер+аниматабл на ресурс — GeckoLib кешує анімацію по аниматаблу. */
    private static final Map<String, Entry> ENTRIES = new HashMap<>();

    private static final ResourceLocation GENERATOR_MODEL =
        new ResourceLocation(ManiacMod.MOD_ID, "geo/entity/generator.geo.json");
    private static final ResourceLocation GENERATOR_TEXTURE =
        new ResourceLocation(ManiacMod.MOD_ID, "textures/entity/generator.png");
    private static final ResourceLocation GENERATOR_ANIMATION =
        new ResourceLocation(ManiacMod.MOD_ID, "animations/entity/generator.animation.json");

    static boolean available() {
        return !broken;
    }

    /**
     * Генератор: модель повільно обертається. Анімація та сама, що в світі
     * ({@code GeneratorEntity.registerControllers}): «running», коли
     * полагоджено, інакше «idle». Проміжних станів (бензин, вибух) у табі
     * немає — вони б видали прогрес, який гравцям не показуємо.
     */
    static boolean drawGenerator(GuiGraphics g, float cx, float baseY, float scale, float yawDeg,
                                 boolean repaired) {
        String loop = repaired ? "running" : "idle";
        Entry e = ENTRIES.computeIfAbsent("generator:" + loop, k -> new Entry(
            new Prop(GENERATOR_MODEL, GENERATOR_TEXTURE, GENERATOR_ANIMATION, loop)));
        return draw(g, e, cx, baseY, scale, yawDeg, 10f);
    }

    /** Маньяк заданого архетипу: анімація стояння з його набору анімацій. */
    static boolean drawManiac(GuiGraphics g, ManiacArchetype archetype,
                              float cx, float baseY, float scale, float yawDeg) {
        if (archetype == null) return false;
        Entry e = ENTRIES.computeIfAbsent("maniac:" + archetype.id(), k -> {
            ManiacVisuals v = archetype.visuals();
            return new Entry(new Prop(v.geoModel(), v.texture(), v.animations(), v.animationSet().idle()));
        });
        return draw(g, e, cx, baseY, scale, yawDeg, 5f);
    }

    // ── Рендер ───────────────────────────────────────────────────────────

    private static boolean draw(GuiGraphics g, Entry entry, float cx, float baseY,
                                float scale, float yawDeg, float pitchDeg) {
        if (broken) return false;

        Minecraft mc = Minecraft.getInstance();
        PoseStack pose = g.pose();
        Quaternionf flip = new Quaternionf().rotateZ((float) Math.PI);
        flip.mul(new Quaternionf().rotateX((float) Math.toRadians(pitchDeg)));

        boolean drawn = false;
        pose.pushPose();
        try {
            pose.translate(cx, baseY, 50.0);
            pose.mulPoseMatrix(new Matrix4f().scaling(scale, scale, -scale));
            pose.mulPose(flip);
            pose.mulPose(Axis.YP.rotationDegrees(yawDeg));
            // GeoObjectRenderer центрує модель як предмет (зсув +0.5/0.51/0.5):
            // сутнісні моделі мають початок у ногах, тож знімаємо зсув наперед.
            pose.translate(-0.5, -0.51, -0.5);

            Lighting.setupForEntityInInventory();
            entry.renderer.draw(pose, entry.prop, g.bufferSource(), 15728880, mc.getFrameTime());
            g.flush();
            drawn = true;
        } catch (RuntimeException | LinkageError failure) {
            broken = true;
            ManiacMod.LOGGER.warn("[tab] geo-вʼюпорт вимкнено до перезапуску: {}", failure.toString());
        } finally {
            pose.popPose();
            Lighting.setupFor3DItems();
        }
        RenderSystem.clear(256, Minecraft.ON_OSX);
        return drawn;
    }

    // ── Внутрішні типи ───────────────────────────────────────────────────

    private static final class Entry {
        final Prop prop;
        final Renderer renderer;

        Entry(Prop prop) {
            this.prop = prop;
            this.renderer = new Renderer(new PropModel());
        }
    }

    /** Мінімальний аниматабл: три ресурси + імʼя циклічної анімації. Жодної сутності. */
    private static final class Prop implements GeoAnimatable {
        final ResourceLocation model, texture, animations;
        final String loopName;
        private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

        Prop(ResourceLocation model, ResourceLocation texture, ResourceLocation animations, String loopName) {
            this.model = model;
            this.texture = texture;
            this.animations = animations;
            this.loopName = loopName;
        }

        @Override
        public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
            RawAnimation loop = RawAnimation.begin().thenLoop(loopName);
            controllers.add(new AnimationController<>(this, "tab_loop", 0,
                state -> state.setAndContinue(loop)));
        }

        @Override
        public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }

        /** Реальний час, а не ігровий тік: Tab живе й на паузі інтегрованого сервера. */
        @Override
        public double getTick(Object animatable) { return System.currentTimeMillis() / 50.0; }
    }

    private static final class PropModel extends GeoModel<Prop> {
        @Override public ResourceLocation getModelResource(Prop p)     { return p.model; }
        @Override public ResourceLocation getTextureResource(Prop p)   { return p.texture; }
        @Override public ResourceLocation getAnimationResource(Prop p) { return p.animations; }
    }

    private static final class Renderer extends GeoObjectRenderer<Prop> {
        Renderer(GeoModel<Prop> model) { super(model); }

        /**
         * Власний вхід замість {@code render(...)}: той при {@code buffer == null}
         * підставляє буфер світового рендера, який у GUI ніхто не скидає, — модель
         * лишилась би невидимою. Тут малюємо у буфер самого GuiGraphics.
         */
        void draw(PoseStack pose, Prop prop, net.minecraft.client.renderer.MultiBufferSource buffers,
                  int light, float partialTick) {
            this.animatable = prop;
            defaultRender(pose, prop, buffers, null, null, 0f, partialTick, light);
        }
    }
}
