package me.foesio.foCoinflip.api;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired after claiming an open flip, before checking funds or withdrawing the joiner's stake. */
public final class CoinflipPreJoinEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final CoinflipSnapshot flip;
    private final UUID joinerId;
    private final String joinerName;
    private final CoinflipResolvedEvent.Source source;
    private boolean cancelled;

    public CoinflipPreJoinEvent(CoinflipSnapshot flip, UUID joinerId, String joinerName,
            CoinflipResolvedEvent.Source source) {
        super(!Bukkit.isPrimaryThread());
        this.flip = flip;
        this.joinerId = joinerId;
        this.joinerName = joinerName;
        this.source = source;
    }

    public CoinflipSnapshot getFlip() { return flip; }
    public UUID getJoinerId() { return joinerId; }
    public String getJoinerName() { return joinerName; }
    public CoinflipResolvedEvent.Source getSource() { return source; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
