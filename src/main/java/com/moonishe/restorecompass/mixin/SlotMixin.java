package com.moonishe.restorecompass.mixin;

import com.moonishe.restorecompass.Restorecompassmod;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Slot.class)
public abstract class SlotMixin {

    @Shadow public abstract ItemStack getItem();

    @Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
    private void preventCompassTaking(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (Restorecompassmod.isRestoreCompass(this.getItem())) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
    private void preventInsertIntoCompassSlot(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (Restorecompassmod.isRestoreCompass(this.getItem())) {
            cir.setReturnValue(false);
        }
    }
}