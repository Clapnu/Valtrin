// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin.mixin;

import dev.valtrin.FlipConfig;
import dev.valtrin.SuggestedPriceAccess;
import net.minecraft.client.gui.font.TextFieldHelper;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pre-types the suggested price into the auction price sign and selects it, so typing replaces it.
 * Nothing is submitted: you still have to press Done (or Esc) yourself.
 */
@Mixin(AbstractSignEditScreen.class)
public class SignFillMixin {
    @Shadow @Final private String[] messages;
    @Shadow @Final private TextFieldHelper signField;
    @Unique private boolean valtrinFilled;

    @Inject(method = "init", at = @At("TAIL"))
    private void valtrinFill(CallbackInfo ci) {
        if (valtrinFilled) return; // init runs again when the window is resized
        valtrinFilled = true;
        try {
            if (!FlipConfig.INSTANCE.signFill || messages.length != 4) return;
            if (!messages[0].isEmpty()) return;
            String joined = (messages[2] + " " + messages[3]).toLowerCase(java.util.Locale.ROOT);
            if (!joined.contains("starting bid")) return; // only the auction price sign
            String text = SuggestedPriceAccess.signText();
            if (text == null) return;
            messages[0] = text;
            signField.selectAll();
        } catch (Throwable ignored) {
            // never break the sign screen
        }
    }
}
