package me.foesio.foCoinflip.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;
import me.foesio.core.number.LargeNumberParser;
import org.bukkit.configuration.file.FileConfiguration;

public final class MoneyUtil {

    private MoneyUtil() {
    }

    /** All player-visible separators, compact units, and ordering come from messages.yml. */
    public static String format(double amount, boolean compact, FileConfiguration presentation) {
        if (!Double.isFinite(amount)) {
            return BigDecimal.ZERO.toPlainString();
        }
        String localeTag = presentation.getString("money-format.locale");
        String standardPattern = presentation.getString("money-format.standard-pattern");
        String compactBasePattern = presentation.getString("money-format.compact-base-pattern");
        String compactPattern = presentation.getString("money-format.compact-pattern");
        List<String> suffixes = presentation.getStringList("money-format.suffixes");
        String template = presentation.getString("money-format.compact-template");
        Locale locale = localeTag == null || localeTag.isBlank()
                ? Locale.ROOT : Locale.forLanguageTag(localeTag);
        if (compact && Math.abs(amount) < 1000) {
            return decimal(amount, compactBasePattern, locale);
        }
        if (!compact || suffixes.isEmpty() || template == null) {
            return decimal(amount, standardPattern, locale);
        }
        BigDecimal scaled = BigDecimal.valueOf(Math.abs(amount));
        BigDecimal thousand = BigDecimal.valueOf(1000);
        int index = -1;
        while (scaled.compareTo(thousand) >= 0 && index < suffixes.size() - 1) {
            scaled = scaled.divide(thousand);
            index++;
        }
        if (index < suffixes.size() - 1 && scaled.compareTo(new BigDecimal("999.995")) >= 0) {
            scaled = scaled.divide(thousand);
            index++;
        }
        String value = decimal(amount < 0 ? scaled.negate() : scaled, compactPattern, locale);
        return template.replace("{value}", value).replace("{suffix}", suffixes.get(index));
    }

    private static String decimal(double amount, String pattern, Locale locale) {
        return decimal(BigDecimal.valueOf(amount), pattern, locale);
    }

    private static String decimal(BigDecimal amount, String pattern, Locale locale) {
        if (pattern == null || pattern.isBlank()) {
            return amount.stripTrailingZeros().toPlainString();
        }
        try {
            DecimalFormat format = new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(locale));
            format.setRoundingMode(RoundingMode.HALF_UP);
            return format.format(amount);
        } catch (IllegalArgumentException invalidOwnerPattern) {
            return amount.stripTrailingZeros().toPlainString();
        }
    }

    public static double parseAmount(String input) {
        if (input == null) {
            throw new NumberFormatException("Amount is missing");
        }
        double parsed = LargeNumberParser.parseDouble(input).orElseThrow(() -> new NumberFormatException("Invalid amount"));
        if (!Double.isFinite(parsed) || parsed < 0D) {
            throw new NumberFormatException("Invalid amount");
        }
        return parsed;
    }

    public static String raw(double amount) {
        return BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString();
    }
}
