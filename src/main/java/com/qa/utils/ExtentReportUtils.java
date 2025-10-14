package com.qa.utils;

import com.aventstack.extentreports.cucumber.adapter.ExtentCucumberAdapter;

public class ExtentReportUtils {


    private ExtentReportUtils() {
        throw new UnsupportedOperationException("Utility class should not be instantiated");
    }

    // Stores the folder path where the Extent report is generated at runtime
    private static String reportFolderPath;

    /**
     * Attach a screenshot to the Extent report as a base64 image.
     * The image is embedded directly into the report as an HTML <img> tag.
     * @param title - Title or description shown above the image in the report.
     * @param base64 - Screenshot encoded as a Base64 string.
     */
    public static void attachScreenshotToExtent(String title, String base64) {
        String imgTag = "<img src='data:image/png;base64," + base64 + "' height='300' width='500'/>";
        ExtentCucumberAdapter.addTestStepLog("<b>" + title + "</b><br>" + imgTag);
    }

    /**
     * Set the report folder path. This should be called once, typically from the main test runner,
     * to store the location where reports are saved for later reference.
     * @param path - Absolute or relative path to the report folder.
     */
    public static void setReportFolderPath(String path) {

        reportFolderPath = path;
    }

    /**
     * Retrieve the stored report folder path.
     * Use this method wherever the report folder path is needed, such as when sending emails or uploading reports.
     * @return the report folder path as a String.
     */
    public static String getReportFolderPath() {
        return reportFolderPath;
    }
}
