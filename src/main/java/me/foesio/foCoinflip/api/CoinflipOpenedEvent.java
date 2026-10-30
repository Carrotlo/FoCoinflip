package me.foesio.foCoinflip.api;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired after the creator's stake is withdrawn and the flip is open in memory. */
public final class CoinflipOpenedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final CoinflipSnapshot flip;

    public CoinflipOpenedEvent(CoinflipSnapshot flip) {
        super(!Bukkit.isPrimaryThread());
        this.flip = flip;
    }

    public CoinflipSnapshot getFlip() { return flip; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
