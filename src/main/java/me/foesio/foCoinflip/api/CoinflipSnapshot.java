package me.foesio.foCoinflip.api;

import java.util.UUID;

/** Immutable identity and wager values for one coinflip. */
public record CoinflipSnapshot(UUID id, UUID creatorId, String creatorName, double stake, long createdAt,
        boolean virtualCreator) {
    public CoinflipSnapshot(UUID id, UUID creatorId, String creatorName, double stake, long createdAt) {
        this(id, creatorId, creatorName, stake, createdAt, false);
    }
}
