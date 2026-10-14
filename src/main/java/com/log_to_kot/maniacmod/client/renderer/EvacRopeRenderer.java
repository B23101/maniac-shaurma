package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.entity.ClimbCarrierEntity;
import com.log_to_kot.maniacmod.entity.EvacRopeEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Мотузка евакуації. Верхній кінець прикріплений до вертольота ({@code y + maxLength}), видима
 * частина росте вниз («кинули мотузку»). Нижній кінець висить на
 * {@link EvacRopeEntity#GROUND_CLEARANCE} над землею і ГОЙДАЄТЬСЯ — форму рахує
 * {@link EvacRopePhysics} (ланцюжок Верле). Кожен сегмент малюється тим самим
 * {@link ThreadRenderer}, що нитки пасток, тож текстура й товщина єдині.
 */
@OnlyIn(Dist.CLIENT)
public class EvacRopeRenderer extends EntityRenderer<EvacRopeEntity> {

    private static final ResourceLocation TEXTURE =
        new ResourceLocation(ManiacMod.MOD_ID, "textures/entity/evac_rope.png");
    private static final float THICKNESS = 0.12f;
    private static final double CLIMBER_SEARCH_RADIUS = 4.0;

    /** Фізика на сутність; Weak — щоб мотузка попереднього матчу не трималась у пам'яті. */
    private final Map<EvacRopeEntity, EvacRopePhysics> physics = new WeakHashMap<>();

    public EvacRopeRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(EvacRopeEntity rope, float yaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light) {
        float length = rope.drawnLength();
        if (length < 0.05f) return;

        double ex = Mth.lerp(partialTick, rope.xOld, rope.getX());
        double ey = Mth.lerp(partialTick, rope.yOld, rope.getY());
        double ez = Mth.lerp(partialTick, rope.zOld, rope.getZ());
        double ax = ex, ay = ey + rope.maxLength(), az = ez;       // точка кріплення до вертольота

        List<ClimbCarrierEntity> climbers = rope.level().getEntitiesOfClass(
            ClimbCarrierEntity.class,
            rope.getBoundingBox().inflate(CLIMBER_SEARCH_RADIUS, 0, CLIMBER_SEARCH_RADIUS));

        EvacRopePhysics sim = physics.computeIfAbsent(rope, r -> new EvacRopePhysics(r.getId()));
        sim.update(ax, ay, az, length, ey, rope.level().getGameTime() + partialTick, climbers);

        // Угорі, під гвинтом, світло ґрунтовне; а param `light` — це світло біля землі (під кроною
        // чи в тіні воно б затемнило всю мотузку). Беремо яскравіше з двох.
        int topLight = LevelRenderer.getLightColor(rope.level(), BlockPos.containing(ax, ay - 1, az));
        int lit = Math.max(light & 0xFFFF, topLight & 0xFFFF) | (Math.max(light >> 16, topLight >> 16) << 16);

        for (int i = 0; i < EvacRopePhysics.SEGMENTS; i++) {
            Vec3 from = new Vec3(sim.x[i] - ex, sim.y[i] - ey, sim.z[i] - ez);
            Vec3 to = new Vec3(sim.x[i + 1] - ex, sim.y[i + 1] - ey, sim.z[i + 1] - ez);
            ThreadRenderer.render(pose, buffers, TEXTURE, from, to, THICKNESS, lit);
        }
    }

    @Override
    public ResourceLocation getTextureLocation(EvacRopeEntity rope) { return TEXTURE; }

    @Override
    public boolean shouldRender(EvacRopeEntity rope, net.minecraft.client.renderer.culling.Frustum f,
                                double x, double y, double z) {
        return true;   // довга вертикальна сутність: ванільний AABB-куллінг її зрізав би
    }
}
