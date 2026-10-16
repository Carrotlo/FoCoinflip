package me.foesio.foCoinflip.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ConfiguredPublicMessagesTest {

    @Test
    void removedAndEmptyMessagesDoNotReachCoresMissingKeyFallback() {
        YamlConfiguration owner = new YamlConfiguration();
        assertFalse(ConfiguredPublicMessages.hasText(owner, "flip-win"));
        owner.set("flip-win", "");
        assertFalse(ConfiguredPublicMessages.hasText(owner, "flip-win"));
        owner.set("flip-win", List.of());
        assertFalse(ConfiguredPublicMessages.hasText(owner, "flip-win"));
        owner.set("flip-win", "&dGewonnen {payout}");
        assertTrue(ConfiguredPublicMessages.hasText(owner, "flip-win"));
    }
}
