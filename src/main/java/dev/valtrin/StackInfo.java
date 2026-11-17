// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What the mod can tell about an item the player is looking at in a menu or inventory. */
record StackInfo(ItemData data, String tier, long buyItNow, boolean activeListing, String name) {

    private static final Pattern BIN = Pattern.compile("Buy it now:\\s*([\\d,]+)");

    /** Returns null for anything that is not a comparable SkyBlock item. */
    static StackInfo read(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;

        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null) return null;
        CompoundTag tag = cd.copyTag();

        String name = stack.getHoverName().getString();

        // Re-encode the item's data as plain NBT bytes and use the same parser as for auctions,
        // so listings and in-game items are always read identically.
        ItemData data;
        byte[] extraBytes;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                NbtIo.write(tag, out);
            }
            extraBytes = bytes.toByteArray();
            data = ItemKeys.parseUncompressed(extraBytes, name);
        } catch (Exception e) {
            return null;
        }
        if (data == ItemData.NONE) return null;

        List<String> lore = new ArrayList<>();
        ItemLore il = stack.get(DataComponents.LORE);
        if (il != null) {
            for (Component c : il.lines()) lore.add(c.getString());
        }

        // Handles recombobulated items too (their rarity line has an obfuscated letter on each side).
        String tier = ItemData.tierFromLore(lore);
        if (tier != null) data = data.withTier(tier);
        data = data.withEtherwarpFromLore(lore);

        long price = -1;
        boolean active = false;
        for (String line : lore) {
            Matcher m = BIN.matcher(line);
            if (m.find()) {
                try { price = Long.parseLong(m.group(1).replace(",", "")); } catch (NumberFormatException ignored) {}
            }
            if (line.trim().startsWith("Ends in:")) active = true;
        }

        return new StackInfo(data, tier, price, active, name);
    }
}
