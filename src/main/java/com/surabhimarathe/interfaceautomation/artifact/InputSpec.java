package com.surabhimarathe.interfaceautomation.artifact;

/** All declared inputs are required; no defaults or sample values may be embedded. */
public record InputSpec(ValueType type, Constraints constraints, String description) {}
