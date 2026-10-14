package com.log_to_kot.maniacmod.client.screen.voice;

import com.log_to_kot.maniacmod.ManiacMod;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Set;

/**
 * Підміняє штатні екрани Simple Voice Chat на екрани мода. Працює так само, як
 * {@code ManiacEscInterceptor}: ловить {@link ScreenEvent.Opening} і міняє екран, що відкривається.
 * Один перехоплювач закриває ВСІ входи в меню SVC — клавішу, кнопки штатних екранів, Cloth Config.
 *
 * ── Підміняється ─────────────────────────────────────────────────────
 *   VoiceChatScreen          → {@link ManiacVoiceMenuScreen}
 *   VoiceChatSettingsScreen  → {@link ManiacVoiceSettingsScreen}
 *   SelectMicrophoneScreen   → {@link ManiacVoiceDeviceScreen} (мікрофон)
 *   SelectSpeakerScreen      → {@link ManiacVoiceDeviceScreen} (динамік)
 *   AdjustVolumesScreen      → {@link ManiacVoiceVolumesScreen}
 * Екрани груп (пакет gui.group: GroupScreen, JoinGroupScreen, CreateGroupScreen, EnterPasswordScreen) ЗАБОРОНЕНІ:
 * відкриття скасовується (меню груп у моді немає, див. voice/README.md — групами керує лише код мода).
 *
 * ── Чому за іменем класу ─────────────────────────────────────────────
 * SVC опційний. Цей клас не має посилань на типи SVC: порівняння йде за рядком імені, а екрани мода
 * (які звертаються до SVC) створюються лише після перевірки, що SVC завантажено. Без SVC перехоплювач
 * просто нічого не робить.
 *
 * ── Куди повертає «Назад» ────────────────────────────────────────────
 * Якщо екран відкрито з іншого голосового екрана мода — повертає на нього. Поза світом (головне меню →
 * Mods → Voice Chat) — на екран, з якого відкрили. У світі з клавіші — закриває меню.
 *
 * Вимкнути підміну без перезбірки: JVM-аргумент {@code -Dmaniacmod.voiceUi=false}.
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ManiacVoiceInterceptor {

    private static final String SVC_ID = "voicechat";
    private static final String GUI = "de.maxhenkel.voicechat.gui.";
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Екрани SVC, що лишились штатними, — пишемо в лог по одному разу (щоб знайти зайве/новий екран). */
    private static final Set<String> LOGGED_UNREPLACED = new HashSet<>();

    private ManiacVoiceInterceptor() {}

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        Screen next = event.getNewScreen();
        if (next == null) return;
        String name = next.getClass().getName();
        if (!name.startsWith(GUI)) return;
        if ("false".equalsIgnoreCase(System.getProperty("maniacmod.voiceUi", "true"))) return;
        if (!ModList.get().isLoaded(SVC_ID)) return;

        // Груп у меню мода немає: жоден груповий екран SVC (клавіша груп, кнопки, інші моди) не відкривається.
        if (name.startsWith(GUI + "group.")) {
            event.setCanceled(true);
            return;
        }

        Screen current = event.getCurrentScreen();
        Minecraft mc = Minecraft.getInstance();
        Screen parent = (current instanceof VoiceScreenBase || mc.level == null) ? current : null;

        Screen replacement = switch (name) {
            case GUI + "VoiceChatScreen" -> new ManiacVoiceMenuScreen(parent);
            case GUI + "VoiceChatSettingsScreen" -> new ManiacVoiceSettingsScreen(parent);
            case GUI + "audiodevice.SelectMicrophoneScreen" -> new ManiacVoiceDeviceScreen(parent, true);
            case GUI + "audiodevice.SelectSpeakerScreen" -> new ManiacVoiceDeviceScreen(parent, false);
            case GUI + "volume.AdjustVolumesScreen" -> new ManiacVoiceVolumesScreen(parent);
            default -> null;
        };
        if (replacement != null) {
            event.setNewScreen(replacement);
        } else if (LOGGED_UNREPLACED.add(name)) {
            LOGGER.info("[maniacmod] SVC screen left vanilla (not replaced): {}", name);
        }
    }
}
