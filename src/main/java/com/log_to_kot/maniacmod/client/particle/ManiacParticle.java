package com.log_to_kot.maniacmod.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Одна клієнтська частинка мода, «характер» якої задає {@link Profile}.
 * Уся поведінка (тривалість, гравітація, згасання, анімація кадрів)
 * — параметри профілю, тому нова частинка = новий профіль, а не новий клас.
 *
 * Кадри беруться з {@link SpriteSet} (файл {@code particles/<id>.json}):
 * якщо {@link Profile#animated()} — кадр змінюється з віком, інакше
 * береться один випадковий кадр на весь час життя.
 */
@OnlyIn(Dist.CLIENT)
public class ManiacParticle extends TextureSheetParticle {

    /**
     * @param minLife    мінімальна тривалість, тіки
     * @param maxLife    максимальна тривалість, тіки
     * @param gravity    гравітація (0 — висить, 1 — як ванільний блок)
     * @param size       розмір quad-а, блоки
     * @param friction   множник швидкості за тік (0.96 — помітно гальмує)
     * @param fadeStart  частка життя, з якої частинка починає гаснути (1.0 — не гасне)
     * @param shrink     чи зменшується до нуля до кінця життя
     * @param fullbright ігнорувати освітлення (іскри, зірки)
     * @param spin       швидкість обертання, радіан/тік (0 — без)
     * @param animated   чи міняти кадри з віком
     */
    public record Profile(int minLife, int maxLife, float gravity, float size, float friction,
                          float fadeStart, boolean shrink, boolean fullbright, float spin,
                          boolean animated) {}

    private final SpriteSet sprites;
    private final Profile profile;
    private final float baseSize;
    private final float spinPerTick;

    protected ManiacParticle(ClientLevel level, double x, double y, double z,
                             double xd, double yd, double zd,
                             SpriteSet sprites, Profile profile) {
        super(level, x, y, z);
        this.sprites = sprites;
        this.profile = profile;

        this.xd = xd;
        this.yd = yd;
        this.zd = zd;
        this.gravity = profile.gravity();
        this.friction = profile.friction();
        this.hasPhysics = profile.gravity() > 0f; // падаючі уламки відбиваються від землі
        this.lifetime = profile.minLife()
            + this.random.nextInt(Math.max(1, profile.maxLife() - profile.minLife() + 1));

        // Невеликий розкид розміру, щоб хмара не виглядала штампом.
        this.baseSize = profile.size() * (0.8f + this.random.nextFloat() * 0.4f);
        this.quadSize = baseSize;

        this.spinPerTick = profile.spin() == 0f ? 0f
            : (this.random.nextBoolean() ? 1f : -1f) * profile.spin() * (0.6f + this.random.nextFloat() * 0.8f);
        this.roll = this.oRoll = this.random.nextFloat() * (float) (Math.PI * 2);

        if (profile.animated()) {
            setSpriteFromAge(sprites);
        } else {
            pickSprite(sprites);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (removed) return;

        if (profile.animated()) setSpriteFromAge(sprites);

        this.oRoll = this.roll;
        this.roll += spinPerTick;

        float life = age / (float) lifetime;
        if (profile.shrink()) {
            this.quadSize = baseSize * (1f - life);
        }
        if (life > profile.fadeStart()) {
            float t = (life - profile.fadeStart()) / Math.max(0.0001f, 1f - profile.fadeStart());
            this.alpha = 1f - t;
        }
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Override
    protected int getLightColor(float partialTick) {
        return profile.fullbright() ? 0xF000F0 : super.getLightColor(partialTick);
    }
}
