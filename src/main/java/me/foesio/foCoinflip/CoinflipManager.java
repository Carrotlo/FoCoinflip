package me.foesio.foCoinflip;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import me.foesio.foCoinflip.api.CoinflipResolvedEvent;
import me.foesio.foCoinflip.config.PluginConfig;
import me.foesio.foCoinflip.economy.EconomyService;
import me.foesio.foCoinflip.model.Coinflip;
import me.foesio.foCoinflip.model.FlipResolution;
import me.foesio.foCoinflip.stats.StatsRepository;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public class CoinflipManager {

    public enum CreateResult {
        SUCCESS,
        ALREADY_HAS_FLIP,
        INSUFFICIENT_FUNDS,
        ECONOMY_ERROR
    }

    public enum DeleteResult {
        SUCCESS,
        NONE_FOUND,
        ECONOMY_ERROR
    }

    public enum JoinStatus {
        SUCCESS,
        NOT_FOUND,
        OWN_FLIP,
        INSUFFICIENT_FUNDS,
        ALREADY_IN_PROGRESS,
        ECONOMY_ERROR
    }

    public record CreateResponse(CreateResult result, Coinflip coinflip) {
    }

    public record DeleteResponse(DeleteResult result, Coinflip coinflip) {
    }

    public record JoinResponse(JoinStatus status, FlipResolution resolution) {
    }

    private final FoCoinflip plugin;
    private final EconomyService economyService;
    private final StatsRepository statsRepository;
    private final PluginConfig config;

    private final Map<UUID, Coinflip> activeById = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> activeByCreator = new ConcurrentHashMap<>();
    private final Set<UUID> suppressedAnnouncementIds = new HashSet<>();

    public CoinflipManager(FoCoinflip plugin, EconomyService economyService, StatsRepository statsRepository, PluginConfig config) {
        this.plugin = plugin;
        this.economyService = economyService;
        this.statsRepository = statsRepository;
        this.config = config;
    }

    public synchronized CreateResponse createFlip(Player creator, double stake) {
        return createFlip((OfflinePlayer) creator, stake);
    }

    /** Creates a flip for an online or offline economy account. */
    public synchronized CreateResponse createFlip(OfflinePlayer creator, double stake) {
        if (creator == null || creator.getUniqueId() == null) {
            return new CreateResponse(CreateResult.ECONOMY_ERROR, null);
        }

        return createFlip(creator.getUniqueId(), playerName(creator), stake);
    }

    /** Creates a flip with a trusted display name from an integration's account link. */
    public synchronized CreateResponse createFlip(UUID creatorId, String creatorName, double stake) {
        if (creatorId == null || !validStake(stake)) {
            return new CreateResponse(CreateResult.ECONOMY_ERROR, null);
        }

        OfflinePlayer creator = Bukkit.getOfflinePlayer(creatorId);
        String resolvedCreatorName = nonBlank(creatorName) == null ? playerName(creator) : creatorName;
        if (hasOpenCreatorFlip(creatorId)) {
            plugin.fileLogger().debug("Create rejected: player already has an open coinflip. player=" + resolvedCreatorName);
            return new CreateResponse(CreateResult.ALREADY_HAS_FLIP, null);
        }

        if (!economyService.has(creator, stake)) {
            plugin.fileLogger().debug("Create rejected: insufficient funds. player=" + resolvedCreatorName
                    + ", stake=" + stake);
            return new CreateResponse(CreateResult.INSUFFICIENT_FUNDS, null);
        }

        if (!economyService.withdraw(creator, stake)) {
            plugin.fileLogger().warn("Create failed: economy withdraw failed. player=" + resolvedCreatorName
                    + ", stake=" + stake);
            return new CreateResponse(CreateResult.ECONOMY_ERROR, null);
        }

        Coinflip coinflip = new Coinflip(UUID.randomUUID(), creatorId, resolvedCreatorName, stake,
                System.currentTimeMillis());
        activeById.put(coinflip.getId(), coinflip);
        activeByCreator.put(creatorId, coinflip.getId());
        plugin.fileLogger().info("Coinflip created. player=" + resolvedCreatorName + ", stake=" + stake
                + ", id=" + coinflip.getId());

        return new CreateResponse(CreateResult.SUCCESS, coinflip);
    }

    public synchronized DeleteResponse deleteOwnFlip(Player creator) {
        return deleteOwnFlip((OfflinePlayer) creator);
    }

    /** Deletes and refunds a flip for an online or offline economy account. */
    public synchronized DeleteResponse deleteOwnFlip(OfflinePlayer creator) {
        if (creator == null || creator.getUniqueId() == null) {
            return new DeleteResponse(DeleteResult.NONE_FOUND, null);
        }
        return deleteOwnFlip(creator.getUniqueId(), playerName(creator));
    }

    /** Deletes and refunds a flip with a trusted display name from an integration's account link. */
    public synchronized DeleteResponse deleteOwnFlip(UUID creatorId, String creatorName) {
        if (creatorId == null) {
            return new DeleteResponse(DeleteResult.NONE_FOUND, null);
        }
        OfflinePlayer creator = Bukkit.getOfflinePlayer(creatorId);
        String resolvedCreatorName = nonBlank(creatorName) == null ? playerName(creator) : creatorName;
        UUID coinflipId = activeByCreator.get(creatorId);
        if (coinflipId == null) {
            plugin.fileLogger().debug("Delete rejected: no open coinflip. player=" + resolvedCreatorName);
            return new DeleteResponse(DeleteResult.NONE_FOUND, null);
        }

        Coinflip coinflip = activeById.get(coinflipId);
        if (coinflip == null) {
            activeByCreator.remove(creatorId);
            plugin.fileLogger().warn("Delete cleanup: stale creator index removed. player=" + resolvedCreatorName);
            return new DeleteResponse(DeleteResult.NONE_FOUND, null);
        }

        if (!coinflip.cancel()) {
            plugin.fileLogger().debug("Delete rejected: coinflip was not open. player=" + resolvedCreatorName
                    + ", id=" + coinflipId);
            return new DeleteResponse(DeleteResult.NONE_FOUND, null);
        }

        if (!economyService.deposit(creator, coinflip.getStake())) {
            // Keep it removed to avoid dupes.
            activeByCreator.remove(creatorId);
            activeById.remove(coinflipId);
            plugin.fileLogger().warn("Delete failed after cancel: refund failed. player=" + resolvedCreatorName
                    + ", stake=" + coinflip.getStake() + ", id=" + coinflipId);
            return new DeleteResponse(DeleteResult.ECONOMY_ERROR, coinflip);
        }

        activeByCreator.remove(creatorId);
        activeById.remove(coinflipId);
        plugin.fileLogger().info("Coinflip deleted and refunded. player=" + resolvedCreatorName
                + ", stake=" + coinflip.getStake() + ", id=" + coinflipId);
        return new DeleteResponse(DeleteResult.SUCCESS, coinflip);
    }

    public synchronized JoinResponse joinFlip(Player joiner, UUID coinflipId) {
        return joinFlip((OfflinePlayer) joiner, coinflipId, CoinflipResolvedEvent.Source.IN_GAME);
    }

    /** Joins a flip from an external integration without opening the in-game animation. */
    public synchronized JoinResponse joinFlipExternal(OfflinePlayer joiner, UUID coinflipId) {
        if (joiner == null || joiner.getUniqueId() == null) {
            return new JoinResponse(JoinStatus.ECONOMY_ERROR, null);
        }
        return joinFlipExternal(joiner.getUniqueId(), playerName(joiner), coinflipId);
    }

    /** Joins a flip with a trusted display name from an integration's account link. */
    public synchronized JoinResponse joinFlipExternal(UUID joinerId, String joinerName, UUID coinflipId) {
        if (joinerId == null) {
            return new JoinResponse(JoinStatus.ECONOMY_ERROR, null);
        }
        OfflinePlayer joiner = Bukkit.getOfflinePlayer(joinerId);
        String resolvedJoinerName = nonBlank(joinerName) == null ? playerName(joiner) : joinerName;
        return joinFlip(joiner, resolvedJoinerName, coinflipId, CoinflipResolvedEvent.Source.EXTERNAL);
    }

    private synchronized JoinResponse joinFlip(OfflinePlayer joiner, UUID coinflipId,
            CoinflipResolvedEvent.Source source) {
        if (joiner == null || joiner.getUniqueId() == null) {
            return new JoinResponse(JoinStatus.ECONOMY_ERROR, null);
        }
        return joinFlip(joiner, playerName(joiner), coinflipId, source);
    }

    private synchronized JoinResponse joinFlip(OfflinePlayer joiner, String joinerName, UUID coinflipId,
            CoinflipResolvedEvent.Source source) {
        if (joiner == null || joiner.getUniqueId() == null || coinflipId == null) {
            return new JoinResponse(JoinStatus.ECONOMY_ERROR, null);
        }
        Coinflip coinflip = activeById.get(coinflipId);
        if (coinflip == null) {
            plugin.fileLogger().debug("Join rejected: coinflip not found. player=" + joinerName
                    + ", id=" + coinflipId);
            return new JoinResponse(JoinStatus.NOT_FOUND, null);
        }

        if (coinflip.getCreatorId().equals(joiner.getUniqueId())) {
            plugin.fileLogger().debug("Join rejected: own coinflip. player=" + joinerName
                    + ", id=" + coinflipId);
            return new JoinResponse(JoinStatus.OWN_FLIP, null);
        }

        if (!coinflip.claimForMatch()) {
            plugin.fileLogger().debug("Join rejected: coinflip already in progress. player=" + joinerName
                    + ", id=" + coinflipId);
            return new JoinResponse(JoinStatus.ALREADY_IN_PROGRESS, null);
        }

        double stake = coinflip.getStake();
        if (!economyService.has(joiner, stake)) {
            coinflip.reopen();
            plugin.fileLogger().debug("Join rejected: insufficient funds. player=" + joinerName
                    + ", stake=" + stake + ", id=" + coinflipId);
            return new JoinResponse(JoinStatus.INSUFFICIENT_FUNDS, null);
        }

        if (!economyService.withdraw(joiner, stake)) {
            coinflip.reopen();
            plugin.fileLogger().warn("Join failed: economy withdraw failed. player=" + joinerName
                    + ", stake=" + stake + ", id=" + coinflipId);
            return new JoinResponse(JoinStatus.ECONOMY_ERROR, null);
        }

        activeById.remove(coinflip.getId());
        activeByCreator.remove(coinflip.getCreatorId());

        boolean creatorWins = ThreadLocalRandom.current().nextBoolean();
        UUID winnerId = creatorWins ? coinflip.getCreatorId() : joiner.getUniqueId();
        UUID loserId = creatorWins ? joiner.getUniqueId() : coinflip.getCreatorId();

        OfflinePlayer winner = Bukkit.getOfflinePlayer(winnerId);
        String winnerName = creatorWins ? coinflip.getCreatorName() : joinerName;
        String loserName = creatorWins ? joinerName : coinflip.getCreatorName();

        double pot = stake * 2.0D;
        double tax = pot * (config.getTaxPercent() / 100.0D);
        double payout = Math.max(0.0D, pot - tax);

        if (!economyService.deposit(winner, payout)) {
            // Best effort rollback.
            OfflinePlayer creatorPlayer = Bukkit.getOfflinePlayer(coinflip.getCreatorId());
            economyService.deposit(creatorPlayer, stake);
            economyService.deposit(joiner, stake);
            plugin.fileLogger().warn("Join failed: payout deposit failed, rollback attempted. winner=" + winnerName
                    + ", payout=" + payout + ", id=" + coinflipId);
            return new JoinResponse(JoinStatus.ECONOMY_ERROR, null);
        }

        coinflip.markResolved();
        statsRepository.recordResolution(winnerId, loserId, payout, stake);

        FlipResolution resolution = new FlipResolution(coinflip, joiner.getUniqueId(), joinerName, winnerId,
                winnerName, loserId, loserName, pot, tax, payout);
        plugin.fileLogger().info("Coinflip resolved. id=" + coinflipId + ", creator=" + coinflip.getCreatorName()
                + ", joiner=" + joinerName + ", winner=" + winnerName + ", pot=" + pot
                + ", tax=" + tax + ", payout=" + payout);
        CoinflipResolvedEvent resolvedEvent = new CoinflipResolvedEvent(resolution, source);
        Bukkit.getPluginManager().callEvent(resolvedEvent);
        if (resolvedEvent.isCoreAnnouncementSuppressed()) {
            suppressedAnnouncementIds.add(resolution.flip().getId());
        }
        return new JoinResponse(JoinStatus.SUCCESS, resolution);
    }

    /** Consumes an integration-requested suppression for the normal GUI broadcast. */
    public synchronized boolean consumeSuppressedAnnouncement(UUID coinflipId) {
        return coinflipId != null && suppressedAnnouncementIds.remove(coinflipId);
    }

    public synchronized Optional<Coinflip> handleCreatorQuit(UUID creatorId) {
        UUID coinflipId = activeByCreator.get(creatorId);
        if (coinflipId == null) {
            return Optional.empty();
        }

        Coinflip coinflip = activeById.get(coinflipId);
        if (coinflip == null) {
            activeByCreator.remove(creatorId);
            return Optional.empty();
        }

        if (!coinflip.cancel()) {
            return Optional.empty();
        }

        OfflinePlayer creator = Bukkit.getOfflinePlayer(creatorId);
        if (!economyService.deposit(creator, coinflip.getStake())) {
            plugin.getLogger().warning("Could not refund coinflip creator on quit for UUID " + creatorId);
            plugin.fileLogger().warn("Quit refund failed. creatorUuid=" + creatorId + ", stake=" + coinflip.getStake());
        }

        activeByCreator.remove(creatorId);
        activeById.remove(coinflipId);
        plugin.fileLogger().info("Coinflip refunded because creator quit. creatorUuid=" + creatorId
                + ", stake=" + coinflip.getStake() + ", id=" + coinflipId);
        return Optional.of(coinflip);
    }

    public synchronized void refundAllOpenFlips() {
        List<Coinflip> coinflips = new ArrayList<>(activeById.values());
        if (!coinflips.isEmpty()) {
            plugin.fileLogger().info("Refunding open coinflips on shutdown. count=" + coinflips.size());
        }
        for (Coinflip coinflip : coinflips) {
            if (coinflip.getStatus() != Coinflip.Status.OPEN) {
                continue;
            }
            OfflinePlayer creator = Bukkit.getOfflinePlayer(coinflip.getCreatorId());
            if (!economyService.deposit(creator, coinflip.getStake())) {
                plugin.getLogger().warning("Failed to refund coinflip on shutdown for UUID " + coinflip.getCreatorId());
                plugin.fileLogger().warn("Shutdown refund failed. creatorUuid=" + coinflip.getCreatorId()
                        + ", stake=" + coinflip.getStake() + ", id=" + coinflip.getId());
            } else {
                plugin.fileLogger().info("Shutdown refund completed. creatorUuid=" + coinflip.getCreatorId()
                        + ", stake=" + coinflip.getStake() + ", id=" + coinflip.getId());
            }
        }
        activeById.clear();
        activeByCreator.clear();
        suppressedAnnouncementIds.clear();
    }

    public synchronized boolean hasActiveFlip(UUID creatorId) {
        return hasOpenCreatorFlip(creatorId);
    }

    public List<Coinflip> getActiveCoinflips() {
        return activeById.values().stream()
                .filter(coinflip -> coinflip.getStatus() == Coinflip.Status.OPEN)
                .sorted(Comparator.comparingLong(Coinflip::getCreatedAt))
                .toList();
    }

    private boolean hasOpenCreatorFlip(UUID creatorId) {
        UUID coinflipId = activeByCreator.get(creatorId);
        if (coinflipId == null) {
            return false;
        }

        Coinflip coinflip = activeById.get(coinflipId);
        if (coinflip != null && coinflip.getStatus() == Coinflip.Status.OPEN) {
            return true;
        }

        activeByCreator.remove(creatorId);
        return false;
    }

    private boolean validStake(double stake) {
        return Double.isFinite(stake) && stake >= 0.0D;
    }

    private String playerName(OfflinePlayer player) {
        String name = player.getName();
        return name == null || name.isBlank() ? player.getUniqueId().toString() : name;
    }

    private String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
