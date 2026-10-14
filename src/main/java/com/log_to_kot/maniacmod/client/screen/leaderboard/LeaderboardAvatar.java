package com.log_to_kot.maniacmod.client.screen.leaderboard;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.overlay.roster.ProfileModels;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.properties.Property;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 3D-бюст ЛЮБОГО гравця — онлайн чи ні: голова, плечі й торс до пояса, ЗАСТИГЛА поза (жодного погойдування
 * чи повороту за курсором). Ноги ніколи не видно: нижню межу кадру ріже виріз віджета.
 *
 * <h3>Звідки модель</h3>
 * Офлайн-гравця в світі клієнта нема, тож малюється «манекен» — {@link Dummy}, клієнтська сутність-гравець,
 * яку НІКОЛИ не додають у світ: вона існує лише як параметр для рендера (так само ванільний екран інвентарю
 * малює гравця). Скін — із властивості {@code textures}, яку сервер зберіг у профілі; поки він докачується,
 * показується дефолтний скін за UUID, і модель не «блимає» чорним. Онлайн-гравця манекен бере зі списку
 * гравців автоматично (скін уже завантажений).
 *
 * <h3>Кадрування</h3>
 * Нічого не підбирається вручну: рамка рахується зі зросту моделі гравця ({@link #BUST_BOTTOM}..{@link #BUST_TOP}
 * блоків від ніг) і вписується в будь-яку пропорцію віджета — і в крихітну іконку рядка, і в великий подіум.
 *
 * <p>Відмовостійкість — та сама, що в профілі: якщо 3D вимкнено після збою рендера
 * ({@link ProfileModels#playerAvailable()}) або світу нема, малюється плоский силует, екран лишається робочим.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class LeaderboardAvatar {

    /** Нижня межа кадру, блоків від ніг: десь посередині торса. */
    static final float BUST_BOTTOM = 0.94f;
    /** Верхня межа кадру: маківка з невеликим запасом. */
    static final float BUST_TOP = 1.95f;
    /** Ширина кадру в блоках: плечі + руки з запасом. */
    private static final float BUST_WIDTH = 1.18f;
    private static final int CACHE_LIMIT = 48;

    /** LRU-кеш манекенів: створення сутності й запит скіну щокадру були б непотрібно дорогими. */
    private static final Map<UUID, Dummy> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Dummy> eldest) {
            return size() > CACHE_LIMIT;
        }
    };
    private static ClientLevel cachedLevel;

    private LeaderboardAvatar() {}

    public static void clear() {
        CACHE.clear();
        cachedLevel = null;
    }

    /**
     * Малює бюст у прямокутнику {@code (x,y,w,h)} з вирізом по його краях.
     *
     * @param skin   {@code textures} гравця з сервера; порожньо — скін за UUID
     * @param yawDeg 180 — обличчям до глядача; різні значення дають статичний «3/4»-ракурс для подіуму
     * @return {@code true}, якщо намальовано 3D; {@code false} — викликач малює силует
     */
    public static boolean draw(GuiGraphics g, UUID id, String name, String skin, int x, int y, int w, int h, float yawDeg) {
        if (!ProfileModels.playerAvailable()) return false;
        Dummy dummy = dummy(id, name, skin);
        if (dummy == null) return false;

        float scale = Math.min(h * 0.99f / (BUST_TOP - BUST_BOTTOM), w * 0.98f / BUST_WIDTH);
        float cx = x + w / 2f;
        // Y ніг у пікселях: нижня межа кадру (BUST_BOTTOM блоків від ніг) стоїть на нижньому краї віджета.
        float baseY = y + h + BUST_BOTTOM * scale;
        g.enableScissor(x, y, x + w, y + h);
        boolean drawn;
        try {
            drawn = ProfileModels.drawPlayer(g, dummy, cx, baseY, scale, yawDeg, 0f);
        } finally {
            g.disableScissor();
        }
        return drawn;
    }

    /** Плоский силует для випадків, коли 3D недоступне. */
    public static void drawSilhouette(GuiGraphics g, int x, int y, int w, int h, int argb) {
        int cx = x + w / 2;
        int head = Math.max(4, Math.min(w, h) / 4);
        g.fill(cx - head, y + h / 5, cx + head, y + h / 5 + head * 2, argb);
        g.fill(cx - head * 2, y + h / 5 + head * 2 + 2, cx + head * 2, y + h, argb);
    }

    private static Dummy dummy(UUID id, String name, String skin) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return null;
        if (level != cachedLevel) {   // сутності прив'язані до світу: новий світ — нові манекени
            CACHE.clear();
            cachedLevel = level;
        }
        String tex = skin == null ? "" : skin;
        Dummy d = CACHE.get(id);
        if (d == null || !d.texture.equals(tex)) {
            try {
                d = new Dummy(level, id, name == null || name.isEmpty() ? "player" : name, tex);
            } catch (RuntimeException | LinkageError e) {
                ManiacMod.LOGGER.warn("[leaderboard] манекен гравця не створено: {}", e.toString());
                return null;
            }
            CACHE.put(id, d);
        }
        return d;
    }

    /**
     * Клієнтський гравець-«манекен» без світу: скін підмінюється на той, що прийшов із сервера.
     * Не додається в рівень і не тікає — тому стоїть абсолютно нерухомо.
     */
    private static final class Dummy extends RemotePlayer {

        final String texture;
        private volatile ResourceLocation skin;
        private volatile String model;

        Dummy(ClientLevel level, UUID id, String name, String texture) {
            super(level, new GameProfile(id, name));
            this.texture = texture;
            this.skin = DefaultPlayerSkin.getDefaultSkin(id);
            this.model = DefaultPlayerSkin.getSkinModelName(id);
            // Усі шари скіну (капюшон, куртка, рукави) — як у справжнього гравця.
            this.entityData.set(DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0x7F);
            if (!texture.isEmpty()) requestSkin(id, name, texture);
        }

        private void requestSkin(UUID id, String name, String texture) {
            GameProfile profile = new GameProfile(id, name);
            profile.getProperties().put("textures", new Property("textures", texture));
            // Колбек приходить у головному потоці клієнта, коли текстура вже готова; до того — дефолтний скін.
            Minecraft.getInstance().getSkinManager().registerSkins(profile, (type, location, tex) -> {
                if (type != MinecraftProfileTexture.Type.SKIN) return;
                this.skin = location;
                this.model = "slim".equals(tex.getMetadata("model")) ? "slim" : "default";
            }, false);
        }

        @Override
        public ResourceLocation getSkinTextureLocation() {
            // Онлайн-гравець: скін зі списку гравців (уже завантажений); інакше — наш.
            return getPlayerInfo() != null ? super.getSkinTextureLocation() : skin;
        }

        @Override
        public String getModelName() {
            return getPlayerInfo() != null ? super.getModelName() : model;
        }

        /** Спостерігач у списку гравців малювався б самою головою — манекен завжди «цілий». */
        @Override
        public boolean isSpectator() { return false; }

        @Override
        public boolean isCreative() { return false; }
    }
}
