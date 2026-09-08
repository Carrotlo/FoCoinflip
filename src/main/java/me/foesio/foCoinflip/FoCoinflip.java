package me.foesio.foCoinflip;

import java.io.File;
import me.foesio.foCoinflip.command.CoinflipCommand;
import me.foesio.foCoinflip.config.PluginConfig;
import me.foesio.foCoinflip.economy.EconomyService;
import me.foesio.foCoinflip.gui.GuiManager;
import me.foesio.foCoinflip.listener.CoinflipListener;
import me.foesio.foCoinflip.stats.StatsRepository;
import me.foesio.foCoinflip.placeholder.CoinflipPlaceholderExpansion;
import me.foesio.core.command.FoAdminCommand;
import me.foesio.core.command.FoAdminMessages;
import me.foesio.core.FoCoreContext;
import me.foesio.core.FoPluginCore;
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
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import net.milkbowl.vault.economy.Economy;

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
    private EconomyService economyService;
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
        this.fileLogger = new FoFileLogger(this);
        this.pluginConfig = new PluginConfig(this);
        this.pluginConfig.load();
        applyCoreConfigDefaults();
        refreshCoreContext();
        this.fileLogger.configure(this.pluginConfig.isFileLoggingEnabled(), true);
        this.fileLogger.info("Enabling FoCoinflip v" + getDescription().getVersion());
        this.metrics = this.core.metrics(BSTATS_PLUGIN_ID);
        this.legacyMessagesPendingMigration = !new File(getDataFolder(), "messages.yml").isFile();
        this.messageService = FoMessageService.load(this, messageMigrations());
        this.legacyMessagesPendingMigration = false;
        migrateSprites();

        Economy economy = resolveEconomy();
        if (economy == null) {
            getLogger().severe("Vault economy provider not found. Disabling plugin.");
            this.fileLogger.error("Vault economy provider not found. Disabling plugin.", null);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        this.fileLogger.info("Vault economy provider hooked: " + economy.getName());

        this.economyService = new EconomyService(this, economy);
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

    public EconomyService getEconomyService() {
        return economyService;
    }

    private Economy resolveEconomy() {
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return null;
        }
        return rsp.getProvider();
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
        Bukkit.getPluginManager().registerEvents(
                new CoinflipListener(this.coinflipManager, this.guiManager,
                        () -> this.core),
                this);
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

    private FoReloadRegistry createReloadRegistry() {
        return FoReloadRegistry.create()
                .add("config", () -> {
                    this.pluginConfig.load();
                    applyCoreConfigDefaults();
                    refreshCoreContext();
                })
                .addMessages(this.messageService)
                .add("sounds", () -> this.sounds.reload())
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
        this.sounds = this.core.createSounds();
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

    private void migrateSprites() {
        messageService.migrateToVersion(core.migrations(), 1, config -> {
            boolean changed = false;
            changed |= FoMessageService.addMissingToken(config, "tokens.prefix", ":gold_nugget:", null);
            changed |= FoMessageService.addMissingToken(config, "flip-created", ":emerald:");
            changed |= FoMessageService.addMissingToken(config, "flip-deleted", ":lava_bucket:");
            changed |= FoMessageService.addMissingToken(config, "admin-reload", ":emerald:");
            changed |= FoMessageService.addMissingToken(config, "admin-reload-failed", ":redstone:");
            changed |= FoMessageService.addMissingToken(config, "editor-opened", ":book:");
            changed |= FoMessageService.addMissingToken(config, "prompt-saved", ":emerald:");
            changed |= FoMessageService.addMissingToken(config, "prompt-invalid", ":redstone:");
            return true;
        });
    }

    private boolean migrateLegacyMessageValues(FileConfiguration messages) {
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
