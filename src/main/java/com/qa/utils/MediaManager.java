package com.qa.utils;

import com.qa.common.DriverManager;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;

public class MediaManager {

    private static byte[] screenshotBytes;

    private MediaManager() {
        throw new UnsupportedOperationException("Utility class - instantiation not allowed");
    }

    public static byte[] getScreenshotBytes() {
        return screenshotBytes;
    }

    /**
     * Capture screenshot from current driver and store in static variable.
     * Also returns the bytes so caller can use immediately.
     */
    public static byte[] captureScreenshot() {
        WebDriver driver = DriverManager.getDriver();

        if (driver == null) {
            TestUtils.log().fatal("WebDriver instance is null. Cannot capture screenshot.");
            return new byte[0];
        }

        if (!(driver instanceof TakesScreenshot)) {
            TestUtils.log().fatal("Driver does not support taking screenshots.");
            return new byte[0];
        }

        try {
            byte[] screenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
            screenshotBytes = screenshot;
            TestUtils.log().info("Screenshot captured.");
            return screenshot;
        } catch (Exception e) {
            TestUtils.log().fatal("Failed to capture screenshot. Exception: {}", e.toString());
            return new byte[0];
        }
    }
}
