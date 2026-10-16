package me.foesio.foCoinflip.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoinflipSoundOptionsTest {

    @TempDir
    Path tempDir;

    @Test
    void soundDefaultsRequireFreshOrProvenLegacyInstallation() throws IOException {
        assertTrue(CoinflipSoundOptions.shouldInstallBundled(tempDir.toFile(), false, false, false));
        assertFalse(CoinflipSoundOptions.shouldInstallBundled(tempDir.toFile(), true, false, false));
        assertTrue(CoinflipSoundOptions.shouldInstallBundled(tempDir.toFile(), true, false, true));
        Path guis = Files.createDirectories(tempDir.resolve("guis"));
        Files.writeString(guis.resolve("main.yml"),
                "content-slots: [0, 1]\ncoinflip-item:\n  material: PLAYER_HEAD\n");
        Files.writeString(guis.resolve("animation.yml"),
                "result-slot: 13\nrolling-item:\n  material: PLAYER_HEAD\n");
        assertTrue(CoinflipSoundOptions.shouldInstallBundled(tempDir.toFile(), true, false, false));
        Files.writeString(guis.resolve("main.yml"), "entries:\n  coinflips:\n    slots: [0, 1]\n");
        assertFalse(CoinflipSoundOptions.shouldInstallBundled(tempDir.toFile(), true, false, false));
        assertFalse(CoinflipSoundOptions.shouldInstallBundled(tempDir.toFile(), true, true, true));
        assertFalse(CoinflipSoundOptions.shouldInstallBundled(tempDir.toFile(), true, false, true));
        Files.writeString(tempDir.resolve("sounds.yml"), "# disabled by owner\n");
        assertFalse(CoinflipSoundOptions.shouldInstallBundled(tempDir.toFile(), true, false, true));
    }

    @Test
    void releasedGuiShapesAreRecognizedBeforeTheirMigration() throws IOException {
        Path guis = Files.createDirectories(tempDir.resolve("guis"));
        for (String mainVersion : new String[] {"v0", "v2", "v3"}) {
            try (var main = getClass().getClassLoader()
                    .getResourceAsStream("legacy-guis/main-" + mainVersion + ".yml");
                 var animation = getClass().getClassLoader()
                    .getResourceAsStream("legacy-guis/animation-v0.yml")) {
                Files.copy(main, guis.resolve("main.yml"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.copy(animation, guis.resolve("animation.yml"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            assertTrue(CoinflipSoundOptions.shouldInstallBundled(tempDir.toFile(), true, false, false),
                    mainVersion + " must install sounds before the GUI converter replaces the old files");
        }
    }

    @Test
    void bundledDottedSoundSectionExposesTheVariation() {
        YamlConfiguration bundled = YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("sounds.yml"), StandardCharsets.UTF_8));
        assertEquals(0.04F, CoinflipSoundOptions.cycleVariation(bundled, bundled, false));
    }

    @Test
    void ownerCanRemoveVariationAndInvalidStartupUsesBundledFieldOnly() {
        YamlConfiguration bundled = new YamlConfiguration();
        bundled.set("gui.cycle.pitch-variation", 0.04);
        YamlConfiguration owner = new YamlConfiguration();
        assertEquals(0.0F, CoinflipSoundOptions.cycleVariation(owner, bundled, true));
        owner.set("gui.cycle.pitch-variation", 0.12);
        assertEquals(0.12F, CoinflipSoundOptions.cycleVariation(owner, bundled, true));
        owner.set("gui.cycle.pitch-variation", "invalid");
        assertEquals(0.04F, CoinflipSoundOptions.cycleVariation(owner, bundled, true));
        assertThrows(IllegalArgumentException.class,
                () -> CoinflipSoundOptions.cycleVariation(owner, bundled, false));
        assertEquals("invalid", owner.getString("gui.cycle.pitch-variation"));
    }
}
