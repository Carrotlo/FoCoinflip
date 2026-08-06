package me.foesio.foCoinflip.model;

import java.util.UUID;

public record FlipResolution(
        Coinflip flip,
        UUID joinerId,
        String joinerName,
        UUID winnerId,
        String winnerName,
        UUID loserId,
        String loserName,
        double pot,
        double tax,
        double payout) {
}
