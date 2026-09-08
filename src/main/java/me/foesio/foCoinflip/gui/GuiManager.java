package me.foesio.foCoinflip.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import me.foesio.core.FoCoreContext;
import me.foesio.core.dialog.DialogButton;
import me.foesio.core.dialog.TextDialogRequest;
import me.foesio.core.dialog.DialogIcons;
import me.foesio.core.editor.CycleOption;
import me.foesio.core.editor.CycleOptions;
import me.foesio.core.editor.EditorDialogInputs;
import me.foesio.core.editor.EditorItemFactory;
import me.foesio.core.editor.EditorMenuBuilder;
import me.foesio.core.editor.EditorMenuHolder;
import me.foesio.core.editor.EditorSaveResult;
import me.foesio.core.editor.EditorSettingSaver;
import me.foesio.core.gui.GuiButtonConfig;
import me.foesio.core.gui.FoButtonStyle;
import me.foesio.core.gui.GuiTitles;
import me.foesio.core.number.LargeNumberParser;
import me.foesio.foCoinflip.CoinflipManager;
import me.foesio.foCoinflip.config.PluginConfig;
import me.foesio.foCoinflip.FoCoinflip;
import me.foesio.foCoinflip.model.Coinflip;
import me.foesio.foCoinflip.model.FlipResolution;
import me.foesio.foCoinflip.stats.PlayerStats;
import me.foesio.foCoinflip.stats.StatsRepository;
import me.foesio.foCoinflip.util.MoneyUtil;
import me.foesio.core.message.FoMessageService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import me.foesio.core.scheduler.FoScheduler;
import org.bukkit.profile.PlayerProfile;

public class GuiManager {

    private static final int ADMIN_GUI_ROWS = 4;
    private static final long REFRESH_COOLDOWN_NANOS = 1_000_000_000L;
    private static final List<Integer> DEFAULT_CONTENT_SLOTS = List.of(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34);
    private static final Pattern JAVA_USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private final FoCoinflip plugin;
    private final PluginConfig config;
    private final FoMessageService messageService;
    private final CoinflipManager coinflipManager;
    private final StatsRepository statsRepository;
    private final Supplier<FoCoreContext> coreSupplier;
    private final GuiButtonConfig buttons = GuiButtonConfig.defaults();
    private final EditorSettingSaver editorSettingSaver;
    private final SkinsRestorerSkullService skinsRestorerSkullService;
    private final Set<UUID> warnedFallbackAdmins = new HashSet<>();
    private final Map<UUID, Long> refreshCooldowns = new HashMap<>();

    private final Map<UUID, AnimationSession> animationSessions = new HashMap<>();
    private final Map<UUID, UUID> playerToSession = new HashMap<>();
    private final Map<UUID, String> sortModes = new HashMap<>();

    private enum SortMode {
        AMOUNT_DESC("amount-desc"),
        AMOUNT_ASC("amount-asc"),
        NEWEST("newest"),
        OLDEST("oldest");

        private final String value;

        SortMode(String value) {
            this.value = value;
        }

        private static SortMode fromValue(String value) {
            for (SortMode mode : values()) {
                if (mode.value.equalsIgnoreCase(value)) {
                    return mode;
                }
            }
            return null;
        }
    }

    public GuiManager(FoCoinflip plugin, PluginConfig config, FoMessageService messageService, CoinflipManager coinflipManager,
            StatsRepository statsRepository, Supplier<FoCoreContext> coreSupplier) {
        this.plugin = plugin;
        this.config = config;
        this.messageService = messageService;
        this.coinflipManager = coinflipManager;
        this.statsRepository = statsRepository;
        this.coreSupplier = coreSupplier;
        this.editorSettingSaver = new EditorSettingSaver(plugin, () -> { });
        this.skinsRestorerSkullService = new SkinsRestorerSkullService(plugin);
    }

    public void openMainMenu(Player player, int requestedPage) {
        FileConfiguration gui = config.mainGui();
        List<CycleOption> sortOptions = configuredSortOptions(gui);
        String sortMode = currentSortMode(player, sortOptions);
        List<Coinflip> activeFlips = sortCoinflips(coinflipManager.getActiveCoinflips(), sortMode);
        int inventorySize = inventorySize(gui, "rows", 6, "main");
        List<Integer> slots = contentSlots(gui, inventorySize);
        int pageSize = Math.max(1, slots.size());
        int maxPage = Math.max(0, (activeFlips.size() - 1) / pageSize);
        int page = Math.max(0, Math.min(requestedPage, maxPage));

        MainGuiHolder holder = new MainGuiHolder(page);
        Inventory inventory = Bukkit.createInventory(holder, inventorySize,
                GuiTitles.format(config.getMainGuiTitle()));

        fillBackground(player, inventory, gui);
        fillCoinflipPage(player, inventory, holder, activeFlips, page, slots);

        if (page > 0) {
            int previousSlot = configuredSlot(gui, "navigation.previous-slot", 45, inventorySize, "main previous");
            if (previousSlot >= 0) {
                inventory.setItem(previousSlot, buttons.previousPage(player, page, maxPage));
            }
        }

        if (page < maxPage) {
            int nextSlot = configuredSlot(gui, "navigation.next-slot", 53, inventorySize, "main next");
            if (nextSlot >= 0) {
                inventory.setItem(nextSlot, buttons.nextPage(player, page, maxPage));
            }
        }

        setConfiguredCycleItem(player, inventory, gui, "items.sort", sortMode, sortOptions, Material.COMPARATOR);
        setConfiguredItem(player, inventory, gui, "items.create", Collections.emptyMap(), Material.EMERALD);
        setConfiguredItem(player, inventory, gui, "items.refresh", Collections.emptyMap(), Material.COMPASS);
        setConfiguredItem(player, inventory, gui, "items.stats", statsTokens(player), Material.BOOK);

        player.openInventory(inventory);
    }

    public void openMainMenuFromCommand(Player player) {
        plugin.getGuiSounds().open(player);
        openMainMenu(player, 0);
    }

    public void openAdminEditor(Player player) {
        openAdminEditor(player, true);
    }

    private void openAdminEditor(Player player, boolean playOpenSound) {
        EditorMenuBuilder editor = EditorMenuBuilder.menu(
                "focoinflip-admin",
                messageService.renderTemplate(config.getAdminGuiTitle(), Map.of()),
                ADMIN_GUI_ROWS)
                .filler(EditorItemFactory.filler());
        editor.action(10, "tax-percent").button(10, Material.PAPER, "Tax Percent",
                List.of("{white}Current: {theme}" + config.getTaxPercent() + "%", "{muted}Click to edit"), "edit the tax percentage");
        editor.action(11, "min-bet").button(11, Material.EMERALD, "Min Bet",
                List.of("{white}Current: {theme}$" + formatMoney(config.getMinBet()), "{muted}Click to edit"), "edit the minimum bet");
        editor.action(12, "max-bet").button(12, Material.DIAMOND, "Max Bet",
                List.of("{white}Current: {theme}$" + formatMoney(config.getMaxBet()), "{muted}Click to edit"), "edit the maximum bet");
        editor.action(13, "compact-money-format").toggle(13, "Compact Money Format", config.isCompactMoneyFormat());
        editor.action(14, "lock-animation-gui").toggle(14, "Lock Animation GUI", config.isLockAnimationGui());
        editor.action(15, "announce-result").toggle(15, "Announce Result", config.isAnnounceCoinflipResult());
        editor.action(16, "animation-duration").button(16, Material.CLOCK, "Animation Duration",
                List.of("{white}Current: {theme}" + config.getAnimationDurationTicks() + " ticks",
                        "{muted}Click to edit"), "edit the animation duration");
        editor.action(19, "frame-interval").button(19, Material.REPEATER, "Frame Interval",
                List.of("{white}Current: {theme}" + config.getAnimationFrameIntervalTicks() + " ticks",
                        "{muted}Click to edit"), "edit the frame interval");
        editor.open(player);
        if (playOpenSound) {
            plugin.getEditorSounds().open(player);
        }
    }

    public void handleMainClick(Player player, MainGuiHolder holder, int slot) {
        FileConfiguration gui = config.mainGui();
        int inventorySize = player.getOpenInventory().getTopInventory().getSize();
        if (slot == configuredSlot(gui, "navigation.previous-slot", 45, inventorySize, "main previous")) {
            plugin.getGuiSounds().previousPage(player);
            openMainMenu(player, holder.getPage() - 1);
            return;
        }

        if (slot == configuredSlot(gui, "navigation.next-slot", 53, inventorySize, "main next")) {
            plugin.getGuiSounds().nextPage(player);
            openMainMenu(player, holder.getPage() + 1);
            return;
        }

        if (slot == configuredSlot(gui, "items.sort.slot", 47, inventorySize, "main sort")) {
            List<CycleOption> options = configuredSortOptions(gui);
            String current = currentSortMode(player, options);
            sortModes.put(player.getUniqueId(), CycleOptions.nextValue(current, options));
            plugin.getGuiSounds().cycle(player);
            openMainMenu(player, holder.getPage());
            return;
        }

        if (slot == configuredSlot(gui, "items.refresh.slot", 49, inventorySize, "main refresh")) {
            if (isRefreshOnCooldown(player)) {
                return;
            }
            plugin.getGuiSounds().click(player);
            openMainMenu(player, holder.getPage());
            return;
        }

        if (slot == configuredSlot(gui, "items.create.slot", 48, inventorySize, "main create")) {
            plugin.getGuiSounds().click(player);
            player.closeInventory();
            sendCreateSuggestion(player);
            return;
        }

        if (slot == configuredSlot(gui, "items.stats.slot", 50, inventorySize, "main stats")) {
            plugin.getGuiSounds().click(player);
            messageService.send(player, "stats",
                    "{prefix}{theme}Your Stats {muted}| {good}Won: {theme}${won}{muted}, "
                            + "{bad}Lost: {theme}${lost}{muted}, {good}Net: {theme}${sum}",
                    statsTokens(player));
            return;
        }

        UUID coinflipId = holder.getCoinflip(slot);
        if (coinflipId == null) {
            return;
        }

        CoinflipManager.JoinResponse joinResponse = coinflipManager.joinFlip(player, coinflipId);
        switch (joinResponse.status()) {
            case SUCCESS -> {
                player.closeInventory();
                startAnimation(joinResponse.resolution());
            }
            case OWN_FLIP -> sendJoinError(player, "join-own", "{prefix}{bad}You cannot join your own coinflip.");
            case INSUFFICIENT_FUNDS -> sendJoinError(player, "not-enough-money", "{prefix}{bad}Not enough money.");
            case ALREADY_IN_PROGRESS, NOT_FOUND -> {
                sendJoinError(player, "flip-no-longer-available", "{prefix}{bad}Coinflip no longer available.");
                openMainMenu(player, holder.getPage());
            }
            case ECONOMY_ERROR -> sendJoinError(player, "economy-error",
                    "{prefix}{bad}Economy transaction failed. {muted}Try again.");
        }
    }

    public void handleAdminClick(Player player, EditorMenuHolder holder, int slot) {
        String action = holder.actionAt(slot);
        if (action == null) {
            return;
        }
        switch (action) {
            case "tax-percent" -> openEditorInput(player, false, "Tax Percent", String.valueOf(config.getTaxPercent()),
                    "0", "100", "a number from 0 to 100", input -> {
                double value = parseDouble(input, "Tax must be a valid number");
                if (value < 0 || value > 100) {
                    throw new IllegalArgumentException("Tax must be between 0 and 100");
                }
                saveConfigValue("settings.tax-percent", value);
                logAdminSetting(player, "settings.tax-percent", value);
                plugin.getEditorSounds().save(player);
                openAdminEditor(player, false);
            });
            case "min-bet" -> openEditorInput(player, true, "Min Bet", MoneyUtil.raw(config.getMinBet()), "0",
                    MoneyUtil.raw(config.getMaxBet()), "a positive money amount, like 50k, 1m, or 1 billion", input -> {
                double value = parseAmount(input, "Min bet must be a valid amount");
                if (value < 0) {
                    throw new IllegalArgumentException("Min bet must be at least 0");
                }
                if (value > config.getMaxBet()) {
                    throw new IllegalArgumentException("Min bet cannot exceed max bet");
                }
                saveConfigValue("settings.min-bet", value);
                logAdminSetting(player, "settings.min-bet", value);
                plugin.getEditorSounds().save(player);
                openAdminEditor(player, false);
            });
            case "max-bet" -> openEditorInput(player, true, "Max Bet", MoneyUtil.raw(config.getMaxBet()),
                    MoneyUtil.raw(config.getMinBet()), "unlimited", "a money amount, like 1m, 1b, or 1 trillion", input -> {
                double value = parseAmount(input, "Max bet must be a valid amount");
                if (value < config.getMinBet()) {
                    throw new IllegalArgumentException("Max bet cannot be below min bet");
                }
                saveConfigValue("settings.max-bet", value);
                logAdminSetting(player, "settings.max-bet", value);
                plugin.getEditorSounds().save(player);
                openAdminEditor(player, false);
            });
            case "compact-money-format" -> {
                boolean enabled = !config.isCompactMoneyFormat();
                saveConfigValue("settings.compact-money-format", enabled);
                logAdminSetting(player, "settings.compact-money-format", enabled);
                plugin.getEditorSounds().toggle(player, enabled);
                openAdminEditor(player, false);
                messageService.send(player, "setting-updated", "{prefix}{good}Setting updated.");
            }
            case "lock-animation-gui" -> {
                boolean enabled = !config.isLockAnimationGui();
                saveConfigValue("settings.lock-animation-gui", enabled);
                logAdminSetting(player, "settings.lock-animation-gui", enabled);
                plugin.getEditorSounds().toggle(player, enabled);
                openAdminEditor(player, false);
                messageService.send(player, "setting-updated", "{prefix}{good}Setting updated.");
            }
            case "announce-result" -> {
                boolean enabled = !config.isAnnounceCoinflipResult();
                saveConfigValue("settings.announce-coinflip-result", enabled);
                logAdminSetting(player, "settings.announce-coinflip-result", enabled);
                plugin.getEditorSounds().toggle(player, enabled);
                openAdminEditor(player, false);
                messageService.send(player, "setting-updated", "{prefix}{good}Setting updated.");
            }
            case "animation-duration" -> openEditorInput(player, false, "Animation Duration",
                    String.valueOf(config.getAnimationDurationTicks()), "20", "unlimited",
                    "a whole number of ticks, minimum 20", input -> {
                int value = parseInteger(input, "Duration must be a whole number");
                if (value < 20) {
                    throw new IllegalArgumentException("Duration must be at least 20 ticks");
                }
                saveConfigValue("settings.animation.duration-ticks", value);
                logAdminSetting(player, "settings.animation.duration-ticks", value);
                plugin.getEditorSounds().save(player);
                openAdminEditor(player, false);
            });
            case "frame-interval" -> openEditorInput(player, false, "Frame Interval",
                    String.valueOf(config.getAnimationFrameIntervalTicks()), "1", "unlimited",
                    "a whole number of ticks, minimum 1", input -> {
                int value = parseInteger(input, "Frame interval must be a whole number");
                if (value < 1) {
                    throw new IllegalArgumentException("Frame interval must be at least 1 tick");
                }
                saveConfigValue("settings.animation.frame-interval-ticks", value);
                logAdminSetting(player, "settings.animation.frame-interval-ticks", value);
                plugin.getEditorSounds().save(player);
                openAdminEditor(player, false);
            });
            default -> {
            }
        }
    }

    private void openEditorInput(Player player, boolean moneyInput, String setting, String currentValue, String minimumValue,
            String maximumValue, String chatFormat, java.util.function.Consumer<String> onSubmit) {
        TextDialogRequest request = editorDialogRequest(moneyInput, setting, currentValue, minimumValue, maximumValue, chatFormat);
        FoCoreContext core = coreSupplier.get();
        boolean openedNative = EditorDialogInputs.openTextFromInventory(plugin, core.inventoryCloseSuppressor(),
                core.dialogService(), player, request, input -> {
                    try {
                        onSubmit.accept(input);
                        messageService.send(player, "prompt-saved", "{prefix}{good}Saved setting.");
                    } catch (IllegalArgumentException exception) {
                        plugin.getEditorSounds().error(player);
                        String reason = exception.getMessage() == null || exception.getMessage().isBlank()
                                ? "Invalid value" : exception.getMessage();
                        messageService.send(player, "prompt-invalid", "{prefix}{bad}{reason}", Map.of("reason", reason));
                        openEditorInput(player, moneyInput, setting, currentValue, minimumValue, maximumValue, chatFormat, onSubmit);
                    }
                }, () -> {
                    messageService.send(player, "prompt-cancelled", "{prefix}{bad}Edit cancelled.");
                    plugin.getEditorSounds().back(player);
                    openAdminEditor(player, false);
                });
        if (openedNative) {
            plugin.getEditorSounds().open(player);
        } else {
            warnAdminFallbackOnce(player, core);
        }
        plugin.fileLogger().info("Admin editor input opened. player=" + player.getName() + ", setting=" + setting
                + ", type=" + (moneyInput ? "money" : "number"));
    }

    private TextDialogRequest editorDialogRequest(boolean money, String setting, String currentValue,
            String minimumValue, String maximumValue, String chatFormat) {
        Map<String, String> replacements = Map.of(
                "setting", setting,
                "current", currentValue,
                "min", minimumValue,
                "max", maximumValue);
        String currency = money ? "$" : "";
        List<String> body = new ArrayList<>();
        body.add(messageService.renderTemplate("{muted}Current value: {theme}" + currency + "{current}", replacements));
        body.add(messageService.renderTemplate("{muted}Accepted range: {theme}" + currency + "{min}{muted} to {theme}"
                + currency + "{max}{muted}.", replacements));
        if (money) {
            body.add(messageService.renderTemplate("{muted}Use k, m, b, or t suffixes for large values.", replacements));
        }

        return new TextDialogRequest(
                messageService.renderTemplate("{theme}{setting}", replacements),
                body,
                messageService.renderTemplate(money ? "{white}New amount" : "{white}New value", replacements),
                currentValue,
                chatFormat,
                DialogButton.save("Save", "", 100),
                DialogButton.cancel("Cancel", "", 100),
                300,
                300,
                32,
                true,
                false,
                false);
    }

    private void warnAdminFallbackOnce(Player player, FoCoreContext core) {
        if (!core.nativeDialogs().configEnabled() || !core.nativeDialogs().warnOnFallback()
                || !player.hasPermission("focoinflip.admin") || !warnedFallbackAdmins.add(player.getUniqueId())) {
            return;
        }
        messageService.send(player, "native-dialogs-unavailable",
                "{prefix}{muted}Native dialogs are not supported on this server, using chat input mode.");
    }

    public void handleAnimationClose(Player player, AnimationHolder holder) {
        AnimationSession session = animationSessions.get(holder.getSessionId());
        if (session == null) {
            playerToSession.remove(player.getUniqueId());
            return;
        }

        if (session.completed) {
            playerToSession.remove(player.getUniqueId());
            return;
        }

        if (!session.lockGui) {
            playerToSession.remove(player.getUniqueId());
            return;
        }

        scheduler().runForPlayer(player, () -> {
            player.openInventory(session.inventory);
            playerToSession.put(player.getUniqueId(), session.id);
        });
    }

    public void handlePlayerQuit(Player player) {
        coreSupplier.get().dialogInputs().clear(player);
        refreshCooldowns.remove(player.getUniqueId());
        sortModes.remove(player.getUniqueId());
        playerToSession.remove(player.getUniqueId());
    }

    private boolean isRefreshOnCooldown(Player player) {
        long now = System.nanoTime();
        long cooldownUntil = refreshCooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (now < cooldownUntil) {
            return true;
        }
        refreshCooldowns.put(player.getUniqueId(), now + REFRESH_COOLDOWN_NANOS);
        return false;
    }

    private void fillCoinflipPage(Player player, Inventory inventory, MainGuiHolder holder, List<Coinflip> coinflips, int page,
            List<Integer> slots) {
        int start = page * slots.size();
        ItemStack filler = configuredItemForViewer(player, config.mainGui(), "items.entry-filler", Collections.emptyMap(),
                Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        for (int slot : slots) {
            inventory.setItem(slot, filler.clone());
        }

        for (int i = 0; i < slots.size(); i++) {
            int index = start + i;
            if (index >= coinflips.size()) {
                break;
            }

            Coinflip coinflip = coinflips.get(index);
            int slot = slots.get(i);
            inventory.setItem(slot, coinflipItem(player, coinflip));
            holder.bindSlot(slot, coinflip.getId());
        }
    }

    private List<CycleOption> configuredSortOptions(FileConfiguration gui) {
        ConfigurationSection section = gui.getConfigurationSection("items.sort");
        List<CycleOption> options = new ArrayList<>();
        if (section != null) {
            for (Map<?, ?> configured : section.getMapList("options")) {
                String value = configured.get("value") == null ? "" : configured.get("value").toString().trim();
                String label = configured.get("label") == null ? "" : configured.get("label").toString().trim();
                if (SortMode.fromValue(value) != null && !label.isBlank()
                        && options.stream().noneMatch(option -> option.value().equalsIgnoreCase(value))) {
                    options.add(new CycleOption(value, label));
                }
            }
        }
        if (options.isEmpty()) {
            return List.of(
                    new CycleOption(SortMode.AMOUNT_DESC.value, "Highest Amount"),
                    new CycleOption(SortMode.AMOUNT_ASC.value, "Lowest Amount"),
                    new CycleOption(SortMode.NEWEST.value, "Newest First"),
                    new CycleOption(SortMode.OLDEST.value, "Oldest First"));
        }
        return List.copyOf(options);
    }

    private String currentSortMode(Player player, List<CycleOption> options) {
        String stored = sortModes.getOrDefault(player.getUniqueId(), SortMode.NEWEST.value);
        boolean configured = options.stream().anyMatch(option -> option.value().equalsIgnoreCase(stored));
        String current = stored;
        if (!configured) {
            current = options.get(0).value();
            sortModes.put(player.getUniqueId(), current);
        }
        return current;
    }

    private List<Coinflip> sortCoinflips(List<Coinflip> coinflips, String sortMode) {
        SortMode mode = SortMode.fromValue(sortMode);
        Comparator<Coinflip> comparator = switch (mode == null ? SortMode.NEWEST : mode) {
            case AMOUNT_DESC -> Comparator.comparingDouble(Coinflip::getStake).reversed()
                    .thenComparing(Comparator.comparingLong(Coinflip::getCreatedAt).reversed());
            case AMOUNT_ASC -> Comparator.comparingDouble(Coinflip::getStake)
                    .thenComparing(Comparator.comparingLong(Coinflip::getCreatedAt).reversed());
            case NEWEST -> Comparator.comparingLong(Coinflip::getCreatedAt).reversed();
            case OLDEST -> Comparator.comparingLong(Coinflip::getCreatedAt);
        };
        return coinflips.stream().sorted(comparator).toList();
    }

    private ItemStack coinflipItem(Player player, Coinflip coinflip) {
        ItemStack item = configuredItemForViewer(player, config.mainGui(), "coinflip-item",
                Map.of("player", coinflip.getCreatorName(), "amount", formatMoney(coinflip.getStake())),
                Material.PLAYER_HEAD);
        ItemMeta itemMeta = item.getItemMeta();
        if (!(itemMeta instanceof SkullMeta meta)) {
            return item;
        }

        applySkullOwner(meta, coinflip.getCreatorId(), coinflip.getCreatorName());
        item.setItemMeta(meta);
        // Skull ownership is applied after the configured metadata. Re-run
        // Core's viewer-aware renderer so a configured :map: (or any other
        // valid token) cannot fall back to literal text during that mutation.
        return DialogIcons.forViewer(player, item);
    }

    private Map<String, String> statsTokens(Player player) {
        PlayerStats stats = statsRepository.getStats(player.getUniqueId());
        return Map.of(
                "won", formatMoney(stats.totalWon()),
                "lost", formatMoney(stats.totalLost()),
                "sum", formatMoney(stats.totalSum()));
    }

    private ItemStack settingItem(Material material, String name, List<String> lore) {
        return EditorItemFactory.item(
                material,
                messageService.renderTemplate("{theme}" + name, Map.of()),
                lore.stream().map(line -> messageService.renderTemplate(line, Map.of())).toList());
    }

    private void setConfiguredCycleItem(Player player, Inventory inventory, FileConfiguration gui, String path, String currentValue,
            List<CycleOption> options, Material fallbackMaterial) {
        ConfigurationSection section = gui.getConfigurationSection(path);
        int slot = configuredSlot(gui, path + ".slot", -1, inventory.getSize(), path);
        if (section == null || slot < 0) {
            return;
        }

        ItemStack item = configuredItemForViewer(player, gui, path, Collections.emptyMap(), fallbackMaterial);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        List<String> renderedLore = FoButtonStyle.buttonLore(CycleOptions.information(messageService, currentValue, options), "cycle").stream()
                .map(line -> messageService.renderTemplateForViewer(player, line, Collections.emptyMap()))
                .toList();
        List<net.kyori.adventure.text.Component> loreComponents = renderedLore.stream()
                .map(line -> DialogIcons.render(player, line))
                .toList();
        String configuredName = section.getString("name", " ");
        String renderedName = messageService.renderTemplateForViewer(player, configuredName, Collections.emptyMap());
        if (!DialogIcons.applyItemMeta(meta, player, DialogIcons.render(player, renderedName), loreComponents)) {
            meta.setDisplayName(DialogIcons.fallbackText(renderedName));
            meta.setLore(renderedLore.stream().map(DialogIcons::fallbackText).toList());
        }
        item.setItemMeta(meta);
        inventory.setItem(slot, item);
    }

    private void saveConfigValue(String path, Object value) {
        EditorSaveResult result = editorSettingSaver.save(path, value);
        if (!result.successful()) {
            String error = result.errorMessage() == null || result.errorMessage().isBlank()
                    ? "Could not save setting" : result.errorMessage();
            throw new IllegalArgumentException(error);
        }
    }

    private ItemStack namedItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.setDisplayName(name);
        if (lore != null && !lore.isEmpty()) {
            meta.setLore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    private void sendCreateSuggestion(Player player) {
        messageService.send(player, "create-command-hint",
                "{prefix}{muted}Create with {theme}/cf create <amount>{muted}, e.g. {white}50k{muted}, {white}1m{muted}, or {white}1 billion");
    }

    private double parseDouble(String input, String error) {
        try {
            double value = Double.parseDouble(input);
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException(error);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(error);
        }
    }

    private double parseAmount(String input, String error) {
        try {
            double value = MoneyUtil.parseAmount(input);
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException(error);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(error);
        }
    }

    private int parseInteger(String input, String error) {
        return LargeNumberParser.parsePositiveInt(input)
                .orElseThrow(() -> new IllegalArgumentException(error));
    }

    private void startAnimation(FlipResolution resolution) {
        Set<Player> viewers = new HashSet<>();
        Player creator = Bukkit.getPlayer(resolution.flip().getCreatorId());
        Player joiner = Bukkit.getPlayer(resolution.joinerId());
        if (creator != null) {
            viewers.add(creator);
        }
        if (joiner != null) {
            viewers.add(joiner);
        }

        startAnimationInternal(resolution, viewers);
    }

    private void startAnimationInternal(FlipResolution resolution, Set<Player> viewerPlayers) {
        UUID sessionId = UUID.randomUUID();
        FileConfiguration gui = config.animationGui();
        int inventorySize = inventorySize(gui, "rows", 3, "animation");
        int displaySlot = configuredSlot(gui, "result-slot", 13, inventorySize, "animation result");
        if (displaySlot < 0) {
            displaySlot = inventorySize / 2;
        }
        Inventory inventory = Bukkit.createInventory(new AnimationHolder(sessionId), inventorySize,
                GuiTitles.format(config.getAnimationGuiTitle()));
        fillAnimationBackground(inventory);

        Set<UUID> viewers = new HashSet<>();
        for (Player viewer : viewerPlayers) {
            if (viewer == null || !viewer.isOnline()) {
                continue;
            }
            plugin.getGuiSounds().open(viewer);
            plugin.sounds().play(viewer, "coinflip.matched");
            viewer.openInventory(inventory);
            viewers.add(viewer.getUniqueId());
            playerToSession.put(viewer.getUniqueId(), sessionId);
        }

        if (viewers.isEmpty()) {
            sendResolutionMessages(resolution);
            announceIfNeeded(resolution);
            return;
        }

        int interval = config.getAnimationFrameIntervalTicks();
        int maxFrames = Math.max(1, config.getAnimationDurationTicks() / interval);

        AnimationSession session = new AnimationSession(sessionId, inventory, resolution, viewers, maxFrames, displaySlot,
                interval, config.isLockAnimationGui());
        animationSessions.put(sessionId, session);

        scheduleAnimationFrame(sessionId, 1L);
    }

    private void fillAnimationBackground(Inventory inventory) {
        FileConfiguration gui = config.animationGui();
        ItemStack filler = configuredItem(gui, "items.filler", Collections.emptyMap(), Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler.clone());
        }
    }

    private void tickAnimation(UUID sessionId) {
        AnimationSession session = animationSessions.get(sessionId);
        if (session == null) {
            return;
        }

        session.frame++;
        if (session.frame < session.maxFrames) {
            session.inventory.setItem(session.displaySlot, rollingItem(session));
            scheduleAnimationFrame(session.id, session.frameInterval);
            return;
        }

        session.completed = true;
        session.inventory.setItem(session.displaySlot, resultItem(session.resolution));
        sendResolutionMessages(session.resolution);
        announceIfNeeded(session.resolution);

        scheduler().runGlobalLater(() -> endAnimation(session.id), 20L);
    }

    private void scheduleAnimationFrame(UUID sessionId, long delay) {
        scheduler().runGlobalLater(() -> {
            if (animationSessions.containsKey(sessionId)) {
                tickAnimation(sessionId);
            }
        }, delay);
    }

    private FoScheduler scheduler() {
        return coreSupplier.get().scheduler();
    }

    private ItemStack rollingItem(AnimationSession session) {
        boolean creatorFrame = session.frame % 2 == 0;
        String playerName = creatorFrame ? session.resolution.flip().getCreatorName() : session.resolution.joinerName();
        String amount = formatMoney(session.resolution.flip().getStake());
        UUID skullOwnerId = creatorFrame ? session.resolution.flip().getCreatorId() : session.resolution.joinerId();

        ItemStack item = configuredItem(config.animationGui(), "rolling-item",
                Map.of("player", playerName, "amount", amount), Material.PLAYER_HEAD);
        ItemMeta itemMeta = item.getItemMeta();
        if (!(itemMeta instanceof SkullMeta meta)) {
            return item;
        }

        applySkullOwner(meta, skullOwnerId, playerName);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack resultItem(FlipResolution resolution) {
        ItemStack item = configuredItem(config.animationGui(), "result-item",
                Map.of(
                        "winner", resolution.winnerName(),
                        "loser", resolution.loserName(),
                        "payout", formatMoney(resolution.payout()),
                        "tax", formatMoney(resolution.tax()),
                        "pot", formatMoney(resolution.pot())),
                Material.PLAYER_HEAD);
        ItemMeta itemMeta = item.getItemMeta();
        if (!(itemMeta instanceof SkullMeta meta)) {
            return item;
        }

        applySkullOwner(meta, resolution.winnerId(), resolution.winnerName());
        item.setItemMeta(meta);
        // This item is shared by all animation viewers, so it must remain in
        // the null-viewer-safe fallback form rather than using one player's
        // client capabilities for the whole inventory.
        return item;
    }

    private void fillBackground(Player player, Inventory inventory, FileConfiguration gui) {
        ItemStack filler = configuredItemForViewer(player, gui, "items.filler", Collections.emptyMap(),
                Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }
    }

    private void setConfiguredItem(Player player, Inventory inventory, FileConfiguration gui, String path, Map<String, String> tokens,
            Material fallbackMaterial) {
        int slot = configuredSlot(gui, path + ".slot", -1, inventory.getSize(), path);
        if (slot < 0) {
            return;
        }
        inventory.setItem(slot, configuredItemForViewer(player, gui, path, tokens, fallbackMaterial));
    }

    private ItemStack configuredItemForViewer(Player player, FileConfiguration gui, String path,
            Map<String, String> tokens, Material fallbackMaterial) {
        return DialogIcons.forViewer(player, configuredItem(player, gui, path, tokens, fallbackMaterial));
    }

    private ItemStack configuredItem(FileConfiguration gui, String path, Map<String, String> tokens, Material fallbackMaterial) {
        return configuredItem(null, gui, path, tokens, fallbackMaterial);
    }

    private ItemStack configuredItem(Player viewer, FileConfiguration gui, String path, Map<String, String> tokens,
            Material fallbackMaterial) {
        ConfigurationSection section = gui.getConfigurationSection(path);
        if (section == null) {
            String fallbackName = "{theme}" + fallbackMaterial.name();
            String renderedFallback = viewer == null
                    ? messageService.renderTemplate(fallbackName, Map.of())
                    : messageService.renderTemplateForViewer(viewer, fallbackName, Map.of());
            return namedItem(fallbackMaterial, renderedFallback, List.of());
        }

        Material material = material(section.getString("material"), fallbackMaterial, path);
        int amount = Math.max(1, Math.min(64, section.getInt("amount", 1)));
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        String name = section.getString("name", " ");
        String renderedName = viewer == null
                ? messageService.renderTemplateForItem(name, tokens)
                : messageService.renderTemplateForViewer(viewer, name, tokens);
        List<String> lore = section.getStringList("lore");
        List<String> renderedLore = lore.stream()
                    .map(line -> viewer == null
                            ? messageService.renderTemplateForItem(line, tokens)
                            : messageService.renderTemplateForViewer(viewer, line, tokens))
                    .toList();
        if (!DialogIcons.applyItemMeta(meta, viewer, renderedName, renderedLore)) {
            meta.setDisplayName(DialogIcons.fallbackText(renderedName));
            meta.setLore(renderedLore.stream().map(DialogIcons::fallbackText).toList());
        }
        if (section.contains("custom-model-data")) {
            int customModelData = section.getInt("custom-model-data");
            if (customModelData > 0) {
                meta.setCustomModelData(customModelData);
            }
        }
        if (section.getBoolean("glow", false)) {
            meta.setEnchantmentGlintOverride(true);
        }
        for (String flagName : section.getStringList("item-flags")) {
            try {
                meta.addItemFlags(ItemFlag.valueOf(flagName.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Invalid item flag '" + flagName + "' in " + path);
                plugin.fileLogger().warn("Invalid item flag in GUI config. path=" + path + ", flag=" + flagName);
            }
        }
        item.setItemMeta(meta);
        return item;
    }

    private Material material(String configured, Material fallback, String path) {
        if (configured == null || configured.isBlank()) {
            return fallback;
        }
        Material material = Material.matchMaterial(configured);
        if (material == null) {
            plugin.getLogger().warning("Invalid material '" + configured + "' in " + path);
            plugin.fileLogger().warn("Invalid material in GUI config. path=" + path + ", material=" + configured);
            return fallback;
        }
        return material;
    }

    private int inventorySize(FileConfiguration gui, String path, int fallbackRows, String label) {
        int rows = gui.getInt(path, fallbackRows);
        if (rows < 1 || rows > 6) {
            plugin.getLogger().warning("Invalid " + label + " rows '" + rows + "', using " + fallbackRows);
            plugin.fileLogger().warn("Invalid GUI row count. label=" + label + ", rows=" + rows
                    + ", fallback=" + fallbackRows);
            rows = fallbackRows;
        }
        return rows * 9;
    }

    private int configuredSlot(FileConfiguration gui, String path, int fallback, int inventorySize, String label) {
        int slot = gui.getInt(path, fallback);
        if (slot < 0 || slot >= inventorySize) {
            if (fallback < 0) {
                plugin.getLogger().warning("Invalid slot for " + label + ": " + slot);
                plugin.fileLogger().warn("Invalid GUI slot. label=" + label + ", slot=" + slot);
                return -1;
            }
            plugin.getLogger().warning("Invalid slot for " + label + ": " + slot + ", using " + fallback);
            plugin.fileLogger().warn("Invalid GUI slot. label=" + label + ", slot=" + slot + ", fallback=" + fallback);
            return fallback >= 0 && fallback < inventorySize ? fallback : -1;
        }
        return slot;
    }

    private List<Integer> contentSlots(FileConfiguration gui, int inventorySize) {
        List<Integer> configured = gui.getIntegerList("content-slots");
        List<Integer> slots = new ArrayList<>();
        for (int slot : configured) {
            if (slot < 0 || slot >= inventorySize) {
                plugin.getLogger().warning("Invalid content slot in main GUI: " + slot);
                plugin.fileLogger().warn("Invalid main GUI content slot. slot=" + slot);
                continue;
            }
            slots.add(slot);
        }
        if (!slots.isEmpty()) {
            return slots;
        }

        return DEFAULT_CONTENT_SLOTS.stream()
                .filter(slot -> slot < inventorySize)
                .toList();
    }

    private void applySkullOwner(SkullMeta meta, UUID ownerId, String ownerName) {
        if (ownerId == null) {
            return;
        }

        boolean validOwnerName = ownerName != null && JAVA_USERNAME_PATTERN.matcher(ownerName).matches();
        if (!Bukkit.getOnlineMode() && validOwnerName
                && skinsRestorerSkullService.applySkin(meta, ownerId, ownerName)) {
            return;
        }

        if (tryApplyBukkitSkullOwner(meta, ownerId)) {
            return;
        }

        if (!validOwnerName) {
            return;
        }

        try {
            PlayerProfile profile = Bukkit.createPlayerProfile(ownerId, ownerName);
            meta.setOwnerProfile(profile);
        } catch (RuntimeException exception) {
            // Keep unresolved cracked/Geyser profiles as default heads.
        }
    }

    private boolean tryApplyBukkitSkullOwner(SkullMeta meta, UUID ownerId) {
        try {
            OfflinePlayer owner = Bukkit.getOfflinePlayer(ownerId);
            meta.setOwningPlayer(owner);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void sendResolutionMessages(FlipResolution resolution) {
        Player winner = Bukkit.getPlayer(resolution.winnerId());
        if (winner != null) {
            plugin.sounds().play(winner, "coinflip.won");
            messageService.send(winner, "flip-win", "{prefix}{good}Won against {theme}{opponent}{good}. Payout {theme}${payout}{good}. {muted}Tax {theme}${tax}{muted}.",
                    Map.of("opponent", resolution.loserName(), "payout", formatMoney(resolution.payout()),
                            "tax", formatMoney(resolution.tax())));
        }

        Player loser = Bukkit.getPlayer(resolution.loserId());
        if (loser != null) {
            plugin.sounds().play(loser, "coinflip.lost");
            messageService.send(loser, "flip-lose", "{prefix}{bad}Lost against {theme}{opponent}{bad}. Lost {theme}${amount}{bad}.",
                    Map.of("opponent", resolution.winnerName(), "amount", formatMoney(resolution.flip().getStake())));
        }
    }

    private void announceIfNeeded(FlipResolution resolution) {
        if (coinflipManager.consumeSuppressedAnnouncement(resolution.flip().getId())) {
            return;
        }
        if (!config.isAnnounceCoinflipResult()) {
            return;
        }

        String line = messageService.render("announce",
                "{prefix}{theme}{winner}{muted} won against {theme}{loser}{muted} for {theme}${payout}{muted}.",
                Map.of(
                        "winner", resolution.winnerName(),
                        "loser", resolution.loserName(),
                        "payout", formatMoney(resolution.payout()),
                        "tax", formatMoney(resolution.tax()),
                        "pot", formatMoney(resolution.pot())));
        Bukkit.broadcastMessage(line);
    }

    private String formatMoney(double amount) {
        return MoneyUtil.format(amount, config.isCompactMoneyFormat());
    }

    private void logAdminSetting(Player player, String path, Object value) {
        plugin.fileLogger().info("Admin setting updated. player=" + player.getName() + ", path=" + path
                + ", value=" + value);
    }

    private void endAnimation(UUID sessionId) {
        AnimationSession session = animationSessions.remove(sessionId);
        if (session == null) {
            return;
        }

        for (UUID viewerId : session.viewers) {
            playerToSession.remove(viewerId);
        }
    }

    private void sendJoinError(Player player, String path, String fallback) {
        messageService.send(player, path, fallback);
        plugin.getGuiSounds().error(player);
    }

    private static final class AnimationSession {

        private final UUID id;
        private final Inventory inventory;
        private final FlipResolution resolution;
        private final Set<UUID> viewers;
        private final int maxFrames;
        private final int displaySlot;
        private final boolean lockGui;
        private final int frameInterval;

        private int frame;
        private boolean completed;
        private AnimationSession(UUID id, Inventory inventory, FlipResolution resolution, Set<UUID> viewers, int maxFrames,
                int displaySlot, int frameInterval, boolean lockGui) {
            this.id = id;
            this.inventory = inventory;
            this.resolution = resolution;
            this.viewers = viewers;
            this.maxFrames = maxFrames;
            this.displaySlot = displaySlot;
            this.lockGui = lockGui;
            this.frameInterval = frameInterval;
        }
    }
}
