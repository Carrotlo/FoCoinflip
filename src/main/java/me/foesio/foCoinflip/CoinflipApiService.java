package me.foesio.foCoinflip;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.lang.reflect.Method;
import me.foesio.foCoinflip.api.CoinflipApi;
import me.foesio.foCoinflip.api.CoinflipSnapshot;
import me.foesio.foCoinflip.api.CoinflipStats;
import me.foesio.foCoinflip.stats.PlayerStats;
import me.foesio.foCoinflip.stats.StatsRepository;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

final class CoinflipApiService implements CoinflipApi {
    private final FoCoinflip plugin;
    private final CoinflipManager manager;
    private final StatsRepository statsRepository;

    CoinflipApiService(FoCoinflip plugin, CoinflipManager manager, StatsRepository statsRepository) {
        this.plugin = plugin;
        this.manager = manager;
        this.statsRepository = statsRepository;
    }

    @Override
    public List<CoinflipSnapshot> openFlips() {
        requireEnabled();
        requireServerThread();
        return manager.getActiveCoinflips().stream().map(CoinflipSnapshots::flip).toList();
    }

    @Override
    public Optional<CoinflipSnapshot> openFlip(UUID creatorId) {
        requireEnabled();
        requireServerThread();
        if (creatorId == null) return Optional.empty();
        return manager.getActiveCoinflips().stream().filter(f -> f.getCreatorId().equals(creatorId))
                .findFirst().map(CoinflipSnapshots::flip);
    }

    @Override
    public CoinflipStats stats(UUID playerId) {
        requireEnabled();
        requireServerThread();
        if (playerId == null) throw new IllegalArgumentException("playerId is required");
        PlayerStats value = statsRepository.getStats(playerId);
        return new CoinflipStats(value.totalWon(), value.totalLost(), value.totalSum());
    }

    @Override
    public CreateOutcome create(UUID creatorId, String creatorName, double stake) {
        requireEnabled();
        requireServerThread();
        return createOutcome(manager.createFlip(creatorId, creatorName, stake));
    }

    @Override
    public CreateOutcome createForPlayer(OfflinePlayer creator, double stake) {
        requireEnabled();
        requireServerThread();
        return createOutcome(manager.createFlip(creator, stake));
    }

    @Override
    public CreateOutcome createForNpc(String npcName, double stake) {
        requireEnabled();
        requireServerThread();
        return createOutcome(manager.createNpcFlip(npcName, stake));
    }

    private CreateOutcome createOutcome(CoinflipManager.CreateResponse response) {
        CoinflipSnapshot flip = response.coinflip() == null ? null : CoinflipSnapshots.flip(response.coinflip());
        return new CreateOutcome(CreateStatus.valueOf(response.result().name()), flip);
    }

    @Override
    public CloseOutcome cancel(UUID creatorId) {
        requireEnabled();
        requireServerThread();
        CoinflipManager.DeleteResponse response = manager.deleteOwnFlip(creatorId, null);
        CoinflipSnapshot flip = response.coinflip() == null ? null : CoinflipSnapshots.flip(response.coinflip());
        return new CloseOutcome(CloseStatus.valueOf(response.result().name()), flip);
    }

    @Override
    public JoinOutcome join(UUID joinerId, String joinerName, UUID flipId) {
        requireEnabled();
        requireServerThread();
        CoinflipManager.JoinResponse response = manager.joinFlipExternal(joinerId, joinerName, flipId);
        return new JoinOutcome(JoinStatus.valueOf(response.status().name()),
                response.resolution() == null ? null : CoinflipSnapshots.resolution(response.resolution()));
    }

    private void requireEnabled() {
        if (!plugin.isEnabled()) throw new IllegalStateException("FoCoinflip is disabled");
    }

    private void requireServerThread() {
        if (!plugin.isFoliaServer()) {
            if (Bukkit.isPrimaryThread()) return;
            throw new IllegalStateException("Coinflip API calls must run on the primary server thread");
        }
        // Paper/Folia exposes this method; keep the call reflective for Spigot compatibility.
        try {
            Method check = Bukkit.class.getMethod("isGlobalTickThread");
            if (Boolean.TRUE.equals(check.invoke(null))) return;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Reject the call if this Folia build cannot confirm global tick ownership.
        }
        throw new IllegalStateException("Coinflip API calls must run on the global tick thread on Folia");
    }
}
