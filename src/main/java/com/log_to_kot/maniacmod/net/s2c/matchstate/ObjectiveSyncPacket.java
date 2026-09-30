package com.log_to_kot.maniacmod.net.s2c.matchstate;

import com.log_to_kot.maniacmod.core.match.MatchObjectives;
import com.log_to_kot.maniacmod.map.zones.GeneratorPoi;
import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Сервер → клієнт: ПОТОЧНА ЦІЛЬ матчу для tab-екрана — скільки
 * генераторів полагоджено, як просунувся кожен, і які віхи вже пройдено.
 *
 * ── Категорія: matchstate ────────────────────────────────────────────
 * Саме цей пакет передбачав докстрінг {@link PhaseSyncPacket}: «якщо
 * колись знадобиться показати досягнення матчу на клієнті, новий пакет
 * іде в matchstate — це стан, а не подія». Він відповідає на питання
 * «що зараз треба зробити команді», на яке клієнт раніше не мав жодного
 * джерела (фаза каже лише «HUNT», а не «3 з 5»). Клієнт нічого не
 * рахує сам: усі числа — з сервера.
 *
 * ── Чому не GeneratorCompletedPacket ────────────────────────────────
 * {@code GeneratorCompletedPacket} — одноразова ПОДІЯ для банера («N з
 * M!»): її не бачить той, хто відкрив Tab після події, хто щойно
 * приєднався чи хто дивиться як глядач (пакет шлеться лише виживим).
 * Цей пакет — ПОВНИЙ СТАН, тому Tab завжди показує правду, а не
 * «те, що клієнт устиг запам'ятати».
 *
 * ── Що всередині ─────────────────────────────────────────────────────
 *   • {@code generatorsDone / generatorsRequired} — «N з M» рівно в тому
 *     ж сенсі, що й у {@code GeneratorCompletedPacket}: M — це
 *     GENERATORS_REQUIRED, N не перевищує M (бонусні генератори понад
 *     норму рахунок не роблять хибним).
 *   • {@code generators} — по одному запису на КОЖЕН генератор карти,
 *     відсортовані за спаданням прогресу: перші M — саме ті, що
 *     найближчі до потрібного числа, їх Tab і малює слотами.
 *   • {@code milestones} — набір {@link MatchObjectives.Objective}
 *     (бітова маска за ordinal — додавання нової ціли в кінець enum
 *     сумісне без зміни формату).
 *
 * Надсилається на ПОДІЮ і разом із ростером (раз на секунду під час
 * матчу) — див. {@code ServerHooks.broadcastObjectives}.
 *
 * @param generatorsDone     скільки генераторів повністю готово (не більше required)
 * @param generatorsRequired скільки треба полагодити для виходу
 * @param generators         усі генератори карти, за спаданням прогресу
 * @param milestones         досягнуті цілі матчу
 */
public record ObjectiveSyncPacket(int generatorsDone,
                                  int generatorsRequired,
                                  List<GeneratorSlot> generators,
                                  Set<MatchObjectives.Objective> milestones) implements S2CPacket {

    /** Захист від зловмисного/зламаного розміру списку при читанні. */
    private static final int MAX_GENERATORS = 64;

    /**
     * @param stage   REPAIR / FUEL / DONE
     * @param visual  IDLE / IN_PROGRESS / FAILED / DONE — той самий стан, що
     *                живить підсвітку генераторів (одне джерело правди)
     * @param percent загальний прогрес 0..100 через ОБИДВІ стадії (ремонт = перша
     *                половина, бензин = друга) — одна шкала для слота в Tab
     */
    public record GeneratorSlot(GeneratorPoi.Stage stage,
                                GeneratorPoi.VisualState visual,
                                int percent) {}

    public ObjectiveSyncPacket {
        generators = List.copyOf(generators);
        milestones = milestones.isEmpty() ? Set.of() : Set.copyOf(milestones);
    }

    public boolean has(MatchObjectives.Objective objective) {
        return milestones.contains(objective);
    }

    /** Порожній стан — до першого пакета й після виходу з матчу. */
    public static ObjectiveSyncPacket empty() {
        return new ObjectiveSyncPacket(0, 0, List.of(), Set.of());
    }

    public ObjectiveSyncPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarInt(), readSlots(buf), readMilestones(buf));
    }

    private static List<GeneratorSlot> readSlots(FriendlyByteBuf buf) {
        int size = Math.min(buf.readVarInt(), MAX_GENERATORS);
        List<GeneratorSlot> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(new GeneratorSlot(
                buf.readEnum(GeneratorPoi.Stage.class),
                buf.readEnum(GeneratorPoi.VisualState.class),
                buf.readVarInt()));
        }
        return list;
    }

    private static Set<MatchObjectives.Objective> readMilestones(FriendlyByteBuf buf) {
        int mask = buf.readVarInt();
        Set<MatchObjectives.Objective> set = EnumSet.noneOf(MatchObjectives.Objective.class);
        for (MatchObjectives.Objective objective : MatchObjectives.Objective.values()) {
            if ((mask & (1 << objective.ordinal())) != 0) set.add(objective);
        }
        return set;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(generatorsDone);
        buf.writeVarInt(generatorsRequired);
        int size = Math.min(generators.size(), MAX_GENERATORS);
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            GeneratorSlot slot = generators.get(i);
            buf.writeEnum(slot.stage());
            buf.writeEnum(slot.visual());
            buf.writeVarInt(slot.percent());
        }
        int mask = 0;
        for (MatchObjectives.Objective objective : milestones) mask |= 1 << objective.ordinal();
        buf.writeVarInt(mask);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onObjectives(this);
    }
}
