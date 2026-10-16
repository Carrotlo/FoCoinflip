package me.foesio.foCoinflip.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import me.foesio.foCoinflip.FoCoinflip;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public class PluginConfig {

    private final FoCoinflip plugin;
    private Map<String, String> legacySortLabels = Map.of();

    public PluginConfig(FoCoinflip plugin) {
        this.plugin = plugin;
    }

    public void load() {
        java.io.File[] existingFiles = plugin.getDataFolder().listFiles();
        boolean existingInstallation = existingFiles != null && existingFiles.length > 0;
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.legacySortLabels = GuiConfigMigrator.installOrMigrate(plugin, raw(), existingInstallation);
        mergeDefaultConfig();
    }

    public FileConfiguration raw() {
        return plugin.getConfig();
    }

    public boolean isFileLoggingEnabled() {
        return raw().getBoolean("file-logging", false);
    }

    public Map<String, String> legacySortLabels() {
        return legacySortLabels;
    }

    public void save() {
        plugin.saveConfig();
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

    private void mergeDefaultConfig() {
        YamlConfiguration defaults = loadDefaults("config.yml");
        if (defaults == null) {
            return;
        }
        raw().setDefaults(defaults);
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

}
