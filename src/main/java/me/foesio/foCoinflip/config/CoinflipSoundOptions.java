package me.foesio.foCoinflip.config;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Additional public sound presentation not interpreted by Core's sound service. */
public final class CoinflipSoundOptions {

    private static final String CYCLE_VARIATION = "gui.cycle.pitch-variation";

    private CoinflipSoundOptions() {
    }

    /** Install the new sound defaults only for a fresh plugin or a proven older GUI installation. */
    public static boolean shouldInstallBundled(File dataFolder, boolean priorInstallation,
                                               boolean priorPublicSchema, boolean legacyInlineGui) {
        if (new File(dataFolder, "sounds.yml").exists() || priorPublicSchema) {
            return false;
        }
        if (!priorInstallation) {
            return true;
        }
        File main = new File(dataFolder, "guis/main.yml");
        File animation = new File(dataFolder, "guis/animation.yml");
        if (legacyInlineGui && !main.exists() && !animation.exists()) {
            return true;
        }
        if (!main.isFile() || !animation.isFile()) {
            return false;
        }
        try {
            YamlConfiguration oldMain = new YamlConfiguration();
            YamlConfiguration oldAnimation = new YamlConfiguration();
            oldMain.load(main);
            oldAnimation.load(animation);
            return oldMain.isConfigurationSection("coinflip-item")
                    && oldMain.isList("content-slots")
                    && oldAnimation.isConfigurationSection("rolling-item")
                    && oldAnimation.contains("result-slot", true);
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException exception) {
            return false;
        }
    }

    public static YamlConfiguration bundled(JavaPlugin plugin) {
        try (InputStream stream = plugin.getResource("sounds.yml")) {
            if (stream == null) {
                throw new IllegalStateException("Bundled sounds.yml is missing");
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read bundled sounds.yml", exception);
        }
    }

    public static float cycleVariation(FileConfiguration owner, FileConfiguration bundled,
                                       boolean startupFallback) {
        if (!owner.contains(CYCLE_VARIATION, true)) {
            return 0.0F; // Owner removed the variation.
        }
        Object value = owner.get(CYCLE_VARIATION);
        if (valid(value)) {
            return ((Number) value).floatValue();
        }
        if (startupFallback && valid(bundled.get(CYCLE_VARIATION))) {
            return ((Number) bundled.get(CYCLE_VARIATION)).floatValue();
        }
        throw new IllegalArgumentException(CYCLE_VARIATION + " must be a finite number between 0 and 0.5");
    }

    private static boolean valid(Object value) {
        return value instanceof Number number && Double.isFinite(number.doubleValue())
                && number.doubleValue() >= 0 && number.doubleValue() <= 0.5;
    }
}
