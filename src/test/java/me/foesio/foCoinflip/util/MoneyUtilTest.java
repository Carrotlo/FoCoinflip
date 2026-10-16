package me.foesio.foCoinflip.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class MoneyUtilTest {

    @Test
    void ownerControlsLocaleSuffixAndPhraseOrder() {
        YamlConfiguration presentation = new YamlConfiguration();
        presentation.set("money-format.locale", "nl-NL");
        presentation.set("money-format.standard-pattern", "#,##0.00");
        presentation.set("money-format.compact-base-pattern", "0.##");
        presentation.set("money-format.compact-pattern", "0.00");
        presentation.set("money-format.compact-template", "{suffix} {value}");
        presentation.set("money-format.suffixes", List.of("duizend", "miljoen"));

        assertEquals("duizend 1,50", MoneyUtil.format(1500, true, presentation));
        assertEquals("miljoen 1,00", MoneyUtil.format(999_995, true, presentation));
        assertEquals("1.500,00", MoneyUtil.format(1500, false, presentation));
        presentation.set("money-format.suffixes", List.of());
        assertEquals("1.500,00", MoneyUtil.format(1500, true, presentation));
    }
}
