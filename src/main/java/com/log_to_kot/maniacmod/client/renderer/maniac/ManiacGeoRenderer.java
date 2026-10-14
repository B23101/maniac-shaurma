package com.log_to_kot.maniacmod.client.renderer.maniac;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoReplacedEntityRenderer;

/**
 * Рендерер, який МАЛЮЄ гео-модель замість ванільної моделі гравця.
 *
 * ── Чому {@code GeoReplacedEntityRenderer}, а не {@code GeoEntityRenderer}
 * ─────────────────────────────────────────────────────────────────────
 * Маньяк — це ЗВИЧАЙНИЙ гравець: сутність {@code Player} не можна
 * зробити {@code GeoAnimatable} (це чужий клас). {@code GeoEntityRenderer}
 * вимагає, щоб сутність сама реалізовувала {@code GeoAnimatable}, тож
 * він тут непридатний. GeckoLib має окремий рендерер саме для цього
 * випадку — «сутність Mojang + зовнішній аниматабл»:
 * {@code GeoReplacedEntityRenderer<E, T>}. {@code E} — реальна сутність
 * ({@code Player}), {@code T} — наш {@link ManiacAnimatable}.
 *
 * ── Один екземпляр на клієнт ─────────────────────────────────────────
 * Рендерер створюється один раз (див. {@code ManiacRenderHandler.init})
 * і викликається вручну з {@code RenderPlayerEvent.Pre}: ванільний
 * рендер скасовується, а цей малює модель у тій самій позі/позиції.
 *
 * ── Голова дивиться туди, куди дивиться гравець ──────────────────────
 * Раніше голова маньяка була «приварена» до тулуба: ні GeckoLib, ні наш
 * аниматабл нічого не знали про поворот голови гравця (модель лише
 * крутилась тілом за {@code yBodyRot}). Тепер кістка {@code head}
 * отримує ДОДАТКОВИЙ поворот = (yaw голови − yaw тіла) по Y і pitch
 * гравця по X — як у ванільного гравця/моба.
 *
 * Чому в {@link #renderRecursively}, а не в {@code GeoModel.setCustomAnimations}:
 * GeckoLib викликає {@code setCustomAnimations} лише раз на «кадр анімації»,
 * а наш {@code ManiacAnimatable.getTick()} повертає ІГРОВИЙ тік (20 Гц) —
 * решту кадрів між тіками рушій вважає повторним рендером і хук пропускає
 * (той самий механізм, через який раніше «не ховалась» голова в першій
 * особі, див. {@code ManiacGeoModel#hideHeadIfFirstPerson}). Тут же поворот
 * додається навколо КОЖНОГО малювання кістки й одразу знімається, тож
 * не залежить від кешу анімацій і не накопичується.
 */
@OnlyIn(Dist.CLIENT)
public class ManiacGeoRenderer extends GeoReplacedEntityRenderer<Player, ManiacAnimatable> {

    /** Скільки градусів голова може повернутись від тіла по yaw (ваніль тягне тіло за головою після ~75°). */
    private static final float MAX_HEAD_YAW_DEG = 75f;
    /** Межі pitch голови, градуси (вгору — мінус, як у гравця). */
    private static final float MAX_HEAD_PITCH_DEG = 80f;
    /**
     * Знаки осей. Якщо ваша модель намальована в Blockbench із дзеркальними осями голови
     * й голова крутиться НАВПАКИ (вліво замість вправо / вгору замість вниз) — просто змініть
     * відповідну константу на -1f.
     */
    private static final float HEAD_YAW_SIGN = 1f;
    private static final float HEAD_PITCH_SIGN = 1f;

    public ManiacGeoRenderer(EntityRendererProvider.Context context) {
        super(context, new ManiacGeoModel(), new ManiacAnimatable());
        // Маньяк вищий і ширший за гравця — тінь має це відображати.
        this.shadowRadius = 0.7f;
    }

    @Override
    public void renderRecursively(PoseStack poseStack, ManiacAnimatable animatable, GeoBone bone,
                                  RenderType renderType, MultiBufferSource bufferSource,
                                  VertexConsumer buffer, boolean isReRender, float partialTick,
                                  int packedLight, int packedOverlay,
                                  float red, float green, float blue, float alpha) {
        Player player = ManiacAnimatable.renderedPlayer();
        if (player == null || !ManiacGeoModel.isHeadBone(bone.getName())) {
            super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer,
                isReRender, partialTick, packedLight, packedOverlay, red, green, blue, alpha);
            return;
        }

        float headYaw = Mth.rotLerp(partialTick, player.yHeadRotO, player.yHeadRot);
        float bodyYaw = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);
        float netYaw = Mth.clamp(Mth.wrapDegrees(headYaw - bodyYaw), -MAX_HEAD_YAW_DEG, MAX_HEAD_YAW_DEG);
        float pitch = Mth.clamp(Mth.lerp(partialTick, player.xRotO, player.getXRot()),
            -MAX_HEAD_PITCH_DEG, MAX_HEAD_PITCH_DEG);

        float rotX = bone.getRotX();
        float rotY = bone.getRotY();
        bone.setRotX(rotX + HEAD_PITCH_SIGN * pitch * Mth.DEG_TO_RAD);
        bone.setRotY(rotY + HEAD_YAW_SIGN * netYaw * Mth.DEG_TO_RAD);
        try {
            super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer,
                isReRender, partialTick, packedLight, packedOverlay, red, green, blue, alpha);
        } finally {
            // Baked-модель спільна з Tab і з наступним кадром — повертаємо як було.
            bone.setRotX(rotX);
            bone.setRotY(rotY);
        }
    }
}
