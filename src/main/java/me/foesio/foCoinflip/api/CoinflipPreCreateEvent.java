package me.foesio.foCoinflip.api;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired after basic validation, before checking funds or withdrawing the creator's stake. */
public final class CoinflipPreCreateEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID creatorId;
    private final String creatorName;
    private final double stake;
    private final CoinflipResolvedEvent.Source source;
    private final boolean virtualCreator;
    private boolean cancelled;

    public CoinflipPreCreateEvent(UUID creatorId, String creatorName, double stake,
            CoinflipResolvedEvent.Source source) {
        this(creatorId, creatorName, stake, source, false);
    }

    public CoinflipPreCreateEvent(UUID creatorId, String creatorName, double stake,
            CoinflipResolvedEvent.Source source, boolean virtualCreator) {
        super(!Bukkit.isPrimaryThread());
        this.creatorId = creatorId;
        this.creatorName = creatorName;
        this.stake = stake;
        this.source = source;
        this.virtualCreator = virtualCreator;
    }

    public UUID getCreatorId() { return creatorId; }
    public String getCreatorName() { return creatorName; }
    public double getStake() { return stake; }
    public CoinflipResolvedEvent.Source getSource() { return source; }
    public boolean isVirtualCreator() { return virtualCreator; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
