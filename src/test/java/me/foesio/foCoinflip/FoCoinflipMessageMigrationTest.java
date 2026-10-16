package me.foesio.foCoinflip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStreamReader;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FoCoinflipMessageMigrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void releasedMessageDefaultsUpgradeOnceWithoutChangingCustomOrRemovedText() {
        YamlConfiguration current = bundled("messages.yml");
        YamlConfiguration defaults = bundled("legacy-messages-v1.5.yml");
        defaults.set("tokens.prefix", defaults.getString("prefix"));
        defaults.set("prefix", null);
        assertTrue(FoCoinflip.upgradeKnownPublicDefaults(defaults, current));
        for (String path : List.of("tokens.prefix", "flip-created", "flip-deleted", "flip-win",
                "flip-lose", "create-command-hint", "admin-reload", "editor-opened", "prompt-saved",
                "prompt-invalid")) {
            assertEquals(current.getString(path), defaults.getString(path), path);
        }
        assertFalse(FoCoinflip.upgradeKnownPublicDefaults(defaults, current));

        YamlConfiguration owner = bundled("legacy-messages-v1.5.yml");
        owner.set("tokens.prefix", owner.getString("prefix"));
        owner.set("prefix", null);
        owner.set("flip-created", "{prefix}&dMünze erstellt {amount}");
        owner.set("flip-deleted", null);

        assertTrue(FoCoinflip.upgradeKnownPublicDefaults(owner, current));
        assertEquals("{prefix}&dMünze erstellt {amount}", owner.getString("flip-created"));
        assertFalse(owner.contains("flip-deleted", true));
        for (String path : List.of("tokens.prefix", "flip-win", "flip-lose", "create-command-hint",
                "admin-reload", "editor-opened", "prompt-saved", "prompt-invalid")) {
            assertEquals(current.getString(path), owner.getString(path), path);
        }
        assertTrue(owner.getString("flip-win").contains(":emerald:"));
        assertTrue(owner.getString("create-command-hint").contains("1Qa"));
        assertFalse(FoCoinflip.upgradeKnownPublicDefaults(owner, current));
    }

    @Test
    void translatedMessageListsRemainValidButSortLabelsNeedScalarText() {
        assertTrue(FoCoinflip.validPublicTextValue("flip-win", List.of("&dGewonnen", "&7Betrag {payout}")));
        assertFalse(FoCoinflip.validPublicTextValue("sort-options.newest", List.of("Neueste")));
        assertFalse(FoCoinflip.validPublicTextValue("money-format.standard-pattern", "0.0.0"));
    }

    @Test
    void compactFormattingDefaultChangesOnlyForKnownReleasedConfig() {
        YamlConfiguration old = bundled("legacy-config-v1.5.yml");
        assertTrue(FoCoinflip.isReleasedCompactMoneyDefault(old));
        old.set("config-version", 2.4);
        assertFalse(FoCoinflip.isReleasedCompactMoneyDefault(old));
        old.set("config-version", 1.5);
        old.set("settings.compact-money-format", true);
        assertFalse(FoCoinflip.isReleasedCompactMoneyDefault(old));
        old.set("settings.compact-money-format", null);
        assertFalse(FoCoinflip.isReleasedCompactMoneyDefault(old));
    }

    @Test
    void failedTextUpgradeRestoresExactActiveBytes() throws Exception {
        Path active = temporaryDirectory.resolve("messages.yml");
        byte[] original = "flip-win: '&dGewonnen'\n# owner's spacing\n".getBytes(StandardCharsets.UTF_8);
        Files.writeString(active, "flip-win: 'changed'\n", StandardCharsets.UTF_8);

        FoCoinflip.restoreAtomically(active, original);

        assertArrayEquals(original, Files.readAllBytes(active));
    }

    @Test
    void configBackupPrecedesMigrationAndIsNeverOverwritten() throws Exception {
        Path config = temporaryDirectory.resolve("config.yml");
        byte[] original = "config-version: 1.5\n# owner's note\n".getBytes(StandardCharsets.UTF_8);
        Files.write(config, original);
        FoCoinflip.backupPublicConfig(config, false, false);
        Path backup = temporaryDirectory.resolve("config.yml.gui-v0-backup");
        assertArrayEquals(original, Files.readAllBytes(backup));

        Files.writeString(config, "public-gui-schema: 1\npublic-text-schema: 1\n");
        FoCoinflip.backupPublicConfig(config, false, false);
        assertArrayEquals(original, Files.readAllBytes(backup));
        FoCoinflip.backupPublicConfig(config, true, true);
        assertArrayEquals(original, Files.readAllBytes(backup));
        Files.delete(backup);
        Files.createDirectory(backup);
        assertThrows(java.io.IOException.class, () -> FoCoinflip.backupPublicConfig(config, false, false));
    }

    private static YamlConfiguration bundled(String path) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                FoCoinflipMessageMigrationTest.class.getClassLoader().getResourceAsStream(path),
                StandardCharsets.UTF_8));
    }
}
