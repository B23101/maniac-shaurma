package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.c2s.intent.ManiacStrikePacket;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.AbilityCooldownPacket;
import com.log_to_kot.maniacmod.net.s2c.identity.RoleSyncPacket;
import com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ЛКМ маньяка: глушить замах під час перезарядки й коли поруч НЕМАЄ
 * кого бити, а коли є — шле {@link ManiacStrikePacket} серверу замість
 * покладання на ванільний удар по сутності.
 *
 * ── Навіщо міксин, якщо сервер і так лочить атаку ────────────────────
 * Сервер справді відхиляє удар через {@code LockType.ATTACK}
 * (shaurma-lib InteractionLock) — але це відбувається вже ПІСЛЯ того,
 * як клієнт програв замах руки і звук. Маньяк бачив би удар, якого не
 * сталося, і не розумів, чи влучив.
 *
 * Тому тут глушиться саме натискання: рука не рухається, звуку немає,
 * і перезарядка читається з поведінки, а не лише зі шкали HUD.
 *
 * ⚠ Це зручність, а не захист. Міксин живе на клієнті, і його можна
 * прибрати — удар усе одно не пройде, бо лок тримає сервер, і серверний
 * {@code ManiacCombatModule#findTarget} усе одно шукає жертву сам,
 * незалежно від того, що клієнт вирішив прислати. Ніколи не переносити
 * сюди перевірки, від яких залежить чесність гри.
 *
 * ── Чому цей метод ще й ШЛЕ пакет, а не лише глушить ─────────────────
 * {@code Minecraft.startAttack()} у ванілі б'є по сутності ТІЛЬКИ якщо
 * вона під прицілом у межах ванільного pick range (~3 блоки у
 * виживанні) — {@code mc.hitResult}, порахований раніше тим самим
 * тіком. Дальність архетипу маньяка (до 8 блоків у конфізі) із цим
 * ніяк не пов'язана: якщо вона більша за ванільний pick range, клієнт
 * просто ніколи не "бачить" ціль під прицілом на такій дистанції —
 * ванільний шлях мовчки не спрацьовує, і удар для далекої цілі
 * ніколи не стається, скільки не міняй конфіг. Тому для маньяка ЛКМ
 * означає "спробуй ударити" НЕЗАЛЕЖНО від {@code hitResult}: пакет
 * летить, коли клієнт САМ бачить жертву в конусі (нижче), а остаточне
 * рішення "хто саме постраждав" усе одно вирішує сервер —
 * {@code ManiacCombatModule#findTarget}. Див. докстрінг того класу.
 *
 * ── Чому клієнт теж перевіряє дальність, а не просто шле пакет завжди ─
 * Раніше кожен клік ЛКМ під час фази бою слав пакет і грав замах,
 * навіть якщо поруч не було жодного виживого — сервер мовчки відмовляв
 * ({@code findTarget} повертав null), а маньяк УЖЕ побачив анімацію
 * удару в порожнечу. Тепер {@link #maniacmod$hasTargetInRange} рахує
 * ТОЧНО ТОЙ САМИЙ конус (60°, дистанція архетипу, той самий поріг
 * впритул), яким на сервері оперує {@code ManiacCombatModule
 * #findTarget}, за даними, які клієнт і так має: позиції гравців зі
 * свого {@code ClientLevel} (вони й так відрендерені — прихованого
 * витоку немає) і ролі/стан із {@code RosterSyncPacket} (уже
 * синхронізований для tab-таблиці). Немає жертви в межах — немає ні
 * замаху, ні звуку, ні пакета; лишається лише кулдаун, коли він і так
 * триває.
 *
 * Розбіжність клієнт/сервер тут не проблема чесності: обидва боки
 * бачать ТІ САМІ позиції (сервер має точні, клієнт — з інтерполяцією
 * рендеру за останній тік), і жодна зі сторін не вирішує "чи завдано
 * шкоди" — це завжди робить сервер. Клієнтська перевірка лише вирішує
 * "чи варто програвати анімацію", тому невелика розбіжність через
 * затримку мережі не даватиме нечесної переваги, максимум — зрідка
 * промах без анімації або (рідше) анімація без влучання при різкому
 * русі цілі за останній тік.
 *
 * Дистанції впритул (нижче за поріг) кут не перевіряється взагалі — та
 * сама причина, що на сервері: напрямок «на впритул ціль» хаотично
 * стрибає від мікрорухів і дав би хибні промахи в анімації для тих, хто
 * стоїть буквально поруч.
 */
@Mixin(Minecraft.class)
public abstract class ManiacAttackGuardMixin {

    /** Той самий конус, що {@code ManiacCombatModule#findTarget}. */
    private static final double POINT_BLANK_BLOCKS = 0.75;
    private static final double CONE_COS = Math.cos(Math.toRadians(60));

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void maniacmod$strikeInsteadOfVanillaAttack(CallbackInfoReturnable<Boolean> cir) {
        if (!ClientMatchState.isManiac()) return;

        // Маньяк — ЛКМ ніколи не йде ванільним шляхом, який тут же
        // скасовується: усе далі в цьому методі або глушить клік, або
        // шле ManiacStrikePacket, а не запускає startAttack().
        cir.setReturnValue(false);

        // Фаза НЕ дозволяє бити (лобі, кіно, підсумки...) — глушимо клік
        // одразу, без огляду на кулдаун. Раніше тут стояв ранній return
        // на "не дозволено", який робив рівно навпаки: поза ігровими
        // фазами клік лишався непроглушеним (замах і звук програвались),
        // і глушився тільки кулдаун усередині самої гри.
        if (!ClientMatchState.allows(PhaseRule.DAMAGE)) return;

        Minecraft mc = Minecraft.getInstance();
        long tick = mc.level != null ? mc.level.getGameTime() : 0L;

        float left = ClientMatchState
            .abilityCooldownFraction(AbilityCooldownPacket.ATTACK_ID, tick);
        if (left > 0f) return; // на перезарядці — ні замаху, ні пакета

        LocalPlayer self = mc.player;
        if (self == null) return;
        if (!maniacmod$hasTargetInRange(self)) return; // нікого бити — ні замаху, ні пакета

        // Клік оброблено як "щось відбулось" — свінг руки й звук усе ж
        // мають зіграти, бо жертва в межах реально є. ЯКОГО РОДУ це був
        // удар (влучив/промазав) гравець дізнається з фактичної зміни
        // стану жертви на екрані, а не з анімації — сервер усе одно сам
        // перевіряє те саме ще раз незалежно від цього клієнтського
        // рішення.
        self.swing(InteractionHand.MAIN_HAND);
        ModNetwork.toServer(new ManiacStrikePacket());
    }

    /**
     * Дзеркало {@code ManiacCombatModule#findTarget} на клієнті: чи є
     * хоч ОДИН виживий (не лежачий) у конусі 60° на дистанції
     * {@code ClientMatchState.attackRangeBlocks()}. Не шукає
     * найближчого — на клієнті досить знати "є хтось чи нема", бо
     * остаточний вибір цілі все одно робить сервер.
     */
    private static boolean maniacmod$hasTargetInRange(LocalPlayer self) {
        double range = ClientMatchState.attackRangeBlocks();
        if (range <= 0.0) return false;
        if (self.level() == null) return false;

        Vec3 eye = self.getEyePosition();
        Vec3 look = self.getLookAngle();

        for (RosterSyncPacket.RosterEntry entry : ClientMatchState.roster()) {
            if (entry.role() != RoleSyncPacket.Role.SURVIVOR) continue;
            if (entry.state() == SurvivorState.UNCONSCIOUS) continue;
            if (entry.playerId().equals(self.getUUID())) continue;

            Player candidate = self.level().getPlayerByUUID(entry.playerId());
            if (candidate == null) continue; // поза видимістю рендеру — не заважає, сервер вирішить сам

            Vec3 toCandidate = candidate.getEyePosition().subtract(eye);
            double distSq = toCandidate.lengthSqr();
            if (distSq > range * range) continue;

            double distance = Math.sqrt(distSq);
            if (distance > POINT_BLANK_BLOCKS) {
                double cos = look.dot(toCandidate) / distance;
                if (cos < CONE_COS) continue;
            }
            return true;
        }
        return false;
    }
}
