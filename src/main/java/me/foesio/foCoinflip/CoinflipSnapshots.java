package me.foesio.foCoinflip;

import me.foesio.foCoinflip.api.CoinflipResolutionSnapshot;
import me.foesio.foCoinflip.api.CoinflipSnapshot;
import me.foesio.foCoinflip.model.Coinflip;
import me.foesio.foCoinflip.model.FlipResolution;

final class CoinflipSnapshots {
    private CoinflipSnapshots() { }

    static CoinflipSnapshot flip(Coinflip flip) {
        return new CoinflipSnapshot(flip.getId(), flip.getCreatorId(), flip.getCreatorName(),
                flip.getStake(), flip.getCreatedAt(), flip.isVirtualCreator());
    }

    static CoinflipResolutionSnapshot resolution(FlipResolution result) {
        return new CoinflipResolutionSnapshot(flip(result.flip()), result.joinerId(),
                result.joinerName(), result.winnerId(), result.winnerName(),
                result.loserId(), result.loserName(), result.pot(), result.tax(), result.payout());
    }
}
