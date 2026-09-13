package com.surabhimarathe.interfaceautomation.discovery;

/** Only enum metadata and a boolean: never values, selectors, labels, URLs or page text. */
public record PreviousAction(UiAction.Type action, ActionResult.Code result, boolean stateChanged) {}
