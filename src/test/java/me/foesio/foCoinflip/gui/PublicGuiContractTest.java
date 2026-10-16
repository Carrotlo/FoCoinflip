package me.foesio.foCoinflip.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import me.foesio.core.gui.publicgui.GuiSnapshot;
import me.foesio.core.gui.publicgui.GuiAtomicCache;
import me.foesio.core.gui.publicgui.ClickKind;
import me.foesio.core.gui.publicgui.PublicGuiDefinition;
import me.foesio.core.gui.publicgui.PublicGuiFrame;
import me.foesio.core.gui.publicgui.PublicGuiPlanner;
import me.foesio.core.gui.publicgui.PublicGuiRenderInput;
import me.foesio.core.gui.publicgui.PublicGuiSchema;
import me.foesio.core.gui.publicgui.PublicGuiStartupFallback;
import me.foesio.core.gui.publicgui.PublicGuiText;
import me.foesio.foCoinflip.config.CoinflipGuiContracts;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublicGuiContractTest {

    @TempDir
    Path temp;

    @Test
    void generatedMenusParseAndExposeTheGameplayPaths() throws Exception {
        PublicGuiDefinition main = PublicGuiSchema.parse(CoinflipGuiContracts.MAIN, "guis/main.yml",
                bundled("guis/main.yml"), CoinflipGuiContracts.main());
        PublicGuiDefinition animation = PublicGuiSchema.parse(CoinflipGuiContracts.ANIMATION,
                "guis/animation.yml", bundled("guis/animation.yml"), CoinflipGuiContracts.animation());

        assertEquals(6, main.rows());
        assertEquals(3, animation.rows());
        assertEquals(List.of(13), animation.requireGroup("display-slots"));
        assertTrue(animation.states().containsKey("rolling"));
        assertTrue(animation.states().containsKey("result"));
        assertFalse(main.buttons().containsKey("close"));
        PublicGuiFrame freshFrame = PublicGuiPlanner.plan(new GuiSnapshot<>(1,
                Map.of(CoinflipGuiContracts.MAIN, main)), CoinflipGuiContracts.MAIN, input(Map.of()));
        assertNull(freshFrame.bindings().get(51));
        assertEquals("coinflip.join", main.entries().get("coinflips").actions().values().iterator().next());
        assertEquals("{good}Mila won!", PublicGuiText.scalar(animation.states().get("result").name(),
                Map.of("winner", "Mila"), "result name"));
        assertTrue(PublicGuiText.lore(animation.states().get("result").lore(),
                Map.of("winner", "Mila", "loser", "Zoë", "pot", "20", "tax", "0", "payout", "20"),
                null, null, "result lore").stream().anyMatch(line -> line.contains("Zoë")));
    }

    @Test
    void movedControlAndOwnerTextReachThePlannedItemAndAction() throws Exception {
        YamlConfiguration owner = bundled("guis/main.yml");
        owner.set("buttons.create.slot", 51);
        owner.set("buttons.create.name", ":book: &dCréér {page}");
        owner.set("buttons.create.lore", List.of("&bLigne {pages}"));
        PublicGuiDefinition definition = PublicGuiSchema.parse(CoinflipGuiContracts.MAIN, "guis/main.yml",
                owner, CoinflipGuiContracts.main());
        PublicGuiFrame frame = PublicGuiPlanner.plan(new GuiSnapshot<>(7,
                Map.of(CoinflipGuiContracts.MAIN, definition)), CoinflipGuiContracts.MAIN,
                input(Map.of("page", "2", "pages", "3")));

        assertEquals("coinflip.create", frame.bindings().get(51).action());
        assertNull(frame.bindings().get(48));
        assertEquals(":book: &dCréér 2", PublicGuiText.scalar(frame.visuals().get(51).fixed().name(),
                frame.visuals().get(51).placeholders(), "name"));
        assertEquals(List.of("&bLigne 3"), PublicGuiText.lore(frame.visuals().get(51).fixed().lore(),
                frame.visuals().get(51).placeholders(), null, null, "lore"));
    }

    @Test
    void removedLoreSpriteAndDisabledControlStayRemoved() throws Exception {
        YamlConfiguration owner = bundled("guis/main.yml");
        owner.set("buttons.create.name", "&dCreate");
        owner.set("buttons.create.lore", List.of());
        owner.set("buttons.stats.enabled", false);
        PublicGuiDefinition definition = PublicGuiSchema.parse(CoinflipGuiContracts.MAIN, "guis/main.yml",
                owner, CoinflipGuiContracts.main());
        PublicGuiFrame frame = PublicGuiPlanner.plan(new GuiSnapshot<>(8,
                Map.of(CoinflipGuiContracts.MAIN, definition)), CoinflipGuiContracts.MAIN,
                input(Map.of()));

        assertEquals("&dCreate", frame.visuals().get(48).fixed().name());
        assertTrue(frame.visuals().get(48).fixed().lore().isEmpty());
        assertNull(frame.bindings().get(50));
    }

    @Test
    void removedOptionalBrowserButtonsDoNotBreakOpeningOrLeaveClickTargets() throws Exception {
        YamlConfiguration owner = bundled("guis/main.yml");
        owner.set("entries.previous", null);
        owner.set("entries.next", null);
        owner.set("buttons.sort", null);
        PublicGuiDefinition definition = PublicGuiSchema.parse(CoinflipGuiContracts.MAIN,
                "guis/main.yml", owner, CoinflipGuiContracts.main());
        assertFalse(definition.entries().containsKey("previous"));
        assertFalse(definition.entries().containsKey("next"));
        assertFalse(GuiManager.buttonEnabled(definition, "sort"));

        PublicGuiFrame frame = PublicGuiPlanner.plan(new GuiSnapshot<>(11,
                Map.of(CoinflipGuiContracts.MAIN, definition)), CoinflipGuiContracts.MAIN,
                new PublicGuiRenderInput(Map.of("page", "1", "pages", "1"), Map.of(), Map.of(), Map.of()));
        assertNull(frame.bindings().get(45));
        assertNull(frame.bindings().get(53));
        assertNull(frame.bindings().get(47));
        assertEquals("coinflip.create", frame.bindings().get(48).action());
    }

    @Test
    void pageArrowsUseCoreStyleAndEmptyNavigationSlotsUseFiller() throws Exception {
        PublicGuiDefinition definition = PublicGuiSchema.parse(CoinflipGuiContracts.MAIN, "guis/main.yml",
                bundled("guis/main.yml"), CoinflipGuiContracts.main());
        assertEquals(":arrow: {theme}&lPREVIOUS PAGE", definition.entries().get("previous").item().name());
        assertEquals(":arrow: {theme}&lNEXT PAGE", definition.entries().get("next").item().name());
        assertEquals("&eℹ Information ↓", definition.entries().get("previous").item().lore().get(2));

        for (int page = 0; page < 3; page++) {
            Map<String, Map<Integer, PublicGuiRenderInput.EntryValue>> regions =
                    GuiManager.pageRegions(definition, page, 2);
            PublicGuiFrame frame = PublicGuiPlanner.plan(new GuiSnapshot<>(12,
                    Map.of(CoinflipGuiContracts.MAIN, definition)), CoinflipGuiContracts.MAIN,
                    new PublicGuiRenderInput(Map.of("page", String.valueOf(page + 1), "pages", "3"),
                            Map.of(), Map.of("sort", new PublicGuiText.CycleSelection(List.of("Newest"), 0)),
                            regions));
            for (String id : List.of("previous", "next")) {
                int slot = definition.entries().get(id).slots().get(0);
                boolean available = id.equals("previous") ? page > 0 : page < 2;
                assertEquals(available ? "entries." + id : "entries." + id + ".empty-item",
                        frame.visuals().get(slot).path());
                assertEquals(available ? Material.ARROW : Material.GRAY_STAINED_GLASS_PANE,
                        frame.visuals().get(slot).fixed().material());
                assertEquals(available, frame.bindings().containsKey(slot));
                if (available) {
                    assertEquals("coinflip." + id, frame.bindings().get(slot).actionFor(ClickKind.LEFT));
                }
            }
        }
        assertTrue(GuiManager.pageRegions(definition, 0, 0).isEmpty());
    }

    @Test
    void dynamicEntryClickUsesTheDisplayedIdAndCycleLabelsFollowOwnerText() throws Exception {
        PublicGuiDefinition definition = PublicGuiSchema.parse(CoinflipGuiContracts.MAIN, "guis/main.yml",
                bundled("guis/main.yml"), CoinflipGuiContracts.main());
        int slot = definition.entries().get("coinflips").slots().get(0);
        PublicGuiRenderInput base = input(Map.of());
        PublicGuiRenderInput values = new PublicGuiRenderInput(base.placeholders(), base.buttonStates(),
                base.cycles(), Map.of("coinflips", Map.of(slot,
                        new PublicGuiRenderInput.EntryValue("uuid-of-visible-flip", null,
                                Map.of("player", "Zoë", "amount", "1,50")))));
        PublicGuiFrame frame = PublicGuiPlanner.plan(new GuiSnapshot<>(9,
                Map.of(CoinflipGuiContracts.MAIN, definition)), CoinflipGuiContracts.MAIN, values);

        assertEquals("uuid-of-visible-flip", frame.bindings().get(slot).entryId());
        assertEquals("coinflip.join", frame.bindings().get(slot).actions().values().iterator().next());
        assertEquals("{theme}Zoë's Coinflip", PublicGuiText.scalar(frame.visuals().get(slot).fixed().name(),
                frame.visuals().get(slot).placeholders(), "name"));
        assertNull(frame.bindings().get(definition.entries().get("coinflips").slots().get(1)));
        List<String> sortLore = PublicGuiText.lore(frame.visuals().get(47).fixed().lore(),
                frame.visuals().get(47).placeholders(), frame.visuals().get(47).cycle(),
                frame.visuals().get(47).selection(), "sort lore");
        assertTrue(sortLore.stream().anyMatch(line -> line.contains("Newest")));
    }

    @Test
    void sortWithNoConfiguredOptionsHasNoAction() throws Exception {
        PublicGuiDefinition definition = PublicGuiSchema.parse(CoinflipGuiContracts.MAIN, "guis/main.yml",
                bundled("guis/main.yml"), CoinflipGuiContracts.main());
        PublicGuiRenderInput base = input(Map.of());
        PublicGuiRenderInput emptySort = new PublicGuiRenderInput(base.placeholders(),
                Map.of("sort", "unavailable"),
                Map.of("sort", new PublicGuiText.CycleSelection(List.of(), -1)), Map.of());
        PublicGuiFrame frame = PublicGuiPlanner.plan(new GuiSnapshot<>(10,
                Map.of(CoinflipGuiContracts.MAIN, definition)), CoinflipGuiContracts.MAIN, emptySort);

        assertNull(frame.bindings().get(47));
        assertEquals("{muted}Sort Unavailable", frame.visuals().get(47).fixed().name());
    }

    @Test
    void coldStartRepairsOnlyInvalidFieldInMemoryAndReloadKeepsPriorSnapshot() throws Exception {
        YamlConfiguration bundled = bundled("guis/main.yml");
        YamlConfiguration owner = bundled("guis/main.yml");
        owner.set("rows", 9);
        owner.set("buttons.create.name", "&dCréér");
        String before = owner.saveToString();
        Path ownerFile = temp.resolve("guis/owner-main.yml");
        Files.createDirectories(ownerFile.getParent());
        Files.writeString(ownerFile, before, StandardCharsets.UTF_8);
        PublicGuiDefinition loaded = PublicGuiStartupFallback.parse(CoinflipGuiContracts.MAIN,
                "guis/main.yml", owner, bundled, CoinflipGuiContracts.main(), replacement -> { });
        assertEquals(6, loaded.rows());
        assertEquals("&dCréér", loaded.buttons().get("create").item().name());
        assertEquals(before, Files.readString(ownerFile, StandardCharsets.UTF_8));

        Path installed = temp.resolve("guis/main.yml");
        Files.createDirectories(installed.getParent());
        Files.writeString(installed, bundled.saveToString(), StandardCharsets.UTF_8);
        GuiAtomicCache<PublicGuiDefinition> cache = new GuiAtomicCache<>(
                Map.of(CoinflipGuiContracts.MAIN, installed),
                (id, file, yaml) -> PublicGuiSchema.parse(id, file, yaml, CoinflipGuiContracts.main()),
                ignored -> List.of());
        assertTrue(cache.reload().successful());
        long revision = cache.snapshot().revision();
        Files.writeString(installed, "rows: 9\n", StandardCharsets.UTF_8);
        assertFalse(cache.reload().successful());
        assertEquals(revision, cache.snapshot().revision());
        assertEquals(6, cache.snapshot().require(CoinflipGuiContracts.MAIN).rows());
    }

    private static PublicGuiRenderInput input(Map<String, String> custom) {
        java.util.HashMap<String, String> tokens = new java.util.HashMap<>(Map.of(
                "page", "1", "pages", "1", "won", "0", "lost", "0", "sum", "0"));
        tokens.putAll(custom);
        return new PublicGuiRenderInput(tokens, Map.of(),
                Map.of("sort", new PublicGuiText.CycleSelection(List.of("Highest", "Newest"), 1)),
                Map.of());
    }

    private static YamlConfiguration bundled(String path) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                PublicGuiContractTest.class.getClassLoader().getResourceAsStream(path),
                StandardCharsets.UTF_8));
    }
}
