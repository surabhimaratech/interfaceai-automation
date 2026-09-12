package com.surabhimarathe.interfaceautomation.legacy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;

/** Synthetic UI target. All balances are immutable; no banking operation is performed. */
@Controller
@RequestMapping("/legacy")
public class LegacyController {
    private static final String MEMBER = "100042";
    private static final String ACCOUNT = "SAV-2048";
    private static final BigDecimal BALANCE = new BigDecimal("1842.73");

    @ModelAttribute
    void common(Model model) {
        model.addAttribute("memberId", MEMBER);
        model.addAttribute("memberName", "Morgan Lee");
        model.addAttribute("accountId", ACCOUNT);
        model.addAttribute("balance", BALANCE.toPlainString());
    }

    @GetMapping({"", "/"})
    String search() { return "legacy/search"; }

    @PostMapping("/members/search")
    String results(@RequestParam(defaultValue = "") String memberId, Model model) {
        model.addAttribute("found", MEMBER.equals(memberId.trim()));
        return "legacy/results";
    }

    @GetMapping("/members/{memberId}")
    String member(@PathVariable String memberId, Model model, HttpServletResponse response) {
        return MEMBER.equals(memberId) ? "legacy/member" : missing(model, response);
    }

    @GetMapping("/accounts/{accountId}")
    String account(@PathVariable String accountId, Model model, HttpServletResponse response) {
        return ACCOUNT.equals(accountId) ? "legacy/account" : missing(model, response);
    }

    @GetMapping("/accounts/{accountId}/fee-reversal")
    String form(@PathVariable String accountId, Model model, HttpServletResponse response) {
        if (!ACCOUNT.equals(accountId)) return missing(model, response);
        model.addAttribute("amount", "");
        model.addAttribute("reason", "");
        return "legacy/reversal";
    }

    @PostMapping("/accounts/{accountId}/fee-reversal/review")
    String review(@PathVariable String accountId,
                  @RequestParam(defaultValue = "") String amount,
                  @RequestParam(defaultValue = "") String reason,
                  Model model, HttpServletResponse response) {
        if (!ACCOUNT.equals(accountId)) return missing(model, response);
        model.addAttribute("amount", amount);
        model.addAttribute("reason", reason);
        BigDecimal value;
        try {
            // Decimal currency only: reject exponent notation, excessive precision and huge input.
            if (!amount.matches("[0-9]{1,3}(\\.[0-9]{1,2})?")) throw new IllegalArgumentException();
            value = new BigDecimal(amount).setScale(2, RoundingMode.UNNECESSARY);
            if (value.signum() <= 0 || value.compareTo(new BigDecimal("100.00")) > 0)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) {
            return invalid("Enter an amount between 0.01 and 100.00 with at most two decimal places.", model, response);
        }
        if (reason.isBlank() || reason.length() > 120)
            return invalid("Enter a reason between 1 and 120 characters.", model, response);
        model.addAttribute("amount", value.toPlainString());
        model.addAttribute("projectedBalance", BALANCE.add(value).toPlainString());
        return "legacy/review";
    }

    @PostMapping("/accounts/{accountId}/fee-reversal/submit")
    String submit(@PathVariable String accountId, Model model, HttpServletResponse response) {
        if (!ACCOUNT.equals(accountId)) return missing(model, response);
        // Target-side backstop; future automation must also reject this action before clicking.
        response.setStatus(HttpStatus.FORBIDDEN.value());
        model.addAttribute("code", "ACTION_BLOCKED");
        model.addAttribute("message", "Submission is blocked by the demo policy. No reversal was submitted.");
        return "legacy/outcome";
    }

    private String invalid(String message, Model model, HttpServletResponse response) {
        response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
        model.addAttribute("error", message);
        return "legacy/reversal";
    }

    private String missing(Model model, HttpServletResponse response) {
        response.setStatus(HttpStatus.NOT_FOUND.value());
        model.addAttribute("code", "RECORD_NOT_FOUND");
        model.addAttribute("message", "The requested record does not exist.");
        return "legacy/outcome";
    }
}
