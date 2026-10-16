package me.foesio.foCoinflip.config;

import java.util.Map;
import java.util.Set;
import me.foesio.core.gui.publicgui.PublicGuiContract;

public final class CoinflipGuiContracts {

    public static final String MAIN = "coinflip.main";
    public static final String ANIMATION = "coinflip.animation";

    private CoinflipGuiContracts() {
    }

    public static PublicGuiContract main() {
        return new PublicGuiContract(
                Set.of("coinflip.previous", "coinflip.next", "coinflip.sort", "coinflip.create",
                        "coinflip.stats", "coinflip.refresh", "coinflip.join"),
                Map.of(), Set.of("page", "pages", "player", "amount", "won", "lost", "sum"),
                PublicGuiContract.InteractionMode.CORE);
    }

    public static PublicGuiContract animation() {
        return new PublicGuiContract(Set.of(), Map.of("display-slots",
                PublicGuiContract.GroupRule.exactly(PublicGuiContract.GroupRole.DYNAMIC_OUTPUT, 1)),
                Set.of("player", "amount", "winner", "loser", "pot", "tax", "payout"),
                PublicGuiContract.InteractionMode.PLUGIN_OWNED);
    }
}
