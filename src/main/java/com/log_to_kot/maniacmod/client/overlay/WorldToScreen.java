package com.log_to_kot.maniacmod.client.overlay;

import com.log_to_kot.maniacmod.ManiacMod;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Проєкція точки світу на екран — для міток, що видно на БУДЬ-ЯКІЙ
 * відстані й крізь стіни (генератори, лежачі союзники).
 *
 * ── Навіщо один спільний клас ────────────────────────────────────────
 * Раніше ту саму математику (матриці камери з {@link RenderLevelStageEvent},
 * множення на позицію, розворот для точок за спиною, притискання до краю)
 * дублювали {@code GeneratorExplosionMarker} і {@code GeneratorHighlightMarker};
 * третя мітка була б третьою копією, яку легко розсинхронізувати. Тепер
 * матриці знімаються тут раз на кадр, а мітки лише просять {@link #project}.
 *
 * ── Чому проєкція, а не світова геометрія ────────────────────────────
 * Світова геометрія обрізається дальньою площиною камери, а сутності за
 * радіусом відстеження в клієнта взагалі немає. Проєкція позиції від цього
 * не залежить. Якщо точка в межах поля зору, але за краєм екрана, мітка
 * притискається до краю й вказує напрямок; якщо точка ПОЗАДУ камери
 * (поза полем зору) — {@link #project} повертає {@code null}, і мітка
 * просто не малюється цього кадру (див. докстрінг {@link #project}).
 *
 * ── Стадія ───────────────────────────────────────────────────────────
 * AFTER_PARTICLES: {@code PoseStack} уже містить поворот камери (проєкція
 * приходить окремо), а на екран ще нічого не намальовано поверх світу.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WorldToScreen {

    /** Точка на екрані в GUI-координатах (з урахуванням масштабу інтерфейсу). */
    public record Point(int x, int y) {}

    /** Проєкція × вид із останнього кадру світу. */
    private static final Matrix4f viewProjection = new Matrix4f();
    private static Vec3 cameraPos = Vec3.ZERO;
    private static boolean valid = false;

    private WorldToScreen() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        viewProjection.set(event.getProjectionMatrix()).mul(event.getPoseStack().last().pose());
        cameraPos = event.getCamera().getPosition();
        valid = true;
    }

    /**
     * Проєктує точку світу.
     *
     * @param edgeMargin відступ від краю екрана, до якого притискається мітка
     * @return точка на екрані, або {@code null}, якщо кадр світу ще не
     *         малювався, або точка ПОЗАДУ камери (за спиною гравець її
     *         не бачить і не має шансів побачити без повороту, тож
     *         показувати тут щось на краю екрана лише вводило б в оману).
     */
    public static Point project(double wx, double wy, double wz,
                                int screenWidth, int screenHeight, int edgeMargin) {
        if (!valid) return null;

        Vector4f clip = new Vector4f(
            (float) (wx - cameraPos.x), (float) (wy - cameraPos.y), (float) (wz - cameraPos.z), 1.0f);
        viewProjection.transform(clip);

        // clip.w — глибина точки вздовж напрямку погляду камери в
        // проєктивних координатах: >0 попереду, <0 позаду. Раніше "за
        // спиною" оброблялось дзеркальним розворотом координат
        // (ndcX/ndcY = -ndcX/-ndcY) у спробі показати "напрямок
        // повернутись" — але дзеркальна проєкція точки-позаду-камери НЕ
        // відповідає жодному реальному напрямку на екрані: та сама
        // світова точка позаду то зліва, то справа, то згори залежно від
        // того, як саме гравець повернувся, тому мітка хаотично
        // стрибала по краю замість плавно ковзати чи зникнути. Тепер
        // "позаду" означає просто "не показуємо" — щойно генератор
        // знову опиняється в межах ±90° від напрямку погляду (clip.w
        // переходить у додатні), мітка коректно з'являється й прилипає
        // до найближчого краю екрана.
        if (clip.w <= 1.0e-6f) return null;

        float ndcX = clip.x / clip.w;
        float ndcY = clip.y / clip.w;

        int sx = Math.round((ndcX * 0.5f + 0.5f) * screenWidth);
        int sy = Math.round((1.0f - (ndcY * 0.5f + 0.5f)) * screenHeight);
        sx = Math.max(edgeMargin, Math.min(screenWidth - edgeMargin, sx));
        sy = Math.max(edgeMargin, Math.min(screenHeight - edgeMargin, sy));
        return new Point(sx, sy);
    }

    /** Горизонтальна відстань від камери до точки, блоки (для підпису «12 м»). */
    public static double distanceXZ(double wx, double wz) {
        double dx = wx - cameraPos.x;
        double dz = wz - cameraPos.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Скидає стан кадру — після виходу з матчу матриці вже нічого не значать. */
    public static void invalidate() {
        valid = false;
    }
}
