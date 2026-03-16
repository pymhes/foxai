package com.aimod.mod;

import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class FoxAIMenuType {

    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
        DeferredRegister.create(ForgeRegistries.MENU_TYPES, AiPlayerMod.MOD_ID);

    public static final RegistryObject<MenuType<FoxAIContainer>> FOX_INVENTORY =
        MENU_TYPES.register("fox_inventory",
            () -> IForgeMenuType.create((windowId, inv, data) ->
                new FoxAIContainer(windowId, inv)));
}
