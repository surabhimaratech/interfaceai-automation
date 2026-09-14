package com.surabhimarathe.interfaceautomation.replay;

import com.surabhimarathe.interfaceautomation.artifact.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ContractValuesTest {
    @Test void usdParsingIsExactAndTyped() {
        assertEquals(new BigDecimal("1842.73"), ContractValues.usd("$1842.73"));
        assertEquals(new BigDecimal("-1.00"), ContractValues.usd("$-1.00"));
        assertTrue(ContractValues.equal(new BigDecimal("25"), new BigDecimal("25.00")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1842.73", "$1,842.73", "$1.0", "$01.00", "$1e2", " $1.00", "$1.00 ", "$NaN", "€1.00"})
    void malformedCurrencyIsNeverCoerced(String text) {
        var ex = assertThrows(IllegalArgumentException.class, () -> ContractValues.usd(text));
        assertEquals("INVALID_USD_DECIMAL", ex.getMessage());
    }

    @Test void wholeInputExpressionsDoNotInterpolateOrCoerce() {
        Map<String, Object> inputs = Map.of("id", "member-value", "amount", new BigDecimal("25.00"));
        assertEquals("member-value", ContractValues.resolve("${inputs.id}", inputs));
        assertEquals("25.00", ContractValues.resolve("${inputs.amount}", inputs));
        assertThrows(IllegalArgumentException.class, () -> ContractValues.input("prefix ${inputs.id}", inputs));
        assertThrows(IllegalArgumentException.class, () -> ContractValues.input("${inputs.absent}", inputs));
        assertThrows(IllegalArgumentException.class, () -> ContractValues.input("${inputs.id.toString()}", inputs));
    }

    @Test void constraintsAndTimeoutConfigurationAreBounded() {
        Constraints decimal = new Constraints(null, null, new BigDecimal("0.01"), new BigDecimal("100"), 2);
        assertFalse(ContractValues.valid(ValueType.DECIMAL, decimal, "25.00"));
        assertFalse(ContractValues.valid(ValueType.DECIMAL, decimal, new BigDecimal("1.001")));
        Constraints string = new Constraints(1, 6, null, null, null);
        assertFalse(ContractValues.valid(ValueType.STRING, string, "x\ny"));
        assertFalse(ContractValues.valid(ValueType.STRING, string, "x".repeat(7)));
        assertThrows(IllegalArgumentException.class, () -> new ReplayOptions(Duration.ZERO, Duration.ofSeconds(1), true));
        assertThrows(IllegalArgumentException.class, () -> new ReplayOptions(Duration.ofSeconds(31), Duration.ofSeconds(60), true));
        assertThrows(IllegalArgumentException.class, () -> new ReplayOptions(Duration.ofSeconds(1), Duration.ofMinutes(6), true));
    }
}
