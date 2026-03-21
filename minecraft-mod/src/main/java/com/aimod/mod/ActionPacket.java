package com.aimod.mod;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client → Server: "FoxAI entity, şu aksiyonları yap"
 * Owner UUID ile sadece o oyuncunun FoxAI'si kontrol edilir.
 */
public class ActionPacket {

    private final String actionsJson;
    private final UUID ownerUUID;

    public ActionPacket(String actionsJson, UUID ownerUUID) {
        this.actionsJson = actionsJson;
        this.ownerUUID   = ownerUUID;
    }

    public static void encode(ActionPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.actionsJson, 131072);
        buf.writeUUID(pkt.ownerUUID);
    }

    public static ActionPacket decode(FriendlyByteBuf buf) {
        String json = buf.readUtf(131072);
        UUID   uuid = buf.readUUID();
        return new ActionPacket(json, uuid);
    }

    public static void handle(ActionPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return;

            // Gönderen oyuncunun UUID'sini al (packet'teki UUID ile doğrula)
            ServerPlayer sender = ctx.get().getSender();
            UUID ownerUUID = (sender != null) ? sender.getUUID() : pkt.ownerUUID;

            boolean foundAny = false;
            int totalFoxEntities = 0;

            for (ServerLevel level : server.getAllLevels()) {
                List<FoxAIEntity> foxEntities = level.getEntitiesOfClass(
                    FoxAIEntity.class,
                    new AABB(-30000, -512, -30000, 30000, 512, 30000)
                );
                totalFoxEntities += foxEntities.size();
                for (FoxAIEntity fox : foxEntities) {
                    // Sadece bu oyuncunun FoxAI'sini kontrol et
                    if (ownerUUID.equals(fox.getOwner())) {
                        fox.queueActions(pkt.actionsJson);
                        foundAny = true;
                        FoxAILog.info("ActionPacket teslim edildi → "
                            + (sender != null ? sender.getName().getString() : "?")
                            + " → " + fox.getName().getString());
                        AiPlayerMod.LOGGER.info("[FoxAI] {} -> {} aksiyonu kuyruğa eklendi.",
                            sender != null ? sender.getName().getString() : "?",
                            fox.getName().getString());
                    }
                }
            }

            // FoxAI bulunamazsa oyuncuya bildir
            if (!foundAny && sender != null) {
                String reason = totalFoxEntities == 0
                    ? "Hiç FoxAI entity yok! /spawn foxai yap."
                    : "FoxAI var (" + totalFoxEntities + " adet) ama UUID eşleşmedi! /spawn foxai yap.";
                FoxAILog.warn("ActionPacket teslim edilemedi → " + reason
                    + " | ownerUUID: " + ownerUUID);
                sender.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(
                        "§8[§aFox§8] §c" + reason),
                    false
                );
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
