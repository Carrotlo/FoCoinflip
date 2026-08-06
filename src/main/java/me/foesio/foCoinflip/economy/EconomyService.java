package me.foesio.foCoinflip.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import me.foesio.foCoinflip.FoCoinflip;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public class EconomyService {

    private final FoCoinflip plugin;
    private final Economy economy;

    public EconomyService(FoCoinflip plugin, Economy economy) {
        this.plugin = plugin;
        this.economy = economy;
    }

    public boolean has(Player player, double amount) {
        return economy.has(player, amount);
    }

    public boolean withdraw(Player player, double amount) {
        EconomyResponse response = economy.withdrawPlayer(player, amount);
        return isSuccess(response, "withdraw", player, amount);
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        EconomyResponse response = economy.depositPlayer(player, amount);
        return isSuccess(response, "deposit", player, amount);
    }

    private boolean isSuccess(EconomyResponse response, String action, OfflinePlayer player, double amount) {
        if (response == null || !response.transactionSuccess()) {
            String name = player == null ? "unknown" : player.getName();
            if (response != null) {
                plugin.getLogger().warning("Economy transaction failed: " + response.errorMessage);
                plugin.fileLogger().warn("Economy " + action + " failed. player=" + name + ", amount=" + amount
                        + ", error=" + response.errorMessage);
            } else {
                plugin.fileLogger().warn("Economy " + action + " failed with null response. player=" + name
                        + ", amount=" + amount);
            }
            return false;
        }
        return true;
    }
}
