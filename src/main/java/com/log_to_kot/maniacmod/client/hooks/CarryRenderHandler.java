package com.log_to_kot.maniacmod.client.hooks;

import com.log_to_kot.maniacmod.ManiacMod;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Малює жертву НА ПЛЕЧІ маньяка: перед рендером моделі зсуває стек трансформацій так,
 * щоб таз жертви лежав на плечі носія (а лягає вона сама — позою carried_shoulder) (див. {@link CarryPose}). Рахується щокадру від інтерпольованої
 * позиції носія — тому жодних ривків, на відміну від серверного сидіння (20 Гц).
 *
 * <h3>Як не зламати стек</h3>
 * У {@code Pre} робимо {@code pushPose()} і ставимо {@code PENDING}; у {@code Post} — {@code popPose()}.
 * Якщо хтось скасував {@code Pre} (тоді {@code Post} не прийде), другий обробник із найнижчим
 * пріоритетом і {@code receiveCanceled} сам знімає наш push — стек ніколи не лишається зсунутим.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CarryRenderHandler {

    private static boolean pending = false;

    private CarryRenderHandler() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPre(RenderPlayerEvent.Pre event) {
        pending = false;
        Player victim = event.getEntity();
        Player carrier = CarryPose.carrierOf(victim);
        if (carrier == null) return;

        float pt = event.getPartialTick();
        CarryPose.Frame frame = CarryPose.compute(carrier, pt);

        // Тіло й голова — у напрямку носія (інтерпольовано в самому кадрі, не за тіками).
        victim.yBodyRot = frame.bodyYaw();
        victim.yBodyRotO = frame.bodyYaw();
        if (victim != Minecraft.getInstance().player) {
            victim.yHeadRot = frame.bodyYaw();
            victim.yHeadRotO = frame.bodyYaw();
        }

        // Стек зараз у ЛЕРП-позиції самої жертви (ноги). Переносимо ТАЗ на плече: корпус кладе
        // сама поза carried_shoulder (кістка body = -90° навколо стегон), тож жодного повороту тут.
        Vec3 own = victim.getPosition(pt);
        Vec3 shift = frame.anchor().subtract(own);
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(shift.x, shift.y - CarryPose.hipY(), shift.z);
        pending = true;
    }

    /** Pre скасовано кимось після нас — Post не буде, знімаємо свій push самі. */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onPreCanceled(RenderPlayerEvent.Pre event) {
        if (pending && event.isCanceled()) {
            event.getPoseStack().popPose();
            pending = false;
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPost(RenderPlayerEvent.Post event) {
        if (!pending) return;
        event.getPoseStack().popPose();
        pending = false;
    }

    /** Іменна табличка повернулась би разом з тілом — у несеного її не малюємо. */
    @SubscribeEvent
    public static void onNameTag(RenderNameTagEvent event) {
        if (event.getEntity() instanceof Player p && CarryPose.carrierOf(p) != null) {
            event.setResult(Event.Result.DENY);
        }
    }
}
