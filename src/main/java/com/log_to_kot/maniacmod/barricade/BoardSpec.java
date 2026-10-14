package com.log_to_kot.maniacmod.barricade;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Одна дошка барикади — незмінні дані, спільні для сервера й клієнта.
 *
 * <p>Дошка — це плоска стрічка між двома точками {@code a→b}. {@code normal} — нормаль її
 * широкої грані (куди дошка «дивиться»), вона вже ортогональна до {@code a→b}
 * (див. {@link BarricadeGeometry#faceNormal}). {@code block} — id блока, текстура якого
 * намальована на дошці.
 *
 * <p>Ті самі дані малює клієнт, з ними ж рахує промінь/колізії сервер — тому барикада у грі
 * виглядає й поводиться точно так, як її побудував адмін: другого джерела правди немає.
 */
public record BoardSpec(Vec3 a, Vec3 b, Vec3 normal, ResourceLocation block) {

    public BoardSpec withBlock(ResourceLocation newBlock) {
        return new BoardSpec(a, b, normal, newBlock);
    }

    // ── NBT ──────────────────────────────────────────────────────────────

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        putVec(tag, "a", a);
        putVec(tag, "b", b);
        putVec(tag, "n", normal);
        tag.putString("block", block.toString());
        return tag;
    }

    public static BoardSpec load(CompoundTag tag) {
        ResourceLocation block = ResourceLocation.tryParse(tag.getString("block"));
        return new BoardSpec(getVec(tag, "a"), getVec(tag, "b"), getVec(tag, "n"),
            block == null ? new ResourceLocation("minecraft", "oak_planks") : block);
    }

    public static ListTag saveAll(List<BoardSpec> boards) {
        ListTag list = new ListTag();
        for (BoardSpec board : boards) list.add(board.save());
        return list;
    }

    public static List<BoardSpec> loadAll(ListTag list) {
        List<BoardSpec> boards = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) boards.add(load(list.getCompound(i)));
        return List.copyOf(boards);
    }

    /** Список дощок у одному тегу — для синхронізованих даних сутності. */
    public static CompoundTag saveWrapped(List<BoardSpec> boards) {
        CompoundTag tag = new CompoundTag();
        tag.put("Boards", saveAll(boards));
        return tag;
    }

    public static List<BoardSpec> loadWrapped(CompoundTag tag) {
        return loadAll(tag.getList("Boards", Tag.TAG_COMPOUND));
    }

    // ── Мережа ───────────────────────────────────────────────────────────

    public void write(FriendlyByteBuf buf) {
        writeVec(buf, a);
        writeVec(buf, b);
        writeVec(buf, normal);
        buf.writeResourceLocation(block);
    }

    public static BoardSpec read(FriendlyByteBuf buf) {
        return new BoardSpec(readVec(buf), readVec(buf), readVec(buf), buf.readResourceLocation());
    }

    private static void putVec(CompoundTag tag, String key, Vec3 v) {
        tag.putDouble(key + "x", v.x);
        tag.putDouble(key + "y", v.y);
        tag.putDouble(key + "z", v.z);
    }

    private static Vec3 getVec(CompoundTag tag, String key) {
        return new Vec3(tag.getDouble(key + "x"), tag.getDouble(key + "y"), tag.getDouble(key + "z"));
    }

    private static void writeVec(FriendlyByteBuf buf, Vec3 v) {
        buf.writeDouble(v.x);
        buf.writeDouble(v.y);
        buf.writeDouble(v.z);
    }

    private static Vec3 readVec(FriendlyByteBuf buf) {
        return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }
}
