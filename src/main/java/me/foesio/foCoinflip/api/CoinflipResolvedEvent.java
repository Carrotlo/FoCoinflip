package me.foesio.foCoinflip.api;

import java.util.Objects;
import me.foesio.foCoinflip.api.CoinflipResolutionSnapshot;
import me.foesio.foCoinflip.api.CoinflipSnapshot;
import me.foesio.foCoinflip.model.FlipResolution;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired after a coinflip has settled and its stats have been recorded.
 *
 * <p>The source lets integrations distinguish the normal in-game GUI flow
 * from an external integration such as a Discord addon. The event is fired
 * on the thread that settles the flip. Statistics have been updated in memory;
 * their SQLite write may still be pending. When a virtual NPC wins, the
 * calculated payout is not deposited into any Vault account.</p>
 */
public final class CoinflipResolvedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final FlipResolution resolution;
    private final CoinflipResolutionSnapshot snapshot;
    private final Source source;
    private boolean coreAnnouncementSuppressed;

    public CoinflipResolvedEvent(FlipResolution resolution, Source source) {
        super(!Bukkit.isPrimaryThread());
        this.resolution = Objects.requireNonNull(resolution, "resolution");
        this.snapshot = new CoinflipResolutionSnapshot(
                new CoinflipSnapshot(resolution.flip().getId(), resolution.flip().getCreatorId(),
                        resolution.flip().getCreatorName(), resolution.flip().getStake(),
                        resolution.flip().getCreatedAt(), resolution.flip().isVirtualCreator()),
                resolution.joinerId(), resolution.joinerName(),
                resolution.winnerId(), resolution.winnerName(), resolution.loserId(),
                resolution.loserName(), resolution.pot(), resolution.tax(), resolution.payout());
        this.source = Objects.requireNonNull(source, "source");
    }

    public FlipResolution getResolution() {
        return resolution;
    }

    /** Preferred immutable payload for new consumers. */
    public CoinflipResolutionSnapshot getSnapshot() {
        return snapshot;
    }

    public Source getSource() {
        return source;
    }

    /** Prevents the normal in-game GUI flow from broadcasting this result. */
    public void suppressCoreAnnouncement() {
        coreAnnouncementSuppressed = true;
    }

    public boolean isCoreAnnouncementSuppressed() {
        return coreAnnouncementSuppressed;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    public enum Source {
        IN_GAME,
        EXTERNAL
    }
}
