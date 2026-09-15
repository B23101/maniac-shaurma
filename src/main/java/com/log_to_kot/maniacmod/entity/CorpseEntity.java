package com.log_to_kot.maniacmod.entity;

import com.log_to_kot.maniacmod.ManiacMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.*;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;
import net.minecraft.world.entity.PathfinderMob;

/**
 * CorpseEntity — a lying player-model entity that represents a dead survivor.
 *
 * Uses GeckoLib geo model: geo/entity/corpse.geo.json
 * which is a standard player skeleton ROTATED 90° on the X axis (lying flat).
 *
 * The dead player's skin name is stored in synced data so the renderer
 * can apply the correct player skin texture.
 *
 * The entity is non-interactive (no AI, no physics movement, no collision damage).
 * It is removed when the revival window expires or the player is revived.
 */
public class CorpseEntity extends PathfinderMob implements GeoEntity {

    public static final EntityType<CorpseEntity> TYPE = EntityType.Builder
        .<CorpseEntity>of(CorpseEntity::new, MobCategory.MISC)
        .sized(1.8f, 0.3f)   // lying flat: wide, very short
        .clientTrackingRange(64)
        .noSummon()
        .build("corpse");

    /** Synced: the dead player's username — used to look up their skin texture. */
    private static final EntityDataAccessor<String> DEAD_PLAYER_NAME =
        SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.STRING);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public CorpseEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setNoAi(true);
        this.setInvulnerable(true);
        this.noPhysics = false;
        this.setSilent(true);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DEAD_PLAYER_NAME, "");
    }

    public void setDeadPlayerName(String name) {
        this.entityData.set(DEAD_PLAYER_NAME, name);
    }

    public String getDeadPlayerName() {
        return this.entityData.get(DEAD_PLAYER_NAME);
    }

    /** Corpses are persistent — don't despawn. */
    @Override
    public boolean removeWhenFarAway(double dist) { return false; }

    /** No interaction with corpse. */
    @Override
    public net.minecraft.world.InteractionResult mobInteract(Player player,
            net.minecraft.world.InteractionHand hand) {
        return net.minecraft.world.InteractionResult.PASS;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("DeadPlayerName", getDeadPlayerName());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setDeadPlayerName(tag.getString("DeadPlayerName"));
    }

    // ── GeckoLib ─────────────────────────────────────────────────────────────

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar r) {
        // Static "dead" pose — no animation needed, the geo model is pre-rotated flat
        r.add(new AnimationController<>(this, "corpse_ctrl", 0,
            state -> PlayState.STOP));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }
}
