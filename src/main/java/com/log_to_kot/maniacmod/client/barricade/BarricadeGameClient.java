package com.log_to_kot.maniacmod.client.barricade;

import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.net.s2c.barricade.BarricadeGameOpenPacket;
import com.log_to_kot.maniacmod.net.s2c.barricade.BarricadeNailPacket;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Точка входу пакетів міні-гри «витягни цвяхи» на клієнті: відкрити/оновити/закрити екран. */
@OnlyIn(Dist.CLIENT)
public final class BarricadeGameClient {

    private BarricadeGameClient() {}

    public static void onOpen(BarricadeGameOpenPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof BarricadeGameScreen screen && screen.key().equals(packet.key())) {
            screen.applySnapshot(packet);   // повторний пакет для того самого екрана — лише оновлення
            return;
        }
        mc.setScreen(new BarricadeGameScreen(packet));
        UiAnim.playAppear();
    }

    public static void onNail(BarricadeNailPacket packet) {
        if (Minecraft.getInstance().screen instanceof BarricadeGameScreen screen) screen.onNail(packet);
    }

    public static void onClose(byte reason) {
        if (Minecraft.getInstance().screen instanceof BarricadeGameScreen screen) screen.onServerClose(reason);
    }
}
