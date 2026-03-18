package com.aimod.mod;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class FoxAINetwork {

    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(AiPlayerMod.MOD_ID, "main"),
        () -> PROTOCOL,
        PROTOCOL::equals,
        PROTOCOL::equals
    );

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, ActionPacket.class,
            ActionPacket::encode, ActionPacket::decode, ActionPacket::handle);
        CHANNEL.registerMessage(id++, FoxSayPacket.class,
            FoxSayPacket::encode, FoxSayPacket::decode, FoxSayPacket::handle);
        CHANNEL.registerMessage(id++, FoxAIContextPacket.class,
            FoxAIContextPacket::encode, FoxAIContextPacket::decode, FoxAIContextPacket::handle);
    }
}
