package com.log_to_kot.maniacmod.core.match;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * Persistent-реєстр блоків, які матч тимчасово змінює.
 *
 * Перед зміною блока модуль реєструє його поточний стан. Після RESET або
 * наступного запуску сервера початковий стан відновлюється, тому забиті
 * двері, тимчасові барикади й інші майбутні об'єкти не залишаються на карті.
 */
public final class MatchBlockRegistry extends SavedData {

    private static final String DATA_ID = "maniacmod_match_blocks";
    private static final String ENTRIES = "Entries";

    private final Map<BlockPos, SavedBlock> savedBlocks = new HashMap<>();

    public static MatchBlockRegistry load(CompoundTag tag) {
        MatchBlockRegistry registry = new MatchBlockRegistry();
        ListTag entries = tag.getList(ENTRIES, CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            BlockPos pos = BlockPos.of(entry.getLong("Pos"));
            registry.savedBlocks.put(pos, SavedBlock.load(entry));
        }
        return registry;
    }

    public static MatchBlockRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
            MatchBlockRegistry::load,
            MatchBlockRegistry::new,
            DATA_ID);
    }

    /**
     * Запам'ятовує стан до зміни. Повторна реєстрація тієї самої позиції
     * не перезаписує оригінал.
     */
    public static void register(ServerLevel level, BlockPos position) {
        MatchBlockRegistry registry = get(level);
        BlockPos pos = position.immutable();
        if (registry.savedBlocks.containsKey(pos)) return;

        BlockEntity blockEntity = level.getBlockEntity(pos);
        CompoundTag blockEntityData = blockEntity == null
            ? null
            : blockEntity.saveWithFullMetadata();
        registry.savedBlocks.put(pos, new SavedBlock(
            NbtUtils.writeBlockState(level.getBlockState(pos)), blockEntityData));
        registry.setDirty();
    }

    /** Відновлює блоки цього сервера у всіх вимірах і очищує реєстр. */
    public static int restoreAll(MinecraftServer server) {
        int restored = 0;
        for (ServerLevel level : server.getAllLevels()) {
            MatchBlockRegistry registry = get(level);
            for (Map.Entry<BlockPos, SavedBlock> entry : registry.savedBlocks.entrySet()) {
                BlockPos pos = entry.getKey();
                level.getChunkAt(pos);
                BlockState state = NbtUtils.readBlockState(
                    level.registryAccess().lookupOrThrow(
                        net.minecraft.core.registries.Registries.BLOCK),
                    entry.getValue().state);
                level.setBlock(pos, state, 3);

                if (entry.getValue().blockEntityData != null) {
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (blockEntity != null) {
                        blockEntity.load(entry.getValue().blockEntityData.copy());
                        blockEntity.setChanged();
                    }
                }
                restored++;
            }
            registry.savedBlocks.clear();
            registry.setDirty();
        }
        return restored;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag entries = new ListTag();
        for (Map.Entry<BlockPos, SavedBlock> entry : savedBlocks.entrySet()) {
            CompoundTag saved = entry.getValue().save();
            saved.putLong("Pos", entry.getKey().asLong());
            entries.add(saved);
        }
        tag.put(ENTRIES, entries);
        return tag;
    }

    private record SavedBlock(CompoundTag state, CompoundTag blockEntityData) {
        private static SavedBlock load(CompoundTag tag) {
            return new SavedBlock(
                tag.getCompound("State"),
                tag.contains("BlockEntity")
                    ? tag.getCompound("BlockEntity")
                    : null);
        }

        private CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.put("State", state.copy());
            if (blockEntityData != null) tag.put("BlockEntity", blockEntityData.copy());
            return tag;
        }
    }
}
