package com.log_to_kot.maniacmod.items;

import com.log_to_kot.maniacmod.game.ManiacGameManager;
import com.log_to_kot.maniacmod.game.SurvivorData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

// ── Аптечка ──────────────────────────────────────────────────────────────────
class MedkitItem extends Item {
    public MedkitItem() { super(new Properties().stacksTo(1)); }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) return InteractionResultHolder.pass(player.getItemInHand(hand));
        ServerPlayer sp = (ServerPlayer) player;
        SurvivorData data = ManiacGameManager.getSurvivorData(sp.getUUID());
        if (data == null) return InteractionResultHolder.fail(player.getItemInHand(hand));
        if (data.getLives() >= SurvivorData.MAX_LIVES) {
            sp.sendSystemMessage(Component.translatable("maniacmod.medkit.full"));
            return InteractionResultHolder.fail(player.getItemInHand(hand));
        }
        data.heal();
        sp.sendSystemMessage(Component.translatable("maniacmod.medkit.used", data.getLives()));
        player.getItemInHand(hand).shrink(1);
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }
}

// ── Гайочний ключ ─────────────────────────────────────────────────────────────
class WrenchItem extends Item {
    public WrenchItem() { super(new Properties().stacksTo(1)); }
}

// ── Викрутка ──────────────────────────────────────────────────────────────────
class ScrewdriverItem extends Item {
    public ScrewdriverItem() { super(new Properties().stacksTo(1)); }
}

// ── Ножниці ───────────────────────────────────────────────────────────────────
class ScissorsItem extends Item {
    public ScissorsItem() { super(new Properties().stacksTo(1)); }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) return InteractionResultHolder.pass(player.getItemInHand(hand));
        if (!(player instanceof ServerPlayer sp)) return InteractionResultHolder.fail(player.getItemInHand(hand));
        boolean used = ManiacGameManager.useScissors(sp);
        if (used) {
            player.getItemInHand(hand).hurtAndBreak(1, player, p -> {});
            return InteractionResultHolder.success(player.getItemInHand(hand));
        }
        return InteractionResultHolder.fail(player.getItemInHand(hand));
    }
}

// ── Бита ──────────────────────────────────────────────────────────────────────
class BatItem extends Item {
    public BatItem() { super(new Properties().stacksTo(1).durability(10)); }
}

// ── Електрошокер ──────────────────────────────────────────────────────────────
class TaserItem extends Item {
    public TaserItem() { super(new Properties().stacksTo(1).durability(3)); }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) return InteractionResultHolder.pass(player.getItemInHand(hand));
        if (!(player instanceof ServerPlayer sp)) return InteractionResultHolder.fail(player.getItemInHand(hand));
        level.getEntitiesOfClass(ServerPlayer.class,
            player.getBoundingBox().inflate(5),
            p -> ManiacGameManager.isManiac(p) && !p.getUUID().equals(player.getUUID())
        ).stream().findFirst().ifPresent(maniac -> {
            maniac.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 100, 10, false, true));
            maniac.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 100, 10, false, true));
            maniac.sendSystemMessage(Component.translatable("maniacmod.taser.hit"));
            sp.sendSystemMessage(Component.translatable("maniacmod.taser.used"));
            player.getItemInHand(hand).hurtAndBreak(1, player, p -> {});
        });
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }
}

// ── Лом ──────────────────────────────────────────────────────────────────────
class CrowbarItem extends Item {
    public CrowbarItem() { super(new Properties().stacksTo(1).durability(20)); }
}

// ── Дефібрилятор ─────────────────────────────────────────────────────────────
class DefibrillatorItem extends Item {
    public DefibrillatorItem() { super(new Properties().stacksTo(1)); }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) return InteractionResultHolder.pass(player.getItemInHand(hand));
        if (!(player instanceof ServerPlayer sp)) return InteractionResultHolder.fail(player.getItemInHand(hand));
        boolean revived = ManiacGameManager.tryRevive(sp);
        return revived
            ? InteractionResultHolder.success(player.getItemInHand(hand))
            : InteractionResultHolder.fail(player.getItemInHand(hand));
    }
}

// ── Ніж Чакі — 6 сек кулдаун ─────────────────────────────────────────────────
class ChuckyKnifeItem extends Item {
    public ChuckyKnifeItem() { super(new Properties().stacksTo(1).fireResistant()); }
}

// ── Щупальце Слендермена — 10 сек кулдаун ────────────────────────────────────
class SlenderTentacleItem extends Item {
    public SlenderTentacleItem() { super(new Properties().stacksTo(1).fireResistant()); }
}

// ── Капкан — ставиться на землю ПКМ по блоку ─────────────────────────────────
class BearTrapItem extends Item {
    public BearTrapItem() { super(new Properties().stacksTo(16)); }

    @Override
    public net.minecraft.world.InteractionResult useOn(net.minecraft.world.item.context.UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (level.isClientSide) return net.minecraft.world.InteractionResult.SUCCESS;
        if (!(ctx.getPlayer() instanceof ServerPlayer sp)) return net.minecraft.world.InteractionResult.FAIL;
        if (!ManiacGameManager.isManiac(sp)) return net.minecraft.world.InteractionResult.FAIL;

        // Ставимо на верхню поверхню блоку на який клікнули
        BlockPos placePos = ctx.getClickedPos().above();
        boolean placed = ManiacGameManager.placeBearTrap(sp, placePos);
        if (placed) {
            ctx.getItemInHand().shrink(1);
            return net.minecraft.world.InteractionResult.SUCCESS;
        }
        return net.minecraft.world.InteractionResult.FAIL;
    }
}

// ── Вірьовка (з кулдауном) ───────────────────────────────────────────────
class RopeItem extends Item {
    public RopeItem() { super(new Properties().stacksTo(1)); }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) return InteractionResultHolder.pass(player.getItemInHand(hand));
        if (!(player instanceof ServerPlayer sp)) return InteractionResultHolder.fail(player.getItemInHand(hand));
        if (!ManiacGameManager.isManiac(sp)) return InteractionResultHolder.fail(player.getItemInHand(hand));

        ServerPlayer target = level.getEntitiesOfClass(ServerPlayer.class,
            sp.getBoundingBox().inflate(2),
            p -> ManiacGameManager.isSurvivor(p)
        ).stream().findFirst().orElse(null);

        if (target == null) {
            sp.sendSystemMessage(Component.translatable("maniacmod.trap.rope.none_nearby"));
            return InteractionResultHolder.fail(player.getItemInHand(hand));
        }

        boolean bound = ManiacGameManager.bindWithRopeFromItem(sp, target);
        return bound
            ? InteractionResultHolder.success(player.getItemInHand(hand))
            : InteractionResultHolder.fail(player.getItemInHand(hand));
    }
}

// ── Електродріт — ставиться на землю ПКМ по блоку ───────────────────────────
class ElectricWireItem extends Item {
    public ElectricWireItem() { super(new Properties().stacksTo(16)); }

    @Override
    public net.minecraft.world.InteractionResult useOn(net.minecraft.world.item.context.UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (level.isClientSide) return net.minecraft.world.InteractionResult.SUCCESS;
        if (!(ctx.getPlayer() instanceof ServerPlayer sp)) return net.minecraft.world.InteractionResult.FAIL;
        if (!ManiacGameManager.isManiac(sp)) return net.minecraft.world.InteractionResult.FAIL;

        BlockPos placePos = ctx.getClickedPos().above();
        boolean placed = ManiacGameManager.placeElectricWire(sp, placePos);
        if (placed) {
            ctx.getItemInHand().shrink(1);
            return net.minecraft.world.InteractionResult.SUCCESS;
        }
        return net.minecraft.world.InteractionResult.FAIL;
    }
}

// ── Міна — ставиться на землю ПКМ по блоку ───────────────────────────────────
class MineItem extends Item {
    public MineItem() { super(new Properties().stacksTo(16)); }

    @Override
    public net.minecraft.world.InteractionResult useOn(net.minecraft.world.item.context.UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (level.isClientSide) return net.minecraft.world.InteractionResult.SUCCESS;
        if (!(ctx.getPlayer() instanceof net.minecraft.server.level.ServerPlayer sp))
            return net.minecraft.world.InteractionResult.FAIL;
        if (!ManiacGameManager.isManiac(sp))
            return net.minecraft.world.InteractionResult.FAIL;

        BlockPos placePos = ctx.getClickedPos().above();
        boolean placed = ManiacGameManager.placeMine(sp, placePos);
        if (placed) {
            ctx.getItemInHand().shrink(1);
            return net.minecraft.world.InteractionResult.SUCCESS;
        }
        return net.minecraft.world.InteractionResult.FAIL;
    }
}
