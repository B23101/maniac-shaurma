package com.log_to_kot.maniacmod.client.renderer.maniac;

import com.log_to_kot.maniacmod.maniacs.ManiacArchetype;
import com.log_to_kot.maniacmod.maniacs.ManiacVisuals;
import com.log_to_kot.maniacmod.maniacs.TestManiacArchetype;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

/**
 * Гео-модель маньяка.
 *
 * ── Чому шляхи беруться ДИНАМІЧНО ────────────────────────────────────
 * Один рендерер обслуговує БУДЬ-ЯКОГО маньяка (модель замінює гравця, а
 * тип сутності в усіх один — {@code Player}). Тому шлях до асе́тів не
 * може бути константою класу: він читається з архетипу ГРАВЦЯ, який
 * зараз малюється ({@code ManiacAnimatable.archetype()}).
 *
 * ── Додати маньяка ───────────────────────────────────────────────────
 * Нічого тут правити не треба. Новий архетип повертає свої
 * {@link ManiacVisuals} (за замовчуванням — файли, названі його id), і
 * ця модель підхопить їх сама.
 *
 * ── Резервний шлях ───────────────────────────────────────────────────
 * GeckoLib може спитати ресурс до першого рендера (попередня компіляція
 * моделі). Тоді контексту ще немає — віддаємо асе́ти тестового маньяка,
 * щоб рушій мав валідний шлях і не падав на старті клієнта.
 *
 * ── Приховування голови від першої особи ─────────────────────────────
 * Маньяк — це модель ЛОКАЛЬНОГО гравця (той самий {@code Player}, що й
 * керує камерою), тож без цього хука він від першої особи бачив би
 * власну голову зсередини — велику текстуровану кулю перед очима.
 * Ваніль цього не показує (свою голову не рендерить нікому), але мод
 * на кастомний firstperson (див. запит користувача) змушує рушій
 * малювати повне тіло гравця, включно з головою, і без цього хука
 * голова маньяка лізла б у кадр так само, як звичайна голова гравця
 * лізла б без ванільного приховування.
 *
 * Ховаємо ЛИШЕ коли: (1) малюваний гравець — це той самий клієнт, що й
 * дивиться (інших маньяків та себе під час смерті/спектатора camera-мод
 * теж може «підставити», але тут дивимось не на тип камери, а на факт
 * «це я сам»), і (2) активна камера саме першої особи. Другий пункт
 * читається через {@code CameraType.isFirstPerson()} — це той самий
 * прапор, яким користується ваніль і будь-який firstperson-мод, що не
 * ламає інші системи (інвентар, дзеркала іменних табличок, снап-шоти):
 * підмінити спосіб РЕНДЕРА камери, не підмінивши цей прапор, для мода
 * немає сенсу, бо всі інші системи гри теж читають саме його.
 */
@OnlyIn(Dist.CLIENT)
public class ManiacGeoModel extends GeoModel<ManiacAnimatable> {

    @Override
    public ResourceLocation getModelResource(ManiacAnimatable animatable) {
        return visuals().geoModel();
    }

    @Override
    public ResourceLocation getTextureResource(ManiacAnimatable animatable) {
        return visuals().texture();
    }

    @Override
    public ResourceLocation getAnimationResource(ManiacAnimatable animatable) {
        return visuals().animations();
    }

    /** Імена кістки голови зустрічаються в різному регістрі залежно від
     * того, як автор моделі назвав її в Blockbench — шукаємо всі варіанти
     * підряд, а не змушуємо кожен .geo.json під один канон. */
    private static final String[] HEAD_BONE_NAMES = { "head", "Head", "HEAD" };

    /**
     * Хук GeckoLib «перед побудовою бон-структури цього кадру» — саме
     * тут прийнято ставити {@code GeoBone#setHidden}, бо викликається
     * щорендер, до малювання, і бачить того самого {@code animatable},
     * для якого зараз рендериться модель.
     */
    @Override
    public void setCustomAnimations(ManiacAnimatable animatable, long instanceId,
                                    AnimationState<ManiacAnimatable> state) {
        super.setCustomAnimations(animatable, instanceId, state);
        boolean hide = isOwnFirstPersonView();
        for (String name : HEAD_BONE_NAMES) {
            getBone(name).ifPresent(bone -> bone.setHidden(hide));
        }
    }

    /**
     * true — це САМ клієнт дивиться на власного маньяка від першої
     * особи (у ваніль-камері чи через сторонній firstperson-мод, який
     * підміняє {@link net.minecraft.client.CameraType}, а не сам факт
     * рендера).
     *
     * Порівняння САМЕ через {@code renderedPlayer() == mc.player}, а не
     * через {@code isLocalPlayer()} чи UUID: інших маньяків або себе,
     * побаченого збоку (спектатор, дзеркало, third-party камера, що
     * дивиться НА гравця, а не З нього) ця умова не зачіпає — голову їм
     * ховати не треба, адже дивиться на них хтось ІНШИЙ.
     */
    private static boolean isOwnFirstPersonView() {
        Player rendered = ManiacAnimatable.renderedPlayer();
        Minecraft mc = Minecraft.getInstance();
        return rendered != null
            && rendered == mc.player
            && mc.options.getCameraType().isFirstPerson();
    }

    private static ManiacVisuals visuals() {
        ManiacArchetype archetype = ManiacAnimatable.archetype();
        if (archetype != null) return archetype.visuals();
        return ManiacVisuals.of(TestManiacArchetype.ID);
    }
}
