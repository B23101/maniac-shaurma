package com.log_to_kot.maniacmod.network;

import com.log_to_kot.maniacmod.client.video.VideoPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server → Client: tells the client to play or stop a video.
 *
 * Payload:
 *   - fileName (String): e.g. "intro.mp4"
 *   - fps      (int):    playback speed
 *   - stop     (bool):   true = stop any playing video
 */
public class VideoSyncPacket {

    private final String  fileName;
    private final int     fps;
    private final boolean stop;

    public VideoSyncPacket(String fileName, int fps) {
        this.fileName = fileName;
        this.fps      = fps;
        this.stop     = false;
    }

    /** Stop-only packet */
    public VideoSyncPacket() {
        this.fileName = "";
        this.fps      = 0;
        this.stop     = true;
    }

    public static VideoSyncPacket decode(FriendlyByteBuf buf) {
        boolean stop = buf.readBoolean();
        if (stop) return new VideoSyncPacket();
        String fileName = buf.readUtf();
        int fps = buf.readInt();
        return new VideoSyncPacket(fileName, fps);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(stop);
        if (!stop) {
            buf.writeUtf(fileName);
            buf.writeInt(fps);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() ->
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                if (stop) {
                    VideoPlayer.stop();
                } else {
                    VideoPlayer.play(fileName, fps, null);
                }
            })
        );
        ctx.setPacketHandled(true);
    }
}
