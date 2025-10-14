package com.qa.utils;

import com.qa.common.DriverManager;
import org.openqa.selenium.By;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.NoSuchElementException;

public class Waits {

    // keep your default wait window from TestUtils.WAITFOR
    private static WebDriverWait defaultWait() {
        return new WebDriverWait(DriverManager.getDriver(),
                Duration.ofSeconds(TestUtils.WAITFOR));
    }

    // ===== Existing fixed-timeout methods =====

    public void waitForVisibilityOfElement(WebElement e) {
        defaultWait().until(ExpectedConditions.visibilityOf(e));
    }

    public void waitForElementToBeClickable(WebElement element) {
        defaultWait().until(ExpectedConditions.elementToBeClickable(element));
    }

    public void waitForTextToBePresentInElement(WebElement element, String txt) {
        defaultWait().until(ExpectedConditions.textToBePresentInElement(element, txt));
    }

    // ===== New: overloads with custom timeout (seconds) =====

    public void waitForVisibilityOfElement(WebElement e, long timeoutSeconds) {
        new WebDriverWait(DriverManager.getDriver(), Duration.ofSeconds(timeoutSeconds))
                .until(ExpectedConditions.visibilityOf(e));
    }

    public void waitForElementToBeClickable(WebElement element, long timeoutSeconds) {
        new WebDriverWait(DriverManager.getDriver(), Duration.ofSeconds(timeoutSeconds))
                .until(ExpectedConditions.elementToBeClickable(element));
    }

    public void waitForPresence(By locator, long timeoutSeconds) {
        new WebDriverWait(DriverManager.getDriver(), Duration.ofSeconds(timeoutSeconds))
                .until(ExpectedConditions.presenceOfElementLocated(locator));
    }

    /** Some cases (file inputs) don’t need “visible”, just enabled. */
    public void waitUntilEnabled(WebElement element, long timeoutSeconds) {
        new WebDriverWait(DriverManager.getDriver(), Duration.ofSeconds(timeoutSeconds))
                .until(d -> {
                    try { return element.isEnabled(); }
                    catch (NoSuchElementException nse) { return false; }
                });
    }

    // ===== Your existing utilities =====

    public void staticWait() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("Thread was interrupted", e);
        }
    }

    public String waitUntilDataGetsAutoPopulated(WebElement element) {
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Thread was interrupted while waiting for auto-populate", e);
        }

        try {
            defaultWait().until(ExpectedConditions.visibilityOf(element));
            return defaultWait().until(driver -> {
                String value = element.getAttribute("value");
                return (value != null && !value.trim().isEmpty()) ? value : null;
            });
        } catch (TimeoutException e) {
            TestUtils.log().error("Timeout: Element value did not populate in time");
            throw e;
        }
    }
}