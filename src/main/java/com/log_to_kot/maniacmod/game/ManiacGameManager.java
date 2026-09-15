package com.log_to_kot.maniacmod.game;

import com.log_to_kot.maniacmod.entity.CorpseEntity;
import com.log_to_kot.maniacmod.entity.ModEntityTypes;
import com.log_to_kot.maniacmod.items.ModItems;
import com.log_to_kot.maniacmod.sound.ModSounds;
import com.log_to_kot.maniacmod.sound.SoundHelper;
import net.minecraft.server.level.ServerLevel;
import com.log_to_kot.maniacmod.entity.ManiacType;
import com.log_to_kot.maniacmod.entity.ManiacPhysicsData;
import com.log_to_kot.maniacmod.network.ModMessages;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.*;

public class ManiacGameManager {

    public enum GameState { WAITING, RUNNING, ENDED }

    private static GameState gameState = GameState.WAITING;
    private static UUID maniacUUID = null;
    private static ManiacType maniacType = null;
    private static ManiacPhysicsData maniacPhysics = null;

    private static final Map<UUID, SurvivorData>  survivorDataMap = new HashMap<>();
    private static final List<GeneratorState>     generators      = new ArrayList<>();
    /** UUID → {генератор що ремонтується, тік останнього use()} */
    private static final java.util.Map<java.util.UUID, RepairSession> repairingMap = new java.util.HashMap<>();

    private static class RepairSession {
        final BlockPos pos;
        long lastUseTick; // server tick count
        RepairSession(BlockPos pos, long tick) { this.pos = pos; this.lastUseTick = tick; }
    }
    private static long serverTick = 0;
    private static final List<ExitState>          exits           = new ArrayList<>();
    private static final List<BearTrapState>      bearTraps       = new ArrayList<>();
    private static final List<ElectricWireState>  electricWires   = new ArrayList<>();
    private static final List<RopeBindState>      ropeBinds       = new ArrayList<>();
    private static final List<MineState>          mines           = new ArrayList<>();
    private static final List<ItemSpawnZone>      itemZones       = new ArrayList<>();
    private static final List<EscapeZone>         escapeZones     = new ArrayList<>();
    private static ManiacSpawnZone                maniacSpawnZone = null;

    /** Dead survivors: UUID → corpse data */
    private static final Map<UUID, CorpseData> corpses = new HashMap<>();

    private static final Random RNG = new Random();

    /**
     * Maniac weapon cooldown: random 10–15 seconds (200–300 ticks).
     * Chosen fresh after each hit.
     */
    private static int maniacAttackCooldown = 0;

    /**
     * Trap placement cooldown (ticks). After placing a trap the maniac
     * must wait before placing the next one.
     * Bear trap: 15s (300t) | Wire: 12s (240t) | Rope: 10s (200t)
     */
    private static int trapCooldownBear = 0;
    private static int trapCooldownWire = 0;
    private static int trapCooldownRope = 0;
    private static int trapCooldownMine = 0;
    private static final int TRAP_COOLDOWN_BEAR   = 500;  // 25 секунд
    private static final int TRAP_COOLDOWN_WIRE   = 500;  // 25 секунд
    private static final int TRAP_COOLDOWN_ROPE   = 500;  // 25 секунд
    private static final int TRAP_COOLDOWN_MINE   = 500;  // 25 секунд

    // Тривалість гри береться з конфігу через ManiacConfig.getGameDuration()

    // ── Start / Reset ────────────────────────────────────────────────────────

    public static boolean startGame(List<ServerPlayer> allPlayers, ServerPlayer maniac,
                                    List<BlockPos> genPositions, List<BlockPos> exitPositions,
                                    ManiacType type, List<ItemSpawnZone> zones,
                                    List<EscapeZone> escZones, ManiacSpawnZone mSpawnZone) {
        if (gameState != GameState.WAITING) return false;
        if (allPlayers.size() < 2) return false;

        maniacUUID = maniac.getUUID();
        maniacType = type;
        maniacPhysics = new ManiacPhysicsData(maniac, type);
        survivorDataMap.clear(); generators.clear(); exits.clear();
        bearTraps.clear(); electricWires.clear(); ropeBinds.clear();
        itemZones.clear(); escapeZones.clear(); corpses.clear();
        maniacSpawnZone = null;
        maniacAttackCooldown = 0;

        for (ServerPlayer p : allPlayers) {
            if (!p.getUUID().equals(maniacUUID)) {
                survivorDataMap.put(p.getUUID(), new SurvivorData(p.getUUID()));
                giveSurvivorItems(p);
            }
        }
        for (BlockPos pos : genPositions)  generators.add(new GeneratorState(pos));
        for (BlockPos pos : exitPositions) exits.add(new ExitState(pos));

        applyManiacEffects(maniac);
        giveManiacItems(maniac);

        itemZones.addAll(zones);
        escapeZones.addAll(escZones);
        maniacSpawnZone = mSpawnZone;

        if (!zones.isEmpty()) {
            ServerLevel level = (ServerLevel) maniac.level();
            for (ItemSpawnZone zone : zones) {
                zone.spawnItems(level, ItemLootTable.build());
            }
            ItemSpawnZone spawnZone = zones.get(0);
            List<ServerPlayer> survivors = allPlayers.stream()
                .filter(p -> !p.getUUID().equals(maniacUUID))
                .collect(java.util.stream.Collectors.toList());
            spawnZone.spawnSurvivors(level, survivors);
            if (maniacSpawnZone != null) maniacSpawnZone.spawnManiac(level, maniac);
        }

        ModMessages.sendTypeToPlayer(maniac, type);
        ModMessages.broadcastScale(type.toRenderScale());
        gameState = GameState.RUNNING;

        broadcast(allPlayers, "§c§l══ МАНЬЯК ПОЧИНАЄТЬСЯ! ══");
        broadcast(allPlayers, "§7Запустіть §e" + GeneratorState.TOTAL_GENERATORS + " §7генераторів і втечіть!");
        maniac.sendSystemMessage(Component.literal("§4§l☠ ТИ — МАНЬЯК! Знищ усіх виживаючих!"));
        return true;
    }

    public static void resetGame() {
        gameState = GameState.WAITING; maniacUUID = null;
        survivorDataMap.clear(); generators.clear(); exits.clear();
        bearTraps.clear(); electricWires.clear(); ropeBinds.clear();
        itemZones.forEach(ItemSpawnZone::resetSpawn);
        itemZones.clear(); escapeZones.clear(); corpses.clear(); mines.clear();
        maniacSpawnZone = null; maniacType = null; maniacPhysics = null;
        maniacAttackCooldown = 0;
        trapCooldownBear = 0; trapCooldownWire = 0;
        trapCooldownRope = 0; trapCooldownMine = 0;
    }

    // ── Items ────────────────────────────────────────────────────────────────

    private static void giveSurvivorItems(ServerPlayer p) {
        p.getInventory().clearContent();
        p.getInventory().add(new ItemStack(ModItems.MEDKIT.get(),        1));
        p.getInventory().add(new ItemStack(ModItems.WRENCH.get(),        1));
        p.getInventory().add(new ItemStack(ModItems.SCREWDRIVER.get(),   1));
        p.getInventory().add(new ItemStack(ModItems.SCISSORS.get(),      1));
        p.getInventory().add(new ItemStack(ModItems.BAT.get(),           1));
        p.getInventory().add(new ItemStack(ModItems.TASER.get(),         1));
        p.getInventory().add(new ItemStack(ModItems.CROWBAR.get(),       1));
        p.getInventory().add(new ItemStack(ModItems.DEFIBRILLATOR.get(), 1));
    }

    private static void giveManiacItems(ServerPlayer maniac) {
        maniac.getInventory().clearContent();
        // По 1 предмету — після використання автоматично поповнюється до 1
        // Зброя залежить від типу маньяка
        maniac.getInventory().add(new ItemStack(maniacType.getWeaponItem(), 1));
        // Скинути візуальну перезарядку зброї при видачі
        maniac.getCooldowns().removeCooldown(maniacType.getWeaponItem());
        maniac.getInventory().add(new ItemStack(ModItems.BEAR_TRAP.get(),     1));
        maniac.getInventory().add(new ItemStack(ModItems.ROPE.get(),          1));
        maniac.getInventory().add(new ItemStack(ModItems.ELECTRIC_WIRE.get(), 1));
        maniac.getInventory().add(new ItemStack(ModItems.MINE.get(),          1));
    }

    /**
     * Поповнює предмети маньяка до 1 якщо використані.
     * Викликається кожну секунду — гравець завжди має по 1 кожного предмету.
     */
    public static void refillManiacItems(ServerPlayer maniac) {
        // Поповнюємо зброю конкретного маньяка
        if (maniacType != null) refillToOne(maniac, maniacType.getWeaponItem());
        refillToOne(maniac, ModItems.BEAR_TRAP.get());
        refillToOne(maniac, ModItems.ROPE.get());
        refillToOne(maniac, ModItems.ELECTRIC_WIRE.get());
        refillToOne(maniac, ModItems.MINE.get());
    }

    private static void refillToOne(ServerPlayer p, net.minecraft.world.item.Item item) {
        if (p.getInventory().countItem(item) == 0) {
            p.getInventory().add(new ItemStack(item, 1));
        }
    }

    public static void applyManiacEffects(ServerPlayer m) {
        m.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED,    Integer.MAX_VALUE, 1, false, true));
        m.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION,      Integer.MAX_VALUE, 0, false, true));
        m.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST,      Integer.MAX_VALUE, 0, false, true));
        m.addEffect(new MobEffectInstance(MobEffects.SATURATION,        Integer.MAX_VALUE, 0, false, false));
        m.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, Integer.MAX_VALUE, 1, false, true));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // MANIAC ATTACK COOLDOWN — 10–15 seconds random
    // ═════════════════════════════════════════════════════════════════════════

    public static boolean isManiacOnAttackCooldown() { return maniacAttackCooldown > 0; }
    public static int getManiacAttackCooldown()      { return maniacAttackCooldown; }
    public static int getManiacMaxAttackCooldown()   { return maniacType != null ? maniacType.attackCooldownTicks : 200; }

    public static void tickManiacCooldown() {
        if (maniacAttackCooldown > 0) maniacAttackCooldown--;
        if (trapCooldownBear > 0) trapCooldownBear--;
        if (trapCooldownWire > 0) trapCooldownWire--;
        if (trapCooldownRope > 0) trapCooldownRope--;
        if (trapCooldownMine > 0) trapCooldownMine--;
    }

    /** Random 10–15 second cooldown after each weapon hit */
    private static void triggerAttackCooldown() {
        // Кулдаун залежить від типу маньяка
        maniacAttackCooldown = maniacType != null ? maniacType.attackCooldownTicks : 200;
    }

    // ── Trap placement cooldown ──────────────────────────────────────────────

    public static boolean isBearTrapOnCooldown()  { return trapCooldownBear > 0; }
    public static boolean isWireOnCooldown()      { return trapCooldownWire > 0; }
    public static boolean isRopeOnCooldown()      { return trapCooldownRope > 0; }
    public static boolean isMineOnCooldown()      { return trapCooldownMine > 0; }
    public static int getBearTrapCooldown()       { return trapCooldownBear; }
    public static int getWireCooldown()           { return trapCooldownWire; }
    public static int getRopeCooldown()           { return trapCooldownRope; }
    public static int getMineCooldown()           { return trapCooldownMine; }

    public static boolean placeBearTrap(ServerPlayer maniac, BlockPos pos) {
        if (trapCooldownBear > 0) {
            maniac.sendSystemMessage(Component.translatable("maniacmod.trap.bear.cooldown", trapCooldownBear / 20));
            return false;
        }
        bearTraps.add(new BearTrapState(pos));
        trapCooldownBear = TRAP_COOLDOWN_BEAR;
        maniac.sendSystemMessage(Component.translatable("maniacmod.trap.bear.placed", TRAP_COOLDOWN_BEAR / 20));
        return true;
    }

    public static boolean placeElectricWire(ServerPlayer maniac, BlockPos pos) {
        if (trapCooldownWire > 0) {
            maniac.sendSystemMessage(Component.translatable("maniacmod.trap.wire.cooldown", trapCooldownWire / 20));
            return false;
        }
        electricWires.add(new ElectricWireState(pos));
        trapCooldownWire = TRAP_COOLDOWN_WIRE;
        maniac.sendSystemMessage(Component.translatable("maniacmod.trap.wire.placed", TRAP_COOLDOWN_WIRE / 20));
        return true;
    }


    public static boolean placeMine(ServerPlayer maniac, BlockPos pos) {
        if (trapCooldownMine > 0) {
            maniac.sendSystemMessage(Component.translatable("maniacmod.trap.mine.cooldown", trapCooldownMine / 20));
            return false;
        }
        mines.add(new MineState(pos));
        trapCooldownMine = TRAP_COOLDOWN_MINE;
        maniac.sendSystemMessage(Component.translatable("maniacmod.trap.mine.placed", TRAP_COOLDOWN_MINE / 20));
        return true;
    }

    public static boolean bindWithRopeFromItem(ServerPlayer maniac, ServerPlayer target) {
        if (!isSurvivor(target)) return false;
        if (trapCooldownRope > 0) {
            maniac.sendSystemMessage(Component.translatable("maniacmod.trap.rope.cooldown", trapCooldownRope / 20));
            return false;
        }
        bindWithRope(target);
        trapCooldownRope = TRAP_COOLDOWN_ROPE;
        return true;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CORPSE / REVIVAL SYSTEM — 3-minute window, CorpseEntity model
    // ═════════════════════════════════════════════════════════════════════════

    public static void createCorpse(ServerPlayer dead) {
        CorpseData corpse = new CorpseData(dead.getUUID(), dead.getName().getString(),
            dead.position(), dead.getYRot());
        corpses.put(dead.getUUID(), corpse);

        // Spawn GeckoLib CorpseEntity at death location
        ServerLevel level = dead.serverLevel();
        CorpseEntity entity = ModEntityTypes.CORPSE.get().create(level);
        if (entity != null) {
            entity.setDeadPlayerName(dead.getName().getString());
            entity.moveTo(dead.getX(), dead.getY(), dead.getZ(), dead.getYRot(), 0f);
            level.addFreshEntity(entity);
            corpse.setCorpseEntityUUID(entity.getUUID());
        }

        // Put dead player in spectator — they watch from their own corpse view
        dead.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
    }

    public static void tickCorpses(List<ServerPlayer> all) {
        List<UUID> expired = new ArrayList<>();
        for (Map.Entry<UUID, CorpseData> entry : corpses.entrySet()) {
            CorpseData cd = entry.getValue();
            if (!cd.canBeRevived()) {
                expired.add(entry.getKey());
                removeCorpseEntity(all, cd);
                all.stream().filter(p -> p.getUUID().equals(entry.getKey())).findFirst()
                    .ifPresent(p -> p.sendSystemMessage(Component.literal(
                        "§8☠ Час на воскресіння вийшов. Ти вибуваєш з гри.")));
            } else {
                int rem = cd.secondsRemaining();
                if (rem % 30 == 0 && rem > 0) {
                    all.stream().filter(p -> p.getUUID().equals(entry.getKey())).findFirst()
                        .ifPresent(p -> p.sendSystemMessage(Component.literal(
                            "§6⚡ Ще §e" + rem + "§6 сек до закінчення воскресіння!")));
                }
            }
        }
        for (UUID id : expired) corpses.remove(id);
    }

    private static void removeCorpseEntity(List<ServerPlayer> all, CorpseData cd) {
        if (cd.getCorpseEntityUUID() == null) return;
        all.stream().findFirst().ifPresent(p -> {
            ServerLevel level = p.serverLevel();
            var entity = level.getEntity(
                java.util.stream.StreamSupport.stream(level.getAllEntities().spliterator(), false)
                    .filter(e -> e instanceof CorpseEntity
                        && e.getUUID().equals(cd.getCorpseEntityUUID()))
                    .map(e -> e.getId()).findFirst().orElse(-1));
            if (entity != null) entity.discard();
        });
    }

    public static boolean tryRevive(ServerPlayer reviver) {
        if (!isSurvivor(reviver)) return false;
        SurvivorData reviverData = survivorDataMap.get(reviver.getUUID());
        if (reviverData == null) return false;

        if (reviverData.getLives() <= 1) {
            reviver.sendSystemMessage(Component.translatable("maniacmod.revive.no_health"));
            return false;
        }

        List<ServerPlayer> all = reviver.getServer().getPlayerList().getPlayers();

        for (Map.Entry<UUID, CorpseData> entry : corpses.entrySet()) {
            CorpseData cd = entry.getValue();
            if (!cd.canBeRevived()) continue;
            if (reviver.position().distanceTo(cd.getPosition()) > 2.5) continue;

            UUID deadId = cd.getDeadUUID();
            SurvivorData revivedData = new SurvivorData(deadId);
            revivedData.setLives(1);
            survivorDataMap.put(deadId, revivedData);
            corpses.remove(deadId);

            // Remove the lying corpse entity
            removeCorpseEntity(all, cd);

            reviverData.takeDamage();

            all.stream().filter(p -> p.getUUID().equals(deadId)).findFirst().ifPresent(p -> {
                p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                p.setPos(cd.getPosition().x, cd.getPosition().y, cd.getPosition().z);
                p.sendSystemMessage(Component.translatable("maniacmod.revive.success_other", reviver.getName().getString(), revivedData.livesBar()));
            });

            reviver.sendSystemMessage(Component.literal(
                "§a⚡ Ти воскресив §l" + cd.getDeadName() + "§a!  " + reviverData.livesBar()));
            broadcast(all, Component.translatable("maniacmod.revive.broadcast", reviver.getName().getString(), cd.getDeadName()));

            reviver.getMainHandItem().shrink(1);
            return true;
        }

        reviver.sendSystemMessage(Component.translatable("maniacmod.revive.none_nearby"));
        return false;
    }

    public static Map<UUID, CorpseData> getCorpses() { return corpses; }

    // ═════════════════════════════════════════════════════════════════════════
    // BEAR TRAP
    // ═════════════════════════════════════════════════════════════════════════

    public static void tickBearTraps(List<ServerPlayer> all) {
        bearTraps.removeIf(BearTrapState::isBroken);
        for (BearTrapState trap : bearTraps) {
            trap.tick();
            if (trap.isEmpty()) {
                for (ServerPlayer p : all) {
                    if (!isSurvivor(p)) continue;
                    if (!trap.getPos().closerThan(p.blockPosition(), 1)) continue;
                    if (trap.catchSurvivor(p.getUUID())) {
                        SurvivorData data = survivorDataMap.get(p.getUUID());
                        if (data == null) continue;
                        boolean died = data.takeDamage();
                        p.sendSystemMessage(Component.literal(
                            "§c🪤 Ти потрапив у КАПКАН! " + data.livesBar()
                            + "\n§7Інший гравець: тримай §eлом §7поруч 3 сек щоб зламати!"));
                        broadcast(all, Component.translatable("maniacmod.trap.bear.broadcast", p.getName().getString()));
                        if (died) eliminateSurvivor(p, all, "§c" + p.getName().getString() + " §7загинув у капкані!");
                    }
                }
            }

            if (trap.hasCaught()) {
                boolean prying = false;
                for (ServerPlayer helper : all) {
                    if (!isSurvivor(helper)) continue;
                    if (helper.getUUID().equals(trap.getCaughtUUID())) continue;
                    if (helper.getMainHandItem().getItem() != ModItems.CROWBAR.get()) continue;
                    if (!trap.getPos().closerThan(helper.blockPosition(), 2)) continue;

                    boolean freed = trap.tickCrowbar(helper.getUUID());
                    prying = true;
                    helper.sendSystemMessage(Component.literal(
                        "§e🔧 Зламуєш капкан... §f" + trap.getPryingProgress() + "%"));
                    if (freed) {
                        all.stream().filter(p -> p.getUUID().equals(trap.getCaughtUUID())).findFirst()
                            .ifPresent(v -> v.sendSystemMessage(Component.translatable("maniacmod.trap.bear.freed")));
                        broadcast(all, Component.translatable("maniacmod.trap.bear.freed"));
                    }
                    break;
                }
                if (!prying && trap.getPryingHelperUUID() != null)
                    trap.cancelPrying(trap.getPryingHelperUUID());

                all.stream().filter(p -> p.getUUID().equals(trap.getCaughtUUID())).findFirst()
                    .ifPresent(p -> p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 5, 20, false, false)));
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ELECTRIC WIRE
    // ═════════════════════════════════════════════════════════════════════════

    public static void tickElectricWires(List<ServerPlayer> all) {
        electricWires.removeIf(ElectricWireState::isDestroyed);
        for (ElectricWireState wire : electricWires) {
            wire.tick();
            if (wire.isActive()) {
                for (ServerPlayer p : all) {
                    if (!isSurvivor(p)) continue;
                    if (!wire.getPos().closerThan(p.blockPosition(), 1)) continue;
                    if (wire.catchSurvivor(p.getUUID())) {
                        SurvivorData data = survivorDataMap.get(p.getUUID());
                        if (data == null) continue;
                        boolean died = data.takeDamage();
                        p.sendSystemMessage(Component.translatable("maniacmod.trap.wire.caught", data.livesBar()).append("\n").append(Component.translatable("maniacmod.trap.wire.hint")));
                        broadcast(all, Component.translatable("maniacmod.trap.wire.broadcast", p.getName().getString()));
                        SoundHelper.playGlobal(all, ModSounds.WIRE_ZAP.get(), 1.0f);
                        if (died) { wire.freeWithScissors(); eliminateSurvivor(p, all, "§e" + p.getName().getString() + " §7загинув від дроту!"); }
                    }
                }
            }
            if (wire.hasCaught()) {
                all.stream().filter(p -> p.getUUID().equals(wire.getCaughtUUID())).findFirst()
                    .ifPresent(p -> {
                        p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 5, 20, false, false));
                        p.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 10, 0, false, false));
                    });
            }
        }
    }

    public static boolean tryCutElectricWire(ServerPlayer helper) {
        for (ElectricWireState wire : electricWires) {
            if (!wire.hasCaught()) continue;
            if (!wire.getPos().closerThan(helper.blockPosition(), 2)) continue;
            if (wire.getCaughtUUID().equals(helper.getUUID())) {
                helper.sendSystemMessage(Component.translatable("maniacmod.trap.wire.cant_self"));
                return false;
            }
            List<ServerPlayer> all = helper.getServer().getPlayerList().getPlayers();
            UUID victim = wire.getCaughtUUID();
            wire.freeWithScissors();
            all.stream().filter(p -> p.getUUID().equals(victim)).findFirst()
                .ifPresent(v -> v.sendSystemMessage(Component.translatable("maniacmod.trap.wire.freed")));
            broadcast(all, Component.translatable("maniacmod.trap.wire.freed"));
            return true;
        }
        return false;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ROPE
    // ═════════════════════════════════════════════════════════════════════════

    public static void bindWithRope(ServerPlayer target) {
        if (!isSurvivor(target)) return;
        ropeBinds.removeIf(r -> r.getBoundUUID().equals(target.getUUID()));
        ropeBinds.add(new RopeBindState(target.getUUID(), target.getX(), target.getY(), target.getZ()));
        target.sendSystemMessage(Component.translatable("maniacmod.trap.rope.bound").append("\n").append(Component.translatable("maniacmod.trap.rope.hint")));
        List<ServerPlayer> all = target.getServer().getPlayerList().getPlayers();
        broadcast(all, "§6" + target.getName().getString() + " §7зв'язаний! Ножниці потрібні!");
    }

    public static void tickRopeBinds(List<ServerPlayer> all) {
        ropeBinds.removeIf(RopeBindState::isFreed);
        for (RopeBindState bind : ropeBinds) {
            all.stream().filter(p -> p.getUUID().equals(bind.getBoundUUID())).findFirst()
                .ifPresent(p -> {
                    p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 5, 20, false, false));
                    p.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 5, 1, false, false));
                });
        }
    }

    public static boolean tryFreeRope(ServerPlayer helper) {
        List<ServerPlayer> all = helper.getServer().getPlayerList().getPlayers();
        for (RopeBindState bind : ropeBinds) {
            if (bind.getBoundUUID().equals(helper.getUUID())) continue;
            ServerPlayer victim = all.stream().filter(p -> p.getUUID().equals(bind.getBoundUUID())).findFirst().orElse(null);
            if (victim == null || victim.distanceTo(helper) > 2.5f) continue;
            bind.freeWithScissors();
            victim.sendSystemMessage(Component.translatable("maniacmod.trap.rope.freed"));
            broadcast(all, Component.translatable("maniacmod.trap.rope.freed"));
            return true;
        }
        return false;
    }

    public static boolean isBoundByRope(UUID uuid) {
        return ropeBinds.stream().anyMatch(r -> r.getBoundUUID().equals(uuid) && !r.isFreed());
    }

    public static boolean useScissors(ServerPlayer helper) {
        if (tryCutElectricWire(helper)) return true;
        if (tryFreeRope(helper)) return true;
        helper.sendSystemMessage(Component.translatable("maniacmod.scissors.none"));
        return false;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // GENERATOR / EXIT
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Викликається з GeneratorBlock.use() кожні ~4 тіки поки гравець тримає ПКМ.
     * Реєструє або оновлює сесію ремонту для гравця.
     */
    public static void refreshRepair(ServerPlayer p, BlockPos pos) {
        repairingMap.compute(p.getUUID(), (uuid, session) -> {
            if (session != null && session.pos.equals(pos)) {
                session.lastUseTick = serverTick;
                return session;
            }
            // Новий генератор або гравець переключився
            if (session != null) hideRepairProgress(p); // очистити старий бар
            return new RepairSession(pos, serverTick);
        });
    }

    /**
     * Виконується кожен тік з tickAll().
     * Перевіряє кожного гравця що ремонтує: якщо минуло > 6 тіків без use() — зупиняємо.
     */
    private static void tickRepairSessions(List<ServerPlayer> all) {
        var it = repairingMap.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            java.util.UUID uuid = entry.getKey();
            RepairSession session = entry.getValue();

            ServerPlayer p = all.stream().filter(pl -> pl.getUUID().equals(uuid)).findFirst().orElse(null);
            if (p == null) { it.remove(); continue; }

            // Якщо гравець відпустив ПКМ — use() перестає викликатись, lastUseTick відстає
            if (serverTick - session.lastUseTick > 6) {
                hideRepairProgress(p);
                it.remove();
                continue;
            }

            // Тікаємо ремонт
            doTickRepair(p, session.pos);
        }
    }

    /** Внутрішній метод: один тік ремонту для гравця на конкретній позиції. */
    private static void doTickRepair(ServerPlayer p, BlockPos genPos) {
        GeneratorState g = generators.stream()
            .filter(gs -> !gs.isActive() && gs.getPos().equals(genPos))
            .findFirst().orElse(null);

        if (g == null) {
            g = new GeneratorState(genPos);
            generators.add(g);
        }

        boolean activated = g.addProgress(1);

        if (g.justFailed()) {
            hideRepairProgress(p);
            SoundHelper.playTo(p, ModSounds.GENERATOR_REPAIR.get(), 0.8f, 0.5f);
            return;
        }

        if (activated) {
            hideRepairProgress(p);
            repairingMap.remove(p.getUUID());
            net.minecraft.server.level.ServerLevel sl = (net.minecraft.server.level.ServerLevel) p.level();
            com.log_to_kot.maniacmod.blocks.GeneratorBlock.activateInWorld(sl, genPos);
            List<ServerPlayer> all = p.getServer().getPlayerList().getPlayers();
            broadcast(all, Component.translatable("maniacmod.generator.repaired",
                countActiveGenerators(), GeneratorState.TOTAL_GENERATORS));
            SoundHelper.playGlobal(all, ModSounds.GENERATOR_ON.get(), 1.0f);
            if (countActiveGenerators() >= GeneratorState.TOTAL_GENERATORS) {
                broadcast(all, Component.translatable("maniacmod.generator.power_on"));
                SoundHelper.playGlobal(all, ModSounds.POWER_ON.get(), 1.0f);
            }
            return;
        }

        // Надсилаємо прогрес клієнту
        ModMessages.INSTANCE.send(
            net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> p),
            new com.log_to_kot.maniacmod.network.GeneratorProgressPacket(
                g.getStageProgress(), g.getStagesDone() + 1, g.getStagesRequired()));
    }

    /** Ховає прогрес-бар ремонту у гравця. */
    public static void hideRepairProgress(ServerPlayer p) {
        ModMessages.INSTANCE.send(
            net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> p),
            new com.log_to_kot.maniacmod.network.GeneratorProgressPacket());
    }

    /** @deprecated Залишено для сумісності. */
    @Deprecated
    public static void tickRepair(ServerPlayer p, BlockPos nearPos) {}

    /** @deprecated Залишено для сумісності. */
    @Deprecated
    public static boolean tickRepairOnBlock(ServerPlayer p, BlockPos genPos) { return false; }

    
    /**
     * Маньяк ламає генератор — скидає стан.
     */
    public static void deactivateGenerator(BlockPos pos) {
        generators.removeIf(g -> g.getPos().equals(pos));
    }

    public static void tickUnlock(ServerPlayer p, BlockPos nearPos) {
        if (countActiveGenerators() < GeneratorState.TOTAL_GENERATORS) return;
        exits.stream().filter(e -> !e.isUnlocked() && e.getPos().closerThan(nearPos, 3)).findFirst()
            .ifPresent(e -> {
                boolean unlocked = e.addProgress(1);
                if (unlocked)
                    broadcast(p.getServer().getPlayerList().getPlayers(), Component.translatable("maniacmod.exit.unlocked"));
            });
    }

    public static void checkEscape(ServerPlayer p) {
        if (gameState != GameState.RUNNING) return;
        if (!survivorDataMap.containsKey(p.getUUID())) return;
        if (isBoundByRope(p.getUUID())) return;

        if (escapeZones.stream().anyMatch(z -> z.contains(p))) {
            survivorDataMap.remove(p.getUUID());
            p.sendSystemMessage(Component.translatable("maniacmod.game.escaped"));
            List<ServerPlayer> all = p.getServer().getPlayerList().getPlayers();
            SoundHelper.playGlobal(all, ModSounds.SURVIVOR_ESCAPED.get(), 1.0f);
            broadcast(all, Component.translatable("maniacmod.game.survivor_escaped", p.getName().getString(), survivorDataMap.size()));
            checkWinCondition(all);
            return;
        }

        exits.stream().filter(ExitState::isUnlocked)
            .filter(e -> e.getPos().closerThan(p.blockPosition(), 2)).findFirst()
            .ifPresent(e -> {
                survivorDataMap.remove(p.getUUID());
                List<ServerPlayer> all = p.getServer().getPlayerList().getPlayers();
                broadcast(all, "§a🏃 " + p.getName().getString() + " §aвтік!");
                checkWinCondition(all);
            });
    }

    public static int countActiveGenerators() {
        return (int) generators.stream().filter(GeneratorState::isActive).count();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // COMBAT
    // ═════════════════════════════════════════════════════════════════════════

    @SubscribeEvent
    public void onLivingAttack(LivingAttackEvent event) {
        if (gameState != GameState.RUNNING) return;
        if (!(event.getEntity() instanceof ServerPlayer target)) return;
        if (!(event.getSource().getEntity() instanceof ServerPlayer attacker)) return;
        List<ServerPlayer> all = attacker.getServer().getPlayerList().getPlayers();

        // Check if attacker is maniac and holding their specific weapon
        boolean holdingWeapon = maniacType != null &&
            attacker.getMainHandItem().getItem() == maniacType.getWeaponItem();
        if (isManiac(attacker) && isSurvivor(target) && holdingWeapon) {
            event.setCanceled(true);
            if (isManiacOnAttackCooldown()) {
                attacker.sendSystemMessage(Component.translatable("maniacmod.attack.cooldown", maniacAttackCooldown / 20));
                return;
            }
            SurvivorData data = survivorDataMap.get(target.getUUID());
            if (data == null) return;
            boolean died = data.takeDamage();
            target.sendSystemMessage(Component.translatable("maniacmod.attack.hit", data.livesBar()));
            triggerAttackCooldown();
            int cdSec = maniacAttackCooldown / 20;
            attacker.sendSystemMessage(Component.translatable(
                "maniacmod.attack.struck", cdSec));
            if (died) eliminateSurvivor(target, all, "§c" + target.getName().getString() + " §7загинув від маньяка!");
        }

        if (isSurvivor(attacker) && isManiac(target)) {
            if (attacker.getMainHandItem().getItem() == ModItems.BAT.get()) {
                event.setCanceled(true);
                target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 10, false, true));
                target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 10, false, true));
                target.sendSystemMessage(Component.translatable("maniacmod.stun_bat"));
                attacker.getMainHandItem().hurtAndBreak(1, attacker, pp -> {});
            }
        }
    }

    // ── Elimination ──────────────────────────────────────────────────────────

    private static void eliminateSurvivor(ServerPlayer p, List<ServerPlayer> all, String msg) {
        survivorDataMap.remove(p.getUUID());
        bearTraps.stream().filter(t -> p.getUUID().equals(t.getCaughtUUID()))
            .forEach(t -> t.tickCrowbar(p.getUUID()));
        electricWires.stream().filter(w -> p.getUUID().equals(w.getCaughtUUID()))
            .forEach(ElectricWireState::freeWithScissors);
        ropeBinds.removeIf(r -> r.getBoundUUID().equals(p.getUUID()));
        broadcast(all, msg);
        createCorpse(p);
        broadcast(all, "§6⚡ " + p.getName().getString()
            + " §7лежить! Дефібрилятор може воскресити протягом §e"
            + CorpseData.REVIVE_WINDOW_SECONDS + "с§7!");
        checkWinCondition(all);
    }

    private static void checkWinCondition(List<ServerPlayer> all) {
        long revivable = corpses.values().stream().filter(CorpseData::canBeRevived).count();
        if (survivorDataMap.isEmpty() && revivable == 0) {
            broadcast(all, Component.translatable("maniacmod.game.maniac_wins"));
            resetGame();
        }
    }

    public static void tickAll(List<ServerPlayer> all) {
        serverTick++;
        tickBearTraps(all);
        tickElectricWires(all);
        tickRopeBinds(all);
        tickMines(all);
        tickManiacCooldown();
        tickRepairSessions(all);
    }

    public static void tickAllSeconds(List<ServerPlayer> all) {
        tickCorpses(all);
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public static ManiacType getManiacType(UUID uuid) {
        if (maniacUUID != null && maniacUUID.equals(uuid)) return maniacType;
        return null;
    }
    public static ManiacPhysicsData getManiacPhysics()             { return maniacPhysics; }
    public static boolean isManiac(Player p)                       { return maniacUUID != null && p.getUUID().equals(maniacUUID); }
    public static boolean isSurvivor(Player p)                     { return survivorDataMap.containsKey(p.getUUID()); }
    public static SurvivorData getSurvivorData(UUID uuid)          { return survivorDataMap.get(uuid); }
    public static GameState getGameState()                         { return gameState; }
    public static UUID getManiacUUID()                             { return maniacUUID; }
    public static Map<UUID, SurvivorData> getSurvivorDataMap()     { return survivorDataMap; }
    public static List<GeneratorState> getGenerators()             { return generators; }
    public static List<ItemSpawnZone> getItemZones()               { return itemZones; }
    public static List<EscapeZone> getEscapeZones()                { return escapeZones; }
    public static ManiacSpawnZone getManiacSpawnZone()             { return maniacSpawnZone; }
    public static List<BearTrapState> getBearTraps()               { return bearTraps; }
    public static List<ElectricWireState> getElectricWires()       { return electricWires; }
    public static List<RopeBindState> getRopeBinds()               { return ropeBinds; }

    // Game timer removed — game ends only when all survivors are eliminated or escape

    private static void tickMines(List<ServerPlayer> all) {
        mines.removeIf(MineState::isGone);
        for (MineState mine : mines) {
            for (ServerPlayer p : all) {
                if (!isSurvivor(p)) continue;
                if (p.blockPosition().distSqr(mine.getPos()) > 4) {
                    mine.cancelDefuse();
                    continue;
                }
                boolean defused = mine.tickDefuse(p.getUUID());
                if (defused) {
                    p.sendSystemMessage(Component.translatable("maniacmod.trap.mine.defused"));
                }
            }
        }
        for (MineState mine : new java.util.ArrayList<>(mines)) {
            if (mine.isGone()) continue;
            for (ServerPlayer p : all) {
                if (!isSurvivor(p)) continue;
                if (p.blockPosition().distSqr(mine.getPos()) <= 1) {
                    if (mine.trigger(p.getUUID())) {
                        SurvivorData data = survivorDataMap.get(p.getUUID());
                        if (data != null) {
                            boolean died = data.takeDamage();
                            p.sendSystemMessage(Component.translatable("maniacmod.trap.mine.hit", data.livesBar()));
                            p.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                                net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, 60, 2, false, true));
                            broadcast(all, Component.translatable("maniacmod.trap.mine.broadcast", p.getName().getString()));
                            if (died) eliminateSurvivor(p, all, "§c" + p.getName().getString() + " §7підірвався на міні!");
                        }
                    }
                }
            }
        }
    }

    private static void broadcast(List<ServerPlayer> players, String msg) {
        players.forEach(p -> p.sendSystemMessage(Component.literal(msg)));
    }

    private static void broadcast(List<ServerPlayer> players, Component msg) {
        players.forEach(p -> p.sendSystemMessage(msg));
    }
}
