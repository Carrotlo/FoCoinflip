package me.foesio.foCoinflip.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import me.foesio.foCoinflip.CoinflipManager;
import me.foesio.foCoinflip.config.PluginConfig;
import me.foesio.foCoinflip.gui.GuiManager;
import me.foesio.foCoinflip.util.MoneyUtil;
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
            messageService.send(sender, "player-only", "{prefix}{bad}Player-only command.");
            return true;
        }

        if (!player.hasPermission("focoinflip.use")) {
            plugin.fileLogger().debug("Player command rejected: no permission. player=" + player.getName());
            messageService.send(player, "no-permission", "{prefix}{bad}No permission.");
            return true;
        }

        if (args.length == 0) {
            guiManager.openMainMenu(player, 0);
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

        messageService.send(player, "usage-main",
                "{prefix}{muted}Use {white}/coinflip{muted}, {white}/coinflip create <amount>{muted}, or {white}/coinflip delete");
        return true;
    }

    private void handleCreate(Player player, String[] args) {
        if (args.length < 2) {
            messageService.send(player, "usage-create", "{prefix}{bad}Usage: {white}/coinflip create <amount>");
            return;
        }

        double amount;
        try {
            amount = MoneyUtil.parseAmount(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
        } catch (NumberFormatException exception) {
            plugin.fileLogger().debug("Create command rejected: invalid amount. player=" + player.getName());
            messageService.send(player, "invalid-amount", "{prefix}{bad}Amount must be valid.");
            return;
        }
        if (!Double.isFinite(amount)) {
            plugin.fileLogger().debug("Create command rejected: non-finite amount. player=" + player.getName());
            messageService.send(player, "invalid-amount", "{prefix}{bad}Amount must be valid.");
            return;
        }

        if (amount < config.getMinBet()) {
            plugin.fileLogger().debug("Create command rejected: below min bet. player=" + player.getName()
                    + ", amount=" + amount + ", min=" + config.getMinBet());
            messageService.send(player, "below-min-bet", "{prefix}{bad}Minimum bet is {theme}${min}{bad}.",
                    Map.of("min", formatMoney(config.getMinBet())));
            return;
        }

        if (amount > config.getMaxBet()) {
            plugin.fileLogger().debug("Create command rejected: above max bet. player=" + player.getName()
                    + ", amount=" + amount + ", max=" + config.getMaxBet());
            messageService.send(player, "above-max-bet", "{prefix}{bad}Maximum bet is {theme}${max}{bad}.",
                    Map.of("max", formatMoney(config.getMaxBet())));
            return;
        }

        CoinflipManager.CreateResponse createResponse = coinflipManager.createFlip(player, amount);
        switch (createResponse.result()) {
            case SUCCESS -> {
                messageService.send(player, "flip-created", "{prefix}{good}Created coinflip for {theme}${amount}{good}.",
                        Map.of("amount", formatMoney(amount)));
                guiManager.openMainMenu(player, 0);
            }
            case ALREADY_HAS_FLIP -> messageService.send(player, "already-has-flip",
                    "{prefix}{bad}You already have an active coinflip.");
            case INSUFFICIENT_FUNDS -> messageService.send(player, "not-enough-money", "{prefix}{bad}Not enough money.");
            case ECONOMY_ERROR -> messageService.send(player, "economy-error",
                    "{prefix}{bad}Economy transaction failed. {muted}Try again.");
        }
    }

    private void handleDelete(Player player) {
        CoinflipManager.DeleteResponse deleteResponse = coinflipManager.deleteOwnFlip(player);
        switch (deleteResponse.result()) {
            case SUCCESS -> {
                messageService.send(player, "flip-deleted", "{prefix}{good}Deleted coinflip and refunded {theme}${amount}{good}.",
                        Map.of("amount", formatMoney(deleteResponse.coinflip().getStake())));
                guiManager.openMainMenu(player, 0);
            }
            case NONE_FOUND -> messageService.send(player, "no-owned-flip",
                    "{prefix}{bad}No active coinflip to delete.");
            case ECONOMY_ERROR -> messageService.send(player, "economy-error",
                    "{prefix}{bad}Economy transaction failed. {muted}Try again.");
        }
    }

    private String formatMoney(double amount) {
        return MoneyUtil.format(amount, config.isCompactMoneyFormat());
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
