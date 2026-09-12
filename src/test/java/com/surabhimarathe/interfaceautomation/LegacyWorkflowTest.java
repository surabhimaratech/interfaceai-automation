package com.surabhimarathe.interfaceautomation;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LegacyWorkflowTest {
    @LocalServerPort int port;
    Playwright playwright;
    Browser browser;
    Page page;

    @BeforeEach void openBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
        page = browser.newPage();
    }

    @AfterEach void closeBrowser() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    String url(String path) { return "http://localhost:" + port + path; }

    @Test void completesReviewThroughVisibleUiAndNeverChangesBalance() {
        page.navigate(url("/legacy"));
        page.getByLabel("Member ID").fill("100042");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Search")).click();
        page.getByRole(AriaRole.ROW).filter(new Locator.FilterOptions().setHasText("100042"))
                .getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName("Open")).click();
        assertEquals(2, page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Open").setExact(true)).count());
        page.getByRole(AriaRole.ROW).filter(new Locator.FilterOptions().setHasText("SAV-2048"))
                .getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName("Open")).click();
        assertEquals(1, page.locator("table table").count());
        assertThat(page.locator("body")).containsText("$1842.73");
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Prepare fee reversal")).click();
        page.getByLabel("Amount", new Page.GetByLabelOptions().setExact(true)).fill("25.00");
        page.getByLabel("Reason").fill("Courtesy adjustment");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Review reversal")).click();
        assertThat(page.getByRole(AriaRole.HEADING).first()).hasText("Fee Reversal Review");
        assertThat(page.locator("body")).containsText("100042");
        assertThat(page.locator("body")).containsText("Morgan Lee");
        assertThat(page.locator("body")).containsText("SAV-2048");
        assertThat(page.locator("body")).containsText("$25.00");
        assertThat(page.locator("body")).containsText("Courtesy adjustment");
        assertThat(page.locator("body")).containsText("$1867.73");
        assertThat(page.getByRole(AriaRole.STATUS)).containsText("not submitted");
        Response blocked = page.waitForResponse(r -> r.url().endsWith("/submit"), () ->
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Submit reversal")).click());
        assertEquals(403, blocked.status());
        assertThat(page.locator("body")).containsText("ACTION_BLOCKED");
        page.navigate(url("/legacy/accounts/SAV-2048"));
        assertThat(page.locator("body")).containsText("$1842.73");
        assertEquals(0, page.locator("[data-testid]").count());
    }

    @Test void missingMemberIsAnExpectedSearchOutcome() {
        page.navigate(url("/legacy"));
        page.getByLabel("Member ID").fill("999999");
        Response response = page.waitForResponse(r -> r.url().endsWith("/members/search"), () ->
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Search")).click());
        assertEquals(200, response.status());
        assertThat(page.getByRole(AriaRole.STATUS)).containsText("MEMBER_NOT_FOUND");
        assertEquals(0, page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Open")).count());
    }

    @Test void invalidAmountKeepsTheFormAndReason() {
        page.navigate(url("/legacy/accounts/SAV-2048/fee-reversal"));
        page.getByLabel("Amount", new Page.GetByLabelOptions().setExact(true)).fill("0");
        page.getByLabel("Reason").fill("Courtesy adjustment");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Review reversal")).click();
        assertThat(page.getByRole(AriaRole.ALERT)).containsText("VALIDATION_REJECTED");
        assertThat(page.getByLabel("Reason")).hasValue("Courtesy adjustment");
        assertEquals(0, page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Submit reversal")).count());
    }

    @Test void serverValidatesDirectPostsAndEscapesReason() throws Exception {
        for (String amount : new String[]{"", "-1", "0", "100.01", "1.001", "1e1", "NaN"}) {
            var response = post("/legacy/accounts/SAV-2048/fee-reversal/review", amount, "Courtesy adjustment");
            assertEquals(422, response.statusCode(), amount);
            assertTrue(response.body().contains("VALIDATION_REJECTED"));
        }
        for (String reason : new String[]{"", "   ", "x".repeat(121)})
            assertEquals(422, post("/legacy/accounts/SAV-2048/fee-reversal/review", "25", reason).statusCode());
        for (String amount : new String[]{"0.01", "100.00"})
            assertEquals(200, post("/legacy/accounts/SAV-2048/fee-reversal/review", amount, "Adjustment").statusCode());
        var escaped = post("/legacy/accounts/SAV-2048/fee-reversal/review", "25", "<script>alert(1)</script>");
        assertEquals(200, escaped.statusCode());
        assertFalse(escaped.body().contains("<script>"));
        assertTrue(escaped.body().contains("&lt;script&gt;"));
        assertEquals(403, post("/legacy/accounts/SAV-2048/fee-reversal/submit", "25", "Adjustment").statusCode());
        assertEquals(404, post("/legacy/accounts/UNKNOWN/fee-reversal/review", "25", "Adjustment").statusCode());
        assertEquals(404, page.navigate(url("/legacy/members/999999")).status());
        assertEquals(404, page.navigate(url("/legacy/accounts/UNKNOWN")).status());
    }

    private HttpResponse<String> post(String path, String amount, String reason) throws Exception {
        String body = "amount=" + URLEncoder.encode(amount, StandardCharsets.UTF_8)
                + "&reason=" + URLEncoder.encode(reason, StandardCharsets.UTF_8);
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url(path)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
