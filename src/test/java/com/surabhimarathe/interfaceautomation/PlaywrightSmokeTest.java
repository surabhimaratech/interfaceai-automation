package com.surabhimarathe.interfaceautomation;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaywrightSmokeTest {

    @Test
    void launchesChromiumAndReadsPage() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch();
            Page page = browser.newPage();

            page.setContent("""
                    <html>
                      <head><title>Smoke Test</title></head>
                      <body><h1>Ready</h1></body>
                    </html>
                    """);

            assertEquals("Smoke Test", page.title());
            assertEquals("Ready", page.locator("h1").textContent());

            browser.close();
        }
    }
}