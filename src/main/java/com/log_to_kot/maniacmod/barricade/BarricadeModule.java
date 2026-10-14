package com.log_to_kot.maniacmod.barricade;

import com.log_to_kot.maniacmod.vr.VrServerCompat;

import com.log_to_kot.maniacmod.blocks.BoardsBlock;
import com.log_to_kot.maniacmod.config.BarricadeConfigs;
import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.core.phase.PhaseListener;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.core.phase.Phases;
import com.log_to_kot.maniacmod.entity.BarricadeEntity;
import com.log_to_kot.maniacmod.items.CrowbarItem;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.s2c.barricade.BarricadeGameClosePacket;
import com.log_to_kot.maniacmod.net.s2c.barricade.BarricadeGameOpenPacket;
import com.log_to_kot.maniacmod.net.s2c.barricade.BarricadeNailPacket;
import com.log_to_kot.maniacmod.net.s2c.notify.ActionBarPacket;
import com.log_to_kot.maniacmod.registry.ModBlocks;
import com.log_to_kot.maniacmod.registry.ModSounds;
import dev.shaurmalib.common.overlay.ActionBarMessageType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Барикади (закриті зони): дощки, які виживі ламають ломом.
 *
 * <h3>Два види</h3>
 * <ul>
 *   <li><b>Фіксована</b> — подвійний блок {@code maniacmod:boards} на дверях/стіні (реєстр —
 *       {@link FixedBarricade});</li>
 *   <li><b>Гнучка</b> — довільні дошки (земля, стіна, діагональ): кастомний рендер
 *       {@link BarricadeEntity} + невидимі колайдери (реєстр — {@link FlexibleBarricade}).</li>
 * </ul>
 * Виживий б'є ПКМ ломом по барикаді — відкривається міні-гра «витягни цвяхи» (див. нижче): на
 * кожну дошку по 2 цвяхи, кожен треба затиснути ЛКМ; коли знято всі дошки — барикада
 * розвалюється (частинки + звук). Лом зношується за КОЖЕН витягнутий цвях
 * ({@code barricades.crowbarWearPerNailPercent}), а не за всю барикаду.
 *
 * <h3>Життєвий цикл</h3>
 * Розмітка живе у {@link BarricadeConfigs} (файл), а не в матчі. У LOBBY і на старті розкидання
 * ({@code SCATTER}) модуль приводить світ у відповідність до розмітки ({@link #restoreAll}):
 * ставить фіксовані блоки, створює відсутні гнучкі барикади, прибирає зайві. Посеред матчу
 * синхронізації немає — зламане лишається зламаним до наступного LOBBY.
 */
public final class BarricadeModule implements PhaseListener {

    private static final String ENTITY_SOURCE = "barricade";
    private static final long NEED_CROWBAR_COOLDOWN_MS = 1500;

    private final Supplier<MatchOrchestrator> match;
    private final BarricadeBuilder builder = new BarricadeBuilder(this);
    private final Map<UUID, Long> lastNeedCrowbarMessage = new HashMap<>();

    public BarricadeModule(Supplier<MatchOrchestrator> match) {
        this.match = match;
    }

    @Override
    public String id() {
        return ENTITY_SOURCE;
    }

    public BarricadeBuilder builder() {
        return builder;
    }

    @Override
    public void onPhaseEnter(GamePhase phase, List<net.minecraft.server.level.ServerPlayer> players) {
        // БАГФІКС: раніше прогрес міні-гри скидався на КОЖНОМУ виході з фази — тобто просто коли
        // HUNT змінювався на POWERED (всі генератори полагоджено), витягнуті цвяхи поверталися, а
        // екрани закривались. Тепер гру закриваємо лише коли матч покидає ігрові фази.
        if (!phase.isGameplay()) closeAllGames(players);
        // ROLE_REVEAL — страховка: до першого кадру гри двері гарантовано стоять на місці
        // (restoreAll ідемпотентний, а зламати щось ще ніхто не встиг).
        if (phase == GamePhase.LOBBY || phase == GamePhase.SCATTER || phase == GamePhase.ROLE_REVEAL) {
            MatchOrchestrator m = match.get();
            if (m != null && m.server() != null) restoreAll(m.server());
        }
    }

    // ── Світ ↔ розмітка ──────────────────────────────────────────────────

    /** Приводить світ у відповідність до розмітки. Безпечно викликати повторно. */
    public void restoreAll(MinecraftServer server) {
        for (FixedBarricade fixed : BarricadeConfigs.fixed()) {
            ServerLevel level = levelOf(server, fixed.dimension());
            if (level != null) placeFixed(level, fixed);
        }

        // Гнучкі: рівно одна сутність на зареєстровану барикаду, зайві (видалені/дублі) — геть.
        Set<String> seen = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            List<BarricadeEntity> existing = new ArrayList<>();
            for (Entity e : level.getAllEntities()) {
                if (e instanceof BarricadeEntity b) existing.add(b);
            }
            for (BarricadeEntity b : existing) {
                FlexibleBarricade layout = BarricadeConfigs.findFlexible(b.barricadeId());
                boolean here = layout != null && layout.dimension().equals(dimId(level));
                if (!here || !seen.add(layout.id().toLowerCase())) b.discard();
                else b.clearNailStates();   // новий матч: усі дошки знову стоять
            }
        }
        removeStrayLabels(server);
        for (FlexibleBarricade flexible : BarricadeConfigs.flexible()) {
            if (seen.contains(flexible.id().toLowerCase())) continue;
            spawnFlexible(server, flexible);
        }
    }

    public void spawnFlexible(FlexibleBarricade flexible) {
        MatchOrchestrator m = match.get();
        if (m != null && m.server() != null) spawnFlexible(m.server(), flexible);
    }

    private void spawnFlexible(MinecraftServer server, FlexibleBarricade flexible) {
        ServerLevel level = levelOf(server, flexible.dimension());
        if (level == null) return;
        BarricadeEntity entity = BarricadeEntity.create(level, flexible.id(), flexible.boards());
        level.getChunkAt(BlockPos.containing(entity.position()));
        level.addFreshEntity(entity);
    }

    private static void placeFixed(ServerLevel level, FixedBarricade fixed) {
        level.getChunkAt(fixed.pos());
        BlockState lower = ModBlocks.BOARDS.get().defaultBlockState()
            .setValue(BoardsBlock.FACING, fixed.facing())
            .setValue(BoardsBlock.HALF, DoubleBlockHalf.LOWER);
        BlockState upper = lower.setValue(BoardsBlock.HALF, DoubleBlockHalf.UPPER);
        // 2|16: оновити клієнтів, без перерахунку форми сусідів — інакше перша половина
        // побачила б порожнє місце над собою й зникла.
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        if (level.getBlockState(fixed.pos()) != lower) level.setBlock(fixed.pos(), lower, flags);
        if (level.getBlockState(fixed.pos().above()) != upper) level.setBlock(fixed.pos().above(), upper, flags);
        closeDoorsBehind(level, fixed);
    }

    /**
     * Справжні (ванільні) двері за дошками мають бути ЗАЧИНЕНІ на старті: після попереднього матчу
     * їх могли лишити відкритими, і «заколочений» прохід виглядав відчиненим. Дивимось на площину
     * за панеллю (дверей може бути двоє поруч — перевіряємо по одному блоку вбік).
     */
    private static void closeDoorsBehind(ServerLevel level, FixedBarricade fixed) {
        Direction back = fixed.facing().getOpposite();
        Direction side = fixed.facing().getClockWise();
        for (int depth = 0; depth <= 1; depth++) {
            for (int lateral = -1; lateral <= 1; lateral++) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos p = fixed.pos().relative(back, depth).relative(side, lateral).above(dy);
                    if (!level.hasChunkAt(p)) continue;
                    BlockState state = level.getBlockState(p);
                    if (state.getBlock() instanceof DoorBlock door && state.getValue(DoorBlock.OPEN)) {
                        door.setOpen(null, level, state, p, false);
                    }
                }
            }
        }
    }

    /** Прибирає все, що модуль поставив у світ, за конкретною барикадою. */
    public void removeFlexibleEntities(MinecraftServer server, String id) {
        for (ServerLevel level : server.getAllLevels()) {
            List<BarricadeEntity> found = new ArrayList<>();
            for (Entity e : level.getAllEntities()) {
                if (e instanceof BarricadeEntity b && b.barricadeId().equalsIgnoreCase(id)) found.add(b);
            }
            found.forEach(Entity::discard);
        }
    }

    /** Прибирає фіксований блок зі світу (розмітку не чіпає). */
    public void removeFixedBlocks(ServerLevel level, BlockPos anyHalf) {
        BlockState state = level.getBlockState(anyHalf);
        if (!state.is(ModBlocks.BOARDS.get())) return;
        BlockPos lower = BoardsBlock.lowerHalf(state, anyHalf);
        level.setBlock(lower, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(lower.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    // ── Приціл лома ──────────────────────────────────────────────────────

    /** Влучання променя в гнучку барикаду. */
    public record FlexibleHit(BarricadeEntity entity, double distance) {}

    /**
     * Гнучка барикада під прицілом гравця ПЕРЕД першим блоком на шляху променя (дошка за стіною
     * не вибивається крізь стіну).
     */
    public FlexibleHit findFlexible(ServerPlayer player, double reach) {
        // VR: промінь іде від контролера, а не від голови (див. VrServerCompat).
        Vec3 eye = VrServerCompat.aimOrigin(player);
        Vec3 look = VrServerCompat.aimDir(player);
        HitResult blockHit = VrServerCompat.isVrPlayer(player)
            ? player.level().clip(new net.minecraft.world.level.ClipContext(eye, eye.add(look.scale(reach)),
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player))
            : player.pick(reach, 1.0F, false);
        double blockDist = blockHit.getType() == HitResult.Type.BLOCK
            ? blockHit.getLocation().distanceTo(eye) : reach;

        FlexibleHit best = null;
        for (BarricadeEntity b : player.level().getEntitiesOfClass(BarricadeEntity.class,
                player.getBoundingBox().inflate(reach + 16.0))) {
            double d = b.rayDistance(eye, look, Math.min(reach, blockDist + 0.05));
            if (d >= 0 && (best == null || d < best.distance())) best = new FlexibleHit(b, d);
        }
        return best;
    }

    // ── Міні-гра «витягни цвяхи» (ПКМ ломом відкриває її, далі — ЛКМ на цвяхах) ─────────────

    /** Скільки тіків після відпускання цвях лишається «розхитаним», перш ніж почне осідати назад. */
    private static final int RELAX_GRACE_TICKS = 6;
    /** Наскільки швидко розхитаний цвях осідає назад (тіків прогресу за тік); тягнуть із швидкістю ≥ 1. */
    private static final double RELAX_PER_TICK = 1.5;
    /** Як часто скрипить цвях, поки його тягнуть. */
    private static final int CREAK_INTERVAL_TICKS = 8;
    /** Наскільки далі за дальність лома гравець може відійти, не втрачаючи гру. */
    private static final double LEAVE_MARGIN_BLOCKS = 2.5;
    /** Скільки точно блоків між «центром» панелі фіксованої барикади і її лицьовою стороною. */
    private static final double FIXED_PANEL_OFFSET = 0.40;

    /**
     * Стан ОДНІЄЇ барикади в міні-грі. Живе на сервері, поки барикаду не знято або не змінилась
     * фаза: гравець може закрити екран, повернутись — і цвяхи лишаються витягнутими (розхитані
     * поступово осідають). Кілька виживих можуть працювати над однією барикадою одночасно, кожен
     * на своєму цвяху.
     */
    private static final class Game {
        final String key;
        final ServerLevel level;
        final BarricadeEntity entity;       // гнучка, або null
        final BlockPos fixedLower;          // фіксована, або null
        final int planks;
        final int nails;
        final double[] progress;
        final boolean[] removed;
        final boolean[] lastHeld;
        final int[] lastSent;
        final long[] lastPulled;
        final long[] lastCreak;
        final Map<UUID, Integer> viewers = new LinkedHashMap<>();   // гравець → цвях, який тримає (−1 — жодного)
        final AABB bounds;
        int nailsLeft;
        UUID labelId;       // підпис над барикадою (TextDisplay), або null
        int labelSignature = -1;

        Game(String key, ServerLevel level, BarricadeEntity entity, BlockPos fixedLower, int planks, AABB bounds) {
            this.key = key;
            this.level = level;
            this.entity = entity;
            this.fixedLower = fixedLower;
            this.planks = planks;
            this.nails = planks * BarricadeScene.NAILS_PER_PLANK;
            this.progress = new double[nails];
            this.removed = new boolean[nails];
            this.lastHeld = new boolean[nails];
            this.lastSent = new int[nails];
            this.lastPulled = new long[nails];
            this.lastCreak = new long[nails];
            this.nailsLeft = nails;
            this.bounds = bounds;
        }

        boolean alive() {
            if (entity != null) return !entity.isRemoved();
            return level.getBlockState(fixedLower).is(ModBlocks.BOARDS.get());
        }
    }

    private final Map<String, Game> games = new HashMap<>();
    private final Map<UUID, String> viewing = new HashMap<>();

    /** ПКМ ломом по гнучкій барикаді: відкрити міні-гру. @return true — подію оброблено. */
    public boolean onCrowbarUseOnFlexible(ServerPlayer player, ItemStack held, BarricadeEntity entity) {
        if (entity.boards().isEmpty()) return false;
        return openGame(player, held, "f:" + entity.barricadeId(), entity, null);
    }

    /** ПКМ ломом по блоку-дошках: відкрити міні-гру. @return true — подію оброблено. */
    public boolean onCrowbarUseOnFixed(ServerPlayer player, ItemStack held, ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(ModBlocks.BOARDS.get())) return false;
        BlockPos lower = BoardsBlock.lowerHalf(state, pos);
        return openGame(player, held, "b:" + dimId(level) + lower.asLong(), null, lower);
    }

    private boolean openGame(ServerPlayer player, ItemStack held, String key,
                             BarricadeEntity entity, BlockPos fixedLower) {
        if (!mayInteract(player)) return false;
        if (!checkCrowbar(player, held)) return true;
        ServerLevel level = (ServerLevel) player.level();

        Game game = games.get(key);
        if (game != null && !game.alive()) {
            dropGame(game);
            game = null;
        }
        if (game == null) {
            game = entity != null
                ? new Game(key, level, entity, null, entity.boards().size(), BarricadeGeometry.bounds(entity.boards()))
                : new Game(key, level, null, fixedLower, BarricadeScene.fixedPlankCount(),
                    new AABB(fixedLower).expandTowards(0, 1, 0).inflate(0.5));
            games.put(key, game);
        }
        updateLabel(game);

        String previous = viewing.get(player.getUUID());
        if (previous != null && !previous.equals(key)) leaveGame(player.getUUID());
        game.viewers.putIfAbsent(player.getUUID(), -1);
        viewing.put(player.getUUID(), key);
        ModNetwork.toPlayer(player, openPacket(game, player));
        return true;
    }

    private static BarricadeGameOpenPacket openPacket(Game game, ServerPlayer player) {
        BitSet removed = new BitSet(game.nails);
        List<Integer> loose = new ArrayList<>();
        for (int i = 0; i < game.nails; i++) {
            if (game.removed[i]) removed.set(i);
            else if (game.progress[i] >= 1.0) loose.add(i);
        }
        int[] looseNails = new int[loose.size()];
        int[] looseProgress = new int[loose.size()];
        for (int i = 0; i < looseNails.length; i++) {
            looseNails[i] = loose.get(i);
            looseProgress[i] = (int) game.progress[looseNails[i]];
        }
        Vec3 look = player.getLookAngle();
        return new BarricadeGameOpenPacket(game.entity == null, game.key,
            game.entity == null ? List.of() : game.entity.boards(),
            (float) look.x, (float) look.z, ManiacConfigs.get(ConfigSchema.BARRICADE_NAIL_PULL_TICKS),
            removed.toLongArray(), looseNails, looseProgress);
    }

    /** C2S: гравець затиснув цвях {@code nail} або відпустив ({@code -1}). */
    public void onNailHold(ServerPlayer player, int nail) {
        Game game = gameOf(player.getUUID());
        if (game == null) return;
        if (nail < 0 || nail >= game.nails || game.removed[nail]) {
            game.viewers.put(player.getUUID(), -1);
            return;
        }
        for (var entry : game.viewers.entrySet()) {
            if (!entry.getKey().equals(player.getUUID()) && entry.getValue() == nail) return;   // цвях зайнято
        }
        game.viewers.put(player.getUUID(), nail);
    }

    /** C2S: гравець закрив екран. Прогрес барикади лишається. */
    public void onGameExit(ServerPlayer player) {
        leaveGame(player.getUUID());
    }

    private Game gameOf(UUID player) {
        String key = viewing.get(player);
        return key == null ? null : games.get(key);
    }

    private void leaveGame(UUID player) {
        String key = viewing.remove(player);
        Game game = key == null ? null : games.get(key);
        if (game != null) game.viewers.remove(player);
    }

    private void dropGame(Game game) {
        for (UUID viewer : game.viewers.keySet()) viewing.remove(viewer);
        games.remove(game.key);
        removeLabel(game);
    }

    @Override
    public void onPhaseTick(GamePhase phase, List<ServerPlayer> players, long ticksInPhase) {
        if (games.isEmpty()) return;
        Map<UUID, ServerPlayer> byId = new HashMap<>();
        for (ServerPlayer p : players) byId.put(p.getUUID(), p);
        for (Game game : new ArrayList<>(games.values())) {
            if (!game.alive()) {
                closeViewers(game, byId, BarricadeGameClosePacket.STATE);
                dropGame(game);
                continue;
            }
            tickGame(game, byId);
        }
    }

    private void tickGame(Game game, Map<UUID, ServerPlayer> byId) {
        long now = game.level.getGameTime();
        int required = ManiacConfigs.get(ConfigSchema.BARRICADE_NAIL_PULL_TICKS);
        double bonus = ManiacConfigs.get(ConfigSchema.BARRICADE_HEARTBEAT_SPEED_BONUS);

        // 1. Кому ще можна грати. Поки гравець у грі — його цвях тягнеться з його швидкістю.
        Map<Integer, Float> pulling = new HashMap<>();
        Map<Integer, ServerPlayer> puller = new HashMap<>();
        for (var it = game.viewers.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            ServerPlayer player = byId.get(entry.getKey());
            int closeReason = player == null ? -2 : invalidReason(player, game);
            if (closeReason != -1) {
                if (closeReason >= 0) {
                    ModNetwork.toPlayer(player, new BarricadeGameClosePacket((byte) closeReason));
                    notifyClosed(player, closeReason);
                }
                viewing.remove(entry.getKey());
                it.remove();
                continue;
            }
            int nail = entry.getValue();
            if (nail >= 0 && !game.removed[nail]) {
                float heartbeat = match.get().survivors().heartbeatOf(player);
                pulling.put(nail, 1f + (float) (bonus * heartbeat));
                puller.put(nail, player);
            }
        }

        // 2. Прогрес цвяхів.
        for (int i = 0; i < game.nails; i++) {
            if (game.removed[i]) continue;
            Float rate = pulling.get(i);
            boolean held = rate != null;
            if (held) {
                game.progress[i] = Math.min(required, game.progress[i] + rate);
                game.lastPulled[i] = now;
                if (now - game.lastCreak[i] >= CREAK_INTERVAL_TICKS) {
                    game.lastCreak[i] = now;
                    nailCreakEffects(game, i, (float) (game.progress[i] / required));
                }
                if (game.progress[i] >= required) {
                    if (nailOut(game, i, puller.get(i), byId)) return;   // барикаду знято
                    continue;
                }
            } else if (game.progress[i] > 0 && now - game.lastPulled[i] > RELAX_GRACE_TICKS) {
                game.progress[i] = Math.max(0, game.progress[i] - RELAX_PER_TICK);
            }
            int shown = (int) game.progress[i];
            if (shown != game.lastSent[i] || held != game.lastHeld[i]) {
                game.lastSent[i] = shown;
                game.lastHeld[i] = held;
                broadcast(game, byId, new BarricadeNailPacket(i, shown, required, held, held ? rate : 1f));
            }
        }
        publishWorldState(game, required);
        updateLabel(game);
    }

    // ── Стан барикади у світі (дошки провисають, падають, лишається N) ───

    /**
     * Публікує стан цвяхів у сутність гнучкої барикади: клієнт за ним малює розхитані, провислі й
     * впалі дошки. Фіксована барикада — блок, у неї власної сутності немає, тож вона показує лише
     * підпис і частинки.
     */
    private void publishWorldState(Game game, int required) {
        if (game.entity == null) return;
        byte[] states = new byte[game.nails];
        for (int i = 0; i < game.nails; i++) {
            if (game.removed[i]) {
                states[i] = BarricadeEntity.NAIL_OUT;
            } else {
                int step = (int) (game.progress[i] / required * 20.0);
                states[i] = (byte) Math.max(0, Math.min(19, step) * 5);
            }
        }
        game.entity.setNailStates(states);
    }

    private static int fallenPlanks(Game game) {
        int fallen = 0;
        for (int plank = 0; plank < game.planks; plank++) {
            boolean a = game.removed[plank * BarricadeScene.NAILS_PER_PLANK];
            boolean b = game.removed[plank * BarricadeScene.NAILS_PER_PLANK + 1];
            if (a && b) fallen++;
        }
        return fallen;
    }

    private static final String LABEL_TAG = "maniac_barricade_label";

    /** Підпис «Дошок лишилось / Цвяхів лишилось» над барикадою; оновлюється, лише коли змінились числа. */
    private void updateLabel(Game game) {
        int fallen = fallenPlanks(game);
        int signature = fallen * 10_000 + game.nailsLeft;
        if (signature == game.labelSignature && game.labelId != null) return;
        game.labelSignature = signature;

        double x = game.bounds.getCenter().x;
        double z = game.bounds.getCenter().z;
        double y = game.entity != null ? game.bounds.maxY + 0.35 : game.fixedLower.getY() + 2.4;

        Component text = Component.translatable("maniacmod.barricade.world.planks",
                game.planks - fallen, game.planks).withStyle(ChatFormatting.GOLD)
            .append("\n")
            .append(Component.translatable("maniacmod.barricade.world.nails",
                game.nailsLeft, game.nails).withStyle(ChatFormatting.GRAY));

        CompoundTag tag = new CompoundTag();
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(y));
        pos.add(DoubleTag.valueOf(z));
        tag.put("Pos", pos);
        ListTag motion = new ListTag();
        for (int i = 0; i < 3; i++) motion.add(DoubleTag.valueOf(0.0));
        tag.put("Motion", motion);
        ListTag rotation = new ListTag();
        rotation.add(FloatTag.valueOf(0f));
        rotation.add(FloatTag.valueOf(0f));
        tag.put("Rotation", rotation);
        ListTag tags = new ListTag();
        tags.add(StringTag.valueOf(LABEL_TAG));
        tag.put("Tags", tags);
        tag.putString("text", Component.Serializer.toJson(text));
        tag.putString("billboard", "center");
        tag.putInt("background", 0x66000000);
        tag.putInt("line_width", 220);
        tag.putFloat("view_range", 0.35f);

        Entity existing = game.labelId == null ? null : game.level.getEntity(game.labelId);
        if (existing instanceof Display.TextDisplay label) {
            label.load(tag);
            return;
        }
        Display.TextDisplay label = EntityType.TEXT_DISPLAY.create(game.level);
        if (label == null) return;
        label.load(tag);
        if (game.level.addFreshEntity(label)) game.labelId = label.getUUID();
    }

    private static void removeLabel(Game game) {
        if (game.labelId == null) return;
        Entity label = game.level.getEntity(game.labelId);
        if (label != null) label.discard();
        game.labelId = null;
    }

    /** Підписи, що лишились від попередньої сесії (перезапуск сервера посеред матчу). */
    private static void removeStrayLabels(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            List<Entity> stray = new ArrayList<>();
            for (Entity e : level.getAllEntities()) {
                if (e instanceof Display.TextDisplay && e.getTags().contains(LABEL_TAG)) stray.add(e);
            }
            stray.forEach(Entity::discard);
        }
    }

    /** −1 — усе гаразд; ≥ 0 — причина закрити (BarricadeGameClosePacket); −2 — гравця вже немає. */
    private int invalidReason(ServerPlayer player, Game game) {
        if (player.level() != game.level) return BarricadeGameClosePacket.STATE;
        if (!mayInteract(player)) return BarricadeGameClosePacket.STATE;
        ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof CrowbarItem) || CrowbarItem.isBroken(held)) {
            return BarricadeGameClosePacket.CROWBAR;
        }
        double reach = ManiacConfigs.get(ConfigSchema.BARRICADE_CROWBAR_RANGE_BLOCKS) + LEAVE_MARGIN_BLOCKS;
        if (game.bounds.distanceToSqr(player.getEyePosition()) > reach * reach) return BarricadeGameClosePacket.FAR;
        return -1;
    }

    private void notifyClosed(ServerPlayer player, int reason) {
        String key = switch (reason) {
            case BarricadeGameClosePacket.FAR -> "maniacmod.barricade.game_far";
            case BarricadeGameClosePacket.CROWBAR -> "maniacmod.barricade.need_crowbar";
            default -> null;
        };
        if (key != null) ModNetwork.toPlayer(player, new ActionBarPacket(ActionBarMessageType.ERROR, key));
    }

    private void closeViewers(Game game, Map<UUID, ServerPlayer> byId, byte reason) {
        for (UUID id : game.viewers.keySet()) {
            ServerPlayer p = byId.get(id);
            if (p != null) ModNetwork.toPlayer(p, new BarricadeGameClosePacket(reason));
        }
    }

    private void broadcast(Game game, Map<UUID, ServerPlayer> byId, BarricadeNailPacket packet) {
        for (UUID id : game.viewers.keySet()) {
            ServerPlayer p = byId.get(id);
            if (p != null) ModNetwork.toPlayer(p, packet);
        }
    }

    /**
     * Цвях вийшов повністю. Коли обидва цвяхи дошки вийшли — дошка падає; коли не лишилось цвяхів —
     * барикада розвалюється.
     *
     * @return true, якщо барикаду знято (гру закрито)
     */
    private boolean nailOut(Game game, int nail, ServerPlayer by, Map<UUID, ServerPlayer> byId) {
        int required = ManiacConfigs.get(ConfigSchema.BARRICADE_NAIL_PULL_TICKS);
        game.removed[nail] = true;
        game.progress[nail] = required;
        game.nailsLeft--;
        for (var entry : game.viewers.entrySet()) {
            if (entry.getValue() == nail) entry.setValue(-1);
        }
        broadcast(game, byId, new BarricadeNailPacket(nail, required, required, false, 1f));
        nailPoppedEffects(game, nail);
        wearCrowbarForNail(game, by);

        int plank = nail / BarricadeScene.NAILS_PER_PLANK;
        int other = nail ^ 1;
        if (game.removed[other]) boardFellEffects(game, plank);

        if (game.nailsLeft > 0) return false;
        finish(game, by, byId);
        return true;
    }

    /**
     * Лом зношується за КОЖЕН витягнутий цвях. Зламався посеред роботи — гравця одразу
     * викидає з гри (цвях, що вилетів останнім, йому ще зараховано).
     */
    private void wearCrowbarForNail(Game game, ServerPlayer by) {
        if (by == null) return;
        ItemStack held = by.getMainHandItem();
        if (!(held.getItem() instanceof CrowbarItem)) return;
        CrowbarItem.wear(held, ManiacConfigs.get(ConfigSchema.BARRICADE_CROWBAR_WEAR_PER_NAIL_PERCENT));
        if (!CrowbarItem.breakIfWornOut(by, held)) return;
        ModNetwork.toPlayer(by, new BarricadeGameClosePacket(BarricadeGameClosePacket.CROWBAR));
        ModNetwork.toPlayer(by, new ActionBarPacket(ActionBarMessageType.ERROR, "maniacmod.trap.crowbar_broke"));
        game.viewers.remove(by.getUUID());
        viewing.remove(by.getUUID());
    }

    private void finish(Game game, ServerPlayer by, Map<UUID, ServerPlayer> byId) {
        Vec3 center;
        if (game.entity != null) {
            breakFlexibleEffects(game.entity);
            center = BarricadeGeometry.centroid(game.entity.boards());
            game.entity.discard();
        } else {
            BlockPos lower = game.fixedLower;
            game.level.levelEvent(2001, lower, Block.getId(game.level.getBlockState(lower)));
            game.level.levelEvent(2001, lower.above(), Block.getId(game.level.getBlockState(lower.above())));
            removeFixedBlocks(game.level, lower);
            center = Vec3.atCenterOf(lower).add(0, 0.5, 0);
        }
        game.level.playSound(null, center.x, center.y, center.z, ModSounds.BARRICADE_COLLAPSE.get(),
            SoundSource.BLOCKS, 1.0f, 0.95f + game.level.random.nextFloat() * 0.1f);
        closeViewers(game, byId, BarricadeGameClosePacket.DONE);
        dropGame(game);
        if (by != null) afterBreak(by);
    }

    // ── Місце цвяха у світі (звуки й частинки) ───────────────────────────

    private Vec3 nailWorldPos(Game game, int nail) {
        if (game.entity != null) {
            List<BoardSpec> boards = game.entity.boards();
            BoardSpec board = boards.get(Math.min(boards.size() - 1, nail / BarricadeScene.NAILS_PER_PLANK));
            Vec3 p = nail % BarricadeScene.NAILS_PER_PLANK == 0 ? board.a() : board.b();
            return p.add(board.normal().scale(0.05));
        }
        // Фіксована: точка зі сцени (X — праворуч від глядача, Y — від низу), накладена на панель блока.
        BarricadeScene scene = BarricadeScene.fixed();
        BarricadeScene.Nail n = scene.nails().get(Math.min(scene.nailCount() - 1, nail));
        BlockState state = game.level.getBlockState(game.fixedLower);
        Direction facing = state.is(ModBlocks.BOARDS.get()) ? state.getValue(BoardsBlock.FACING) : Direction.NORTH;
        double fx = facing.getStepX(), fz = facing.getStepZ();
        double rightX = fz, rightZ = -fx;            // (−facing) × up
        BlockPos lower = game.fixedLower;
        return new Vec3(
            lower.getX() + 0.5 - fx * FIXED_PANEL_OFFSET + rightX * (n.x() - 0.5),
            lower.getY() + n.y(),
            lower.getZ() + 0.5 - fz * FIXED_PANEL_OFFSET + rightZ * (n.x() - 0.5));
    }

    private BlockState plankState(Game game, int plank) {
        ResourceLocation id;
        if (game.entity != null) {
            List<BoardSpec> boards = game.entity.boards();
            id = boards.get(Math.min(boards.size() - 1, plank)).block();
        } else {
            BarricadeScene scene = BarricadeScene.fixed();
            id = scene.planks().get(Math.min(scene.plankCount() - 1, plank)).block();
        }
        Block block = ForgeRegistries.BLOCKS.getValue(id);
        return block == null || block == Blocks.AIR ? Blocks.OAK_PLANKS.defaultBlockState() : block.defaultBlockState();
    }

    /** Цвях тягнуть: лише тріски (без звуку — звуки дає виліт цвяха й падіння дошки). */
    private void nailCreakEffects(Game game, int nail, float fraction) {
        Vec3 at = nailWorldPos(game, nail);
        game.level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, plankState(game, nail / 2)),
            at.x, at.y, at.z, 2, 0.05, 0.05, 0.05, 0.02);
    }

    /** Цвях вискочив. */
    private void nailPoppedEffects(Game game, int nail) {
        Vec3 at = nailWorldPos(game, nail);
        game.level.playSound(null, at.x, at.y, at.z, ModSounds.NAIL_POP.get(), SoundSource.BLOCKS,
            1.0f, 0.92f + game.level.random.nextFloat() * 0.16f);
        game.level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, plankState(game, nail / 2)),
            at.x, at.y, at.z, 8, 0.12, 0.12, 0.12, 0.06);
    }

    /** Обидва цвяхи дошки вийшли — вона падає. */
    private void boardFellEffects(Game game, int plank) {
        Vec3 a = nailWorldPos(game, plank * 2);
        Vec3 b = nailWorldPos(game, plank * 2 + 1);
        Vec3 mid = a.add(b).scale(0.5);
        game.level.playSound(null, mid.x, mid.y, mid.z, ModSounds.BOARD_FALL.get(), SoundSource.BLOCKS,
            1.0f, 0.9f + game.level.random.nextFloat() * 0.2f);
        game.level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, plankState(game, plank)),
            mid.x, mid.y, mid.z, 14, 0.3, 0.2, 0.3, 0.08);
    }

    /** Матч покинув ігрові фази: екрани закриваються, прогрес міні-гри й підписи зникають. */
    private void closeAllGames(List<ServerPlayer> players) {
        Map<UUID, ServerPlayer> byId = new HashMap<>();
        for (ServerPlayer p : players) byId.put(p.getUUID(), p);
        for (Game game : games.values()) {
            closeViewers(game, byId, BarricadeGameClosePacket.STATE);
            removeLabel(game);
        }
        games.clear();
        viewing.clear();
    }

    /** Гравець вийшов із гри — його місце в міні-грі звільняється. */
    public void onPlayerGone(UUID player) {
        leaveGame(player);
    }

    /** Чи може цей гравець зараз виламувати (роль, фаза, стан). Тихо — без повідомлень. */
    private boolean mayInteract(ServerPlayer player) {
        MatchOrchestrator m = match.get();
        if (m == null) return false;
        if (!m.isSurvivor(player.getUUID())) return false;
        if (!Phases.isGameplay() || !Phases.allows(PhaseRule.ITEM_USE)) return false;
        var state = m.survivorStateOf(player.getUUID());
        return state == null || !state.isCrawlOnly();
    }

    private boolean checkCrowbar(ServerPlayer player, ItemStack held) {
        if (!(held.getItem() instanceof CrowbarItem)) {
            long now = System.currentTimeMillis();
            Long last = lastNeedCrowbarMessage.get(player.getUUID());
            if (last == null || now - last >= NEED_CROWBAR_COOLDOWN_MS) {
                lastNeedCrowbarMessage.put(player.getUUID(), now);
                ModNetwork.toPlayer(player, new ActionBarPacket(ActionBarMessageType.ERROR,
                    "maniacmod.barricade.need_crowbar"));
            }
            return false;
        }
        if (CrowbarItem.isBroken(held)) {
            ModNetwork.toPlayer(player, new ActionBarPacket(ActionBarMessageType.ERROR,
                "maniacmod.trap.crowbar_broken"));
            return false;
        }
        return true;
    }

    private void afterBreak(ServerPlayer player) {
        player.swing(InteractionHand.MAIN_HAND, true);
        // Лом міг розсипатись на останньому цвяху — тоді кажемо про це.
        boolean crowbarLeft = player.getMainHandItem().getItem() instanceof CrowbarItem;
        ModNetwork.toPlayer(player, new ActionBarPacket(ActionBarMessageType.SUCCESS,
            crowbarLeft ? "maniacmod.barricade.broken" : "maniacmod.barricade.broken_crowbar_gone"));
    }

    private void breakFlexibleEffects(BarricadeEntity entity) {
        if (!(entity.level() instanceof ServerLevel level)) return;
        List<BoardSpec> all = entity.boards();
        for (int i = 0; i < all.size(); i++) {
            if (entity.isBoardFallen(i)) continue;   // впала дошка вже розсипалась при падінні
            BoardSpec board = all.get(i);
            Block block = ForgeRegistries.BLOCKS.getValue(board.block());
            BlockState state = block == null || block == Blocks.AIR
                ? Blocks.OAK_PLANKS.defaultBlockState() : block.defaultBlockState();
            Vec3 mid = board.a().add(board.b()).scale(0.5);
            int count = Math.max(6, (int) (BarricadeGeometry.length(board) * 8));
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
                mid.x, mid.y, mid.z, count,
                BarricadeGeometry.length(board) * 0.25, 0.25, BarricadeGeometry.length(board) * 0.25, 0.15);
            level.playSound(null, mid.x, mid.y, mid.z, SoundEvents.WOOD_BREAK, SoundSource.BLOCKS,
                1.0f, 0.75f + level.random.nextFloat() * 0.4f);
        }
    }

    // ── Допоміжне ────────────────────────────────────────────────────────

    public static String dimId(Level level) {
        return level.dimension().location().toString();
    }

    private static ServerLevel levelOf(MinecraftServer server, String dimension) {
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }
}
