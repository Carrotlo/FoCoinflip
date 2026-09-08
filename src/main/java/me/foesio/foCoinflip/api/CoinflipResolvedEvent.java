package me.foesio.foCoinflip.api;

import java.util.Objects;
import me.foesio.foCoinflip.model.FlipResolution;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired after a coinflip has been paid out and its stats have been recorded.
 *
 * <p>The source lets integrations distinguish the normal in-game GUI flow
 * from an external integration such as a Discord addon. The event is fired
 * synchronously on the server thread.</p>
 */
public final class CoinflipResolvedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final FlipResolution resolution;
    private final Source source;
    private boolean coreAnnouncementSuppressed;

    public CoinflipResolvedEvent(FlipResolution resolution, Source source) {
        super(false);
        this.resolution = Objects.requireNonNull(resolution, "resolution");
        this.source = Objects.requireNonNull(source, "source");
    }

    public FlipResolution getResolution() {
        return resolution;
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
