package com.log_to_kot.maniacmod.barricade;

import com.log_to_kot.maniacmod.config.BarricadeConfigs;
import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.s2c.barricade.BarricadeBuildStatePacket;
import com.log_to_kot.maniacmod.net.s2c.notify.ActionBarPacket;
import dev.shaurmalib.common.overlay.ActionBarMessageType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Адмінський режим будування гнучкої барикади (сервер).
 *
 * <h3>Як будується</h3>
 * {@code /maniac barricade build} вмикає режим. Далі ПКМ по поверхні: перший клік — точка 1,
 * другий — точка 2 і з'являється дошка між ними; так скільки завгодно разів. Текстура дошки —
 * блок у руці (порожня/небудівельна рука — остання використана). ПКМ по готовій дошці ІНШИМ
 * блоком міняє текстуру ВСІХ нерегістрованих дощок. Shift+ПКМ по дошці — прибрати її (випадково
 * поставлену); без дошки під прицілом — скасувати незавершену першу точку.
 * {@code /maniac barricade create} реєструє весь буфер однією командою (без прицілювання):
 * далі текстури вже не змінити — лише видалити барикаду й створити заново.
 *
 * <h3>Чия тут правда</h3>
 * Буфер живе на сервері; клієнту йде {@link BarricadeBuildStatePacket}, щоб він малював буфер і
 * прев'ю. Клік приходить як {@code RightClickBlock} (точка влучання від клієнта, перевірена
 * ваніллю за досяжністю), тому окремого C2S-пакета немає.
 */
public final class BarricadeBuilder {

    private static final ResourceLocation DEFAULT_BLOCK = new ResourceLocation("minecraft", "oak_planks");

    private static final class Session {
        boolean active;
        final List<BoardSpec> buffer = new ArrayList<>();
        Vec3 pending;
        ResourceLocation lastBlock = DEFAULT_BLOCK;
    }

    private final BarricadeModule module;
    private final Map<UUID, Session> sessions = new HashMap<>();

    BarricadeBuilder(BarricadeModule module) {
        this.module = module;
    }

    public boolean isActive(UUID id) {
        Session s = sessions.get(id);
        return s != null && s.active;
    }

    /** Увімкнути/вимкнути режим. {@code force == null} — перемкнути. @return новий стан. */
    public boolean toggle(ServerPlayer player, Boolean force) {
        Session s = sessions.computeIfAbsent(player.getUUID(), k -> new Session());
        s.active = force != null ? force : !s.active;
        if (!s.active) s.pending = null;
        sync(player, s);
        notify(player, ActionBarMessageType.INFO,
            s.active ? "maniacmod.barricade.build_on" : "maniacmod.barricade.build_off");
        return s.active;
    }

    public void drop(UUID id) {
        sessions.remove(id);
    }

    public void clearAll() {
        sessions.clear();
    }

    public void clearBuffer(ServerPlayer player) {
        Session s = sessions.get(player.getUUID());
        if (s == null) return;
        s.buffer.clear();
        s.pending = null;
        sync(player, s);
    }

    public int bufferSize(ServerPlayer player) {
        Session s = sessions.get(player.getUUID());
        return s == null ? 0 : s.buffer.size();
    }

    // ── Клік у режимі ────────────────────────────────────────────────────

    public void onUse(ServerPlayer player, BlockHitResult hit, ItemStack held) {
        Session s = sessions.get(player.getUUID());
        if (s == null || !s.active) return;

        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();

        if (player.isShiftKeyDown()) {
            int idx = pick(s, eye, look, 8.0);
            if (idx >= 0) {
                s.buffer.remove(idx);
                sync(player, s);
                notify(player, ActionBarMessageType.INFO, "maniacmod.barricade.board_removed",
                    String.valueOf(s.buffer.size()));
            } else if (s.pending != null) {
                s.pending = null;
                sync(player, s);
                notify(player, ActionBarMessageType.INFO, "maniacmod.barricade.point_cancelled");
            }
            return;
        }

        ResourceLocation heldBlock = blockOf(held);
        Vec3 hitLoc = hit.getLocation();

        // Клік по готовій дошці ІНШИМ блоком — нова текстура для всього буфера.
        if (s.pending == null && heldBlock != null) {
            int idx = pick(s, eye, look, eye.distanceTo(hitLoc) + 0.01);
            if (idx >= 0 && !heldBlock.equals(s.buffer.get(idx).block())) {
                s.buffer.replaceAll(b -> b.withBlock(heldBlock));
                s.lastBlock = heldBlock;
                sync(player, s);
                notify(player, ActionBarMessageType.SUCCESS, "maniacmod.barricade.retextured",
                    String.valueOf(s.buffer.size()));
                return;
            }
        }
        if (heldBlock != null) s.lastBlock = heldBlock;

        Vec3 point = hitLoc.add(Vec3.atLowerCornerOf(hit.getDirection().getNormal())
            .scale(BarricadeGeometry.SURFACE_OFFSET));

        if (s.pending == null) {
            if (s.buffer.size() >= ManiacConfigs.get(ConfigSchema.BARRICADE_MAX_BOARDS)) {
                notify(player, ActionBarMessageType.ERROR, "maniacmod.barricade.too_many_boards");
                return;
            }
            s.pending = point;
            sync(player, s);
            return;
        }

        double length = s.pending.distanceTo(point);
        if (length < BarricadeGeometry.MIN_LENGTH) {
            notify(player, ActionBarMessageType.ERROR, "maniacmod.barricade.board_too_short");
            return;
        }
        if (length > ManiacConfigs.get(ConfigSchema.BARRICADE_MAX_BOARD_LENGTH_BLOCKS)) {
            notify(player, ActionBarMessageType.ERROR, "maniacmod.barricade.board_too_long");
            return;
        }
        Vec3 normal = BarricadeGeometry.faceNormal(s.pending, point, eye);
        s.buffer.add(new BoardSpec(s.pending, point, normal, s.lastBlock));
        s.pending = null;
        sync(player, s);
    }

    // ── Реєстрація ───────────────────────────────────────────────────────

    /** @return null при успіху, інакше ключ перекладу помилки. */
    public String create(ServerPlayer player, String requestedId) {
        Session s = sessions.get(player.getUUID());
        if (s == null || s.buffer.isEmpty()) return "maniacmod.barricade.err_empty_buffer";
        String id = requestedId != null ? requestedId : BarricadeConfigs.nextId();
        FlexibleBarricade barricade = new FlexibleBarricade(id,
            player.level().dimension().location().toString(), s.buffer);
        if (!BarricadeConfigs.addFlexible(barricade)) return "maniacmod.barricade.err_id_taken";
        module.spawnFlexible(barricade);
        s.buffer.clear();
        s.pending = null;
        sync(player, s);
        return null;
    }

    // ── Допоміжне ────────────────────────────────────────────────────────

    private static int pick(Session s, Vec3 eye, Vec3 look, double maxLen) {
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < s.buffer.size(); i++) {
            double d = BarricadeGeometry.rayDistance(s.buffer.get(i), eye, look, maxLen);
            if (d >= 0 && d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    private static ResourceLocation blockOf(ItemStack held) {
        if (!(held.getItem() instanceof BlockItem item)) return null;
        return ForgeRegistries.BLOCKS.getKey(item.getBlock());
    }

    private static void sync(ServerPlayer player, Session s) {
        ModNetwork.toPlayer(player, new BarricadeBuildStatePacket(
            s.active, s.active ? s.buffer : List.of(), s.active ? s.pending : null));
    }

    private static void notify(ServerPlayer player, ActionBarMessageType type, String key, String... args) {
        ModNetwork.toPlayer(player, new ActionBarPacket(type, key, args));
    }
}
