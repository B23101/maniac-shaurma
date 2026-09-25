package com.log_to_kot.maniacmod.client.renderer.maniac;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.maniac.ClientManiacs;
import com.log_to_kot.maniacmod.maniacs.ManiacArchetype;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Підміна моделі гравця на гео-модель маньяка.
 *
 * ── Механізм ─────────────────────────────────────────────────────────
 * Ванільний рендер гравця ({@code PlayerRenderer}) скасовується у
 * {@code RenderPlayerEvent.Pre} і замінюється викликом нашого
 * {@link ManiacGeoRenderer} у ТІЙ САМІЙ позі, з тим самим стеком
 * трансформацій і освітленням. Це найменш інвазивний спосіб: жодних
 * міксинів у рендер-ланцюжок, жодних підмінених зареєстрованих
 * рендерерів для {@code EntityType.PLAYER} — лише «на цьому кадрі цього
 * гравця малюю не я».
 *
 * ── Чому контекст рендерера будується вручну ─────────────────────────
 * Forge не дає доступу до {@code EntityRendererProvider.Context} поза
 * подією реєстрації, а реєструвати рендерер для {@code EntityType.PLAYER}
 * не можна (він один на всіх гравців і потрібен ванілі для скінів). Тому
 * контекст збирається з already-існуючих клієнтських підсистем
 * ({@code Minecraft}) — усі вони на момент {@code FMLClientSetupEvent}
 * уже створені.
 *
 * ── Що НЕ зроблено (свідомо) ─────────────────────────────────────────
 *   • Іменні таблички: скасування {@code Pre} знімає й їх, а
 *     {@code renderNameTag} у рендерері — {@code protected}, тож
 *     викликати його ззовні не можна. Якщо таблички для маньяка
 *     знадобляться — це окремий шар ({@code GeoRenderLayer}).
 *   • Рука від першої особи: ваніль малює її окремим шляхом
 *     ({@code ItemInHandRenderer}). Для маньяка це окрема задача.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ManiacRenderHandler {

    private static ManiacGeoRenderer renderer;

    private ManiacRenderHandler() {}

    /** Викликається один раз із {@code ClientSetup.onRegisterRenderers}. */
    public static void init() {
        Minecraft mc = Minecraft.getInstance();
        EntityRendererProvider.Context context = new EntityRendererProvider.Context(
            mc.getEntityRenderDispatcher(),
            mc.getItemRenderer(),
            mc.getBlockRenderer(),
            mc.getEntityRenderDispatcher().getItemInHandRenderer(),
            mc.getResourceManager(),
            mc.getEntityModels(),
            mc.font);
        renderer = new ManiacGeoRenderer(context);
    }

    @SubscribeEvent
    public static void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
        if (renderer == null) return;

        Player player = event.getEntity();
        ManiacArchetype archetype = ClientManiacs.archetypeOf(player.getUUID());
        if (archetype == null) return; // звичайний гравець — ванільний рендер лишається

        event.setCanceled(true);

        float partialTick = event.getPartialTick();
        long gameTick = player.level().getGameTime();

        // Контекст анімації ставиться ДО рендера й знімається ПІСЛЯ:
        // весь ланцюг GeckoLib (модель, текстура, контролер) читає саме
        // його, і оскільки рендер послідовний, «поточний маньяк» завжди
        // відповідає тому, кого малюють цю мить.
        ManiacAnimatable.begin(player, archetype, gameTick);
        try {
            renderer.render(player, player.getViewYRot(partialTick), partialTick,
                event.getPoseStack(), event.getMultiBufferSource(), event.getPackedLight());
        } finally {
            ManiacAnimatable.end();
        }
    }
}
