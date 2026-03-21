package com.aimod.mod;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client → Server: FoxAI entity sohbet balonu göster
 */
public class FoxSayPacket {

    private final String message;
    private final UUID ownerUUID;

    public FoxSayPacket(String message, UUID ownerUUID) {
        this.message   = message;
        this.ownerUUID = ownerUUID;
    }

    public static void encode(FoxSayPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.message, 512);
        buf.writeUUID(pkt.ownerUUID);
    }

    public static FoxSayPacket decode(FriendlyByteBuf buf) {
        String msg  = buf.readUtf(512);
        UUID   uuid = buf.readUUID();
        return new FoxSayPacket(msg, uuid);
    }

    public static void handle(FoxSayPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return;

            ServerPlayer sender = ctx.get().getSender();
            UUID ownerUUID = (sender != null) ? sender.getUUID() : pkt.ownerUUID;

            // FoxAI entity bulundu mu?
            boolean found = false;
            for (ServerLevel level : server.getAllLevels()) {
                for (FoxAIEntity fox : level.getEntitiesOfClass(FoxAIEntity.class,
                        new AABB(-30000,-512,-30000,30000,512,30000))) {
                    if (ownerUUID.equals(fox.getOwner())) {
                        found = true;
                        break;
                    }
                }
                if (found) break;
            }

            // Sadece bir kez broadcast yap (entity sayısına göre değil)
            if (found) {
                server.getPlayerList().broadcastSystemMessage(
                    Component.literal("§8<§aFoxAI§8> §f" + pkt.message), false
                );
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
