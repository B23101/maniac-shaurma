package com.log_to_kot.maniacmod.client.screen.wiki;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.items.ItemVisuals;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

/**
 * Малює РЕАЛЬНУ 3D-модель предмета (ту саму, що в руці й на землі) у
 * прямокутнику GUI, з довільним поворотом.
 *
 * ── Чому через {@link ItemRenderer}, а не окремий GeckoLib-виклик ─────
 * Предмети мода бувають двох видів: geo-предмети ({@code builtin/entity}
 * + {@code ItemGeoRenderer}) і плоскі ({@code item/generated}, як шина чи
 * адреналін). {@code ItemRenderer#render} з контекстом
 * {@link ItemDisplayContext#NONE} уніфікує обидва: для geo-предмета він
 * кличе його власний рендерер (з усіма анімаціями GeckoLib — {@code idle}
 * грає, бо стек той самий, що в руці), для плоского — малює об'ємний
 * спрайт. Контекст NONE означає «жодних display-трансформацій з JSON
 * моделі» — вони написані під руку/GUI-іконку (у біти, наприклад, зсув
 * {@code -6.4, -6.4} під іконку) і тут тільки заважали б.
 *
 * ── Центрування ──────────────────────────────────────────────────────
 * Geo-моделі побудовані «від підлоги» (кістка стоїть на y=0), а не
 * навколо центру, тому для обертання навколо середини їх треба зсунути.
 * {@link #fitOf} читає {@code geo/item/<id>.geo.json} і рахує габаритний
 * паралелепіпед із усіх кубів (без кісток рук) — так новий предмет не
 * потребує ручної таблиці зсувів. Плоскі моделі ванільний рендерер уже
 * центрує сам.
 *
 * ── Глибина ──────────────────────────────────────────────────────────
 * Як і {@code TabModel3d}: модель лишає в буфері глибини «слід» (малюється
 * на z=150), тому одразу після неї буфер очищується — інакше все, що GUI
 * малює далі (текст, іскри), зникало б там, де стоїть предмет.
 *
 * ── Відмовостійкість ─────────────────────────────────────────────────
 * Якщо рендер предмета хоч раз кидає виняток (чужий мод, зламана модель),
 * 3D вимикається до перезапуску гри, екран переходить на плоский запасний
 * малюнок. Вікі не повинна ламати гру через косметику.
 */
@OnlyIn(Dist.CLIENT)
final class ItemWikiModel3d {

    /** Габарит моделі в одиницях блока: центр і найбільший розмір. */
    record Fit(float cx, float cy, float cz, float size) {}

    private static final Fit UNIT = new Fit(0f, 0f, 0f, 1f);
    private static final Map<String, Fit> FITS = new HashMap<>();
    private static boolean broken = false;

    private ItemWikiModel3d() {}

    static boolean available() {
        return !broken;
    }

    /**
     * @param cx,cy     центр моделі на екрані (координати GUI з урахуванням pose)
     * @param pixelSize скільки пікселів займає НАЙБІЛЬШИЙ вимір моделі
     * @param yawDeg    обертання навколо вертикалі
     * @param tiltDeg   нахил (додатний — верх до глядача)
     * @param rollDeg   додатковий нахил убік (для «погойдування»)
     * @return {@code true}, якщо модель намальовано
     */
    static boolean render(GuiGraphics g, ItemStack stack, String id,
                          float cx, float cy, float pixelSize,
                          float yawDeg, float tiltDeg, float rollDeg) {
        if (broken || stack.isEmpty()) return false;

        Minecraft mc = Minecraft.getInstance();
        ItemRenderer renderer = mc.getItemRenderer();
        PoseStack pose = g.pose();
        boolean drawn = false;

        pose.pushPose();
        try {
            BakedModel model = renderer.getModel(stack, mc.level, mc.player, 0);
            Fit fit = fitOf(id, model.isCustomRenderer());
            float scale = pixelSize / Math.max(0.05f, fit.size());

            pose.translate(cx, cy, 150.0);
            pose.scale(scale, -scale, scale);          // той самий «y вниз», що в ванільного GUI-предмета
            pose.mulPose(Axis.XP.rotationDegrees(tiltDeg));
            pose.mulPose(Axis.YP.rotationDegrees(yawDeg));
            pose.mulPose(Axis.ZP.rotationDegrees(rollDeg));
            pose.translate(-fit.cx(), -fit.cy(), -fit.cz());

            // 3D-освітлення з напрямним світлом: поки предмет крутиться, грані
            // по черзі ловлять світло — саме це читається як «об'єм».
            Lighting.setupFor3DItems();
            RenderSystem.enableDepthTest();
            renderer.render(stack, ItemDisplayContext.NONE, false, pose, g.bufferSource(),
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, model);
            g.flush();
            drawn = true;
        } catch (RuntimeException | LinkageError failure) {
            broken = true;
            ManiacMod.LOGGER.warn("[wiki] 3D-рендер предмета '{}' вимкнено до перезапуску: {}",
                id, failure.toString());
        } finally {
            pose.popPose();
            Lighting.setupFor3DItems();
        }
        RenderSystem.clear(256, Minecraft.ON_OSX);
        return drawn;
    }

    /** Габарит моделі предмета (кешується). Для плоских моделей — одиничний куб у нулі. */
    static Fit fitOf(String id, boolean geoModel) {
        if (!geoModel) return UNIT;
        return FITS.computeIfAbsent(id, ItemWikiModel3d::readGeoFit);
    }

    /**
     * Габаритний паралелепіпед з усіх кубів {@code .geo.json}. Кістки рук
     * ({@code RightArm}/{@code LeftArm}) пропускаємо: у рендері їх підміняє
     * скін гравця, а в самому предметі вони б «розтягли» габарит.
     * Обертання кісток навмисно ігнорується — для центрування достатньо
     * наближення, а точний розрахунок потребував би повного обходу ієрархії.
     */
    private static Fit readGeoFit(String id) {
        ResourceLocation loc = ItemVisuals.of(id).geoModel();
        try {
            var resource = Minecraft.getInstance().getResourceManager().getResource(loc);
            if (resource.isEmpty()) return UNIT;
            try (Reader reader = resource.get().openAsReader()) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                JsonArray geometries = root.getAsJsonArray("minecraft:geometry");
                if (geometries == null || geometries.isEmpty()) return UNIT;
                JsonArray bones = geometries.get(0).getAsJsonObject().getAsJsonArray("bones");
                if (bones == null) return UNIT;

                float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
                float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
                boolean any = false;
                for (JsonElement boneEl : bones) {
                    JsonObject bone = boneEl.getAsJsonObject();
                    String name = bone.has("name") ? bone.get("name").getAsString() : "";
                    if (name.equals("RightArm") || name.equals("LeftArm")) continue;
                    if (!bone.has("cubes")) continue;
                    for (JsonElement cubeEl : bone.getAsJsonArray("cubes")) {
                        JsonObject cube = cubeEl.getAsJsonObject();
                        JsonArray o = cube.getAsJsonArray("origin");
                        JsonArray s = cube.getAsJsonArray("size");
                        if (o == null || s == null) continue;
                        for (int i = 0; i < 3; i++) {
                            float lo = o.get(i).getAsFloat();
                            float hi = lo + s.get(i).getAsFloat();
                            min[i] = Math.min(min[i], Math.min(lo, hi));
                            max[i] = Math.max(max[i], Math.max(lo, hi));
                        }
                        any = true;
                    }
                }
                if (!any) return UNIT;

                float ex = max[0] - min[0], ey = max[1] - min[1], ez = max[2] - min[2];
                float size = Math.max(ex, Math.max(ey, ez)) / 16f;
                return new Fit(
                    (min[0] + max[0]) / 32f,
                    (min[1] + max[1]) / 32f,
                    (min[2] + max[2]) / 32f,
                    Math.max(size, 0.05f));
            }
        } catch (Exception e) {
            ManiacMod.LOGGER.warn("[wiki] не вдалося прочитати габарит '{}': {}", loc, e.toString());
            return UNIT;
        }
    }
}
