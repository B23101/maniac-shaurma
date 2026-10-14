package com.log_to_kot.maniacmod.registry;

import com.log_to_kot.maniacmod.ManiacMod;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Кастомні частинки мода.
 *
 * ── Що потрібно для НОВОЇ частинки (три місця, не одне) ──────────────
 *   1. Константа тут (id = ім'я файлів нижче).
 *   2. {@code assets/maniacmod/particles/<id>.json} — список кадрів:
 *      {@code {"textures": ["maniacmod:<id>_0", "maniacmod:<id>_1", ...]}}.
 *   3. Кадри {@code assets/maniacmod/textures/particle/<id>_N.png}
 *      (будь-який квадрат: 8×8, 16×16, 32×32...).
 *   4. Провайдер у {@code client.particle.ManiacParticles} — там же
 *      «характер» частинки (тривалість, гравітація, розмір).
 * Без п. 2–3 частинка реєструється, але у грі це фіолетово-чорний
 * квадрат «missing texture».
 *
 * Сервер шле їх звичайним {@code ServerLevel#sendParticles}: тип
 * синхронізується реєстром, окремого пакета не треба.
 */
public final class ModParticles {

    public static final DeferredRegister<ParticleType<?>> PARTICLES =
        DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, ManiacMod.MOD_ID);

    /** Уламок кістки — перелом ноги. */
    public static final RegistryObject<SimpleParticleType> BONE_SHARD = simple("bone_shard");

    /** Спалах зубців капкана в момент захлопування. */
    public static final RegistryObject<SimpleParticleType> TRAP_SNAP = simple("trap_snap");

    /** Іскра електричного дроту. */
    public static final RegistryObject<SimpleParticleType> WIRE_SPARK = simple("wire_spark");

    /** Зірочка над оглушеним маньяком. */
    public static final RegistryObject<SimpleParticleType> STUN_STAR = simple("stun_star");

    /** Клуб диму димової міни — велика напівпрозора «завіса». */
    public static final RegistryObject<SimpleParticleType> SMOKE_CLOUD = simple("smoke_cloud");

    private ModParticles() {}

    private static RegistryObject<SimpleParticleType> simple(String id) {
        // overrideLimiter=false: звичайний ліміт відстані/кількості; наші
        // ефекти короткі й локальні, «далекого» показу не потребують
        // (де потрібно — сервер шле force=true у sendParticles).
        return PARTICLES.register(id, () -> new SimpleParticleType(false));
    }
}
