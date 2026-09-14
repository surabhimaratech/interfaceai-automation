package com.surabhimarathe.interfaceautomation.artifact;

import java.math.BigDecimal;

/** STRING uses minLength/maxLength; DECIMAL uses minimum/maximum/maxScale. No executable regex. */
public record Constraints(Integer minLength, Integer maxLength, BigDecimal minimum,
                          BigDecimal maximum, Integer maxScale) {}
