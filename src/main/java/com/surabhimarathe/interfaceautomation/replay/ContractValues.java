package com.surabhimarathe.interfaceautomation.replay;

import com.surabhimarathe.interfaceautomation.artifact.*;
import java.math.BigDecimal;
import java.util.Map;
import java.util.regex.Pattern;

final class ContractValues {
    private static final Pattern INPUT = Pattern.compile("\\$\\{inputs\\.([A-Za-z][A-Za-z0-9_]*)}");
    static boolean valid(ValueType type, Constraints c, Object value) {
        if (type == ValueType.STRING)
            return value instanceof String s && s.length() >= c.minLength() && s.length() <= c.maxLength()
                    && s.codePoints().noneMatch(Character::isISOControl);
        return value instanceof BigDecimal d && d.precision() <= 30 && Math.abs((long) d.scale()) <= 8
                && d.compareTo(c.minimum()) >= 0 && d.compareTo(c.maximum()) <= 0
                && Math.max(0, d.stripTrailingZeros().scale()) <= c.maxScale();
    }
    static Object input(String expression, Map<String, Object> inputs) {
        var match = INPUT.matcher(expression);
        if (!match.matches() || !inputs.containsKey(match.group(1))) throw new IllegalArgumentException("INVALID_EXPRESSION");
        return inputs.get(match.group(1));
    }
    static String text(Object value) { return value instanceof BigDecimal d ? d.toPlainString() : (String) value; }
    static String resolve(String text, Map<String, Object> inputs) {
        return INPUT.matcher(text).matches() ? text(input(text, inputs)) : text;
    }
    static BigDecimal usd(String text) {
        // Exact ASCII dollars and two fractional digits. No grouping, exponent, locale or trimming.
        if (text == null || text.length() > 40 || !text.matches("\\$-?(0|[1-9][0-9]*)\\.[0-9]{2}"))
            throw new IllegalArgumentException("INVALID_USD_DECIMAL");
        return new BigDecimal(text.substring(1));
    }
    static boolean equal(Object a, Object b) {
        return a instanceof BigDecimal d && b instanceof BigDecimal other ? d.compareTo(other) == 0 : a.equals(b);
    }
}
