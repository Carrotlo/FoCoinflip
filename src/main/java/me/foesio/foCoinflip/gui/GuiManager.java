package me.foesio.foCoinflip.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
import me.foesio.core.gui.publicgui.GuiLoadReport;
import me.foesio.core.gui.publicgui.PublicGuiClick;
import me.foesio.core.gui.publicgui.PublicGuiDefinition;
import me.foesio.core.gui.publicgui.PublicGuiFrame;
import me.foesio.core.gui.publicgui.PublicGuiInventoryHolder;
import me.foesio.core.gui.publicgui.PublicGuiRenderInput;
import me.foesio.core.gui.publicgui.PublicGuiRenderer;
import me.foesio.core.gui.publicgui.PublicGuiService;
import me.foesio.core.gui.publicgui.PublicGuiText;
import me.foesio.foCoinflip.config.CoinflipGuiContracts;
import me.foesio.foCoinflip.config.CoinflipSoundOptions;
import me.foesio.core.number.LargeNumberParser;
import me.foesio.foCoinflip.CoinflipManager;
import me.foesio.foCoinflip.config.PluginConfig;
import me.foesio.foCoinflip.FoCoinflip;
import me.foesio.foCoinflip.model.Coinflip;
import me.foesio.foCoinflip.model.FlipResolution;
import me.foesio.foCoinflip.stats.PlayerStats;
import me.foesio.foCoinflip.stats.StatsRepository;
import me.foesio.foCoinflip.util.MoneyUtil;
import me.foesio.foCoinflip.util.ConfiguredPublicMessages;
import me.foesio.core.message.FoMessageService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.event.Listener;
import me.foesio.core.scheduler.FoScheduler;
import org.bukkit.profile.PlayerProfile;

public class GuiManager {

    private static final int ADMIN_GUI_ROWS = 4;
    private static final long REFRESH_COOLDOWN_NANOS = 1_000_000_000L;
    private static final Pattern JAVA_USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private final FoCoinflip plugin;
    private final PluginConfig config;
    private final FoMessageService messageService;
    private final CoinflipManager coinflipManager;
    private final StatsRepository statsRepository;
    private final Supplier<FoCoreContext> coreSupplier;
    private final EditorSettingSaver editorSettingSaver;
    private final SkinsRestorerSkullService skinsRestorerSkullService;
    private final Set<UUID> warnedFallbackAdmins = new HashSet<>();
    private final Map<UUID, Long> refreshCooldowns = new HashMap<>();

    private final Map<UUID, AnimationSession> animationSessions = new HashMap<>();
    private final Map<UUID, UUID> playerToSession = new HashMap<>();
    private final Map<UUID, String> sortModes = new HashMap<>();
    private final Map<UUID, Integer> openPages = new HashMap<>();
    private PublicGuiService publicGuis;
    private final FileConfiguration bundledSounds;
    private boolean warnedInvalidCycleVariation;

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
        this.bundledSounds = CoinflipSoundOptions.bundled(plugin);
    }

    public void openMainMenu(Player player, int requestedPage) {
        if (publicGuis == null || publicGuis.snapshot() == null) {
            ConfiguredPublicMessages.send(messageService, player, "gui-unavailable");
            return;
        }
        PublicGuiDefinition gui = publicGuis.snapshot().require(CoinflipGuiContracts.MAIN);
        List<CycleOption> sortOptions = configuredSortOptions();
        String sortMode = currentSortMode(player, sortOptions);
        List<Coinflip> activeFlips = sortCoinflips(coinflipManager.getActiveCoinflips(), sortMode);
        List<Integer> slots = gui.entries().get("coinflips").slots();
        int maxPage = slots.isEmpty() ? 0 : Math.max(0, (activeFlips.size() - 1) / slots.size());
        int page = Math.max(0, Math.min(requestedPage, maxPage));
        Map<Integer, PublicGuiRenderInput.EntryValue> entries = new HashMap<>();
        Map<Integer, Coinflip> displayed = new HashMap<>();
        for (int index = 0; index < slots.size(); index++) {
            int flipIndex = page * slots.size() + index;
            if (flipIndex >= activeFlips.size()) {
                break;
            }
            Coinflip flip = activeFlips.get(flipIndex);
            int slot = slots.get(index);
            entries.put(slot, new PublicGuiRenderInput.EntryValue(flip.getId().toString(), null,
                    Map.of("player", flip.getCreatorName(), "amount", formatMoney(flip.getStake()))));
            displayed.put(slot, flip);
        }
        Map<String, Map<Integer, PublicGuiRenderInput.EntryValue>> regions = new HashMap<>();
        regions.put("coinflips", entries);
        regions.putAll(pageRegions(gui, page, maxPage));
        if (activeFlips.isEmpty() && gui.entries().containsKey("empty-notice")
                && !gui.entries().get("empty-notice").slots().isEmpty()) {
            int noticeSlot = gui.entries().get("empty-notice").slots().get(0);
            regions.put("empty-notice", Map.of(noticeSlot,
                    new PublicGuiRenderInput.EntryValue("empty", null, Map.of())));
        }
        Map<String, String> tokens = guiTokens();
        tokens.putAll(statsTokens(player));
        tokens.put("page", String.valueOf(page + 1));
        tokens.put("pages", String.valueOf(maxPage + 1));
        Map<String, String> states = new HashMap<>();
        boolean sortEnabled = buttonEnabled(gui, "sort");
        if (sortOptions.isEmpty() && sortEnabled) {
            states.put("sort", "unavailable");
        }
        List<String> labels = sortOptions.stream().map(CycleOption::label).toList();
        int selected = -1;
        for (int i = 0; i < sortOptions.size(); i++) {
            if (sortOptions.get(i).value().equals(sortMode)) {
                selected = i;
                break;
            }
        }
        Map<String, PublicGuiText.CycleSelection> cycles = sortEnabled
                ? Map.of("sort", new PublicGuiText.CycleSelection(labels, selected)) : Map.of();
        PublicGuiRenderInput input = new PublicGuiRenderInput(tokens, states, cycles, regions);
        Inventory previousInventory = player.getOpenInventory().getTopInventory();
        publicGuis.open(player, CoinflipGuiContracts.MAIN, input);
        Inventory inventory = player.getOpenInventory().getTopInventory();
        if (inventory == previousInventory
                || !(inventory.getHolder() instanceof PublicGuiInventoryHolder opened)
                || !CoinflipGuiContracts.MAIN.equals(opened.frame().definition().menuId())) {
            return;
        }
        openPages.put(player.getUniqueId(), page);
        for (Map.Entry<Integer, Coinflip> entry : displayed.entrySet()) {
            ItemStack item = inventory.getItem(entry.getKey());
            if (item != null && item.getItemMeta() instanceof SkullMeta skull) {
                applySkullOwner(skull, entry.getValue().getCreatorId(), entry.getValue().getCreatorName());
                item.setItemMeta(skull);
                inventory.setItem(entry.getKey(), item);
            }
        }
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

    public void startPublicGuis(Listener animationRoute) {
        if (publicGuis != null) {
            throw new IllegalStateException("Public GUIs are already started");
        }
        PublicGuiService service = new PublicGuiService(plugin);
        java.nio.file.Path root = plugin.getDataFolder().toPath().resolve("guis");
        service.menu(CoinflipGuiContracts.MAIN, root.resolve("main.yml"), CoinflipGuiContracts.main())
                .menu(CoinflipGuiContracts.ANIMATION, root.resolve("animation.yml"), CoinflipGuiContracts.animation())
                .entryPoint("coinflip.command", CoinflipGuiContracts.MAIN)
                .entryPoint("coinflip.match", CoinflipGuiContracts.ANIMATION)
                .requireEntry(CoinflipGuiContracts.MAIN, "coinflips", 0)
                .requireState(CoinflipGuiContracts.ANIMATION, "rolling")
                .requireState(CoinflipGuiContracts.ANIMATION, "result")
                .pluginRoute(CoinflipGuiContracts.ANIMATION, animationRoute);
        for (String action : CoinflipGuiContracts.main().actions()) {
            service.action(action, this::handlePublicClick);
        }
        service.start();
        this.publicGuis = service;
        GuiLoadReport report = service.reload();
        if (!report.successful()) {
            plugin.getLogger().severe("FoCoinflip public GUIs are unavailable until their files are fixed and reloaded.");
        }
    }

    public boolean reloadPublicGuis() {
        return publicGuis != null && publicGuis.reload().successful();
    }

    public boolean validatePublicGuis() {
        return publicGuis != null && publicGuis.validateInstalled().successful();
    }

    public void closePublicGuis() {
        animationSessions.clear();
        playerToSession.clear();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getOpenInventory().getTopInventory().getHolder() instanceof AnimationHolder) {
                viewer.closeInventory();
            }
        }
        if (publicGuis != null) {
            publicGuis.close();
            publicGuis = null;
        }
    }

    private void handlePublicClick(PublicGuiClick click) {
        Player player = click.player();
        int page = openPages.getOrDefault(player.getUniqueId(), 0);
        switch (click.actionId()) {
            case "coinflip.previous" -> {
                plugin.getGuiSounds().previousPage(player);
                openMainMenu(player, page - 1);
            }
            case "coinflip.next" -> {
                plugin.getGuiSounds().nextPage(player);
                openMainMenu(player, page + 1);
            }
            case "coinflip.sort" -> {
                List<CycleOption> options = configuredSortOptions();
                if (!options.isEmpty()) {
                    String current = currentSortMode(player, options);
                    sortModes.put(player.getUniqueId(), CycleOptions.nextValue(current, options));
                    playGuiCycle(player);
                    openMainMenu(player, page);
                }
            }
            case "coinflip.create" -> {
                plugin.getGuiSounds().click(player);
                player.closeInventory();
                sendCreateSuggestion(player);
            }
            case "coinflip.refresh" -> {
                if (!isRefreshOnCooldown(player)) {
                    plugin.getGuiSounds().click(player);
                    openMainMenu(player, page);
                }
            }
            case "coinflip.stats" -> {
                plugin.getGuiSounds().click(player);
                ConfiguredPublicMessages.send(messageService, player, "stats", statsTokens(player));
            }
            case "coinflip.join" -> {
                if (click.entryId() == null) {
                    return;
                }
                try {
                    joinFromPublicGui(player, UUID.fromString(click.entryId()), page);
                } catch (IllegalArgumentException ignored) {
                    sendJoinError(player, "flip-no-longer-available");
                }
            }
            default -> throw new IllegalStateException("Unregistered public GUI action: " + click.actionId());
        }
    }

    static boolean buttonEnabled(PublicGuiDefinition gui, String id) {
        return gui.buttons().containsKey(id) && gui.buttons().get(id).enabled();
    }

    static Map<String, Map<Integer, PublicGuiRenderInput.EntryValue>> pageRegions(
            PublicGuiDefinition gui, int page, int maxPage) {
        Map<String, Map<Integer, PublicGuiRenderInput.EntryValue>> regions = new HashMap<>();
        if (page > 0 && gui.entries().containsKey("previous")
                && !gui.entries().get("previous").slots().isEmpty()) {
            int slot = gui.entries().get("previous").slots().get(0);
            regions.put("previous", Map.of(slot,
                    new PublicGuiRenderInput.EntryValue("previous", null, Map.of())));
        }
        if (page < maxPage && gui.entries().containsKey("next")
                && !gui.entries().get("next").slots().isEmpty()) {
            int slot = gui.entries().get("next").slots().get(0);
            regions.put("next", Map.of(slot,
                    new PublicGuiRenderInput.EntryValue("next", null, Map.of())));
        }
        return regions;
    }

    private void joinFromPublicGui(Player player, UUID coinflipId, int page) {
        CoinflipManager.JoinResponse joinResponse = coinflipManager.joinFlip(player, coinflipId);
        switch (joinResponse.status()) {
            case SUCCESS -> {
                player.closeInventory();
                startAnimation(joinResponse.resolution());
            }
            case OWN_FLIP -> sendJoinError(player, "join-own");
            case INSUFFICIENT_FUNDS -> sendJoinError(player, "not-enough-money");
            case ALREADY_IN_PROGRESS, NOT_FOUND -> {
                sendJoinError(player, "flip-no-longer-available");
                openMainMenu(player, page);
            }
            case ECONOMY_ERROR -> sendJoinError(player, "economy-error");
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
                    MoneyUtil.raw(config.getMaxBet()), "a positive money amount, like 50k, 1m, or 1Qa", input -> {
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
                    MoneyUtil.raw(config.getMinBet()), "unlimited", "a money amount, like 1m, 1b, or 1Td", input -> {
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
            body.add(messageService.renderTemplate("{muted}Use K through Td suffixes for large values.", replacements));
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
        openPages.remove(player.getUniqueId());
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

    private List<CycleOption> configuredSortOptions() {
        List<CycleOption> options = new ArrayList<>();
        for (SortMode mode : SortMode.values()) {
            String label = messageService.config().getString("sort-options." + mode.value);
            if (label != null && !label.isBlank()) {
                options.add(new CycleOption(mode.value, label));
            }
        }
        return List.copyOf(options);
    }

    private String currentSortMode(Player player, List<CycleOption> options) {
        String stored = sortModes.getOrDefault(player.getUniqueId(), SortMode.NEWEST.value);
        if (options.isEmpty()) {
            return stored;
        }
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

    private Map<String, String> statsTokens(Player player) {
        PlayerStats stats = statsRepository.getStats(player.getUniqueId());
        return Map.of(
                "won", formatMoney(stats.totalWon()),
                "lost", formatMoney(stats.totalLost()),
                "sum", formatMoney(stats.totalSum()));
    }

    private Map<String, String> guiTokens() {
        return new HashMap<>(messageService.tokenValues());
    }

    private ItemStack settingItem(Material material, String name, List<String> lore) {
        return EditorItemFactory.item(
                material,
                messageService.renderTemplate("{theme}" + name, Map.of()),
                lore.stream().map(line -> messageService.renderTemplate(line, Map.of())).toList());
    }

    private void saveConfigValue(String path, Object value) {
        EditorSaveResult result = editorSettingSaver.save(path, value);
        if (!result.successful()) {
            String error = result.errorMessage() == null || result.errorMessage().isBlank()
                    ? "Could not save setting" : result.errorMessage();
            throw new IllegalArgumentException(error);
        }
    }

    private void sendCreateSuggestion(Player player) {
        ConfiguredPublicMessages.send(messageService, player, "create-command-hint");
    }

    private void playGuiCycle(Player player) {
        float variation;
        try {
            variation = CoinflipSoundOptions.cycleVariation(plugin.sounds().configuration(),
                    bundledSounds, false);
        } catch (IllegalArgumentException invalid) {
            if (!warnedInvalidCycleVariation) {
                plugin.getLogger().warning(invalid.getMessage()
                        + "; using the newest bundled default in memory until the owner fixes sounds.yml");
                warnedInvalidCycleVariation = true;
            }
            variation = CoinflipSoundOptions.cycleVariation(plugin.sounds().configuration(),
                    bundledSounds, true);
        }
        if (variation == 0.0F) {
            plugin.sounds().play(player, "gui.cycle");
        } else {
            plugin.sounds().playWithPitchVariation(player, "gui.cycle", variation);
        }
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
        if (publicGuis == null || publicGuis.snapshot() == null) {
            sendResolutionMessages(resolution);
            announceIfNeeded(resolution);
            return;
        }
        Map<String, String> animationTokens = guiTokens();
        animationTokens.put("amount", formatMoney(resolution.flip().getStake()));
        animationTokens.put("winner", resolution.winnerName());
        animationTokens.put("loser", resolution.loserName());
        animationTokens.put("payout", formatMoney(resolution.payout()));
        animationTokens.put("tax", formatMoney(resolution.tax()));
        animationTokens.put("pot", formatMoney(resolution.pot()));
        PublicGuiFrame frame = publicGuis.plan(CoinflipGuiContracts.ANIMATION,
                new PublicGuiRenderInput(animationTokens, Map.of(), Map.of(), Map.of()));
        int displaySlot = frame.definition().requireGroup("display-slots").get(0);
        Inventory inventory = Bukkit.createInventory(new AnimationHolder(sessionId), frame.definition().size(),
                DialogIcons.fallbackText(frame.title()));
        PublicGuiRenderer.renderInto(null, inventory, frame);

        Set<UUID> viewers = new HashSet<>();
        for (Player viewer : viewerPlayers) {
            if (viewer == null || !viewer.isOnline()) {
                continue;
            }
            viewer.openInventory(inventory);
            if (viewer.getOpenInventory().getTopInventory() != inventory) {
                continue;
            }
            plugin.getGuiSounds().open(viewer);
            plugin.sounds().play(viewer, "coinflip.matched");
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

        AnimationSession session = new AnimationSession(sessionId, inventory, frame, animationTokens, resolution, viewers, maxFrames, displaySlot,
                interval, config.isLockAnimationGui());
        animationSessions.put(sessionId, session);

        scheduleAnimationFrame(sessionId, 1L);
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
        session.inventory.setItem(session.displaySlot, resultItem(session));
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
        UUID skullOwnerId = creatorFrame ? session.resolution.flip().getCreatorId() : session.resolution.joinerId();

        Map<String, String> tokens = new HashMap<>(session.presentationTokens);
        tokens.put("player", playerName);
        ItemStack item = PublicGuiRenderer.state(null, session.layoutFrame, "rolling", tokens);
        ItemMeta itemMeta = item.getItemMeta();
        if (!(itemMeta instanceof SkullMeta meta)) {
            return item;
        }

        applySkullOwner(meta, skullOwnerId, playerName);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack resultItem(AnimationSession session) {
        FlipResolution resolution = session.resolution;
        ItemStack item = PublicGuiRenderer.state(null, session.layoutFrame, "result", session.presentationTokens);
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
            ConfiguredPublicMessages.send(messageService, winner, "flip-win",
                    Map.of("opponent", resolution.loserName(), "payout", formatMoney(resolution.payout()),
                            "tax", formatMoney(resolution.tax())));
        }

        Player loser = Bukkit.getPlayer(resolution.loserId());
        if (loser != null) {
            plugin.sounds().play(loser, "coinflip.lost");
            ConfiguredPublicMessages.send(messageService, loser, "flip-lose",
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

        ConfiguredPublicMessages.broadcast(messageService, "announce", Map.of(
                "winner", resolution.winnerName(),
                "loser", resolution.loserName(),
                "payout", formatMoney(resolution.payout()),
                "tax", formatMoney(resolution.tax()),
                "pot", formatMoney(resolution.pot())));
    }

    private String formatMoney(double amount) {
        return MoneyUtil.format(amount, config.isCompactMoneyFormat(), messageService.config());
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

    private void sendJoinError(Player player, String path) {
        ConfiguredPublicMessages.send(messageService, player, path);
        plugin.getGuiSounds().error(player);
    }

    private static final class AnimationSession {

        private final UUID id;
        private final Inventory inventory;
        private final PublicGuiFrame layoutFrame;
        private final Map<String, String> presentationTokens;
        private final FlipResolution resolution;
        private final Set<UUID> viewers;
        private final int maxFrames;
        private final int displaySlot;
        private final boolean lockGui;
        private final int frameInterval;

        private int frame;
        private boolean completed;
        private AnimationSession(UUID id, Inventory inventory, PublicGuiFrame frame, Map<String, String> presentationTokens,
                FlipResolution resolution, Set<UUID> viewers, int maxFrames,
                int displaySlot, int frameInterval, boolean lockGui) {
            this.id = id;
            this.inventory = inventory;
            this.layoutFrame = frame;
            this.presentationTokens = Map.copyOf(presentationTokens);
            this.resolution = resolution;
            this.viewers = viewers;
            this.maxFrames = maxFrames;
            this.displaySlot = displaySlot;
            this.lockGui = lockGui;
            this.frameInterval = frameInterval;
        }
    }
}
