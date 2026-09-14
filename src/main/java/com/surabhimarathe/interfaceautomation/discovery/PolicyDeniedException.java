package com.surabhimarathe.interfaceautomation.discovery;

/** Safe signal for observation/navigation APIs that cannot return an ActionResult. */
final class PolicyDeniedException extends RuntimeException {
    PolicyDeniedException() { super("POLICY_DENIED"); }
}
