package com.log_to_kot.maniacmod.cinematic;

import com.log_to_kot.maniacmod.ManiacMod;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.Collection;
import java.util.List;

/**
 * Renders "cinematic frames" by sending custom map data packets to players.
 *
 * How it works:
 *  - Each "frame" is a 128×128 pixel image baked into the mod as a byte array
 *  - We send a ClientboundMapItemDataPacket with the pixel data directly
 *  - Players hold a map item; the map displays our custom image
 *  - By sending frames rapidly we create a "video" effect
 *
 * Frame IDs used: 9000–9099 (reserved for cinematic, won't conflict with real maps)
 */
public class CinematicMapRenderer {

    public static final int MAP_ID_CINEMATIC = 9000;
    public static final int MAP_SIZE = 128;

    /**
     * Send a single 128×128 frame to all players.
     * @param players  recipients
     * @param pixels   128×128 = 16384 bytes, each byte is a Minecraft map color index
     */
    public static void sendFrame(List<ServerPlayer> players, byte[] pixels) {
        if (pixels.length != MAP_SIZE * MAP_SIZE) {
            ManiacMod.LOGGER.warn("[CinematicMap] Invalid frame size: {}", pixels.length);
            return;
        }

        var patch = new MapItemSavedData.MapPatch(0, 0, MAP_SIZE, MAP_SIZE, pixels);

        ClientboundMapItemDataPacket packet = new ClientboundMapItemDataPacket(
            MAP_ID_CINEMATIC,
            (byte) 0,        // scale (0 = 1 block per pixel)
            false,           // locked
            List.of(),       // decorations (empty)
            patch
        );

        players.forEach(p -> p.connection.send(packet));
    }

    /**
     * Give each player a map item tuned to MAP_ID_CINEMATIC.
     * Called before the cinematic starts so the map is visible in hand.
     */
    public static void giveMapItem(ServerPlayer player) {
        net.minecraft.world.item.ItemStack mapStack =
            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.FILLED_MAP);
        mapStack.getOrCreateTag().putInt("map", MAP_ID_CINEMATIC);
        // Save old hotbar slot 0 item, replace temporarily
        player.getInventory().setItem(0, mapStack);
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(0));
    }

    /**
     * Remove the cinematic map from player's hand and restore slot.
     */
    public static void removeMapItem(ServerPlayer player) {
        player.getInventory().setItem(0, net.minecraft.world.item.ItemStack.EMPTY);
    }

    // ── Frame generators ─────────────────────────────────────────────────────

    /**
     * Generate a solid color frame.
     * @param mapColor Minecraft map color index (see MapColor)
     */
    public static byte[] solidFrame(byte mapColor) {
        byte[] pixels = new byte[MAP_SIZE * MAP_SIZE];
        java.util.Arrays.fill(pixels, mapColor);
        return pixels;
    }

    /**
     * Generate a frame with a centered text rendered as simple pixel art.
     * Uses a very basic 5×7 bitmap font.
     */
    public static byte[] textFrame(byte bgColor, byte fgColor, String... lines) {
        byte[] pixels = solidFrame(bgColor);
        // Simple pixel text rendering — each char is 5×7
        int startY = (MAP_SIZE - lines.length * 9) / 2;
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            int startX = (MAP_SIZE - line.length() * 6) / 2;
            int y = startY + li * 9;
            for (int ci = 0; ci < line.length(); ci++) {
                drawChar(pixels, startX + ci * 6, y, line.charAt(ci), fgColor);
            }
        }
        return pixels;
    }

    // ── Minimal 5×7 bitmap font ───────────────────────────────────────────────

    private static void drawChar(byte[] pixels, int x, int y, char c, byte color) {
        int[][] bitmap = FONT.getOrDefault(c, FONT.get('?'));
        if (bitmap == null) return;
        for (int row = 0; row < bitmap.length && row < 7; row++) {
            for (int col = 0; col < bitmap[row].length && col < 5; col++) {
                if (bitmap[row][col] == 1) {
                    int px = x + col;
                    int py = y + row;
                    if (px >= 0 && px < MAP_SIZE && py >= 0 && py < MAP_SIZE) {
                        pixels[py * MAP_SIZE + px] = color;
                    }
                }
            }
        }
    }

    // Minimal font — only capital letters and digits needed for the cinematic
    private static final java.util.Map<Character, int[][]> FONT = new java.util.HashMap<>();

    static {
        // A
        FONT.put('A', new int[][]{
            {0,1,1,1,0},{1,0,0,0,1},{1,1,1,1,1},{1,0,0,0,1},{1,0,0,0,1},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('B', new int[][]{
            {1,1,1,1,0},{1,0,0,0,1},{1,1,1,1,0},{1,0,0,0,1},{1,1,1,1,0},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('C', new int[][]{
            {0,1,1,1,0},{1,0,0,0,1},{1,0,0,0,0},{1,0,0,0,1},{0,1,1,1,0},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('D', new int[][]{
            {1,1,1,0,0},{1,0,0,1,0},{1,0,0,0,1},{1,0,0,1,0},{1,1,1,0,0},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('E', new int[][]{
            {1,1,1,1,1},{1,0,0,0,0},{1,1,1,1,0},{1,0,0,0,0},{1,1,1,1,1},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('F', new int[][]{
            {1,1,1,1,1},{1,0,0,0,0},{1,1,1,1,0},{1,0,0,0,0},{1,0,0,0,0},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('G', new int[][]{
            {0,1,1,1,0},{1,0,0,0,0},{1,0,1,1,1},{1,0,0,0,1},{0,1,1,1,1},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('H', new int[][]{
            {1,0,0,0,1},{1,0,0,0,1},{1,1,1,1,1},{1,0,0,0,1},{1,0,0,0,1},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('I', new int[][]{
            {1,1,1,1,1},{0,0,1,0,0},{0,0,1,0,0},{0,0,1,0,0},{1,1,1,1,1},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('J', new int[][]{
            {0,0,0,0,1},{0,0,0,0,1},{0,0,0,0,1},{1,0,0,0,1},{0,1,1,1,0},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('K', new int[][]{
            {1,0,0,0,1},{1,0,0,1,0},{1,1,1,0,0},{1,0,0,1,0},{1,0,0,0,1},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('L', new int[][]{
            {1,0,0,0,0},{1,0,0,0,0},{1,0,0,0,0},{1,0,0,0,0},{1,1,1,1,1},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('M', new int[][]{
            {1,0,0,0,1},{1,1,0,1,1},{1,0,1,0,1},{1,0,0,0,1},{1,0,0,0,1},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('N', new int[][]{
            {1,0,0,0,1},{1,1,0,0,1},{1,0,1,0,1},{1,0,0,1,1},{1,0,0,0,1},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('O', new int[][]{
            {0,1,1,1,0},{1,0,0,0,1},{1,0,0,0,1},{1,0,0,0,1},{0,1,1,1,0},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('P', new int[][]{
            {1,1,1,1,0},{1,0,0,0,1},{1,1,1,1,0},{1,0,0,0,0},{1,0,0,0,0},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('Q', new int[][]{
            {0,1,1,0,0},{1,0,0,1,0},{1,0,0,1,0},{1,0,1,1,0},{0,1,1,0,1},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('R', new int[][]{
            {1,1,1,1,0},{1,0,0,0,1},{1,1,1,1,0},{1,0,1,0,0},{1,0,0,1,1},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('S', new int[][]{
            {0,1,1,1,1},{1,0,0,0,0},{0,1,1,1,0},{0,0,0,0,1},{1,1,1,1,0},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('T', new int[][]{
            {1,1,1,1,1},{0,0,1,0,0},{0,0,1,0,0},{0,0,1,0,0},{0,0,1,0,0},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('U', new int[][]{
            {1,0,0,0,1},{1,0,0,0,1},{1,0,0,0,1},{1,0,0,0,1},{0,1,1,1,0},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('V', new int[][]{
            {1,0,0,0,1},{1,0,0,0,1},{1,0,0,0,1},{0,1,0,1,0},{0,0,1,0,0},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('W', new int[][]{
            {1,0,0,0,1},{1,0,0,0,1},{1,0,1,0,1},{1,1,0,1,1},{1,0,0,0,1},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('X', new int[][]{
            {1,0,0,0,1},{0,1,0,1,0},{0,0,1,0,0},{0,1,0,1,0},{1,0,0,0,1},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('Y', new int[][]{
            {1,0,0,0,1},{0,1,0,1,0},{0,0,1,0,0},{0,0,1,0,0},{0,0,1,0,0},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('Z', new int[][]{
            {1,1,1,1,1},{0,0,0,1,0},{0,0,1,0,0},{0,1,0,0,0},{1,1,1,1,1},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put(' ', new int[][]{
            {0,0,0,0,0},{0,0,0,0,0},{0,0,0,0,0},{0,0,0,0,0},{0,0,0,0,0},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('.', new int[][]{
            {0,0,0,0,0},{0,0,0,0,0},{0,0,0,0,0},{0,0,0,0,0},{0,1,1,0,0},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('!', new int[][]{
            {0,0,1,0,0},{0,0,1,0,0},{0,0,1,0,0},{0,0,0,0,0},{0,0,1,0,0},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('?', new int[][]{
            {0,1,1,1,0},{1,0,0,0,1},{0,0,0,1,0},{0,0,1,0,0},{0,0,1,0,0},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('[', new int[][]{
            {0,1,1,0,0},{0,1,0,0,0},{0,1,0,0,0},{0,1,0,0,0},{0,1,1,0,0},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put(']', new int[][]{
            {0,0,1,1,0},{0,0,0,1,0},{0,0,0,1,0},{0,0,0,1,0},{0,0,1,1,0},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('-', new int[][]{
            {0,0,0,0,0},{0,0,0,0,0},{1,1,1,1,1},{0,0,0,0,0},{0,0,0,0,0},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put(':', new int[][]{
            {0,0,0,0,0},{0,1,1,0,0},{0,0,0,0,0},{0,1,1,0,0},{0,0,0,0,0},{0,0,0,0,0},{0,0,0,0,0}});

        // Digits
        FONT.put('0', new int[][]{{0,1,1,1,0},{1,0,0,1,1},{1,0,1,0,1},{1,1,0,0,1},{0,1,1,1,0},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('1', new int[][]{{0,0,1,0,0},{0,1,1,0,0},{0,0,1,0,0},{0,0,1,0,0},{0,1,1,1,0},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('2', new int[][]{{0,1,1,1,0},{1,0,0,0,1},{0,0,1,1,0},{0,1,0,0,0},{1,1,1,1,1},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('3', new int[][]{{1,1,1,1,0},{0,0,0,0,1},{0,0,1,1,0},{0,0,0,0,1},{1,1,1,1,0},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('4', new int[][]{{0,0,0,1,0},{0,0,1,1,0},{0,1,0,1,0},{1,1,1,1,1},{0,0,0,1,0},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('5', new int[][]{{1,1,1,1,1},{1,0,0,0,0},{1,1,1,1,0},{0,0,0,0,1},{1,1,1,1,0},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('6', new int[][]{{0,1,1,1,0},{1,0,0,0,0},{1,1,1,1,0},{1,0,0,0,1},{0,1,1,1,0},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('7', new int[][]{{1,1,1,1,1},{0,0,0,0,1},{0,0,0,1,0},{0,0,1,0,0},{0,0,1,0,0},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('8', new int[][]{{0,1,1,1,0},{1,0,0,0,1},{0,1,1,1,0},{1,0,0,0,1},{0,1,1,1,0},{0,0,0,0,0},{0,0,0,0,0}});
        FONT.put('9', new int[][]{{0,1,1,1,0},{1,0,0,0,1},{0,1,1,1,1},{0,0,0,0,1},{0,1,1,1,0},{0,0,0,0,0},{0,0,0,0,0}});
    }

    // Minecraft map color constants (most useful ones)
    public static final byte BLACK      = 119;  // dark gray/black
    public static final byte DARK_RED   = 35;   // dark red
    public static final byte RED        = 33;   // bright red
    public static final byte YELLOW     = 69;   // yellow
    public static final byte WHITE      = 34;   // white
    public static final byte GRAY       = 49;   // gray
    public static final byte DARK_GRAY  = 50;   // dark gray
    public static final byte GREEN      = 5;    // green
    public static final byte DARK_GREEN = 4;    // dark green
    public static final byte BLUE       = 65;   // blue
}
