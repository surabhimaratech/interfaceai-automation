package com.surabhimarathe.interfaceautomation.artifact;

/** Named business outcomes detected by explicit visible UI conditions. */
public record OutcomeSpec(String code, PostconditionSpec condition) {}
