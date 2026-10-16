package me.foesio.foCoinflip.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import me.foesio.foCoinflip.CoinflipManager;
import me.foesio.foCoinflip.config.PluginConfig;
import me.foesio.foCoinflip.gui.GuiManager;
import me.foesio.foCoinflip.util.MoneyUtil;
import me.foesio.foCoinflip.util.ConfiguredPublicMessages;
import me.foesio.core.message.FoMessageService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public class CoinflipCommand implements CommandExecutor, TabCompleter {

    private final PluginConfig config;
    private final FoMessageService messageService;
    private final CoinflipManager coinflipManager;
    private final GuiManager guiManager;
    private final me.foesio.foCoinflip.FoCoinflip plugin;

    public CoinflipCommand(me.foesio.foCoinflip.FoCoinflip plugin, PluginConfig config, FoMessageService messageService,
            CoinflipManager coinflipManager, GuiManager guiManager) {
        this.plugin = plugin;
        this.config = config;
        this.messageService = messageService;
        this.coinflipManager = coinflipManager;
        this.guiManager = guiManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.fileLogger().debug("Player command rejected: sender is not a player. sender=" + sender.getName());
            ConfiguredPublicMessages.send(messageService, sender, "player-only");
            return true;
        }

        if (!player.hasPermission("focoinflip.use")) {
            plugin.fileLogger().debug("Player command rejected: no permission. player=" + player.getName());
            ConfiguredPublicMessages.send(messageService, player, "no-permission");
            plugin.sounds().play(player, "coinflip.error");
            return true;
        }

        if (args.length == 0) {
            guiManager.openMainMenuFromCommand(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("create")) {
            handleCreate(player, args);
            return true;
        }

        if (args[0].equalsIgnoreCase("delete")) {
            handleDelete(player);
            return true;
        }

        ConfiguredPublicMessages.send(messageService, player, "usage-main");
        plugin.sounds().play(player, "coinflip.error");
        return true;
    }

    private void handleCreate(Player player, String[] args) {
        if (args.length < 2) {
            ConfiguredPublicMessages.send(messageService, player, "usage-create");
            plugin.sounds().play(player, "coinflip.error");
            return;
        }

        double amount;
        try {
            amount = MoneyUtil.parseAmount(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
        } catch (NumberFormatException exception) {
            plugin.fileLogger().debug("Create command rejected: invalid amount. player=" + player.getName());
            ConfiguredPublicMessages.send(messageService, player, "invalid-amount");
            plugin.sounds().play(player, "coinflip.error");
            return;
        }
        if (!Double.isFinite(amount)) {
            plugin.fileLogger().debug("Create command rejected: non-finite amount. player=" + player.getName());
            ConfiguredPublicMessages.send(messageService, player, "invalid-amount");
            plugin.sounds().play(player, "coinflip.error");
            return;
        }

        if (amount < config.getMinBet()) {
            plugin.fileLogger().debug("Create command rejected: below min bet. player=" + player.getName()
                    + ", amount=" + amount + ", min=" + config.getMinBet());
            ConfiguredPublicMessages.send(messageService, player, "below-min-bet",
                    Map.of("min", formatMoney(config.getMinBet())));
            plugin.sounds().play(player, "coinflip.error");
            return;
        }

        if (amount > config.getMaxBet()) {
            plugin.fileLogger().debug("Create command rejected: above max bet. player=" + player.getName()
                    + ", amount=" + amount + ", max=" + config.getMaxBet());
            ConfiguredPublicMessages.send(messageService, player, "above-max-bet",
                    Map.of("max", formatMoney(config.getMaxBet())));
            plugin.sounds().play(player, "coinflip.error");
            return;
        }

        CoinflipManager.CreateResponse createResponse = coinflipManager.createFlip(player, amount);
        switch (createResponse.result()) {
            case SUCCESS -> {
                ConfiguredPublicMessages.send(messageService, player, "flip-created",
                        Map.of("amount", formatMoney(amount)));
                plugin.sounds().play(player, "coinflip.created");
                guiManager.openMainMenuFromCommand(player);
            }
            case ALREADY_HAS_FLIP -> sendCreateError(player, "already-has-flip");
            case INSUFFICIENT_FUNDS -> sendCreateError(player, "not-enough-money");
            case ECONOMY_ERROR -> sendCreateError(player, "economy-error");
        }
    }

    private void handleDelete(Player player) {
        CoinflipManager.DeleteResponse deleteResponse = coinflipManager.deleteOwnFlip(player);
        switch (deleteResponse.result()) {
            case SUCCESS -> {
                ConfiguredPublicMessages.send(messageService, player, "flip-deleted",
                        Map.of("amount", formatMoney(deleteResponse.coinflip().getStake())));
                plugin.sounds().play(player, "coinflip.cancelled");
                guiManager.openMainMenuFromCommand(player);
            }
            case NONE_FOUND -> sendCreateError(player, "no-owned-flip");
            case ECONOMY_ERROR -> sendCreateError(player, "economy-error");
        }
    }

    private void sendCreateError(Player player, String path) {
        ConfiguredPublicMessages.send(messageService, player, path);
        plugin.sounds().play(player, "coinflip.error");
    }

    private String formatMoney(double amount) {
        return MoneyUtil.format(amount, config.isCompactMoneyFormat(), messageService.config());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            String input = args[0].toLowerCase();
            if ("create".startsWith(input)) {
                completions.add("create");
            }
            if ("delete".startsWith(input)) {
                completions.add("delete");
            }
        }
        return completions;
    }
}
