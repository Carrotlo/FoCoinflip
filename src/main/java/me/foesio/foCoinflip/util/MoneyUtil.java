package me.foesio.foCoinflip.util;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import me.foesio.core.number.LargeNumberParser;
import me.foesio.core.number.NumberFormatters;

public final class MoneyUtil {

    private static final ThreadLocal<DecimalFormat> FORMAT = ThreadLocal.withInitial(() ->
            new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US)));
    private MoneyUtil() {
    }

    public static String format(double amount) {
        return FORMAT.get().format(amount);
    }

    public static String format(double amount, boolean compact) {
        if (!compact) {
            return format(amount);
        }
        return NumberFormatters.compact(amount);
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
