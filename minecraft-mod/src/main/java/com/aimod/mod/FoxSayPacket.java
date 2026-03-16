package com.aimod.mod;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.function.Supplier;

/**
 * Client → Server: FoxAI entity sohbet balonu göster
 */
public class FoxSayPacket {

    private final String message;

    public FoxSayPacket(String message) {
        this.message = message;
    }

    public static void encode(FoxSayPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.message, 512);
    }

    public static FoxSayPacket decode(FriendlyByteBuf buf) {
        return new FoxSayPacket(buf.readUtf(512));
    }

    public static void handle(FoxSayPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return;

            // Tüm oyunculara FoxAI'nin söylediğini göster
            for (ServerLevel level : server.getAllLevels()) {
                level.getEntitiesOfClass(FoxAIEntity.class,
                    new AABB(-30000,-512,-30000,30000,512,30000))
                    .forEach(fox -> {
                        // Entity'nin ağzından söylet
                        server.getPlayerList().broadcastSystemMessage(
                            Component.literal("§8<§aFoxAI§8> §f" + pkt.message), false
                        );
                    });
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
