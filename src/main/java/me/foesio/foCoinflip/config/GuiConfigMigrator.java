package me.foesio.foCoinflip.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import me.foesio.core.gui.publicgui.GuiDefinitionException;
import me.foesio.core.gui.publicgui.PublicGuiContract;
import me.foesio.core.gui.publicgui.PublicGuiDefinition;
import me.foesio.core.gui.publicgui.PublicGuiSchema;
import me.foesio.foCoinflip.FoCoinflip;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

/** One-time conversion of the released Coinflip GUI shapes to Core's generated schema. */
final class GuiConfigMigrator {

    static final int SCHEMA_VERSION = 2;
    private static final String MAIN = "guis/main.yml";
    private static final String ANIMATION = "guis/animation.yml";
    private static final String RELEASED_23_MAIN = "legacy-guis/main-v2.3.yml";
    private static final List<String> SORT_VALUES = List.of("amount-desc", "amount-asc", "newest", "oldest");

    private GuiConfigMigrator() {
    }

    static Map<String, String> installOrMigrate(FoCoinflip plugin, FileConfiguration config,
                                                boolean existingInstallation) {
        Path root = plugin.getDataFolder().toPath();
        Path mainFile = root.resolve(MAIN);
        Path animationFile = root.resolve(ANIMATION);
        boolean mainExists = Files.isRegularFile(mainFile);
        boolean animationExists = Files.isRegularFile(animationFile);
        boolean legacyConfig = config.isConfigurationSection("gui.main")
                || config.isConfigurationSection("gui.animation");
        if (!existingInstallation && !mainExists && !animationExists && !legacyConfig) {
            installFresh(plugin, config, mainFile, animationFile);
            return Map.of();
        }

        if (config.getInt("public-gui-schema", 0) >= SCHEMA_VERSION) {
            return Map.of();
        }
        try {
            YamlConfiguration installedMain = mainExists ? loadStrict(mainFile) : null;
            YamlConfiguration installedAnimation = animationExists ? loadStrict(animationFile) : null;
            if (isCurrent(installedMain) && isCurrent(installedAnimation)) {
                YamlConfiguration updatedMain = migratePublicPagination(plugin, installedMain);
                PublicGuiSchema.parse(CoinflipGuiContracts.MAIN, MAIN, updatedMain,
                        CoinflipGuiContracts.main());
                PublicGuiSchema.parse(CoinflipGuiContracts.ANIMATION, ANIMATION, installedAnimation,
                        CoinflipGuiContracts.animation());
                int priorSchema = config.getInt("public-gui-schema", 0);
                byte[] originalMain = Files.readAllBytes(mainFile);
                byte[] originalConfig = Files.readAllBytes(root.resolve("config.yml"));
                Path stagedMain = null;
                try {
                    stagedMain = stage(mainFile, updatedMain);
                    Path backup = mainFile.resolveSibling("main.yml.public-gui-v"
                            + priorSchema + "-backup");
                    if (Files.notExists(backup)) {
                        Files.write(backup, originalMain);
                    }
                    replace(stagedMain, mainFile);
                    config.set("public-gui-schema", SCHEMA_VERSION);
                    plugin.saveConfig();
                    if (YamlConfiguration.loadConfiguration(root.resolve("config.yml").toFile())
                            .getInt("public-gui-schema", 0) != SCHEMA_VERSION) {
                        throw new IOException("GUI schema version was not persisted");
                    }
                } catch (IOException | RuntimeException failure) {
                    restore(mainFile, originalMain);
                    restore(root.resolve("config.yml"), originalConfig);
                    config.set("public-gui-schema", priorSchema);
                    throw failure;
                } finally {
                    if (stagedMain != null) {
                        Files.deleteIfExists(stagedMain);
                    }
                }
                return Map.of();
            }
            if (isCurrent(installedMain) != isCurrent(installedAnimation)) {
                throw new IOException("Mixed old/new GUI files require recovery from the retained original backups");
            }
            if ((!mainExists && !config.isConfigurationSection("gui.main"))
                    || (!animationExists && !config.isConfigurationSection("gui.animation"))) {
                throw new IOException("A prior GUI file is missing; preserving a possible owner deletion");
            }
            int mainVersion = legacyVersion(installedMain, MAIN, 3);
            int animationVersion = legacyVersion(installedAnimation, ANIMATION, 1);
            boolean released23Main = isReleased23Main(installedMain);
            if (installedMain == null) {
                installedMain = oldFromConfig(plugin, "legacy-guis/main-v0.yml", config.getConfigurationSection("gui.main"));
            }
            if (installedAnimation == null) {
                installedAnimation = oldFromConfig(plugin, "legacy-guis/animation-v0.yml", config.getConfigurationSection("gui.animation"));
            }
            Map<String, String> sortLabels = sortLabels(installedMain);
            YamlConfiguration newMain = convert(plugin, installedMain,
                    mainBaseline(installedMain, mainVersion),
                    MAIN, mainMappings(), CoinflipGuiContracts.MAIN, CoinflipGuiContracts.main());
            YamlConfiguration newAnimation = convert(plugin, installedAnimation,
                    released23Main || animationVersion != 0
                            ? "legacy-guis/animation-v1.yml" : "legacy-guis/animation-v0.yml",
                    ANIMATION, animationMappings(), CoinflipGuiContracts.ANIMATION,
                    CoinflipGuiContracts.animation());
            Map<Path, byte[]> originals = new LinkedHashMap<>();
            byte[] configOriginal = Files.readAllBytes(root.resolve("config.yml"));
            originals.put(mainFile, mainExists ? Files.readAllBytes(mainFile) : null);
            originals.put(animationFile, animationExists ? Files.readAllBytes(animationFile) : null);
            Map<Path, Path> staged = new LinkedHashMap<>();
            try {
                staged.put(mainFile, stage(mainFile, newMain));
                staged.put(animationFile, stage(animationFile, newAnimation));
                // Validate staged bytes, not a synthetic path registered as a live menu.
                validateMigratedNavigation(installedMain, PublicGuiSchema.parse(
                        CoinflipGuiContracts.MAIN, MAIN, loadStrict(staged.get(mainFile)),
                        CoinflipGuiContracts.main()));
                PublicGuiSchema.parse(CoinflipGuiContracts.ANIMATION, ANIMATION,
                        loadStrict(staged.get(animationFile)), CoinflipGuiContracts.animation());
                backup(mainFile, originals.get(mainFile), mainVersion);
                backup(animationFile, originals.get(animationFile), animationVersion);
                if (legacyConfig && (!mainExists || !animationExists)) {
                    backup(root.resolve("config.yml"), Files.readAllBytes(root.resolve("config.yml")), 0);
                }
                replace(staged.get(mainFile), mainFile);
                replace(staged.get(animationFile), animationFile);
                config.set("public-gui-schema", SCHEMA_VERSION);
                plugin.saveConfig();
                if (YamlConfiguration.loadConfiguration(root.resolve("config.yml").toFile())
                        .getInt("public-gui-schema", 0) != SCHEMA_VERSION) {
                    throw new IOException("GUI schema version was not persisted");
                }
                return sortLabels;
            } catch (IOException | RuntimeException exception) {
                for (Map.Entry<Path, byte[]> entry : originals.entrySet()) {
                    restore(entry.getKey(), entry.getValue());
                }
                restore(root.resolve("config.yml"), configOriginal);
                config.set("public-gui-schema", null);
                throw exception;
            } finally {
                for (Path path : staged.values()) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException | InvalidConfigurationException | GuiDefinitionException exception) {
            plugin.getLogger().severe("Public GUI migration failed; installed files were left unchanged: "
                    + exception.getMessage());
            return Map.of();
        }
    }

    private static boolean isCurrent(YamlConfiguration yaml) {
        return yaml != null && !yaml.contains("gui-version", true)
                && (yaml.contains("entries", true) || yaml.contains("display-slots", true));
    }

    static boolean isReleased23Main(YamlConfiguration yaml) {
        return yaml != null && !yaml.contains("gui-version", true)
                && (yaml.isConfigurationSection("navigation")
                || (yaml.isConfigurationSection("items.entry-filler")
                && yaml.isConfigurationSection("items.sort")
                && yaml.isConfigurationSection("items.refresh")));
    }

    static String mainBaseline(YamlConfiguration yaml, int version) {
        if (isReleased23Main(yaml)) {
            return RELEASED_23_MAIN;
        }
        return version <= 1 ? "legacy-guis/main-v0.yml"
                : version == 2 ? "legacy-guis/main-v2.yml" : "legacy-guis/main-v3.yml";
    }

    static void validateMigratedNavigation(YamlConfiguration old, PublicGuiDefinition definition)
            throws GuiDefinitionException {
        for (String id : List.of("previous", "next")) {
            if (old.contains("navigation." + id + "-slot", true)
                    || old.isConfigurationSection("items." + id)) {
                String action = "coinflip." + id;
                var entry = definition.entries().get(id);
                if (entry == null || !entry.actions().containsValue(action)) {
                    throw new GuiDefinitionException("entries." + id,
                            "old navigation control was not migrated to action " + action);
                }
            }
        }
    }

    static YamlConfiguration migratePublicPagination(FoCoinflip plugin, YamlConfiguration old)
            throws IOException, GuiDefinitionException {
        YamlConfiguration latest = bundled(null, MAIN);
        YamlConfiguration priorDefaults = bundled(null, "legacy-guis/main-public-v1.yml");
        YamlConfiguration updated = bundled(null, MAIN);
        Set<String> ownerPaths = new LinkedHashSet<>();
        for (String path : priorDefaults.getKeys(true)) {
            if (path.startsWith("buttons.previous.") || path.equals("buttons.previous")
                    || path.startsWith("buttons.next.") || path.equals("buttons.next")) {
                continue;
            }
            if (priorDefaults.isConfigurationSection(path)) {
                if (!old.contains(path, true)) {
                    updated.set(path, null);
                    ownerPaths.add(path);
                }
            } else if (!Objects.equals(old.get(path), priorDefaults.get(path))) {
                updated.set(path, old.get(path));
                ownerPaths.add(path);
            }
        }
        for (String path : old.getKeys(true)) {
            if (!priorDefaults.contains(path, true) && !old.isConfigurationSection(path)
                    && !path.startsWith("buttons.previous.") && !path.startsWith("buttons.next.")) {
                updated.set(path, old.get(path));
                ownerPaths.add(path);
            }
        }
        for (String id : List.of("previous", "next")) {
            String button = "buttons." + id;
            String entry = "entries." + id;
            if (!old.contains(button, true)) {
                if (!old.contains(entry, true)) {
                    updated.set(entry, null);
                }
                continue;
            }
            if (old.contains(entry, true)) {
                throw new IOException(MAIN + ": both " + button + " and " + entry + " are configured");
            }
            Object enabled = old.get(button + ".enabled");
            if (enabled != null && !(enabled instanceof Boolean)) {
                pluginWarning(plugin, "GUI migration " + MAIN + ":" + button
                        + ".enabled invalid " + enabled + "; using newest bundled default true");
            }
            if (Boolean.FALSE.equals(enabled)) {
                updated.set(entry, null);
                continue;
            }
            String slot = button + ".slot";
            if (!Objects.equals(old.get(slot), priorDefaults.get(slot))) {
                Object movedSlot = old.get(slot);
                if (movedSlot == null) {
                    pluginWarning(plugin, "GUI migration " + MAIN + ":" + slot
                            + " is missing; using newest bundled slot");
                } else {
                    updated.set(entry + ".slots", List.of(movedSlot));
                    ownerPaths.add(entry + ".slots");
                }
            }
            for (String field : List.of("material", "amount", "name", "lore", "custom-model-data",
                    "glow", "item-flags", "skull-skin-url")) {
                String from = button + "." + field;
                if (!Objects.equals(old.get(from), priorDefaults.get(from))) {
                    updated.set(entry + ".item." + field, old.get(from));
                    ownerPaths.add(entry + ".item." + field);
                }
                String filler = "filler." + field;
                if (!Objects.equals(old.get(filler), priorDefaults.get(filler))) {
                    updated.set(entry + ".empty-item." + field, old.get(filler));
                    ownerPaths.add(entry + ".empty-item." + field);
                }
            }
            if (!old.getBoolean("filler.enabled", true)) {
                updated.set(entry + ".empty-item", null);
            }
        }
        for (int attempt = 0; attempt < ownerPaths.size() + 1; attempt++) {
            try {
                PublicGuiSchema.parse(CoinflipGuiContracts.MAIN, MAIN, updated, CoinflipGuiContracts.main());
                return updated;
            } catch (GuiDefinitionException error) {
                String repairPath = closestRepairPath(error.key(), ownerPaths, latest);
                if (repairPath == null) {
                    throw error;
                }
                Object replacement = latest.get(repairPath);
                Object invalid = updated.get(repairPath);
                if (Objects.equals(invalid, replacement)) {
                    throw error;
                }
                pluginWarning(plugin, "GUI migration " + MAIN + ":" + repairPath + " invalid "
                        + invalid + " (" + error.getMessage() + "); using newest bundled default " + replacement);
                if (latest.isConfigurationSection(repairPath)) {
                    updated.set(repairPath, null);
                    ConfigurationSection section = latest.getConfigurationSection(repairPath);
                    for (String key : section.getKeys(true)) {
                        if (!section.isConfigurationSection(key)) {
                            updated.set(repairPath + "." + key, section.get(key));
                        }
                    }
                } else {
                    updated.set(repairPath, replacement);
                }
            }
        }
        throw new IOException(MAIN + ": public pagination migration could not isolate invalid GUI fields");
    }

    private static void installFresh(FoCoinflip plugin, FileConfiguration config, Path mainFile,
                                     Path animationFile) {
        Path mainStage = null;
        Path animationStage = null;
        byte[] originalConfig = null;
        try {
            Path configFile = plugin.getDataFolder().toPath().resolve("config.yml");
            originalConfig = Files.readAllBytes(configFile);
            mainStage = stageBundled(plugin, MAIN, mainFile);
            animationStage = stageBundled(plugin, ANIMATION, animationFile);
            PublicGuiSchema.parse(CoinflipGuiContracts.MAIN, MAIN, loadStrict(mainStage),
                    CoinflipGuiContracts.main());
            PublicGuiSchema.parse(CoinflipGuiContracts.ANIMATION, ANIMATION, loadStrict(animationStage),
                    CoinflipGuiContracts.animation());
            replace(mainStage, mainFile);
            replace(animationStage, animationFile);
            config.set("public-gui-schema", SCHEMA_VERSION);
            plugin.saveConfig();
            if (YamlConfiguration.loadConfiguration(configFile.toFile())
                    .getInt("public-gui-schema", 0) != SCHEMA_VERSION) {
                throw new IOException("Fresh GUI schema version was not persisted");
            }
        } catch (IOException | InvalidConfigurationException | GuiDefinitionException | RuntimeException failure) {
            try {
                Files.deleteIfExists(mainFile);
                Files.deleteIfExists(animationFile);
                if (originalConfig != null) {
                    restore(plugin.getDataFolder().toPath().resolve("config.yml"), originalConfig);
                    config.set("public-gui-schema", null);
                }
            } catch (IOException rollbackFailure) {
                plugin.getLogger().severe("Fresh public GUI rollback failed: " + rollbackFailure.getMessage());
            }
            plugin.getLogger().severe("Fresh public GUI installation failed: " + failure.getMessage());
        } finally {
            for (Path staged : new Path[] {mainStage, animationStage}) {
                if (staged != null) {
                    try {
                        Files.deleteIfExists(staged);
                    } catch (IOException cleanupFailure) {
                        plugin.getLogger().warning("Could not remove staged GUI file: " + cleanupFailure.getMessage());
                    }
                }
            }
        }
    }

    private static Path stageBundled(FoCoinflip plugin, String resource, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path staged = Files.createTempFile(target.getParent(), ".gui-install-", ".yml");
        try (InputStream stream = plugin.getResource(resource)) {
            if (stream == null) {
                throw new IOException("Missing bundled GUI resource " + resource);
            }
            Files.write(staged, stream.readAllBytes());
        } catch (IOException failure) {
            Files.deleteIfExists(staged);
            throw failure;
        }
        return staged;
    }

    static int legacyVersion(YamlConfiguration yaml, String path, int latestKnown) throws IOException {
        if (yaml == null || !yaml.contains("gui-version", true)) {
            return 0;
        }
        Object value = yaml.get("gui-version");
        if (!(value instanceof Integer version) || version < 0 || version > latestKnown) {
            throw new IOException(path + ": unsupported gui-version " + value
                    + "; leaving owner files unchanged");
        }
        return version;
    }

    private static YamlConfiguration oldFromConfig(FoCoinflip plugin, String baseline,
                                                    ConfigurationSection section) throws IOException {
        YamlConfiguration result = bundled(plugin, baseline);
        if (section.contains("size", true) && !section.contains("rows", true)) {
            result.set("rows", null);
        }
        for (String key : section.getKeys(true)) {
            if (!section.isConfigurationSection(key)) {
                result.set(key, section.get(key));
            }
        }
        for (Map.Entry<String, String> rename : Map.of(
                "coinflip-item-name", "coinflip-item.name", "coinflip-item-lore", "coinflip-item.lore",
                "stats-name", "items.stats.name", "stats-lore", "items.stats.lore",
                "empty-name", "empty-item.name", "empty-lore", "empty-item.lore").entrySet()) {
            if (section.contains(rename.getKey(), true)) {
                result.set(rename.getValue(), section.get(rename.getKey()));
            }
        }
        return result;
    }

    static YamlConfiguration convert(FoCoinflip plugin, YamlConfiguration old, String baseline,
                                              String target, Map<String, String> mappings, String menuId,
                                              PublicGuiContract contract) throws IOException, GuiDefinitionException {
        YamlConfiguration oldDefaults = bundled(plugin, baseline);
        YamlConfiguration latest = bundled(plugin, target);
        if (old.contains("size", true)) {
            Object sizeValue = old.get("size");
            Object rowsValue = old.get("rows");
            if (sizeValue instanceof Integer size && size >= 9 && size <= 54 && size % 9 == 0) {
                if (rowsValue == null) {
                    old.set("rows", size / 9);
                } else if (!(rowsValue instanceof Integer rows) || rows * 9 != size) {
                    pluginWarning(plugin, "GUI migration " + target + ": conflicting legacy rows/size "
                            + rowsValue + "/" + sizeValue + "; using newest bundled rows");
                    old.set("rows", latest.get("rows"));
                }
            } else {
                pluginWarning(plugin, "GUI migration " + target + ": invalid legacy size " + sizeValue
                        + "; using newest bundled rows");
                old.set("rows", latest.get("rows"));
            }
        }
        if (target.equals(MAIN)) {
            restoreArchivedNavigation(old);
        }
        for (Map.Entry<String, String> mapping : mappings.entrySet()) {
            String oldPath = mapping.getKey();
            String newPath = mapping.getValue();
            if (!old.contains(oldPath, true)) {
                if (oldDefaults.contains(oldPath, true) && optional(newPath)) {
                    latest.set(newPath, null);
                }
                continue;
            }
            Object value = old.get(oldPath);
            Object priorDefault = oldDefaults.get(oldPath);
            if (oldDefaults.contains(oldPath, true) && Objects.equals(value, priorDefault)) {
                continue;
            }
            if (newPath.equals("display-slots") || newPath.equals("entries.empty-notice.slots")
                    || newPath.equals("entries.previous.slots") || newPath.equals("entries.next.slots")) {
                value = List.of(value);
            }
            latest.set(newPath, value);
        }
        if (target.equals(MAIN) && latest.contains("entries.empty-notice.slots", true)) {
            List<?> noticeSlots = latest.getList("entries.empty-notice.slots");
            List<?> entrySlots = latest.getList("entries.coinflips.slots");
            if (noticeSlots != null && entrySlots != null && noticeSlots.stream().allMatch(Integer.class::isInstance)
                    && entrySlots.stream().allMatch(Integer.class::isInstance)
                    && entrySlots.stream().anyMatch(noticeSlots::contains)) {
                pluginWarning(plugin, "GUI migration " + target + ":entries.coinflips.slots conflicts with"
                        + " entries.empty-notice.slots " + noticeSlots + "; removing only overlapping entry slots");
                latest.set("entries.coinflips.slots",
                        entrySlots.stream().filter(slot -> !noticeSlots.contains(slot)).toList());
            }
        }
        for (String button : List.of("previous", "next", "sort", "create", "stats", "refresh")) {
            String oldPath = "items." + button;
            if (target.equals(MAIN) && !old.contains(oldPath, true)
                    && oldDefaults.contains(oldPath, true)) {
                if (button.equals("previous") || button.equals("next")) {
                    latest.set("entries." + button, null);
                } else {
                    latest.set("buttons." + button + ".enabled", false);
                }
            }
        }
        if (!old.contains("items.filler", true)
                && oldDefaults.contains("items.filler", true)) {
            latest.set("filler.enabled", false);
        }
        if (target.equals(MAIN) && !old.contains("items.entry-filler", true)
                && oldDefaults.contains("items.entry-filler", true)) {
            latest.set("entries.coinflips.empty-item", null);
            latest.set("entries.empty-notice.empty-item", null);
        }
        if (target.equals(MAIN) && old.contains("items.entry-filler", true)) {
            for (String field : List.of("material", "amount", "name", "lore", "custom-model-data",
                    "glow", "item-flags")) {
                String oldPath = "items.entry-filler." + field;
                String newPath = "entries.empty-notice.empty-item." + field;
                if (old.contains(oldPath, true)
                        && !Objects.equals(old.get(oldPath), oldDefaults.get(oldPath))) {
                    latest.set(newPath, old.get(oldPath));
                } else if (!old.contains(oldPath, true) && oldDefaults.contains(oldPath, true)
                        && optional(newPath)) {
                    latest.set(newPath, null);
                }
            }
        }
        if (target.equals(MAIN) && !old.contains("empty-item", true)
                && oldDefaults.contains("empty-item", true)) {
            latest.set("entries.empty-notice", null);
        }
        for (int attempt = 0; attempt < mappings.size() + 10; attempt++) {
            try {
                PublicGuiSchema.parse(menuId, target, latest, contract);
                return latest;
            } catch (GuiDefinitionException error) {
                String badPath = error.key();
                YamlConfiguration defaults = bundled(plugin, target);
                String repairPath = closestRepairPath(badPath, mappings.values(), defaults);
                if (repairPath == null) {
                    throw error;
                }
                Object previous = latest.get(repairPath);
                Object replacement = defaults.get(repairPath);
                if (Objects.equals(previous, replacement)) {
                    throw error;
                }
                pluginWarning(plugin, "GUI migration " + target + ":" + repairPath + " invalid "
                        + previous + " (" + error.getMessage() + "); using newest bundled default " + replacement);
                latest.set(repairPath, replacement);
            }
        }
        throw new IOException(target + ": migration could not isolate invalid GUI fields");
    }

    private static void restoreArchivedNavigation(YamlConfiguration old) {
        for (String id : List.of("previous", "next")) {
            ConfigurationSection archived = old.getConfigurationSection("legacy-navigation." + id);
            if (old.contains("items." + id, true) || archived == null) {
                continue;
            }
            for (String key : archived.getKeys(true)) {
                if (!archived.isConfigurationSection(key)) {
                    old.set("items." + id + "." + key, archived.get(key));
                }
            }
            String slotPath = "navigation." + id + "-slot";
            if (old.contains(slotPath, true)) {
                old.set("items." + id + ".slot", old.get(slotPath));
            }
        }
    }

    private static String closestRepairPath(String bad, java.util.Collection<String> mapped,
                                             YamlConfiguration defaults) {
        if (bad == null) {
            return null;
        }
        String stripped = bad.replaceAll("\\[[0-9]+]", "");
        if (mapped.contains(stripped) && defaults.contains(stripped, true)) {
            return stripped;
        }
        String path = stripped;
        while (path.contains(".")) {
            path = path.substring(0, path.lastIndexOf('.'));
            if (mapped.contains(path) && defaults.contains(path, true)) {
                return path;
            }
        }
        return null;
    }

    private static boolean optional(String path) {
        return path.endsWith(".name") || path.endsWith(".lore") || path.endsWith(".custom-model-data")
                || path.endsWith(".amount") || path.endsWith(".glow") || path.endsWith(".item-flags")
                || path.endsWith(".empty-item");
    }

    static Map<String, String> mainMappings() {
        Map<String, String> paths = new LinkedHashMap<>();
        paths.put("title", "title");
        paths.put("rows", "rows");
        paths.put("content-slots", "entries.coinflips.slots");
        paths.put("empty-item.slot", "entries.empty-notice.slots");
        paths.put("navigation.previous-slot", "entries.previous.slots");
        paths.put("navigation.next-slot", "entries.next.slots");
        mapItem(paths, "coinflip-item", "entries.coinflips.item");
        mapItem(paths, "empty-item", "entries.empty-notice.item");
        mapItem(paths, "items.filler", "filler");
        mapItem(paths, "items.entry-filler", "entries.coinflips.empty-item");
        for (String control : List.of("previous", "next")) {
            mapItem(paths, "items." + control, "entries." + control + ".item");
            paths.put("items." + control + ".slot", "entries." + control + ".slots");
        }
        for (String control : List.of("sort", "create", "stats", "refresh")) {
            mapItem(paths, "items." + control, "buttons." + control);
            paths.put("items." + control + ".slot", "buttons." + control + ".slot");
        }
        paths.put("items.sort.selected-option-format", "buttons.sort.cycle.selected-format");
        paths.put("items.sort.option-format", "buttons.sort.cycle.unselected-format");
        return paths;
    }

    static Map<String, String> animationMappings() {
        Map<String, String> paths = new LinkedHashMap<>();
        paths.put("title", "title");
        paths.put("rows", "rows");
        paths.put("result-slot", "display-slots");
        mapItem(paths, "rolling-item", "states.rolling");
        mapItem(paths, "result-item", "states.result");
        mapItem(paths, "items.filler", "filler");
        return paths;
    }

    private static void mapItem(Map<String, String> paths, String before, String after) {
        for (String field : List.of("material", "amount", "name", "lore", "custom-model-data",
                "glow", "item-flags")) {
            paths.put(before + "." + field, after + "." + field);
        }
    }

    static Map<String, String> sortLabels(YamlConfiguration old) {
        if (!old.isConfigurationSection("items.sort")) {
            return Map.of();
        }
        Object rawOptions = old.get("items.sort.options");
        if (rawOptions != null && !(rawOptions instanceof List<?>)) {
            // A malformed old list is invalid, not evidence of owner deletion.
            return Map.of();
        }
        Map<String, String> labels = new HashMap<>();
        for (String id : SORT_VALUES) {
            labels.put(id, "");
        }
        for (Map<?, ?> option : old.getMapList("items.sort.options")) {
            Object value = option.get("value");
            Object label = option.get("label");
            if (value instanceof String id && label instanceof String text && SORT_VALUES.contains(id)) {
                labels.put(id, text);
            } else if (value instanceof String id && SORT_VALUES.contains(id)) {
                // An invalid old label gets the newest bundled default.
                labels.remove(id);
            }
        }
        return Map.copyOf(labels);
    }

    private static YamlConfiguration bundled(FoCoinflip plugin, String path) throws IOException {
        try (InputStream input = plugin == null
                ? GuiConfigMigrator.class.getClassLoader().getResourceAsStream(path)
                : plugin.getResource(path)) {
            if (input == null) {
                throw new IOException("Missing bundled GUI resource " + path);
            }
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            return yaml;
        } catch (InvalidConfigurationException exception) {
            throw new IOException("Invalid bundled GUI resource " + path, exception);
        }
    }

    private static void pluginWarning(FoCoinflip plugin, String text) {
        if (plugin != null) {
            plugin.getLogger().warning(text);
        }
    }

    private static YamlConfiguration loadStrict(Path path) throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(path.toFile());
        return yaml;
    }

    static Path stage(Path target, YamlConfiguration content) throws IOException {
        Files.createDirectories(target.getParent());
        Path staged = Files.createTempFile(target.getParent(), ".gui-migration-", ".yml");
        Files.writeString(staged, content.saveToString(), StandardCharsets.UTF_8);
        return staged;
    }

    static void backup(Path file, byte[] original, int originalVersion) throws IOException {
        if (original == null) {
            return;
        }
        Path backup = file.resolveSibling(file.getFileName() + ".gui-v" + originalVersion + "-backup");
        if (Files.notExists(backup)) {
            Files.write(backup, original);
        }
    }

    static void restore(Path target, byte[] original) throws IOException {
        if (original == null) {
            Files.deleteIfExists(target);
            return;
        }
        Path staged = Files.createTempFile(target.getParent(), ".gui-rollback-", ".yml");
        Files.write(staged, original);
        replace(staged, target);
    }

    static void replace(Path staged, Path target) throws IOException {
        try {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
