package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.screen.mainmenu.ManiacTitleScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Назва вікна гри: «Маніяк — Головне меню», «Маніяк — Полювання · Вижилий» тощо.
 *
 * ── Звідки текст ─────────────────────────────────────────────────────
 * Усе — з lang-файлів ({@code maniacmod.window.*}), тож назву легко змінити,
 * не чіпаючи код: {@code maniacmod.window.title} — початок назви, далі
 * {@code maniacmod.window.state.*} — де гравець (меню, світи, завантаження),
 * а в світі — назва фази матчу ({@code maniacmod.phase.*}, ті самі ключі, що й
 * у HUD) і роль гравця ({@code maniacmod.window.role.*}).
 *
 * ── Як і коли ставиться ──────────────────────────────────────────────
 * Ванільний Minecraft сам перезаписує заголовок (зміна екрана, вхід у світ),
 * тому ми щотіка рахуємо потрібний текст і ставимо його, якщо він змінився,
 * а також перестраховуємось і повторюємо кожні {@link #REASSERT_TICKS} тіків.
 * Міксинів немає — лише {@code Window#setTitle} з головного потоку клієнта.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CustomWindowTitle {

    /** Раз на скільки тіків повторно ставимо назву, навіть якщо вона не змінилась (ваніль могла її затерти). */
    private static final int REASSERT_TICKS = 5;

    private static String lastApplied = null;
    private static int ticksSinceApply = 0;

    private CustomWindowTitle() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getWindow() == null) return;

        String title = compute(mc);
        ticksSinceApply++;
        if (title.equals(lastApplied) && ticksSinceApply < REASSERT_TICKS) return;

        mc.getWindow().setTitle(title);
        lastApplied = title;
        ticksSinceApply = 0;
    }

    private static String compute(Minecraft mc) {
        String base = I18n.get("maniacmod.window.title");

        // Не в світі: назва за екраном.
        if (mc.level == null || mc.player == null) {
            return base + " — " + I18n.get(menuStateKey(mc.screen));
        }

        // У світі: фаза матчу + роль (якщо вона вже відома).
        String phase = I18n.get(ClientMatchState.phase().translationKey());
        String roleKey = roleKey();
        return roleKey == null
            ? base + " — " + phase
            : base + " — " + phase + " · " + I18n.get(roleKey);
    }

    private static String menuStateKey(Screen screen) {
        if (screen instanceof TitleScreen || screen instanceof ManiacTitleScreen) {
            return "maniacmod.window.state.main_menu";
        }
        if (screen instanceof SelectWorldScreen || screen instanceof CreateWorldScreen) {
            return "maniacmod.window.state.worlds";
        }
        if (screen instanceof JoinMultiplayerScreen || screen instanceof ConnectScreen) {
            return "maniacmod.window.state.multiplayer";
        }
        if (screen == null || screen instanceof ReceivingLevelScreen
            || screen instanceof ProgressScreen || screen instanceof GenericDirtMessageScreen) {
            return "maniacmod.window.state.loading";
        }
        return "maniacmod.window.state.menu";
    }

    private static String roleKey() {
        if (ClientMatchState.isManiac()) return "maniacmod.window.role.maniac";
        if (ClientMatchState.isSurvivor()) return "maniacmod.window.role.survivor";
        return null;
    }
}
