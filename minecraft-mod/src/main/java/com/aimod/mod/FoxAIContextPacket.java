package com.aimod.mod;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server → Client paketi.
 * FoxAI entity'sinin etrafındaki dünya bilgisini client'a iletir.
 * ChatEventHandler.buildContext() bunu alıp API'ye ekler.
 */
public class FoxAIContextPacket {

    private final String contextJson;

    public FoxAIContextPacket(String contextJson) {
        this.contextJson = contextJson;
    }

    public static void encode(FoxAIContextPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.contextJson, 65536);
    }

    public static FoxAIContextPacket decode(FriendlyByteBuf buf) {
        return new FoxAIContextPacket(buf.readUtf(65536));
    }

    public static void handle(FoxAIContextPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // Client tarafında FoxAI context'ini sakla
            FoxAIContextCache.set(pkt.contextJson);
        });
        ctx.get().setPacketHandled(true);
    }

    public String getContextJson() {
        return contextJson;
    }
}
