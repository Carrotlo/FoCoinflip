package me.foesio.foCoinflip.api;

import java.util.UUID;

/** Immutable values from a settled coinflip. An NPC winner receives no Vault deposit. */
public record CoinflipResolutionSnapshot(CoinflipSnapshot flip, UUID joinerId, String joinerName,
        UUID winnerId, String winnerName, UUID loserId, String loserName,
        double pot, double tax, double payout) {
}
