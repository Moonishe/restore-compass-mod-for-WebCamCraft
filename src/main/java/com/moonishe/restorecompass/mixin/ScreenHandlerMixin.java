package com.moonishe.restorecompass.mixin;

import com.moonishe.restorecompass.Restorecompassmod;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
public abstract class ScreenHandlerMixin {

    @Shadow @Final public net.minecraft.core.NonNullList<Slot> slots;
    @Shadow public abstract ItemStack getCarried();

    @Inject(method = "doClick", at = @At("HEAD"), cancellable = true)
    private void preventCompassInteraction(int slotIndex, int button, ClickType actionType,
                                           Player player, CallbackInfo ci) {
        if (slotIndex >= 0 && slotIndex < this.slots.size()) {
            Slot slot = this.slots.get(slotIndex);
            ItemStack stack = slot.getItem();

            if (Restorecompassmod.isRestoreCompass(stack)) {
                ci.cancel();
                return;
            }
        }

        // Также проверяем курсор
        ItemStack cursorStack = this.getCarried();
        if (Restorecompassmod.isRestoreCompass(cursorStack)) {
            ci.cancel();
        }
    }
}