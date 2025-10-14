package com.qa.runner;

import com.qa.utils.ConfigManager;
import com.qa.utils.EmailManager;
import com.qa.utils.ExtentReportUtils;
import io.github.cdimascio.dotenv.Dotenv;
import org.testng.TestNG;
import org.testng.annotations.Test;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Properties;

public class MasterRunner {

    private static final String FAILED_SCENARIO_FILE = "target/failedscenarios.txt";
    private static final String EXTENT_PROPERTIES_FILE = "src/test/resources/extent.properties";
    private static final String REPORT_ROOT = "extends_reports";
    private static final Properties CONFIG = new ConfigManager().getConfigProps();

    // Minimal addition: email modes for configurability
    private enum EmailMode { BOTH, FAILED, PASSED, NONE }

    @Test
    public void runAutomationSuite() {
        printBanner("MASTER TEST RUNNER STARTED");

        deleteIfExists(FAILED_SCENARIO_FILE);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String sparkPath = "Test Reports/Report/Initial_Run_Spark_" + timestamp;

        setupExtentProperties(sparkPath);
        runTestNG(TestngRunner.class);

        boolean finalFailureDetected = isFailedScenarioDetected();

        if (isRetryEnabled() && finalFailureDetected) {
            System.out.println("Retry is ENABLED. Retrying failed scenarios...");
            String retrySparkPath = "Test Reports/Report/Retry_Run_Spark_" + timestamp;
            setupExtentProperties(retrySparkPath);
            runTestNG(RetryFailedRunner.class);
            finalFailureDetected = isFailedScenarioDetected(); // Check again after retry
        } else if (!isRetryEnabled() && finalFailureDetected) {
            System.out.println("Retry is DISABLED. Skipping failed scenario rerun.");
        } else {
            System.out.println("All scenarios PASSED in the first run!");
        }

        printBanner("MASTER TEST RUNNER COMPLETED");

        // Minimal change: single decision point based on config and outcome
        if (shouldSendEmail(finalFailureDetected)) {
            System.out.println("Preparing to send outcome email with reports...");
            sendOutcomeEmailWithReports(finalFailureDetected);
        } else {
            System.out.println("Email sending skipped based on configuration.");
        }

        // Fail this test so TestNG marks it red
        if (finalFailureDetected) {
            throw new AssertionError("Test failures detected!");
        }
    }

    private boolean isRetryEnabled() {
        return "true".equalsIgnoreCase(CONFIG.getProperty("Retry"));
    }

    // Minimal addition: centralize email decision (modes: both|failed|passed|none)
    private boolean shouldSendEmail(boolean failureDetected) {
        EmailMode mode = getEmailMode();
        switch (mode) {
            case BOTH:
                return true;
            case FAILED:
                return failureDetected;
            case PASSED:
                return !failureDetected;
            case NONE:
            default:
                return false;
        }
    }

    // Minimal addition: read email mode; keep backward compatibility with sendEmailOnFailure=true
    private EmailMode getEmailMode() {
        String legacy = CONFIG.getProperty("sendEmailOnFailure");
        if ("true".equalsIgnoreCase(legacy)) {
            return EmailMode.FAILED;
        }
        String val = getConfigOrDefault("emailOn", "failed").toUpperCase();
        try {
            return EmailMode.valueOf(val);
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid emailOn value '" + val + "'. Falling back to FAILED.");
            return EmailMode.FAILED;
        }
    }

    private boolean isFailedScenarioDetected() {
        File file = new File(FAILED_SCENARIO_FILE);
        boolean exists = file.exists() && file.length() > 0;
        System.out.println("Failed scenario file exists & non-empty: " + exists);
        return exists;
    }

    private void setupExtentProperties(String sparkReportPath) {
        System.setProperty("extent.properties.file", EXTENT_PROPERTIES_FILE);
        System.setProperty("spark.report.path", sparkReportPath);

        System.out.println("Using Extent Config: " + EXTENT_PROPERTIES_FILE);
        System.out.println("Spark Report Path  : " + sparkReportPath + ".html");
    }

    private void runTestNG(Class<?> runnerClass) {
        try {
            TestNG testng = new TestNG();
            testng.setTestClasses(new Class[]{runnerClass});
            testng.setUseDefaultListeners(true);
            testng.setDefaultSuiteName("AutoSuite_" + runnerClass.getSimpleName());
            testng.setDefaultTestName("AutoTest_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")));
            testng.run();
        } catch (Exception e) {
            System.err.println("Error running TestNG class: " + runnerClass.getSimpleName());
            e.printStackTrace();
        }
    }

    // Minimal change: generalized sender with correct subject/body for pass/fail and optional config overrides
    private void sendOutcomeEmailWithReports(boolean failureDetected) {
        try {
            Thread.sleep(2000); // Allow files to be flushed
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        File latestReportFolder = getLatestReportFolder(new File(REPORT_ROOT));
        if (latestReportFolder == null) {
            System.err.println("No report folder found inside: " + REPORT_ROOT);
            return;
        }

        String reportPath = latestReportFolder.getAbsolutePath() + "/Test Reports";
        ExtentReportUtils.setReportFolderPath(reportPath);

        File htmlReport = new File(ExtentReportUtils.getReportFolderPath(), "Report/Spark.html");
        File screenshotDir = new File(ExtentReportUtils.getReportFolderPath(), "screenshot");

        if (!htmlReport.exists()) {
            System.err.println("Extent HTML report not found at: " + htmlReport.getAbsolutePath());
            return;
        }

        File[] screenshots = findAllPngFiles(screenshotDir);
        File[] attachments = (screenshots.length > 0)
                ? combineFiles(htmlReport, screenshots)
                : new File[]{htmlReport};

        // Minimal addition: pick subject/body based on outcome with optional config overrides
        String[] sb = getEmailSubjectAndBody(failureDetected);
        String subject = sb[0];
        String body = sb[1];

        System.out.println("Sending outcome email with report" + (screenshots.length > 0 ? " and screenshots..." : "..."));
        EmailManager.sendEmailWithAttachments(subject, body, attachments);
    }

    private File getLatestReportFolder(File reportRoot) {
        if (!reportRoot.exists() || !reportRoot.isDirectory()) return null;
        File[] folders = reportRoot.listFiles(File::isDirectory);
        if (folders == null || folders.length == 0) return null;

        return Arrays.stream(folders)
                .max(Comparator.comparingLong(File::lastModified))
                .orElse(null);
    }

    private File[] findAllPngFiles(File dir) {
        if (dir.exists() && dir.isDirectory()) {
            File[] pngs = dir.listFiles((f, name) -> name.toLowerCase().endsWith(".png"));
            return pngs != null ? pngs : new File[0];
        }
        return new File[0];
    }

    private File[] combineFiles(File baseFile, File[] others) {
        File[] combined = new File[others.length + 1];
        combined[0] = baseFile;
        System.arraycopy(others, 0, combined, 1, others.length);
        return combined;
    }

    private void deleteIfExists(String path) {
        File file = new File(path);
        if (file.exists()) {
            if (file.delete()) {
                System.out.println("Deleted previous file: " + path);
            } else {
                System.err.println("Failed to delete previous file: " + path);
            }
        }
    }

    private void printBanner(String message) {
        System.out.println("───────────────────────────────────────────────");
        System.out.println(message);
        System.out.println(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss")));
        System.out.println("───────────────────────────────────────────────");
    }

    // ===== Minimal new helpers for configurable, result-aware email text =====

    // Fetch from config with default
    private String getConfigOrDefault(String key, String defVal) {
        String v = CONFIG.getProperty(key);
        return (v == null || v.trim().isEmpty()) ? defVal : v.trim();
    }

    // Return subject and body based on run outcome, allowing optional overrides in config.properties
    private String[] getEmailSubjectAndBody(boolean failureDetected) {
        if (failureDetected) {
            String subject = getConfigOrDefault("email.subject.failed", "Failed Smoke Test Detected for " + "AMFI Production server"+ " – Time to Investigate!");
            String body = getConfigOrDefault(
                    "email.body.failed",
                    "Hi Team,<br><br>Smoke Test scenarios have <b>failed</b> in the latest run for " +"AMFI Production server"+". Please find attached the <b>automation report</b>"
                            + " along with any available <b>screenshots</b>.<br><br>Kindly review and take action.<br><br>Regards,<br>Automation"
            );
            return new String[]{subject, body};
        } else {
            String subject = getConfigOrDefault("email.subject.passed", "Smoke Scenarios Passed for "+"AMFI Production server"+" – Green Run Report");
            String body = getConfigOrDefault(
                    "email.body.passed",
                    "Hi Team,<br><br>Great news! <b>Smoke Test has successfully passed on "+"AMFI Production server"+"</b> in the latest run. Attaching the automation report for your records."
                            + "<br><br>Regards,<br>Automation"
            );
            return new String[]{subject, body};
        }
    }
}