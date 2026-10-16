package me.foesio.foCoinflip;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import me.foesio.foCoinflip.command.CoinflipCommand;
import me.foesio.foCoinflip.config.PluginConfig;
import me.foesio.foCoinflip.config.CoinflipSoundOptions;
import me.foesio.foCoinflip.gui.GuiManager;
import me.foesio.foCoinflip.listener.CoinflipListener;
import me.foesio.foCoinflip.stats.StatsRepository;
import me.foesio.foCoinflip.placeholder.CoinflipPlaceholderExpansion;
import me.foesio.core.command.FoAdminCommand;
import me.foesio.core.command.FoAdminMessages;
import me.foesio.core.FoCoreContext;
import me.foesio.core.FoPluginCore;
import me.foesio.core.economy.VaultEconomyBridge;
import me.foesio.core.sound.FoAdminSounds;
import me.foesio.core.sound.FoEditorSounds;
import me.foesio.core.sound.FoGuiSounds;
import me.foesio.core.sound.FoSoundService;
import me.foesio.core.config.FoConfigDefaults;
import me.foesio.core.logging.FoFileLogger;
import me.foesio.core.message.FoMessageMigrations;
import me.foesio.core.message.FoMessageService;
import me.foesio.core.metrics.FoMetrics;
import me.foesio.core.reload.FoReloadRegistry;
import me.foesio.core.reload.FoReloadResult;
import me.foesio.core.update.UpdateNoticeService;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public final class FoCoinflip extends JavaPlugin {

    private static final String DEFAULT_MESSAGE_PREFIX = "{theme}FoCoinflip &8» {muted}";
    private static final String DEFAULT_MESSAGE_THEME = "#03fc88";
    private static final String DEFAULT_MESSAGE_MUTED = "#a7b8b0";
    private static final String DEFAULT_MESSAGE_WHITE = "#ffffff";
    private static final String DEFAULT_MESSAGE_GOOD = "#3ecf8e";
    private static final String DEFAULT_MESSAGE_BAD = "#ff5d73";

    private PluginConfig pluginConfig;
    private FoMessageService messageService;
    private boolean legacyMessagesPendingMigration;
    private boolean legacyMessageMigrationPending;
    private boolean legacyCompactMoneyDefault;
    private VaultEconomyBridge economyService;
    private StatsRepository statsRepository;
    private CoinflipManager coinflipManager;
    private GuiManager guiManager;
    private CoinflipPlaceholderExpansion placeholderExpansion;
    private FoCoreContext core;
    private FoSoundService sounds;
    private FoEditorSounds editorSounds;
    private FoGuiSounds guiSounds;
    private FoAdminSounds adminSounds;
    private UpdateNoticeService updateNotices;
    private FoFileLogger fileLogger;
    private FoMetrics metrics;

    private static final int BSTATS_PLUGIN_ID = 33178;

    @Override
    public void onEnable() {
        File existingConfig = new File(getDataFolder(), "config.yml");
        File[] priorFiles = getDataFolder().listFiles();
        boolean priorInstallation = priorFiles != null && priorFiles.length > 0;
        YamlConfiguration priorConfig = existingConfig.isFile()
                ? YamlConfiguration.loadConfiguration(existingConfig) : new YamlConfiguration();
        boolean priorPublicSchema = priorConfig.getInt("public-gui-schema", 0) >= 1;
        boolean priorPublicTextSchema = priorConfig.getInt("public-text-schema", 0) >= 1;
        this.legacyCompactMoneyDefault = !priorPublicTextSchema
                && isReleasedCompactMoneyDefault(priorConfig);
        boolean legacyInlineGui = priorConfig.isConfigurationSection("gui.main")
                || priorConfig.isConfigurationSection("gui.animation");
        boolean installBundledSounds = CoinflipSoundOptions.shouldInstallBundled(getDataFolder(),
                priorInstallation, priorPublicSchema, legacyInlineGui);
        try {
            backupPublicConfig(existingConfig.toPath(), priorPublicSchema, priorPublicTextSchema);
        } catch (IOException exception) {
            getLogger().severe("Could not back up config.yml before public UI migration: "
                    + exception.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        this.fileLogger = new FoFileLogger(this);
        this.pluginConfig = new PluginConfig(this);
        this.pluginConfig.load();
        if (getConfig().getInt("public-gui-schema", 0) >= 1
                && installBundledSounds) {
            saveResource("sounds.yml", false);
        }
        applyCoreConfigDefaults();
        refreshCoreContext();
        this.fileLogger.configure(this.pluginConfig.isFileLoggingEnabled(), true);
        this.fileLogger.info("Enabling FoCoinflip v" + getDescription().getVersion());
        this.metrics = this.core.metrics(BSTATS_PLUGIN_ID);
        this.legacyMessageMigrationPending = !priorPublicTextSchema;
        this.legacyMessagesPendingMigration = !new File(getDataFolder(), "messages.yml").isFile();
        if (this.legacyMessageMigrationPending && !this.legacyMessagesPendingMigration) {
            File messages = new File(getDataFolder(), "messages.yml");
            File backup = new File(getDataFolder(), "messages.yml.gui-v0-backup");
            if (!backup.exists()) {
                try {
                    Files.copy(messages.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
                } catch (IOException exception) {
                    getLogger().severe("Could not back up messages.yml before migrating public text: "
                            + exception.getMessage());
                    Bukkit.getPluginManager().disablePlugin(this);
                    return;
                }
            }
        }
        if (!priorInstallation && this.legacyMessagesPendingMigration) {
            saveResource("messages.yml", false);
        }
        this.messageService = FoMessageService.loadPreservingRemovals(this, messageMigrations());
        migratePublicMessageFields();
        this.legacyMessagesPendingMigration = false;
        this.legacyMessageMigrationPending = false;

        this.economyService = this.core.createVaultEconomy();
        if (!this.economyService.isAvailable()) {
            getLogger().severe("Vault economy provider not found. Disabling plugin.");
            this.fileLogger.error("Vault economy provider not found. Disabling plugin.", null);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        this.fileLogger.info("Vault economy provider hooked.");
        this.statsRepository = new StatsRepository(this, this.pluginConfig);
        try {
            this.statsRepository.initialize();
        } catch (RuntimeException exception) {
            this.fileLogger.error("Failed to initialize stats storage", exception);
            throw exception;
        }
        this.coinflipManager = new CoinflipManager(this, this.economyService, this.statsRepository, this.pluginConfig);
        this.guiManager = new GuiManager(this, this.pluginConfig, this.messageService, this.coinflipManager, this.statsRepository,
                () -> this.core);
        this.updateNotices = this.core.createUpdateNotices(this.messageService, "focoinflip", this.adminSounds).start();

        registerCommands();
        registerListeners();
        registerPlaceholders();
        this.fileLogger.info("FoCoinflip enabled.");
    }

    @Override
    public void onDisable() {
        if (this.guiManager != null) {
            this.guiManager.closePublicGuis();
        }
        if (this.fileLogger != null) {
            this.fileLogger.info("Disabling FoCoinflip.");
        }
        if (this.coinflipManager != null) {
            this.coinflipManager.refundAllOpenFlips();
        }
        if (this.statsRepository != null) {
            this.statsRepository.close();
        }
        if (this.placeholderExpansion != null) {
            this.placeholderExpansion.unregister();
            if (this.fileLogger != null) {
                this.fileLogger.info("PlaceholderAPI expansion unregistered.");
            }
        }
        if (this.core != null) {
            this.core.close();
            this.core = null;
            this.updateNotices = null;
        }
        if (this.fileLogger != null) {
            this.fileLogger.info("FoCoinflip disabled.");
            this.fileLogger.close();
        }
    }

    public FoFileLogger fileLogger() {
        return fileLogger;
    }

    /**
     * Public integration access for optional addons such as FoDiscordBot
     * integrations. The returned manager is only available while the plugin
     * is enabled and its economy has initialized successfully.
     */
    public CoinflipManager getCoinflipManager() {
        return coinflipManager;
    }

    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }

    public VaultEconomyBridge getEconomyService() {
        return economyService;
    }

    private void registerCommands() {
        CoinflipCommand coinflipCommand = new CoinflipCommand(this, this.pluginConfig, this.messageService,
                this.coinflipManager, this.guiManager);

        PluginCommand main = getCommand("focoinflip");
        if (main != null) {
            main.setExecutor(coinflipCommand);
            main.setTabCompleter(coinflipCommand);
        } else {
            this.fileLogger.warn("Command focoinflip is missing from plugin.yml.");
        }

        registerAdminCommand();
    }

    private void registerAdminCommand() {
        FoAdminMessages adminMessages = FoAdminMessages.builder()
                .generalNoPermission("no-permission", "{prefix}{bad}No permission.")
                .generalPlayerOnly("player-only", "{prefix}{bad}Player-only command.")
                .usage("usage-admin", "{prefix}{bad}Usage: {white}/focoinflipadmin <version|reload|editor>")
                .reloadSuccess("admin-reload", "{prefix}{good}Reloaded config, messages, and public GUIs.")
                .reloadFailed("admin-reload-failed", "{prefix}{bad}Reload failed at {step}: {error}.")
                .editorOpened("editor-opened", "{prefix}{good}Editor opened.")
                .build();
        FoAdminCommand.builder(this, this.messageService)
                .commandName("focoinflipadmin")
                .permission("focoinflip.admin")
                .reloads(createReloadRegistry())
                .updates(this.updateNotices)
                .editor(this.guiManager::openAdminEditor)
                .adminMessages(adminMessages)
                .adminSounds(this.adminSounds)
                .register();
    }

    private void registerListeners() {
        this.guiManager.startPublicGuis(
                new CoinflipListener(this.coinflipManager, this.guiManager,
                        () -> this.core));
        this.fileLogger.info("Listeners registered.");
    }

    private void registerPlaceholders() {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            this.fileLogger.info("PlaceholderAPI not found; placeholders disabled.");
            return;
        }
        this.placeholderExpansion = new CoinflipPlaceholderExpansion(this, this.statsRepository);
        this.placeholderExpansion.register();
        this.fileLogger.info("PlaceholderAPI expansion registered.");
    }

    public FoReloadResult reloadPluginFiles() {
        if (this.fileLogger != null) {
            this.fileLogger.info("Reload started.");
        }
        FoReloadResult result = createReloadRegistry().reload();
        if (this.fileLogger != null && result.successful()) {
            this.fileLogger.info("Reloaded config, messages, GUI files, and dialogs. fileLogging="
                    + this.pluginConfig.isFileLoggingEnabled());
        } else if (this.fileLogger != null) {
            this.fileLogger.warn("Reload failed at " + result.failedStep() + ": " + result.errorMessage());
        }
        return result;
    }

    public FoGuiSounds getGuiSounds() {
        return guiSounds;
    }

    public FoEditorSounds getEditorSounds() {
        return editorSounds;
    }

    public FoAdminSounds getAdminSounds() {
        return adminSounds;
    }

    public FoSoundService sounds() {
        return sounds;
    }

    public FoMessageService messages() {
        return messageService;
    }

    private FoReloadRegistry createReloadRegistry() {
        return FoReloadRegistry.create()
                .add("public files validation", this::validatePublicFilesInstalled)
                .add("config", () -> {
                    this.pluginConfig.load();
                    applyCoreConfigDefaults();
                    refreshCoreContext();
                })
                .addMessages(this.messageService)
                .add("public GUIs", () -> {
                    if (!this.guiManager.reloadPublicGuis()) {
                        throw new IllegalStateException("Public GUI validation failed; previous cached menus remain active");
                    }
                })
                .add("economy", () -> {
                    if (!this.economyService.reload()) {
                        throw new IllegalStateException("Vault economy provider not found.");
                    }
                })
                .add("sounds", () -> {
                    if (!this.sounds.reloadChecked()) {
                        throw new IllegalStateException("sounds.yml is invalid; previous sounds remain active");
                    }
                })
                .add("admin integrations", this::refreshAdminIntegrations)
                .add("logging", () -> this.fileLogger.configure(this.pluginConfig.isFileLoggingEnabled(), false));
    }

    private void applyCoreConfigDefaults() {
        FoConfigDefaults.addStandardDefaults(this);
        saveConfig();
    }

    private void refreshCoreContext() {
        FoCoreContext previousCore = this.core;
        boolean metricsWereStarted = this.metrics != null;
        this.core = FoPluginCore.create(this);
        this.sounds = FoSoundService.loadPreservingRemovals(this);
        this.editorSounds = FoEditorSounds.create(this.sounds);
        this.guiSounds = FoGuiSounds.create(this.sounds);
        this.adminSounds = FoAdminSounds.create(this.sounds);
        this.core.warnIfNativeDialogsUnavailable();
        if (previousCore != null) {
            previousCore.close();
        }
        this.metrics = metricsWereStarted ? this.core.metrics(BSTATS_PLUGIN_ID) : null;
    }

    private void refreshAdminIntegrations() {
        if (this.updateNotices != null) {
            HandlerList.unregisterAll(this.updateNotices);
        }
        this.updateNotices = this.core.createUpdateNotices(this.messageService, "focoinflip", this.adminSounds).start();
        registerAdminCommand();
    }

    private FoMessageMigrations messageMigrations() {
        return FoMessageMigrations.create()
                .add(this::migrateLegacyMessageValues)
                .build();
    }

    static void backupPublicConfig(java.nio.file.Path configFile, boolean guiCurrent,
                                   boolean textCurrent) throws IOException {
        if (!Files.isRegularFile(configFile) || guiCurrent && textCurrent) {
            return;
        }
        java.nio.file.Path backup = configFile.resolveSibling("config.yml.gui-v0-backup");
        try {
            Files.copy(configFile, backup, StandardCopyOption.COPY_ATTRIBUTES);
        } catch (java.nio.file.FileAlreadyExistsException retained) {
            if (!Files.isRegularFile(backup)) {
                throw new IOException("Existing public UI backup is not a file: " + backup, retained);
            }
            // Keep the original pre-upgrade copy across retries.
        }
    }

    static boolean upgradeKnownPublicDefaults(YamlConfiguration owner, YamlConfiguration bundled) {
        boolean changed = false;
        changed |= replaceKnownDefault(owner, bundled, "tokens.prefix",
                "{theme}FoCoinflip &8» {muted}");
        changed |= replaceKnownDefault(owner, bundled, "flip-created",
                "{prefix}{good}Created coinflip for {theme}${amount}{good}.");
        changed |= replaceKnownDefault(owner, bundled, "flip-deleted",
                "{prefix}{good}Deleted coinflip and refunded {theme}${amount}{good}.");
        changed |= replaceKnownDefault(owner, bundled, "flip-win",
                "{prefix}{good}Won against {theme}{opponent}{good}. Payout {theme}${payout}{good}. {muted}Tax {theme}${tax}{muted}.");
        changed |= replaceKnownDefault(owner, bundled, "flip-lose",
                "{prefix}{bad}Lost against {theme}{opponent}{bad}. Lost {theme}${amount}{bad}.");
        changed |= replaceKnownDefault(owner, bundled, "create-command-hint",
                "{prefix}{muted}Create with {theme}/cf create <amount>{muted}, e.g. {white}50k{muted}, {white}1m{muted}, or {white}1 billion");
        changed |= replaceKnownDefault(owner, bundled, "admin-reload",
                "{prefix}{good}Reloaded config, messages, and public GUIs.");
        changed |= replaceKnownDefault(owner, bundled, "editor-opened",
                "{prefix}{good}Editor opened.");
        changed |= replaceKnownDefault(owner, bundled, "prompt-saved",
                "{prefix}{good}Saved setting.");
        changed |= replaceKnownDefault(owner, bundled, "prompt-invalid",
                "{prefix}{bad}{reason}");
        return changed;
    }

    private static boolean replaceKnownDefault(YamlConfiguration owner, YamlConfiguration bundled,
                                               String path, String oldDefault) {
        if (!owner.contains(path, true) || !Objects.equals(owner.get(path), oldDefault)) {
            return false;
        }
        owner.set(path, bundled.get(path));
        return true;
    }

    static boolean isReleasedCompactMoneyDefault(FileConfiguration priorConfig) {
        Object version = priorConfig.get("config-version");
        return version instanceof Number number && number.doubleValue() == 1.5D
                && Boolean.FALSE.equals(priorConfig.get("settings.compact-money-format"));
    }

    private void migratePublicMessageFields() {
        if (getConfig().getInt("public-text-schema", 0) >= 1) {
            return;
        }
        YamlConfiguration bundled = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(
                Objects.requireNonNull(getResource("messages.yml")), java.nio.charset.StandardCharsets.UTF_8));
        java.util.function.Function<YamlConfiguration, Boolean> upgrade = owner -> {
            boolean changed = upgradeKnownPublicDefaults(owner, bundled);
            for (String key : bundled.getKeys(true)) {
                if (!bundled.isConfigurationSection(key) || !owner.contains(key, true)
                        || owner.isConfigurationSection(key)) {
                    continue;
                }
                Object invalid = owner.get(key);
                owner.set(key, null);
                ConfigurationSection destination = owner.createSection(key);
                ConfigurationSection source = bundled.getConfigurationSection(key);
                for (String child : source.getKeys(true)) {
                    if (!source.isConfigurationSection(child)) {
                        destination.set(child, source.get(child));
                    }
                }
                getLogger().warning("Public text migration messages.yml:" + key + " invalid " + invalid
                        + "; using newest bundled section");
                changed = true;
            }
            for (String key : bundled.getKeys(true)) {
                if (bundled.isConfigurationSection(key) || !owner.contains(key, true)) {
                    continue;
                }
                Object value = owner.get(key);
                if (!validPublicTextValue(key, value)) {
                    getLogger().warning("Public text migration messages.yml:" + key + " invalid " + value
                            + "; using newest bundled default " + bundled.get(key));
                    owner.set(key, bundled.get(key));
                    changed = true;
                }
            }
            for (String key : java.util.List.of("gui-unavailable", "sort-options.amount-desc",
                    "sort-options.amount-asc", "sort-options.newest", "sort-options.oldest",
                    "money-format.locale", "money-format.standard-pattern", "money-format.compact-base-pattern",
                    "money-format.compact-pattern", "money-format.compact-template", "money-format.suffixes")) {
                String sortId = key.startsWith("sort-options.") ? key.substring("sort-options.".length()) : null;
                String inherited = sortId == null ? null : pluginConfig.legacySortLabels().get(sortId);
                if (sortId != null && inherited != null && inherited.isEmpty()
                        && this.legacyMessagesPendingMigration) {
                    owner.set(key, null);
                    changed = true;
                    continue;
                }
                if (!owner.contains(key, true)) {
                    if (inherited == null) {
                        owner.set(key, bundled.get(key));
                        changed = true;
                    } else if (!inherited.isEmpty()) {
                        owner.set(key, inherited);
                        changed = true;
                    }
                }
            }
            return changed;
        };
        java.nio.file.Path messagesFile = getDataFolder().toPath().resolve("messages.yml");
        java.nio.file.Path configFile = getDataFolder().toPath().resolve("config.yml");
        java.nio.file.Path staged = null;
        byte[] originalMessages = null;
        byte[] originalConfig = null;
        boolean installedReplacement = false;
        try {
            originalMessages = Files.readAllBytes(messagesFile);
            originalConfig = Files.readAllBytes(configFile);
            YamlConfiguration owner = new YamlConfiguration();
            owner.load(messagesFile.toFile());
            boolean alreadyCurrent = owner.isConfigurationSection("sort-options")
                    && owner.isConfigurationSection("money-format")
                    && !legacyMessagesPendingMigration;
            if (alreadyCurrent) {
                getLogger().warning("messages.yml has current public text sections without a schema marker;"
                        + " preserving omitted keys and recording the current schema");
            }
            boolean changed = !alreadyCurrent && upgrade.apply(owner);
            validatePublicMessages(owner, bundled);
            if (changed) {
                java.nio.file.Path backup = messagesFile.resolveSibling("messages.yml.gui-v0-backup");
                if (Files.notExists(backup) && !legacyMessagesPendingMigration) {
                    Files.write(backup, originalMessages);
                }
                staged = Files.createTempFile(messagesFile.getParent(), ".public-text-", ".yml");
                Files.writeString(staged, owner.saveToString(), java.nio.charset.StandardCharsets.UTF_8);
                replaceAtomically(staged, messagesFile);
                installedReplacement = true;
                this.legacyMessagesPendingMigration = false;
                this.legacyMessageMigrationPending = false;
                messageService.reload();
            }
            if (!alreadyCurrent && legacyCompactMoneyDefault) {
                getConfig().set("settings.compact-money-format", true);
            }
            getConfig().set("public-text-schema", 1);
            saveConfig();
            if (YamlConfiguration.loadConfiguration(configFile.toFile()).getInt("public-text-schema", 0) != 1) {
                throw new IOException("public-text-schema was not persisted");
            }
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException | RuntimeException failure) {
            getLogger().severe("Public text migration failed; retaining prior files: " + failure.getMessage());
            if (installedReplacement && originalMessages != null) {
                try {
                    restoreAtomically(messagesFile, originalMessages);
                    messageService.reload();
                } catch (IOException rollbackFailure) {
                    getLogger().severe("Public text message rollback failed: " + rollbackFailure.getMessage());
                }
            }
            if (originalConfig != null) {
                try {
                    restoreAtomically(configFile, originalConfig);
                    reloadConfig();
                } catch (IOException rollbackFailure) {
                    getLogger().severe("Public text config rollback failed: " + rollbackFailure.getMessage());
                }
            }
        } finally {
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException cleanupFailure) {
                    getLogger().warning("Could not remove staged public text file: " + cleanupFailure.getMessage());
                }
            }
        }
    }

    private void validateMessagesInstalled() {
        YamlConfiguration candidate = new YamlConfiguration();
        try {
            candidate.load(new File(getDataFolder(), "messages.yml"));
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IllegalStateException("messages.yml is invalid; previous messages remain active", exception);
        }
        YamlConfiguration bundled = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(
                Objects.requireNonNull(getResource("messages.yml")), java.nio.charset.StandardCharsets.UTF_8));
        validatePublicMessages(candidate, bundled);
    }

    private static void validatePublicMessages(YamlConfiguration candidate, YamlConfiguration bundled) {
        for (String key : bundled.getKeys(true)) {
            if (bundled.isConfigurationSection(key) && candidate.contains(key, true)
                    && !candidate.isConfigurationSection(key)) {
                throw new IllegalStateException("messages.yml:" + key
                        + " must be a section; previous messages remain active");
            }
        }
        for (String key : bundled.getKeys(true)) {
            if (bundled.isConfigurationSection(key) || !candidate.contains(key, true)) {
                continue;
            }
            Object value = candidate.get(key);
            if (!validPublicTextValue(key, value)) {
                throw new IllegalStateException("messages.yml:" + key + " has an invalid value; previous messages remain active");
            }
        }
    }

    private static void replaceAtomically(java.nio.file.Path staged, java.nio.file.Path target) throws IOException {
        try {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unavailable) {
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void restoreAtomically(java.nio.file.Path target, byte[] bytes) throws IOException {
        java.nio.file.Path staged = Files.createTempFile(target.getParent(), ".public-text-rollback-", ".tmp");
        try {
            Files.write(staged, bytes);
            replaceAtomically(staged, target);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    static boolean validPublicTextValue(String key, Object value) {
        if (key.equals("money-format.suffixes")) {
            return value instanceof java.util.List<?> list && list.stream().allMatch(String.class::isInstance);
        }
        if (key.startsWith("money-format.") || key.startsWith("sort-options.") || key.startsWith("tokens.")) {
            if (!(value instanceof String text)) {
                return false;
            }
            if (key.endsWith("-pattern")) {
                try {
                    new java.text.DecimalFormat(text);
                } catch (IllegalArgumentException invalidPattern) {
                    return false;
                }
            }
            if (key.equals("money-format.locale")) {
                try {
                    new java.util.Locale.Builder().setLanguageTag(text).build();
                } catch (java.util.IllformedLocaleException invalidLocale) {
                    return false;
                }
            }
            return true;
        }
        return value instanceof String || value instanceof java.util.List<?> list
                && list.stream().allMatch(String.class::isInstance);
    }

    private void validatePublicFilesInstalled() {
        validateMessagesInstalled();
        if (!sounds.validateInstalled()) {
            throw new IllegalStateException("sounds.yml is invalid; previous sounds remain active");
        }
        YamlConfiguration soundCandidate = new YamlConfiguration();
        try {
            soundCandidate.load(new File(getDataFolder(), "sounds.yml"));
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IllegalStateException("sounds.yml is invalid; previous sounds remain active", exception);
        }
        CoinflipSoundOptions.cycleVariation(soundCandidate, CoinflipSoundOptions.bundled(this), false);
        if (guiManager != null && !guiManager.validatePublicGuis()) {
            throw new IllegalStateException("A public GUI is invalid; previous menus remain active");
        }
    }

    private boolean migrateLegacyMessageValues(FileConfiguration messages) {
        if (!legacyMessageMigrationPending) {
            return false;
        }
        boolean changed = false;
        if (this.legacyMessagesPendingMigration) {
            ConfigurationSection legacyMessages = this.pluginConfig.raw().getConfigurationSection("messages");
            if (legacyMessages != null) {
                copySection(legacyMessages, messages);
                changed = true;
            }
        }
        if (messages.isSet("prefix")) {
            String legacyPrefix = messages.getString("prefix");
            if (legacyPrefix != null) {
                changed |= setIfMissingOrBundledDefault(messages, "prefix", DEFAULT_MESSAGE_PREFIX, legacyPrefix);
            }
            messages.set("prefix", null);
            changed = true;
        }

        changed |= migrateLegacyColorToken(messages, "theme", DEFAULT_MESSAGE_THEME);
        changed |= migrateLegacyColorToken(messages, "muted", DEFAULT_MESSAGE_MUTED);
        changed |= migrateLegacyColorToken(messages, "white", DEFAULT_MESSAGE_WHITE);
        changed |= migrateLegacyColorToken(messages, "good", DEFAULT_MESSAGE_GOOD);
        changed |= migrateLegacyColorToken(messages, "bad", DEFAULT_MESSAGE_BAD);
        return changed;
    }

    private void copySection(ConfigurationSection source, ConfigurationSection target) {
        for (String key : source.getKeys(false)) {
            ConfigurationSection child = source.getConfigurationSection(key);
            if (child != null) {
                ConfigurationSection targetChild = target.getConfigurationSection(key);
                if (targetChild == null) {
                    targetChild = target.createSection(key);
                }
                copySection(child, targetChild);
                continue;
            }
            target.set(key, source.get(key));
        }
    }

    private boolean migrateLegacyColorToken(FileConfiguration messages, String tokenName, String bundledDefault) {
        String legacyPath = "colors." + tokenName;
        if (!this.pluginConfig.raw().isSet(legacyPath)) {
            return false;
        }
        String legacyValue = this.pluginConfig.raw().getString(legacyPath);
        return legacyValue != null
                && setIfMissingOrBundledDefault(messages, tokenName, bundledDefault, legacyValue);
    }

    private boolean setIfMissingOrBundledDefault(
            FileConfiguration messages,
            String tokenName,
            String bundledDefault,
            String value) {
        String path = "tokens." + tokenName;
        String currentValue = messages.getString(path);
        if (currentValue != null && !Objects.equals(currentValue, bundledDefault)) {
            return false;
        }
        if (Objects.equals(currentValue, value)) {
            return false;
        }
        messages.set(path, value);
        return true;
    }
}
