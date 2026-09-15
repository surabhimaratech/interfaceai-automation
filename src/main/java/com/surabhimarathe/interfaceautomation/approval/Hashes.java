package com.surabhimarathe.interfaceautomation.approval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import static com.surabhimarathe.interfaceautomation.approval.ApprovalException.*;

final class Hashes {
    private Hashes() {}
    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw fail(Code.INVALID_IDENTITY); }
    }
    static String scoped(String domain, String value) {
        return sha256((domain + "\0" + value).getBytes(StandardCharsets.UTF_8));
    }
    static boolean digest(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
}
