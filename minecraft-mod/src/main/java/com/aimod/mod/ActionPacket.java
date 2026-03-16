package com.aimod.mod;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.List;
import java.util.function.Supplier;

/**
 * Client → Server: "FoxAI entity, şu aksiyonları yap"
 */
public class ActionPacket {

    private final String actionsJson;

    public ActionPacket(String actionsJson) {
        this.actionsJson = actionsJson;
    }

    public static void encode(ActionPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.actionsJson, 131072);
    }

    public static ActionPacket decode(FriendlyByteBuf buf) {
        return new ActionPacket(buf.readUtf(131072));
    }

    public static void handle(ActionPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return;

            for (ServerLevel level : server.getAllLevels()) {
                List<FoxAIEntity> foxEntities = level.getEntitiesOfClass(
                    FoxAIEntity.class,
                    new AABB(-30000, -512, -30000, 30000, 512, 30000)
                );
                for (FoxAIEntity fox : foxEntities) {
                    fox.queueActions(pkt.actionsJson);
                    AiPlayerMod.LOGGER.info("[FoxAI] {} aksiyonu kuyruğa eklendi.", fox.getName().getString());
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
