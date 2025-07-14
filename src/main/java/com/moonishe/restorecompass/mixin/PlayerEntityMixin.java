package com.moonishe.restorecompass.mixin;

import com.moonishe.restorecompass.Restorecompassmod;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public class PlayerEntityMixin {

    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("HEAD"), cancellable = true)
    private void preventCompassDrop(ItemStack stack, boolean throwRandomly, boolean retainOwnership,
                                    CallbackInfoReturnable<ItemEntity> cir) {
        if (Restorecompassmod.isRestoreCompass(stack)) {
            cir.setReturnValue(null);
        }
    }
}