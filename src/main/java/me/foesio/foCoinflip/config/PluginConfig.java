package me.foesio.foCoinflip.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import me.foesio.core.config.ResourceFiles;
import me.foesio.foCoinflip.FoCoinflip;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public class PluginConfig {

    private final FoCoinflip plugin;
    private FileConfiguration mainGui;
    private FileConfiguration animationGui;

    public PluginConfig(FoCoinflip plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        mergeDefaultConfig();
        this.mainGui = loadResourceConfig("guis/main.yml", "gui.main");
        this.animationGui = loadResourceConfig("guis/animation.yml", "gui.animation");
    }

    public FileConfiguration raw() {
        return plugin.getConfig();
    }

    public boolean isFileLoggingEnabled() {
        return raw().getBoolean("file-logging", false);
    }

    public FileConfiguration mainGui() {
        return mainGui;
    }

    public FileConfiguration animationGui() {
        return animationGui;
    }

    public void save() {
        plugin.saveConfig();
    }

    public String getMainGuiTitle() {
        return mainGui.getString("title", "&8ᴄᴏɪɴꜰʟɪᴘ");
    }

    public String getAnimationGuiTitle() {
        return animationGui.getString("title", "&8ꜰʟɪᴘᴘɪɴɢ...");
    }

    public String getAdminGuiTitle() {
        return "&8ᴄᴏɪɴꜰʟɪᴘ ᴇᴅɪᴛᴏʀ";
    }

    public double getTaxPercent() {
        double value = raw().getDouble("settings.tax-percent", 0.0D);
        if (!Double.isFinite(value)) {
            value = 0.0D;
        }
        return clamp(value, 0.0D, 100.0D);
    }

    public double getMinBet() {
        double value = raw().getDouble("settings.min-bet", 100.0D);
        if (!Double.isFinite(value)) {
            value = 100.0D;
        }
        return Math.max(0.0D, value);
    }

    public double getMaxBet() {
        double value = raw().getDouble("settings.max-bet", 9_999_999_999D);
        if (!Double.isFinite(value)) {
            value = 9_999_999_999D;
        }
        return Math.max(getMinBet(), value);
    }

    public boolean isLockAnimationGui() {
        return raw().getBoolean("settings.lock-animation-gui", false);
    }

    public boolean isAnnounceCoinflipResult() {
        return raw().getBoolean("settings.announce-coinflip-result", true);
    }

    public boolean isCompactMoneyFormat() {
        return raw().getBoolean("settings.compact-money-format", true);
    }

    public int getAnimationDurationTicks() {
        return Math.max(20, raw().getInt("settings.animation.duration-ticks", 60));
    }

    public int getAnimationFrameIntervalTicks() {
        return Math.max(1, raw().getInt("settings.animation.frame-interval-ticks", 4));
    }

    private double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private FileConfiguration loadResourceConfig(String path, String legacyPath) {
        File resourceFile = ResourceFiles.dataFile(plugin, path);
        boolean created = !resourceFile.exists();
        File file = ResourceFiles.saveDefault(plugin, path);
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.load(file);
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().warning("Failed to load " + path + ": " + exception.getMessage());
            plugin.fileLogger().error("Failed to load " + path, exception);
        }

        YamlConfiguration defaults = loadDefaults(path);
        if (defaults != null) {
            configuration.setDefaults(defaults);
        }

        boolean changed = false;
        if (created && legacyPath != null) {
            ConfigurationSection legacySection = raw().getConfigurationSection(legacyPath);
            if (legacySection != null) {
                copySection(legacySection, configuration);
                migrateLegacyGuiKeys(configuration, legacyPath);
                changed = true;
            }
        }
        if (path.equals("guis/main.yml")) {
            changed |= migrateLegacyContentSlots(configuration);
            changed |= migrateLegacyNavigation(configuration);
            changed |= migrateLegacyRefreshLayout(configuration);
            if (defaults != null) {
                changed |= migrateDefaultMainStyle(configuration, defaults);
            }
        }
        if (defaults != null) {
            changed |= mergeMissing(configuration, defaults);
        }
        if (changed) {
            saveYaml(file, configuration, path);
        }
        return configuration;
    }

    private boolean migrateDefaultMainStyle(ConfigurationSection configuration, YamlConfiguration defaults) {
        boolean changed = false;
        changed |= replaceExactDefault(configuration, defaults, "items.sort.name", "{theme}Sort");
        changed |= replaceExactDefault(configuration, defaults, "items.create.name", "{theme}Create Coinflip");
        changed |= replaceExactDefault(configuration, defaults, "items.create.lore", List.of(
                "{white}Click to view command usage",
                "{white}Use /cf create <amount>"));
        changed |= replaceExactDefault(configuration, defaults, "items.stats.name", "{theme}Your Stats");
        changed |= replaceExactDefault(configuration, defaults, "items.stats.lore", List.of(
                "{white}Total Won: {good}${won}",
                "{white}Total Lost: {bad}${lost}",
                "{white}Total Sum: {theme}${sum}"));
        changed |= replaceExactDefault(configuration, defaults, "items.refresh.name", "{theme}Refresh");
        changed |= replaceExactDefault(configuration, defaults, "items.refresh.lore", List.of(
                "{white}Refresh the current page"));
        return changed;
    }

    private boolean replaceExactDefault(ConfigurationSection configuration, YamlConfiguration defaults,
            String path, Object oldValue) {
        if (!configuration.isSet(path) || !Objects.equals(configuration.get(path), oldValue) || !defaults.isSet(path)) {
            return false;
        }
        Object newValue = defaults.get(path);
        if (Objects.equals(configuration.get(path), newValue)) {
            return false;
        }
        configuration.set(path, newValue);
        return true;
    }

    private void mergeDefaultConfig() {
        YamlConfiguration defaults = loadDefaults("config.yml");
        if (defaults == null) {
            return;
        }
        raw().setDefaults(defaults);
        if (mergeMissing(raw(), defaults)) {
            save();
        }
    }

    private YamlConfiguration loadDefaults(String path) {
        try (InputStream inputStream = plugin.getResource(path)) {
            if (inputStream != null) {
                return YamlConfiguration.loadConfiguration(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to load defaults for " + path + ": " + exception.getMessage());
            plugin.fileLogger().error("Failed to load bundled defaults for " + path, exception);
        }
        return null;
    }

    private boolean mergeMissing(ConfigurationSection target, ConfigurationSection defaults) {
        boolean changed = false;
        for (String key : defaults.getKeys(false)) {
            Object value = defaults.get(key);
            ConfigurationSection defaultSection = defaults.getConfigurationSection(key);
            if (defaultSection != null) {
                ConfigurationSection targetSection;
                if (!target.isSet(key)) {
                    targetSection = target.createSection(key);
                    changed = true;
                } else {
                    targetSection = target.getConfigurationSection(key);
                    if (targetSection == null) {
                        continue;
                    }
                }
                changed |= mergeMissing(targetSection, defaultSection);
                continue;
            }

            if (!target.isSet(key)) {
                target.set(key, value);
                changed = true;
            }
        }
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

    private void migrateLegacyGuiKeys(ConfigurationSection target, String legacyPath) {
        if (legacyPath.equals("gui.main")) {
            copyIfPresent(target, "coinflip-item-name", "coinflip-item.name");
            copyIfPresent(target, "coinflip-item-lore", "coinflip-item.lore");
            copyIfPresent(target, "stats-name", "items.stats.name");
            copyIfPresent(target, "stats-lore", "items.stats.lore");
            copyIfPresent(target, "empty-name", "empty-item.name");
            copyIfPresent(target, "empty-lore", "empty-item.lore");
            copyIfPresent(target, "create-name", "items.create.name");
            copyIfPresent(target, "create-lore", "items.create.lore");
            return;
        }

        if (legacyPath.equals("gui.animation")) {
            copyIfPresent(target, "rolling-name", "rolling-item.name");
            copyIfPresent(target, "rolling-lore", "rolling-item.lore");
            copyIfPresent(target, "result-name", "result-item.name");
            copyIfPresent(target, "result-lore", "result-item.lore");
        }
    }

    private boolean migrateLegacyNavigation(ConfigurationSection configuration) {
        boolean changed = false;
        changed |= migrateNavigationSlot(configuration, "items.previous.slot", "navigation.previous-slot");
        changed |= migrateNavigationSlot(configuration, "items.next.slot", "navigation.next-slot");
        changed |= archiveAndRemoveNavigationItem(configuration, "previous");
        changed |= archiveAndRemoveNavigationItem(configuration, "next");
        return changed;
    }

    private boolean migrateLegacyRefreshLayout(ConfigurationSection configuration) {
        if (configuration.contains("items.refresh", true) || !configuration.contains("items.stats.slot", true)) {
            return false;
        }
        if (configuration.getInt("items.stats.slot") != 49) {
            return false;
        }
        configuration.set("items.stats.slot", 50);
        return true;
    }

    private boolean migrateLegacyContentSlots(ConfigurationSection configuration) {
        if (!configuration.contains("content-slots", true)
                || !configuration.getIntegerList("content-slots").equals(legacyContentSlots())) {
            return false;
        }
        configuration.set("content-slots", List.of(
                10, 11, 12, 13, 14, 15, 16,
                19, 20, 21, 22, 23, 24, 25,
                28, 29, 30, 31, 32, 33, 34));
        return true;
    }

    private List<Integer> legacyContentSlots() {
        return java.util.stream.IntStream.range(0, 45).boxed().toList();
    }

    private boolean migrateNavigationSlot(ConfigurationSection configuration, String oldPath, String newPath) {
        if (configuration.isSet(newPath) || !configuration.isSet(oldPath)) {
            return false;
        }
        configuration.set(newPath, configuration.get(oldPath));
        return true;
    }

    private boolean archiveAndRemoveNavigationItem(ConfigurationSection configuration, String key) {
        String oldPath = "items." + key;
        ConfigurationSection oldSection = configuration.getConfigurationSection(oldPath);
        if (oldSection == null) {
            return false;
        }

        String archivePath = "legacy-navigation." + key;
        if (!configuration.isSet(archivePath)) {
            ConfigurationSection archive = configuration.createSection(archivePath);
            copySection(oldSection, archive);
        }
        configuration.set(oldPath, null);
        return true;
    }

    private void copyIfPresent(ConfigurationSection section, String sourcePath, String targetPath) {
        if (section.contains(sourcePath)) {
            section.set(targetPath, section.get(sourcePath));
        }
    }

    private void saveYaml(File file, YamlConfiguration configuration, String path) {
        try {
            configuration.save(file);
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to save " + path + ": " + exception.getMessage());
            plugin.fileLogger().error("Failed to save " + path, exception);
        }
    }
}
