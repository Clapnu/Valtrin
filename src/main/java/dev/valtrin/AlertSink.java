// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

/**
 * Where the engine sends what it wants the user to see. In the game the sink turns each item into a chat alert; the
 * server program turns it into data for the mods connected to it. The engine itself knows nothing about Minecraft.
 */
interface AlertSink {
    /** A plain status notice: {@code good} = green "all fine again", otherwise a red warning. */
    void notice(boolean good, String lead, String rest, long at);

    /** One flip alert. {@code real} is false for the "/flip test" alert, which is not counted in the profit stats. */
    void flip(FlipAlert alert, long at, boolean real);

    /** How many alerts are still waiting to be shown (used to stop the queue growing without limit). */
    int pending();

    /**
     * False while this sink's real alerts are not shown (in the game: while the Valtrin server delivers the alerts instead).
     * The engine then keeps them out of /flip next, /flip recent and the sent count, so those only hold alerts the player saw.
     */
    default boolean showsAlerts() { return true; }

    /** True while polling should run because the player is on Hypixel (always true on the server). */
    boolean onHypixel();
}
