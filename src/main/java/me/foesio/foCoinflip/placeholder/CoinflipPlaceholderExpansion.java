package me.foesio.foCoinflip.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.foesio.foCoinflip.FoCoinflip;
import me.foesio.foCoinflip.stats.PlayerStats;
import me.foesio.foCoinflip.stats.StatsRepository;
import me.foesio.foCoinflip.util.MoneyUtil;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Lifecycle adapter kept local because the core placeholder builder does not expose
 * the registered expansion handle needed for explicit PlaceholderAPI cleanup.
 */
public class CoinflipPlaceholderExpansion extends PlaceholderExpansion {

    private final FoCoinflip plugin;
    private final StatsRepository statsRepository;

    public CoinflipPlaceholderExpansion(FoCoinflip plugin, StatsRepository statsRepository) {
        this.plugin = plugin;
        this.statsRepository = statsRepository;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "focoinflip";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Carrotio";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        String normalized = params.toLowerCase();
        if (player == null || player.getUniqueId() == null) {
            return normalized.endsWith("_formatted") ? formatMoney(0D) : MoneyUtil.raw(0D);
        }

        PlayerStats stats = statsRepository.getStats(player.getUniqueId());
        return switch (normalized) {
            case "total_won", "total_won_raw" -> MoneyUtil.raw(stats.totalWon());
            case "total_won_formatted" -> formatMoney(stats.totalWon());
            case "total_lost", "total_lost_raw" -> MoneyUtil.raw(stats.totalLost());
            case "total_lost_formatted" -> formatMoney(stats.totalLost());
            case "total_sum", "total_sum_raw" -> MoneyUtil.raw(stats.totalSum());
            case "total_sum_formatted" -> formatMoney(stats.totalSum());
            default -> null;
        };
    }

    private String formatMoney(double amount) {
        return MoneyUtil.format(amount, plugin.getConfig().getBoolean("settings.compact-money-format", false));
    }
}
