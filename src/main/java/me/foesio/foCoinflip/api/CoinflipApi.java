package me.foesio.foCoinflip.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.OfflinePlayer;

/** FoCoinflip's supported Java integration boundary. Obtain it from Bukkit ServicesManager. */
public interface CoinflipApi {

    /** Current open flips; the returned list and its entries are immutable snapshots. */
    List<CoinflipSnapshot> openFlips();

    /** Current open flip for this player, if any. */
    Optional<CoinflipSnapshot> openFlip(UUID creatorId);

    /** Current cache value, including outcomes whose SQLite write has not completed. */
    CoinflipStats stats(UUID playerId);

    /** Immediately charges the specified player's Vault account. The flip is null on failure. */
    CreateOutcome create(UUID creatorId, String creatorName, double stake);

    /** Charges this player's Vault account immediately, even when they did not issue a command. */
    CreateOutcome createForPlayer(OfflinePlayer creator, double stake);

    /** Opens a server-funded flip for a virtual NPC; no NPC Vault account is charged. */
    CreateOutcome createForNpc(String npcName, double stake);

    /** Cancels a creator's open flip. Paid-player flips are refunded; a failed refund still closes it. */
    CloseOutcome cancel(UUID creatorId);

    /** Matches and settles a flip immediately, without GUI animation.
     * The resolution is null unless the status is SUCCESS. */
    JoinOutcome join(UUID joinerId, String joinerName, UUID flipId);

    enum CreateStatus { SUCCESS, INVALID_PLAYER, INVALID_STAKE, BELOW_MIN, ABOVE_MAX, ALREADY_HAS_FLIP,
        INSUFFICIENT_FUNDS, CANCELLED, ECONOMY_ERROR, INVALID_NPC_NAME }
    enum CloseStatus { SUCCESS, NONE_FOUND, ECONOMY_ERROR }
    enum JoinStatus { SUCCESS, INVALID_PLAYER, INVALID_FLIP_ID, NOT_FOUND, OWN_FLIP, INSUFFICIENT_FUNDS,
        ALREADY_IN_PROGRESS, CANCELLED, ECONOMY_ERROR }

    record CreateOutcome(CreateStatus status, CoinflipSnapshot flip) { }
    record CloseOutcome(CloseStatus status, CoinflipSnapshot flip) { }
    record JoinOutcome(JoinStatus status, CoinflipResolutionSnapshot resolution) { }
}
