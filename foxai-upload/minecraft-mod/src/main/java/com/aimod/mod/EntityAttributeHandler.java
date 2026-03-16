package com.aimod.mod;

import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = AiPlayerMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class EntityAttributeHandler {

    @SubscribeEvent
    public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(FoxAIEntity.FOXAI.get(), FoxAIEntity.createAttributes().build());
        AiPlayerMod.LOGGER.info("[FoxAI] FoxAI entity attribute'ları kaydedildi.");
    }
}
