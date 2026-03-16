package com.aimod.mod;

import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(AiPlayerMod.MOD_ID)
public class AiPlayerMod {
    public static final String MOD_ID = "aiplayermod";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public AiPlayerMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        // Entity tiplerini kaydet
        FoxAIEntity.ENTITY_TYPES.register(modBus);

        modBus.addListener(this::commonSetup);
        modBus.addListener(this::clientSetup);
        modBus.addListener(this::onRegisterRenderers);

        MinecraftForge.EVENT_BUS.register(this);

        LOGGER.info("[FoxAI] Mod yükleniyor...");
        LOGGER.info("[FoxAI] Botu çağır: /spawn foxai");
        LOGGER.info("[FoxAI] Komut: {} <komut>  |  Sohbet: fox <mesaj>", ModConfig.getTriggerPrefix());
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // Forge network kanalını kaydet
        event.enqueueWork(FoxAINetwork::register);
        LOGGER.info("[FoxAI] Ortak kurulum & network kaydı tamamlandı.");
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        LOGGER.info("[FoxAI] İstemci kurulumu tamamlandı.");
    }

    @SubscribeEvent
    public void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(FoxAIEntity.FOXAI.get(), FoxAIEntity.Renderer::new);
    }

}
