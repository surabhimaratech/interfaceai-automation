package com.surabhimarathe.interfaceautomation.artifact;

/** Logical target only. Concrete origin must be supplied by trusted runtime policy. */
public record TargetSpec(String targetId, String entryPath) {}
