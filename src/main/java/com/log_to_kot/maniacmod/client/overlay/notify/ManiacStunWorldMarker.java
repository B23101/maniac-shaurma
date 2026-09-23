package com.log_to_kot.maniacmod.client.overlay.notify;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.overlay.WorldToScreen;
import com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.UUID;

/**
 * 5 зірочок, що обертаються, НАД ГОЛОВОЮ оглушеного маньяка — видно
 * УСІМ гравцям матчу (виживим і самому маньяку теж, дивлячись на себе
 * ззовні неможливо, але тімейти й маньяк одне одного бачать однаково).
 *
 * ── Джерело даних ────────────────────────────────────────────────────
 * {@link ClientMatchState#isManiacStunned(UUID)} — дзеркало серверного
 * {@code ManiacStunModule}, прийшло через {@code ManiacStunPacket}
 * (широкомовний, усім гравцям матчу — див. докстрінг пакета).
 *
 * ── Звідки беремо позицію сутності ─────────────────────────────────────
 * {@code UUID → Player} через {@code self.level().getPlayerByUUID(...)}
 * — той самий перевірений прийом, що вже використовує
 * {@code ManiacAttackGuardMixin#maniacmod$hasTargetInRange} для
 * пошуку сутності виживого на клієнті за UUID з {@link ClientMatchState#roster()}.
 * Свідомо НЕ {@code mc.level.players()} (метод з невизначеною
 * доступністю в цій кодовій базі) — перебираємо ростер (уже
 * синхронізований список усіх гравців матчу) і для кожного, хто зараз
 * оглушений, дістаємо сутність за UUID.
 *
 * ── Проєкція ─────────────────────────────────────────────────────────
 * Той самий {@link WorldToScreen}, що інші world-мітки: видно крізь
 * стіни. На відміну від {@code DownedSurvivorMarker} (окремі
 * координати з пакета — видно по всій карті), тут позиція завжди йде
 * від живої клієнтської сутності, тож мітка природно зникає, коли
 * маньяк виходить за межі трекінгу сутностей клієнта — прийнятно, бо
 * стан оглушення короткий (секунди), гравець фізично не встигає піти
 * далеко.
 */
@OnlyIn(Dist.CLIENT)
public final class ManiacStunWorldMarker {

    private static final int EDGE_MARGIN = 20;
    private static final float OUTER_RADIUS = 4.5f;
    private static final float RING_RADIUS = 11f;

    /** Один повний оберт кільця зірочок за цей час, мс. */
    private static final long ROTATION_PERIOD_MS = 2200L;

    private static final int STAR_ARGB = 0xFFFFE066;

    private ManiacStunWorldMarker() {}

    public static void render(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();
        long now = System.currentTimeMillis();
        float ringRotation = (now % ROTATION_PERIOD_MS) / (float) ROTATION_PERIOD_MS * 360f;

        for (RosterSyncPacket.RosterEntry entry : ClientMatchState.roster()) {
            UUID id = entry.playerId();
            if (!ClientMatchState.isManiacStunned(id)) continue;

            Player player = mc.level.getPlayerByUUID(id);
            if (player == null) continue;

            double wx = player.getX();
            double wy = player.getY() + player.getBbHeight() + 0.35;
            double wz = player.getZ();

            WorldToScreen.Point point = WorldToScreen.project(wx, wy, wz, width, height, EDGE_MARGIN);
            if (point == null) continue;

            drawRing(graphics, point.x(), point.y(), ringRotation);
        }
    }

    /** 5 зірочок рівномірно по колу навколо мітки, кожна ще й сама трохи повернута — «крутяться». */
    private static void drawRing(GuiGraphics graphics, int cx, int cy, float ringRotation) {
        for (int i = 0; i < 5; i++) {
            double angle = Math.toRadians(ringRotation + i * 72.0);
            float sx = cx + (float) (RING_RADIUS * Math.cos(angle));
            float sy = cy + (float) (RING_RADIUS * 0.55 * Math.sin(angle)) - RING_RADIUS * 0.3f;
            StarShape.draw(graphics, sx, sy, OUTER_RADIUS, ringRotation * 2f, STAR_ARGB);
        }
    }
}

