package me.foesio.foCoinflip.util;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MoneyUtil {

    private static final ThreadLocal<DecimalFormat> FORMAT = ThreadLocal.withInitial(() ->
            new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US)));
    private static final ThreadLocal<DecimalFormat> COMPACT_FORMAT = ThreadLocal.withInitial(() ->
            new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.US)));
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "^([0-9]+(?:\\.[0-9]+)?)(?:\\s*([a-z]+))?$",
            Pattern.CASE_INSENSITIVE);

    private MoneyUtil() {
    }

    public static String format(double amount) {
        return FORMAT.get().format(amount);
    }

    public static String format(double amount, boolean compact) {
        if (!compact) {
            return format(amount);
        }
        return compact(amount);
    }

    public static double parseAmount(String input) {
        if (input == null) {
            throw new NumberFormatException("Amount is missing");
        }

        String normalized = input.trim().replace(",", "").toLowerCase(Locale.ROOT);
        Matcher matcher = AMOUNT_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            throw new NumberFormatException("Invalid amount");
        }

        BigDecimal value = new BigDecimal(matcher.group(1));
        String suffix = matcher.group(2);
        if (suffix != null) {
            value = value.multiply(suffixMultiplier(suffix));
        }

        double parsed = value.doubleValue();
        if (!Double.isFinite(parsed)) {
            throw new NumberFormatException("Invalid amount");
        }
        return parsed;
    }

    public static String raw(double amount) {
        return BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString();
    }

    private static String compact(double amount) {
        double absolute = Math.abs(amount);
        if (absolute >= 1_000_000_000_000D) {
            return COMPACT_FORMAT.get().format(amount / 1_000_000_000_000D) + "T";
        }
        if (absolute >= 1_000_000_000D) {
            return COMPACT_FORMAT.get().format(amount / 1_000_000_000D) + "B";
        }
        if (absolute >= 1_000_000D) {
            return COMPACT_FORMAT.get().format(amount / 1_000_000D) + "M";
        }
        if (absolute >= 1_000D) {
            return COMPACT_FORMAT.get().format(amount / 1_000D) + "K";
        }
        return format(amount);
    }

    private static BigDecimal suffixMultiplier(String suffix) {
        return switch (suffix) {
            case "k", "thousand", "thousands" -> BigDecimal.valueOf(1_000L);
            case "m", "million", "millions" -> BigDecimal.valueOf(1_000_000L);
            case "b", "billion", "billions" -> BigDecimal.valueOf(1_000_000_000L);
            case "t", "trillion", "trillions" -> BigDecimal.valueOf(1_000_000_000_000L);
            default -> throw new NumberFormatException("Invalid amount");
        };
    }
}
