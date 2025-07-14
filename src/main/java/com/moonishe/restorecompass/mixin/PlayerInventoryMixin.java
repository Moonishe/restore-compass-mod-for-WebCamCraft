package com.moonishe.restorecompass.mixin;

import com.moonishe.restorecompass.Restorecompassmod;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Inventory.class)
public abstract class PlayerInventoryMixin {

    @Shadow public abstract ItemStack getSelected();

    @Inject(method = "removeFromSelected", at = @At("HEAD"), cancellable = true)
    private void preventSelectedItemDrop(boolean entireStack, CallbackInfoReturnable<ItemStack> cir) {
        ItemStack stack = this.getSelected();
        if (Restorecompassmod.isRestoreCompass(stack)) {
            cir.setReturnValue(ItemStack.EMPTY);
        }
    }

    @Inject(method = "removeItem(II)Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"), cancellable = true)
    private void preventCompassRemoval(int slot, int amount, CallbackInfoReturnable<ItemStack> cir) {
        Inventory inventory = (Inventory) (Object) this;
        ItemStack stack = inventory.getItem(slot);
        if (Restorecompassmod.isRestoreCompass(stack)) {
            cir.setReturnValue(ItemStack.EMPTY);
        }
    }
}