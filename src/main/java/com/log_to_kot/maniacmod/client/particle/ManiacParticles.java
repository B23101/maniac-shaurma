package com.log_to_kot.maniacmod.client.particle;

import com.log_to_kot.maniacmod.registry.ModParticles;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;

/**
 * Провайдери кастомних частинок і їхні «характери».
 *
 * Хочете змінити ВИГЛЯД — підмініть PNG у {@code textures/particle/}.
 * Хочете змінити ПОВЕДІНКУ (довше живе, швидше падає, більша) — правте
 * {@link ManiacParticle.Profile} нижче.
 */
@OnlyIn(Dist.CLIENT)
public final class ManiacParticles {

    private ManiacParticles() {}

    //                                                        life   grav  size  fric  fade  shrink bright spin  anim
    /** Уламок кістки: розліт, падає, крутиться, гасне під кінець. */
    static final ManiacParticle.Profile BONE_SHARD =
        new ManiacParticle.Profile(18, 32, 0.55f, 0.10f, 0.94f, 0.6f, false, false, 0.25f, false);

    /** Спалах капкана: коротко, анімований, яскравий, стискається. */
    static final ManiacParticle.Profile TRAP_SNAP =
        new ManiacParticle.Profile(6, 9, 0.0f, 0.32f, 0.80f, 1.0f, true, true, 0.0f, true);

    /** Іскра дроту: дуже коротка, мерехтить кадрами, без гравітації. */
    static final ManiacParticle.Profile WIRE_SPARK =
        new ManiacParticle.Profile(5, 11, 0.06f, 0.16f, 0.88f, 0.5f, true, true, 0.0f, true);

    /** Зірочка оглушення: повільно здіймається, м'яко гасне. */
    static final ManiacParticle.Profile STUN_STAR =
        new ManiacParticle.Profile(20, 30, -0.02f, 0.20f, 0.96f, 0.5f, false, true, 0.10f, false);

    /**
     * Дим димової міни: ДУЖЕ ВЕЛИКИЙ (3+ блоки на частинку — вони перекриваються
     * й дають суцільну густу стіну), напівпрозорий,
     * майже без руху й гравітації; живе довго, гасне останні 35% життя.
     * Тривалість підібрана так, щоб видимий дим доживав і гас приблизно
     * тоді, коли закінчується сам газ ({@code TrapModule.SMOKE_NO_EMIT_TICKS}).
     */
    static final ManiacParticle.Profile SMOKE_CLOUD =
        new ManiacParticle.Profile(90, 130, 0.0f, 3.2f, 0.97f, 0.7f, false, false, 0.01f, false);

    public static void register(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(ModParticles.BONE_SHARD.get(), sprites -> provider(sprites, BONE_SHARD));
        event.registerSpriteSet(ModParticles.TRAP_SNAP.get(),  sprites -> provider(sprites, TRAP_SNAP));
        event.registerSpriteSet(ModParticles.WIRE_SPARK.get(), sprites -> provider(sprites, WIRE_SPARK));
        event.registerSpriteSet(ModParticles.STUN_STAR.get(),  sprites -> provider(sprites, STUN_STAR));
        event.registerSpriteSet(ModParticles.SMOKE_CLOUD.get(), sprites -> provider(sprites, SMOKE_CLOUD));
    }

    private static ParticleProvider<SimpleParticleType> provider(SpriteSet sprites, ManiacParticle.Profile profile) {
        return (type, level, x, y, z, xd, yd, zd) ->
            new ManiacParticle(level, x, y, z, xd, yd, zd, sprites, profile);
    }
}
