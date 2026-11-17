// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import java.lang.reflect.Method;

/**
 * Is Shift held right now? Minecraft moved this method between classes over the years, so it is looked up by name once
 * (Minecraft.hasShiftDown(), else Screen.hasShiftDown()). If neither exists the answer is "yes", so the extra
 * information is never lost.
 */
final class ShiftKey {
    private ShiftKey() {}

    private static boolean resolved;
    private static Method onMinecraft;
    private static Method onScreen;

    static boolean down() {
        try {
            if (!resolved) {
                resolved = true;
                try {
                    onMinecraft = net.minecraft.client.Minecraft.class.getMethod("hasShiftDown");
                } catch (ReflectiveOperationException ignored) {
                }
                try {
                    onScreen = net.minecraft.client.gui.screens.Screen.class.getMethod("hasShiftDown");
                } catch (ReflectiveOperationException ignored) {
                }
            }
            if (onMinecraft != null) return (Boolean) onMinecraft.invoke(net.minecraft.client.Minecraft.getInstance());
            if (onScreen != null) return (Boolean) onScreen.invoke(null);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return true;
    }
}
