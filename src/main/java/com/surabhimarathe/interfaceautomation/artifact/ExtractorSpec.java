package com.surabhimarathe.interfaceautomation.artifact;

/** ROW_VALUE means the unique data cell in a uniquely matched semantic row. No selectors. */
public record ExtractorSpec(String output, LocatorSpec locator, Read read,
                            Format format, String expectedInputExpression) {
    public enum Read { TEXT, ROW_VALUE }
    public enum Format { TEXT, USD_DECIMAL }
}
