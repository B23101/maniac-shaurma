package com.log_to_kot.maniacmod.client.renderer.maniac;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
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
 */
@OnlyIn(Dist.CLIENT)
public class ManiacGeoRenderer extends GeoReplacedEntityRenderer<Player, ManiacAnimatable> {

    public ManiacGeoRenderer(EntityRendererProvider.Context context) {
        super(context, new ManiacGeoModel(), new ManiacAnimatable());
        // Маньяк вищий і ширший за гравця — тінь має це відображати.
        this.shadowRadius = 0.7f;
    }
}
