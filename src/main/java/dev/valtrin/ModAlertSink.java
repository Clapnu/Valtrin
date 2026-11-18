// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import java.util.Queue;

/** The in-game sink: turns the engine's alerts into the same chat alerts as before and queues them for the client thread. */
final class ModAlertSink implements AlertSink {
    private final Queue<ValtrinMod.TimedAlert> queue;

    ModAlertSink(Queue<ValtrinMod.TimedAlert> queue) {
        this.queue = queue;
    }

    @Override
    public void notice(boolean good, String lead, String rest, long at) {
        if (serverDelivers()) return; // the server sends the alerts and the notices; this computer only keeps its price data fresh
        queue.add(new ValtrinMod.TimedAlert(good ? FlipAlerts.good(lead, rest) : FlipAlerts.warn(lead, rest), at));
    }

    @Override
    public void flip(FlipAlert f, long at, boolean real) {
        if (real && serverDelivers()) return; // the same flip arrives from the server (the "/flip test" alert still shows)
        SellerClick.remember(f.uuid(), f.sellerUuid(), f.sellerName());
        var msg = FlipAlerts.build(f.name(), f.tier(), f.uuid(), f.buy(), f.sell(), f.profit(), f.margin(), f.volume(),
                f.detail(), f.sellerName(), f.conf(), f.resale());
        queue.add(real ? new ValtrinMod.TimedAlert(msg, at, f.profit()) : new ValtrinMod.TimedAlert(msg, at));
    }

    @Override
    public int pending() {
        return queue.size();
    }

    private volatile ServerLink link;

    void setLink(ServerLink l) {
        this.link = l;
    }

    /** True while the Valtrin server is delivering the alerts. */
    private boolean serverDelivers() {
        ServerLink l = link;
        return l != null && l.connected();
    }

    @Override
    public boolean onHypixel() {
        // Polling goes on while the server delivers alerts: it keeps the price tooltips and the AH colours fresh. Its own alerts are dropped above.
        return ValtrinMod.onHypixel;
    }
}
