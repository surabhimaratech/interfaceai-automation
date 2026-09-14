package com.surabhimarathe.interfaceautomation.discovery;

/** Provider-neutral failure seam, so scripted discovery never loads a provider client. */
public class DecisionFailure extends RuntimeException {
    public DecisionFailure(String code) { super(code); }
}
