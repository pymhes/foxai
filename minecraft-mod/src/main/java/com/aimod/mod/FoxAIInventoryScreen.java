package com.aimod.mod;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

public class FoxAIInventoryScreen extends AbstractContainerScreen<FoxAIContainer> {

    // Vanilla chest texture kullan (3 satır)
    private static final ResourceLocation CHEST_GUI =
        new ResourceLocation("minecraft", "textures/gui/container/generic_54.png");

    public FoxAIInventoryScreen(FoxAIContainer container, Inventory playerInv, Component title) {
        super(container, playerInv, title);
        // 3 satır envanter + oyuncu envanteri
        this.imageHeight = 114 + 6 * 18 + 14;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;

        // Üst kısım (FoxAI envanteri)
        graphics.blit(CHEST_GUI, x, y, 0, 0, this.imageWidth, 3 * 18 + 17);

        // Alt kısım (oyuncu envanteri)
        graphics.blit(CHEST_GUI, x, y + 3 * 18 + 17 + 4, 0, 126, this.imageWidth, 96);
    }
}
