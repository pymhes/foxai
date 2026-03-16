package com.aimod.mod;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class FoxAIContainer extends AbstractContainerMenu {

    private final Container foxInventory;
    public static final int FOX_SLOTS = 27;

    // Server-side constructor (entity envanteri ile)
    public FoxAIContainer(int windowId, Inventory playerInv, Container foxInv) {
        super(FoxAIMenuType.FOX_INVENTORY.get(), windowId);
        this.foxInventory = foxInv;
        checkContainerSize(foxInv, FOX_SLOTS);
        foxInv.startOpen(playerInv.player);

        // FoxAI envanter slotları (3 satır x 9 sütun)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(foxInv, col + row * 9,
                    8 + col * 18, 18 + row * 18));
            }
        }

        // Oyuncu envanteri (3 satır)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInv, col + row * 9 + 9,
                    8 + col * 18, 84 + row * 18 + 14));
            }
        }

        // Oyuncu hotbar
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInv, col,
                8 + col * 18, 142 + 14));
        }
    }

    // Client-side constructor (network üzerinden)
    public FoxAIContainer(int windowId, Inventory playerInv) {
        this(windowId, playerInv, new net.minecraft.world.SimpleContainer(FOX_SLOTS));
    }

    @Override
    public boolean stillValid(Player player) {
        return this.foxInventory.stillValid(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);

        if (slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();

            if (index < FOX_SLOTS) {
                // FoxAI'den oyuncuya
                if (!this.moveItemStackTo(stack, FOX_SLOTS, this.slots.size(), true))
                    return ItemStack.EMPTY;
            } else {
                // Oyuncudan FoxAI'ye
                if (!this.moveItemStackTo(stack, 0, FOX_SLOTS, false))
                    return ItemStack.EMPTY;
            }

            if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
            else slot.setChanged();
        }
        return result;
    }
}
