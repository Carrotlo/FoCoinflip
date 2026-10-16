package me.foesio.foCoinflip.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import me.foesio.core.gui.publicgui.PublicGuiSchema;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuiConfigMigratorTest {

    @TempDir
    Path temp;

    @Test
    void upgradesV3WithCustomTextRemovedLoreAndOldDefaultReplacement() throws Exception {
        YamlConfiguration old = bundled("legacy-guis/main-v3.yml");
        old.set("items.create.name", "&dCréér {page}");
        old.set("items.create.lore", List.of());
        old.set("items.create.amount", null);
        old.set("items.stats", null);
        old.set("items.close.slot", 51);
        YamlConfiguration upgraded = main(old, "legacy-guis/main-v3.yml");

        assertEquals("&dCréér {page}", upgraded.getString("buttons.create.name"));
        assertEquals(List.of(), upgraded.getStringList("buttons.create.lore"));
        assertFalse(upgraded.contains("buttons.create.amount", true));
        assertFalse(upgraded.getBoolean("buttons.stats.enabled"));
        assertFalse(upgraded.contains("buttons.close", true));
        assertEquals(bundled("guis/main.yml").getIntegerList("entries.coinflips.slots"),
                upgraded.getIntegerList("entries.coinflips.slots"));
        PublicGuiSchema.parse(CoinflipGuiContracts.MAIN, "guis/main.yml", upgraded,
                CoinflipGuiContracts.main());
    }

    @Test
    void repairsInvalidFieldsFromNewestDefaultsWithoutLosingValidSiblings() throws Exception {
        YamlConfiguration old = bundled("legacy-guis/main-v3.yml");
        old.set("items.create.name", "&dValid custom name");
        old.set("items.create.material", "NOT_A_MATERIAL");
        old.set("items.refresh.slot", 500);
        old.set("items.previous.lore", "wrong type");
        YamlConfiguration upgraded = main(old, "legacy-guis/main-v3.yml");
        YamlConfiguration latest = bundled("guis/main.yml");

        assertEquals("&dValid custom name", upgraded.getString("buttons.create.name"));
        assertEquals(latest.getString("buttons.create.material"), upgraded.getString("buttons.create.material"));
        assertEquals(latest.getInt("buttons.refresh.slot"), upgraded.getInt("buttons.refresh.slot"));
        assertEquals(latest.getStringList("entries.previous.item.lore"),
                upgraded.getStringList("entries.previous.item.lore"));
    }

    @Test
    void customEntrySlotsKeepEveryNonConflictingPosition() throws Exception {
        YamlConfiguration old = bundled("legacy-guis/main-v3.yml");
        old.set("content-slots", List.of(10, 22, 24));
        YamlConfiguration upgraded = main(old, "legacy-guis/main-v3.yml");

        assertEquals(List.of(10, 24), upgraded.getIntegerList("entries.coinflips.slots"));
        assertEquals(List.of(22), upgraded.getIntegerList("entries.empty-notice.slots"));
        PublicGuiSchema.parse(CoinflipGuiContracts.MAIN, "guis/main.yml", upgraded,
                CoinflipGuiContracts.main());
    }

    @Test
    void removedLegacySortOptionIsCarriedAsAbsentText() {
        YamlConfiguration old = bundled("legacy-guis/main-v3.yml");
        old.set("items.sort.options", List.of(java.util.Map.of("value", "newest", "label", "Neueste")));

        assertEquals("Neueste", GuiConfigMigrator.sortLabels(old).get("newest"));
        assertEquals("", GuiConfigMigrator.sortLabels(old).get("amount-desc"));
        assertEquals("", GuiConfigMigrator.sortLabels(old).get("amount-asc"));
        assertEquals("", GuiConfigMigrator.sortLabels(old).get("oldest"));
    }

    @Test
    void convertsReleasedV0AndRetainsTranslatedAnimationState() throws Exception {
        YamlConfiguration oldMain = bundled("legacy-guis/main-v0.yml");
        oldMain.set("items.create.name", "&bNeu erstellen");
        YamlConfiguration main = main(oldMain, "legacy-guis/main-v0.yml");
        assertEquals("&bNeu erstellen", main.getString("buttons.create.name"));
        assertTrue(main.contains("buttons.sort", true));

        YamlConfiguration oldAnimation = bundled("legacy-guis/animation-v0.yml");
        oldAnimation.set("result-item.name", "&dGagnant {winner}");
        YamlConfiguration upgraded = GuiConfigMigrator.convert(null, oldAnimation,
                "legacy-guis/animation-v0.yml", "guis/animation.yml",
                GuiConfigMigrator.animationMappings(), CoinflipGuiContracts.ANIMATION,
                CoinflipGuiContracts.animation());
        assertEquals("&dGagnant {winner}", upgraded.getString("states.result.name"));
        assertEquals(List.of(13), upgraded.getIntegerList("display-slots"));
        PublicGuiSchema.parse(CoinflipGuiContracts.ANIMATION, "guis/animation.yml", upgraded,
                CoinflipGuiContracts.animation());
    }

    @Test
    void upgradesActualReleased23ShapeWithCoreOwnedPagination() throws Exception {
        YamlConfiguration old = bundled("legacy-guis/main-v2.3.yml");
        assertTrue(GuiConfigMigrator.isReleased23Main(old));
        String baseline = GuiConfigMigrator.mainBaseline(old,
                GuiConfigMigrator.legacyVersion(old, "guis/main.yml", 3));
        assertEquals("legacy-guis/main-v2.3.yml", baseline);

        YamlConfiguration upgraded = main(old, baseline);
        YamlConfiguration latest = bundled("guis/main.yml");
        assertEquals(List.of(45), upgraded.getIntegerList("entries.previous.slots"));
        assertEquals(List.of(53), upgraded.getIntegerList("entries.next.slots"));
        assertEquals(latest.getIntegerList("entries.coinflips.slots"),
                upgraded.getIntegerList("entries.coinflips.slots"));
        assertEquals(latest.getStringList("buttons.sort.lore"),
                upgraded.getStringList("buttons.sort.lore"));
        assertEquals(latest.getInt("buttons.stats.slot"), upgraded.getInt("buttons.stats.slot"));
        assertEquals(latest.getInt("buttons.refresh.slot"), upgraded.getInt("buttons.refresh.slot"));
        assertEquals(50, upgraded.getInt("buttons.stats.slot"));
        assertEquals(49, upgraded.getInt("buttons.refresh.slot"));
        GuiConfigMigrator.validateMigratedNavigation(old, PublicGuiSchema.parse(CoinflipGuiContracts.MAIN,
                "guis/main.yml", upgraded, CoinflipGuiContracts.main()));
    }

    @Test
    void released23MigrationKeepsOwnerChangesAndRepairsOnlyInvalidMaterial() throws Exception {
        YamlConfiguration old = bundled("legacy-guis/main-v2.3.yml");
        old.set("title", "&8Übersetzte Münze");
        old.set("navigation.previous-slot", 46);
        old.set("items.sort.name", "&dSortierung");
        old.set("items.create.lore", List.of());
        old.set("items.stats.name", "&bStatistik");
        old.set("items.stats.material", "NOT_A_REAL_MATERIAL");
        YamlConfiguration upgraded = main(old, GuiConfigMigrator.mainBaseline(old, 0));

        assertEquals("&8Übersetzte Münze", upgraded.getString("title"));
        assertEquals(List.of(46), upgraded.getIntegerList("entries.previous.slots"));
        assertEquals("&dSortierung", upgraded.getString("buttons.sort.name"));
        assertEquals(List.of(), upgraded.getStringList("buttons.create.lore"));
        assertEquals("&bStatistik", upgraded.getString("buttons.stats.name"));
        assertEquals(bundled("guis/main.yml").getString("buttons.stats.material"),
                upgraded.getString("buttons.stats.material"));
        GuiConfigMigrator.validateMigratedNavigation(old, PublicGuiSchema.parse(CoinflipGuiContracts.MAIN,
                "guis/main.yml", upgraded, CoinflipGuiContracts.main()));
    }

    @Test
    void missingExistingNavigationIsRejectedBeforeMigrationCanAdvanceItsSchema() throws Exception {
        YamlConfiguration old = bundled("legacy-guis/main-v2.3.yml");
        YamlConfiguration upgraded = main(old, "legacy-guis/main-v2.3.yml");
        upgraded.set("entries.previous", null);
        assertThrows(me.foesio.core.gui.publicgui.GuiDefinitionException.class,
                () -> GuiConfigMigrator.validateMigratedNavigation(old, PublicGuiSchema.parse(
                        CoinflipGuiContracts.MAIN, "guis/main.yml", upgraded, CoinflipGuiContracts.main())));
    }

    @Test
    void intentionallyRemovedOldNavigationRemainsAbsent() throws Exception {
        YamlConfiguration old = bundled("legacy-guis/main-v3.yml");
        old.set("items.previous", null);
        YamlConfiguration upgraded = main(old, "legacy-guis/main-v3.yml");
        assertFalse(upgraded.contains("entries.previous", true));
        GuiConfigMigrator.validateMigratedNavigation(old, PublicGuiSchema.parse(
                CoinflipGuiContracts.MAIN, "guis/main.yml", upgraded, CoinflipGuiContracts.main()));
    }

    @Test
    void removedLegacyOptionalEmptyNoticeStaysAbsent() throws Exception {
        YamlConfiguration old = bundled("legacy-guis/main-v3.yml");
        old.set("empty-item", null);
        old.set("items.entry-filler", null);
        YamlConfiguration upgraded = main(old, "legacy-guis/main-v3.yml");

        assertFalse(upgraded.contains("entries.empty-notice", true));
        assertFalse(upgraded.contains("entries.coinflips.empty-item", true));
    }

    @Test
    void removedAnimationFillerStaysDisabled() throws Exception {
        YamlConfiguration old = bundled("legacy-guis/animation-v1.yml");
        old.set("items.filler", null);
        YamlConfiguration upgraded = GuiConfigMigrator.convert(null, old,
                "legacy-guis/animation-v1.yml", "guis/animation.yml",
                GuiConfigMigrator.animationMappings(), CoinflipGuiContracts.ANIMATION,
                CoinflipGuiContracts.animation());

        assertFalse(upgraded.getBoolean("filler.enabled"));
        PublicGuiSchema.parse(CoinflipGuiContracts.ANIMATION, "guis/animation.yml", upgraded,
                CoinflipGuiContracts.animation());
    }

    @Test
    void unknownOrMalformedOldSchemaDoesNotAssumeAReleasedShape() {
        YamlConfiguration old = bundled("legacy-guis/main-v3.yml");
        old.set("gui-version", 4);
        assertThrows(java.io.IOException.class,
                () -> GuiConfigMigrator.legacyVersion(old, "guis/main.yml", 3));
        old.set("gui-version", "v3");
        assertThrows(java.io.IOException.class,
                () -> GuiConfigMigrator.legacyVersion(old, "guis/main.yml", 3));
    }

    @Test
    void archivedNavigationAndCustomEntryFillerCarryForward() throws Exception {
        YamlConfiguration old = bundled("legacy-guis/main-v3.yml");
        old.set("items.previous", null);
        old.set("legacy-navigation.previous.material", "OAK_BUTTON");
        old.set("legacy-navigation.previous.name", "&dVorige pagina");
        old.set("legacy-navigation.previous.lore", List.of("&7Terug"));
        old.set("navigation.previous-slot", 46);
        old.set("items.entry-filler.name", "&bLeeg");
        YamlConfiguration upgraded = main(old, "legacy-guis/main-v3.yml");

        assertEquals("&dVorige pagina", upgraded.getString("entries.previous.item.name"));
        assertEquals(List.of(46), upgraded.getIntegerList("entries.previous.slots"));
        assertEquals("&bLeeg", upgraded.getString("entries.coinflips.empty-item.name"));
        assertEquals("&bLeeg", upgraded.getString("entries.empty-notice.empty-item.name"));
    }

    @Test
    void publicButtonMigrationKeepsOwnerControlsAndUsesFillerWhenUnavailable() throws Exception {
        YamlConfiguration old = bundled("legacy-guis/main-public-v1.yml");
        old.set("buttons.previous.slot", 46);
        old.set("buttons.previous.name", "&dVorige pagina");
        old.set("buttons.previous.lore", List.of("&7Terug"));
        old.set("buttons.next", null);
        old.set("filler.material", "BLACK_STAINED_GLASS_PANE");
        YamlConfiguration updated = GuiConfigMigrator.migratePublicPagination(null, old);

        assertFalse(updated.contains("buttons.previous", true));
        assertFalse(updated.contains("buttons.next", true));
        assertEquals(List.of(46), updated.getIntegerList("entries.previous.slots"));
        assertEquals("&dVorige pagina", updated.getString("entries.previous.item.name"));
        assertEquals(List.of("&7Terug"), updated.getStringList("entries.previous.item.lore"));
        assertEquals("BLACK_STAINED_GLASS_PANE",
                updated.getString("entries.previous.empty-item.material"));
        assertFalse(updated.contains("entries.next", true));
        PublicGuiSchema.parse(CoinflipGuiContracts.MAIN, "guis/main.yml", updated,
                CoinflipGuiContracts.main());

        YamlConfiguration untouched = bundled("legacy-guis/main-public-v1.yml");
        YamlConfiguration defaults = GuiConfigMigrator.migratePublicPagination(null, untouched);
        assertEquals(bundled("guis/main.yml").getStringList("entries.previous.item.lore"),
                defaults.getStringList("entries.previous.item.lore"));
    }

    @Test
    void publicButtonMigrationRepairsInvalidItemFieldAndKeepsTranslatedSibling() throws Exception {
        YamlConfiguration old = bundled("legacy-guis/main-public-v1.yml");
        old.set("buttons.previous.material", "NOT_A_MATERIAL");
        old.set("buttons.previous.name", "&dVorige pagina");
        YamlConfiguration updated = GuiConfigMigrator.migratePublicPagination(null, old);

        assertEquals("ARROW", updated.getString("entries.previous.item.material"));
        assertEquals("&dVorige pagina", updated.getString("entries.previous.item.name"));
        PublicGuiSchema.parse(CoinflipGuiContracts.MAIN, "guis/main.yml", updated,
                CoinflipGuiContracts.main());
    }

    @Test
    void publicButtonMigrationKeepsEmptyLoreAndDisabledNavigation() throws Exception {
        YamlConfiguration old = bundled("legacy-guis/main-public-v1.yml");
        old.set("buttons.previous.lore", List.of());
        old.set("buttons.previous.name", "&dNo sprite");
        old.set("buttons.next.enabled", false);
        YamlConfiguration updated = GuiConfigMigrator.migratePublicPagination(null, old);

        assertEquals(List.of(), updated.getStringList("entries.previous.item.lore"));
        assertEquals("&dNo sprite", updated.getString("entries.previous.item.name"));
        assertFalse(updated.contains("entries.next", true));
    }

    @Test
    void v2KnownDefaultsReceiveNewCycleStyleAndCloseControlIsRemoved() throws Exception {
        YamlConfiguration old = bundled("legacy-guis/main-v2.yml");
        YamlConfiguration upgraded = main(old, "legacy-guis/main-v2.yml");
        YamlConfiguration latest = bundled("guis/main.yml");

        assertEquals(latest.getStringList("buttons.sort.lore"), upgraded.getStringList("buttons.sort.lore"));
        assertEquals(latest.getString("buttons.sort.cycle.selected-format"),
                upgraded.getString("buttons.sort.cycle.selected-format"));
        assertEquals(latest.getInt("buttons.stats.slot"), upgraded.getInt("buttons.stats.slot"));
        assertEquals(latest.getInt("buttons.refresh.slot"), upgraded.getInt("buttons.refresh.slot"));
        assertFalse(upgraded.contains("buttons.close", true));
    }

    @Test
    void legacySizeConvertsToRowsAndConflictsUseNewestDefault() throws Exception {
        YamlConfiguration old = bundled("legacy-guis/animation-v0.yml");
        old.set("rows", null);
        old.set("size", 18);
        YamlConfiguration upgraded = GuiConfigMigrator.convert(null, old,
                "legacy-guis/animation-v0.yml", "guis/animation.yml",
                GuiConfigMigrator.animationMappings(), CoinflipGuiContracts.ANIMATION,
                CoinflipGuiContracts.animation());
        assertEquals(2, upgraded.getInt("rows"));

        old = bundled("legacy-guis/main-v0.yml");
        old.set("rows", 4);
        old.set("size", 45);
        upgraded = main(old, "legacy-guis/main-v0.yml");
        assertEquals(bundled("guis/main.yml").getInt("rows"), upgraded.getInt("rows"));
    }

    @Test
    void backupRetainsExactPreUpgradeBytesAndRollbackRestoresActiveFile() throws Exception {
        Path active = temp.resolve("guis/main.yml");
        Files.createDirectories(active.getParent());
        byte[] original = "title: '&dÄlter'\n# owner's spacing\n".getBytes(StandardCharsets.UTF_8);
        Files.write(active, original);
        YamlConfiguration replacement = bundled("guis/main.yml");
        Path staged = GuiConfigMigrator.stage(active, replacement);
        GuiConfigMigrator.backup(active, original, 3);
        GuiConfigMigrator.replace(staged, active);
        assertTrue(Files.size(active) > original.length);
        org.junit.jupiter.api.Assertions.assertArrayEquals(original,
                Files.readAllBytes(active.resolveSibling("main.yml.gui-v3-backup")));
        GuiConfigMigrator.restore(active, original);
        org.junit.jupiter.api.Assertions.assertArrayEquals(original, Files.readAllBytes(active));
    }

    private static YamlConfiguration main(YamlConfiguration old, String baseline) throws Exception {
        return GuiConfigMigrator.convert(null, old, baseline, "guis/main.yml",
                GuiConfigMigrator.mainMappings(), CoinflipGuiContracts.MAIN, CoinflipGuiContracts.main());
    }

    private static YamlConfiguration bundled(String path) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                GuiConfigMigratorTest.class.getClassLoader().getResourceAsStream(path),
                StandardCharsets.UTF_8));
    }
}
