package com.log_to_kot.maniacmod;

import com.log_to_kot.maniacmod.blocks.ModBlocks;
import com.log_to_kot.maniacmod.entity.ModEntityTypes;
import com.log_to_kot.maniacmod.items.ModCreativeTab;
import com.log_to_kot.maniacmod.sound.ModSounds;
import com.log_to_kot.maniacmod.events.ServerEventHandler;
import com.log_to_kot.maniacmod.game.ManiacGameManager;
import com.log_to_kot.maniacmod.items.ModItems;
import com.log_to_kot.maniacmod.network.ModMessages;
import com.log_to_kot.maniacmod.config.ManiacConfig;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.bernie.geckolib.GeckoLib;

@Mod(ManiacMod.MOD_ID)
public class ManiacMod {

    public static final String MOD_ID = "maniacmod";
    public static final Logger LOGGER = LogManager.getLogger();

    public ManiacMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // Реєструємо конфіг (файл: config/maniacmod-server.toml)
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(
            ModConfig.Type.SERVER, ManiacConfig.SERVER_SPEC, "maniacmod-server.toml");

        ModItems.ITEMS.register(modEventBus);
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlocks.BLOCK_ITEMS.register(modEventBus);
        ModCreativeTab.TABS.register(modEventBus);
        ModEntityTypes.ENTITY_TYPES.register(modEventBus);
        ModSounds.SOUNDS.register(modEventBus);

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::clientSetupEvent);

        MinecraftForge.EVENT_BUS.register(new ServerEventHandler());
        MinecraftForge.EVENT_BUS.register(new ManiacGameManager());

        GeckoLib.initialize();
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        ModMessages.register();
        LOGGER.info("[ManiacMod] v3.0 initialized!");
    }

    private void clientSetupEvent(final FMLClientSetupEvent event) {
        // Запускаємо завантаження FFmpeg у фоні одразу при старті клієнта.
        // FfmpegDownloadScreen автоматично показує прогрес якщо FFmpeg не встановлено.
        event.enqueueWork(() -> {
            try {
                Class<?> installer = Class.forName(
                    "com.log_to_kot.maniacmod.client.video.FfmpegInstaller");
                installer.getMethod("ensureAvailable", Runnable.class)
                    .invoke(null, (Runnable) () ->
                        LOGGER.info("[ManiacMod] FFmpeg готовий!"));
            } catch (Exception e) {
                LOGGER.warn("[ManiacMod] Не вдалося ініціалізувати FfmpegInstaller: {}", e.getMessage());
            }
        });
    }
}
