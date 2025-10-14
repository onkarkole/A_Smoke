package com.qa.hooks;

import com.qa.common.DevToolsManager;
import com.qa.common.DriverManager;
import com.qa.common.SessionManager;
import com.qa.pages.LoginPage;
import com.qa.utils.*;
import io.cucumber.java.After;
import io.cucumber.java.AfterAll;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

public class ProjectHooks {

	private final Properties prop = new Properties();
	private WebDriver driver;
	private String scenarioName;
	private String currentRole;

	public static String dynamicDataFileName;
    private final ConfigManager config = new ConfigManager();

	@Before(order = 0)
	public void getProperty() {
		prop.putAll(config.getConfigProps());
        ReportCleaner.cleanOldReportFolders();
        SecureConfig.init();
    }

	@Before(order = 1)
	public void beforeScenario(Scenario scenario) {
		scenarioName = scenario.getName();
		currentRole = extractUserRoleFromScenarioName(scenarioName);

		dynamicDataFileName = generateFileNameFromFeatureTitle(scenario);
		System.out.println("JSON Test Data File: " + dynamicDataFileName);

		if (DriverManager.getDriver() == null) {
			driver = new DriverManager().initializeBrowser(
					prop.getProperty("browser"),
					prop.getProperty("headlessMode")
			);
		} else {
			driver = DriverManager.getDriver();
		}

		boolean roleSwitched = SessionManager.isRoleSwitch(currentRole);
		boolean shouldLogin = SessionManager.isFirstScenario() || SessionManager.shouldReLogin() || roleSwitched;

		if (shouldLogin) {
			if (!SessionManager.isFirstScenario() && roleSwitched && !SessionManager.isAlreadyLoggedOut()) {
				System.out.println("Logging out as previous role");
				tryLogout();
			}
			SessionManager.setCurrentRole(currentRole);
			SessionManager.resetReLoginFlag();
			SessionManager.markFirstScenarioCompleted();
			SessionManager.resetLoggedOutFlag();
            new LoginPage().getRole(SessionManager.getCurrentRole());
		} else {
			System.out.println("Continuing session as " + currentRole + " without re-login");
		}
	}

    @After(order = 1)
    public void tearDown(Scenario scenario) {
        String screenshotName = scenario.getName().replaceAll(" ", "_");

        if (scenario.isFailed()) {
            // Use your reusable utility
            byte[] pngBytes = MediaManager.captureScreenshot();

            // Attach to Cucumber only if we actually have bytes
            if (pngBytes.length > 0) {
                scenario.attach(pngBytes, "image/png", screenshotName);
                // Extent usually wants Base64 (optionally with a data URL prefix depending on your util)
                String base64 = Base64.getEncoder().encodeToString(pngBytes);
                ExtentReportUtils.attachScreenshotToExtent("Failed Step Screenshot", base64);
            } else {
                TestUtils.log().warn("Screenshot not attached: empty byte array returned by ScreenshotUtil.");
            }

            SessionManager.markReLoginNeeded();
            tryLogout();
        }
    }


    @After(order = 0)
	public void cleanupAfterScenario() {
		if (SessionManager.shouldReLogin()) {
			tryLogout();
		}
	}

	@AfterAll
	public static void saveAllApiLogsAndQuitBrowser() {
		DriverManager.quitDriver();
        DevToolsManager.clearDevTools();
	}

	private void tryLogout() {
		try {
			WebDriver driver = DriverManager.getDriver();
			if (driver.findElements(By.xpath("//button[@id='dropdown-basic-button ']")).size() > 0) {
				new LoginPage().clickOnLogout();
				System.out.println("Logged out successfully");
			} else {
				System.out.println("Already logged out or logout button not found — skipping");
			}
		} catch (Exception e) {
			System.out.println("Logout skipped or failed: " + e.getMessage());
		}
		SessionManager.markLoggedOut();
	}

	private String extractUserRoleFromScenarioName(String name) {
		name = name.toLowerCase();
		if (name.contains("admin")) return "admin";
		if (name.contains("trainer")) return "trainer";
		if (name.contains("beneficiary")) return "beneficiary";
		if (name.contains("superadmin") || name.contains("super admin")) return "superadmin";
		if (name.contains("contentmanager") || name.contains("content manager")) return "contentmanager";
		if (name.contains("complianceofficer") || name.contains("compliance officer")) return "complianceofficer";
        if (name.contains("traininggency") || name.contains("training agency")) return "traininggency";

		return "default";
	}

	private String generateFileNameFromFeatureTitle(Scenario scenario) {
		String featureTitle = getFeatureTitleFromScenario(scenario);
		String pascalCaseEntity = extractEntityNameFromFeatureTitle(featureTitle);
		return decapitalize(pascalCaseEntity) + ".json";
	}

	private String getFeatureTitleFromScenario(Scenario scenario) {
		String rawFeaturePath = scenario.getUri().getPath();

		if (isWindows() && rawFeaturePath.matches("^/[A-Za-z]:.*")) {
			rawFeaturePath = rawFeaturePath.substring(1);
		}

		try {
			Path path = Paths.get(rawFeaturePath);

			try (Stream<String> lines = Files.lines(path)) {
				Optional<String> featureLine = lines
						.filter(line -> line.trim().toLowerCase().startsWith("feature:"))
						.findFirst();

				return featureLine
						.map(line -> line.replaceFirst("(?i)feature:", "").trim())
						.orElse("UnknownFeature");
			}

		} catch (InvalidPathException e) {
			System.err.println("Invalid path: " + rawFeaturePath + " | Error: " + e.getMessage());
		} catch (IOException e) {
			System.err.println("Error reading feature file: " + rawFeaturePath + " | Error: " + e.getMessage());
		} catch (Exception e) {
			System.err.println("Unexpected error while reading feature title: " + e.getMessage());
		}

		return "UnknownFeature";
	}

	private boolean isWindows() {
		return System.getProperty("os.name").toLowerCase().contains("win");
	}

	private String extractEntityNameFromFeatureTitle(String title) {
		String cleaned = title
				.replaceAll("(?i)add|edit|delete|update|create", "")
				.replaceAll("(?i)functionality|feature|module", "")
				.replaceAll("[^a-zA-Z ]", "")
				.trim();

		if (cleaned.isEmpty()) return "DefaultName";

		StringBuilder pascal = new StringBuilder();
		for (String word : cleaned.split("\\s+")) {
			pascal.append(Character.toUpperCase(word.charAt(0)))
					.append(word.substring(1).toLowerCase());
		}
		return pascal.toString();
	}

	private String decapitalize(String input) {
		if (input == null || input.isEmpty()) return input;
		return Character.toLowerCase(input.charAt(0)) + input.substring(1);
	}
}