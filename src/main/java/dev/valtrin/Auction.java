// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

/** A single active BIN auction from the Hypixel API. */
record Auction(String uuid, String name, String tier, long price, long start, String itemBytes, String seller) {
}
