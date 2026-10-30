package me.foesio.foCoinflip.api;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired after an open flip is removed; refundSucceeded means all required refunds succeeded. */
public final class CoinflipClosedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final CoinflipSnapshot flip;
    private final Reason reason;
    private final boolean refundSucceeded;

    public CoinflipClosedEvent(CoinflipSnapshot flip, Reason reason, boolean refundSucceeded) {
        super(!Bukkit.isPrimaryThread());
        this.flip = flip;
        this.reason = reason;
        this.refundSucceeded = refundSucceeded;
    }

    public CoinflipSnapshot getFlip() { return flip; }
    public Reason getReason() { return reason; }
    public boolean isRefundSucceeded() { return refundSucceeded; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }

    public enum Reason { PLAYER_CANCEL, CREATOR_QUIT, SHUTDOWN, SETTLEMENT_FAILED, NPC_CANCEL }
}
