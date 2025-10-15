package com.qa.common;

import com.qa.utils.*;

import io.github.bonigarcia.wdm.WebDriverManager;

import org.openqa.selenium.*;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.chrome.*;
import org.openqa.selenium.edge.*;
import org.openqa.selenium.firefox.*;
import org.openqa.selenium.safari.*;

import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.v127.network.Network;
import org.openqa.selenium.devtools.v127.network.model.Response;

import org.openqa.selenium.support.ui.WebDriverWait;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class DriverManager {

    private static final ThreadLocal<WebDriver> tlDriver = new ThreadLocal<>();
    private static final ConfigManager CONFIG = new ConfigManager();

    // ---- Config values (with sane defaults) ----
    private final String baseUrl = SecureConfig.value(SecKeys.AMFIAPPLICATIONBASEURL);

    private final Duration pageLoadTimeout =
            Duration.ofSeconds(Long.parseLong(CONFIG.getConfigProps().getProperty("pageLoadTimeoutSec", "90")));
    private final Duration scriptTimeout =
            Duration.ofSeconds(Long.parseLong(CONFIG.getConfigProps().getProperty("scriptTimeoutSec", "30")));
    private final Duration implicitWait =
            Duration.ofSeconds(Long.parseLong(CONFIG.getConfigProps().getProperty("implicitWaitSec", "10")));

    // Make these STATIC so static methods can use them (and to avoid “never used” warnings)
    private static final long NAVIGATION_MAX_WAIT_SEC =
            Long.parseLong(CONFIG.getConfigProps().getProperty("navigationMaxWaitSec", "90"));
    private static final int NAVIGATION_RETRY_COUNT =
            Integer.parseInt(CONFIG.getConfigProps().getProperty("navigationRetryCount", "3"));
    private static final int RETRY_DELAY_SEC =
            Integer.parseInt(CONFIG.getConfigProps().getProperty("retryDelaySec", "2"));

    // ---- CDP bits (Chrome/Edge) ----
    private static final ThreadLocal<DevTools> TL_DEVTOOLS = new ThreadLocal<>();
    private static final ThreadLocal<String> TL_NET_ERROR = new ThreadLocal<>();
    private static final ThreadLocal<String> TL_NET_ERROR_TYPE = new ThreadLocal<>();
    private static final ThreadLocal<Integer> TL_DOC_STATUS = new ThreadLocal<>();

    // ====================================================================================
    // Public API
    // ====================================================================================

    /**
     * Initialize browser with configured timeouts.
     * Chrome/Edge: CDP is attached for DNS/Server error detection.
     */
    public WebDriver initializeBrowser(String browserName, String isHeadless) {
        TestUtils.log().info("Initializing browser: {}", browserName);

        // If caller passed null, read from config headlessMode
        final String headlessFlag = (isHeadless != null)
                ? isHeadless
                : CONFIG.getConfigProps().getProperty("headlessMode", "false");
        final boolean headless = "true".equalsIgnoreCase(headlessFlag);

        switch (browserName.toLowerCase()) {
            case "chrome": {
                WebDriverManager.chromedriver().setup();
                ChromeDriver chromeDriver = new ChromeDriver(buildChromeOptions(headless));
                tlDriver.set(chromeDriver);
                initDevToolsIfSupported(chromeDriver);
                applyCdpDownloadBehaviorForChrome(chromeDriver);
                break;
            }
            case "edge": {
                WebDriverManager.edgedriver().setup();
                EdgeDriver edgeDriver = new EdgeDriver(buildEdgeOptions(headless));
                tlDriver.set(edgeDriver);
                initDevToolsIfSupported(edgeDriver);
                applyCdpDownloadBehaviorForEdge(edgeDriver);
                break;
            }
            case "firefox": {
                WebDriverManager.firefoxdriver().setup();
                FirefoxOptions firefoxOptions = buildFirefoxOptions(headless);
                tlDriver.set(new FirefoxDriver(firefoxOptions));
                break;
            }
            case "safari": {
                if (headless) {
                    TestUtils.log().error("Safari does not support headless mode.");
                    throw new UnsupportedOperationException("Safari does not support headless mode.");
                }
                WebDriverManager.safaridriver().setup();
                SafariOptions safariOptions = new SafariOptions();
                tlDriver.set(new SafariDriver(safariOptions));
                break;
            }
            default:
                throw new IllegalStateException("INVALID BROWSER: " + browserName);
        }

        WebDriver driver = getDriver();
        driver.manage().deleteAllCookies();
        try {
            driver.manage().window().maximize(); // harmless in headless (no-op)
        } catch (Exception ignore) { }
        driver.manage().timeouts().implicitlyWait(implicitWait);
        driver.manage().timeouts().pageLoadTimeout(pageLoadTimeout);
        driver.manage().timeouts().scriptTimeout(scriptTimeout);
        return driver;
    }

    /**
     * Open a URL and validate navigation health with retries:
     * - Retries ONLY on network/timeout/WebDriver navigation errors
     * - Stops retrying if document.readyState reaches 'complete'
     * - Ignores benign subresource errors (fonts, favicon, images, etc.)
     */
    public static void checkNavigationHealth(String url) {
        WebDriver driver = getDriver();
        if (driver == null) {
            throw new IllegalStateException("WebDriver is not initialized. Call initializeBrowser() first.");
        }

        // Best-effort DNS preflight (non-fatal if it fails due to proxies/VPN)
        try {
            String host = URI.create(url).getHost();
            if (host != null) InetAddress.getByName(host);
        } catch (Exception e) {
            TestUtils.log().warn("DNS preflight resolve failed (non-fatal): {}", e.toString());
        }

        RuntimeException last = null;

        for (int attempt = 1; attempt <= NAVIGATION_RETRY_COUNT; attempt++) {
            TL_NET_ERROR.remove();
            TL_NET_ERROR_TYPE.remove();
            TL_DOC_STATUS.remove();

            URI uri = null;
            try {
                uri = new URI(url);
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }
            String[] segments = uri.getPath().split("/");
            String portalName = segments.length > 1 ? segments[1] : "unknown";

            TestUtils.log().info("🌐 Navigating to: {} (attempt {}/{})", portalName, attempt, NAVIGATION_RETRY_COUNT);

            try {
                driver.navigate().to(url);

                // Wait up to NAVIGATION_MAX_WAIT_SEC for readyState=complete
                waitForDocumentReady(driver, NAVIGATION_MAX_WAIT_SEC);

                // If CDP observed a document-level failure (rare after 'complete', but check)
                String netErr = TL_NET_ERROR.get();
                String type = TL_NET_ERROR_TYPE.get();
                if (netErr != null && isCriticalResource(type)) {
                    throw new RuntimeException("Document network error: " + netErr);
                }

                // If we captured HTTP status for the main document and it is 4xx/5xx
                Integer status = TL_DOC_STATUS.get();
                if (status != null && status >= 400) {
                    throw new RuntimeException("HTTP " + status + " for document");
                }

                // Detect browser error pages (like shown in screenshots)
                if (looksLikeBrowserErrorPage(driver)) {
                    throw new RuntimeException("Browser network error page detected");
                }

                TestUtils.log().info("✅ Navigation healthy to : {}", portalName);
                return; // success, stop retrying

            } catch (RuntimeException e) {
                last = new RuntimeException("Attempt " + attempt + " failed: " + e.getMessage(), e);
                TestUtils.log().warn("⚠️ Attempt {}/{} failed: {}", attempt, NAVIGATION_RETRY_COUNT, e.toString());

                if (attempt < NAVIGATION_RETRY_COUNT) {
                    TestUtils.log().info("⏳ Retrying in {}s...", RETRY_DELAY_SEC);
                    try { Thread.sleep(RETRY_DELAY_SEC * 1000L); } catch (InterruptedException ignored) {}
                }
            }
        }

        throw (last != null)
                ? last
                : new RuntimeException("Navigation health check failed after retries for: " + url);
    }

    public static synchronized WebDriver getDriver() {
        return tlDriver.get();
    }

    public static synchronized void quitDriver() {
        try {
            // Clean up DevTools listeners if present (no disconnect() in modern Selenium)
            DevTools dt = TL_DEVTOOLS.get();
            if (dt != null) {
                try { dt.clearListeners(); } catch (Exception ignore) {}
            }

            WebDriver driver = tlDriver.get();
            if (driver != null) {
                driver.quit();
            }
        } catch (Exception e) {
            TestUtils.log().warn("Error while quitting driver: {}", e.getMessage());
        } finally {
            tlDriver.remove();
            TL_DEVTOOLS.remove();
            TL_NET_ERROR.remove();
            TL_NET_ERROR_TYPE.remove();
            TL_DOC_STATUS.remove();
        }
    }

    // ====================================================================================
    // Internal helpers
    // ====================================================================================

    private void initDevToolsIfSupported(WebDriver driver) {
        try {
            DevTools devTools;
            if (driver instanceof ChromeDriver) {
                devTools = ((ChromeDriver) driver).getDevTools();
            } else if (driver instanceof EdgeDriver) {
                devTools = ((EdgeDriver) driver).getDevTools();
            } else {
                return; // Firefox/Safari: no CDP
            }

            devTools.createSession();
            devTools.send(Network.enable(Optional.empty(), Optional.empty(), Optional.empty()));

            // Capture HTTP status for main document
            devTools.addListener(Network.responseReceived(), event -> {
                if (event.getType() != null && "Document".equalsIgnoreCase(event.getType().toString())) {
                    Response response = event.getResponse();
                    if (response != null) {
                        int statusCode = (int) response.getStatus();
                        TL_DOC_STATUS.set(statusCode);
                    }
                }
            });

            // Only consider main-document/XHR failures; ignore subresources & benign aborts
            devTools.addListener(Network.loadingFailed(), event -> {
                if (Boolean.TRUE.equals(event.getCanceled())) return;

                String type = (event.getType() == null) ? "" : event.getType().toString();
                String lowerType = type.toLowerCase();
                String err = event.getErrorText();

                // Ignore benign aborts common in headless
                if (err != null && err.contains("ERR_ABORTED")) return;

                // Only treat document/xhr/fetch as critical; ignore fonts/images/css/media/favicon/etc.
                if (!"document".equalsIgnoreCase(type) && !"xhr".equalsIgnoreCase(type) && !"fetch".equalsIgnoreCase(type)) {
                    TestUtils.log().debug("Ignored non-critical loadingFailed: {} ({})", err, type);
                    return;
                }

                TL_NET_ERROR.set(err);
                TL_NET_ERROR_TYPE.set(lowerType);
                TestUtils.log().error("❌ Network Failure ({}): {}", type, err);
            });

            TL_DEVTOOLS.set(devTools);
            TestUtils.log().info("CDP Network listener attached for DNS/Server detection.");

        } catch (Exception e) {
            TestUtils.log().warn("DevTools could not be initialized: {}", e.getMessage());
        }
    }

    private static boolean isCriticalResource(String resourceType) {
        if (resourceType == null) return true;
        String t = resourceType.toLowerCase();
        return t.contains("document") || t.contains("xhr") || t.contains("fetch");
    }

    private static void waitForDocumentReady(WebDriver driver, long maxWaitSec) {
        new WebDriverWait(driver, Duration.ofSeconds(maxWaitSec))
                .pollingEvery(Duration.ofMillis(250))
                .ignoring(JavascriptException.class)
                .until(d -> {
                    Object s = ((JavascriptExecutor) d).executeScript("return document.readyState");
                    return "complete".equalsIgnoreCase(String.valueOf(s));
                });
    }

    private static boolean looksLikeBrowserErrorPage(WebDriver driver) {
        try {
            String title = driver.getTitle();
            String body = (String) ((JavascriptExecutor) driver)
                    .executeScript("return document.documentElement.innerText || '';");
            String t = title == null ? "" : title.toLowerCase();
            String b = body == null ? "" : body.toLowerCase();

            String[] markers = new String[]{
                    "this site can’t be reached", "this site can't be reached",
                    "hmmm... can't reach this page",
                    "took too long to respond",
                    "err_connection_timed_out",
                    "check the proxy and the firewall",
                    "dns_probe_finished",
                    "we can’t connect to the server", "we can't connect to the server"
            };
            for (String m : markers) {
                if (t.contains(m) || b.contains(m)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // ====================================================================================
    // Options builders
    // ====================================================================================

    private ChromeOptions buildChromeOptions(boolean isHeadless) {
        ChromeOptions options = new ChromeOptions();
        if (isHeadless) {
            options.addArguments("--headless=new", "--window-size=1920,1080");
        }

        Path downloadDir = DownloadUtils.getDownloadDir();

        Map<String, Object> prefs = new HashMap<>();
        // Silent downloads
        prefs.put("download.default_directory", downloadDir.toString());
        prefs.put("download.prompt_for_download", false);
        prefs.put("download.directory_upgrade", true);
        prefs.put("safebrowsing.enabled", true);
        prefs.put("plugins.always_open_pdf_externally", true);
        prefs.put("profile.default_content_setting_values.automatic_downloads", 1);
        prefs.put("savefile.default_directory", downloadDir.toString());

        // Disable autofill/password nags
        prefs.put("profile.autofill_profile_enabled", false);
        prefs.put("profile.autofill_address_enabled", false);
        prefs.put("credentials_enable_service", false);
        prefs.put("profile.password_manager_enabled", false);

        options.setExperimentalOption("prefs", prefs);

        options.addArguments(
                "--incognito",
                "--disable-save-password-bubble",
                "--disable-popup-blocking",
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--allow-insecure-localhost",
                "--ignore-certificate-errors",
                "--remote-allow-origins=*"
        );

        if (baseUrl != null && !baseUrl.isBlank()) {
            options.addArguments("--unsafely-treat-insecure-origin-as-secure=" + baseUrl);
        }

        options.setPageLoadStrategy(PageLoadStrategy.NORMAL);
        return options;
    }

    private EdgeOptions buildEdgeOptions(boolean isHeadless) {
        EdgeOptions options = new EdgeOptions();
        if (isHeadless) {
            options.addArguments("--headless=new", "--window-size=1920,1080");
        }

        Path downloadDir = DownloadUtils.getDownloadDir();

        Map<String, Object> prefs = new HashMap<>();
        prefs.put("download.default_directory", downloadDir.toString());
        prefs.put("download.prompt_for_download", false);
        prefs.put("download.directory_upgrade", true);
        prefs.put("safebrowsing.enabled", true);
        prefs.put("plugins.always_open_pdf_externally", true);
        prefs.put("profile.default_content_setting_values.automatic_downloads", 1);
        prefs.put("savefile.default_directory", downloadDir.toString());

        prefs.put("profile.autofill_profile_enabled", false);
        prefs.put("profile.autofill_address_enabled", false);

        options.setExperimentalOption("prefs", prefs);

        options.addArguments(
                "--ignore-certificate-errors",
                "--allow-insecure-localhost"
        );

        options.setPageLoadStrategy(PageLoadStrategy.NORMAL);
        return options;
    }

    private FirefoxOptions buildFirefoxOptions(boolean isHeadless) {
        FirefoxOptions options = new FirefoxOptions();
        if (isHeadless) {
            options.addArguments("-headless");
            options.addArguments("--width=1920", "--height=1080");
        }

        Path downloadDir = DownloadUtils.getDownloadDir();

        options.addPreference("browser.download.folderList", 2);
        options.addPreference("browser.download.dir", downloadDir.toString());
        options.addPreference("browser.helperApps.neverAsk.saveToDisk",
                "application/pdf,application/octet-stream,application/zip,"
                        + "application/vnd.ms-excel,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,"
                        + "text/csv");
        options.addPreference("pdfjs.disabled", true);
        options.addPreference("browser.download.useDownloadDir", true);
        options.addPreference("browser.download.manager.showWhenStarting", false);

        return options;
    }

    // ====================================================================================
    // CDP download behavior
    // ====================================================================================

    private void applyCdpDownloadBehaviorForChrome(ChromeDriver chromeDriver) {
        try {
            Path downloadDir = DownloadUtils.getDownloadDir();

            Map<String, Object> browserParams = new HashMap<>();
            browserParams.put("behavior", "allow");
            browserParams.put("downloadPath", downloadDir.toString());
            browserParams.put("eventsEnabled", true);
            chromeDriver.executeCdpCommand("Browser.setDownloadBehavior", browserParams);

            TestUtils.log().info("CDP download behavior (Browser) applied for Chrome: {}", downloadDir);
        } catch (Exception e) {
            TestUtils.log().warn("Failed to apply CDP download behavior for Chrome. Falling back to prefs only.", e);
        }
    }

    private void applyCdpDownloadBehaviorForEdge(EdgeDriver edgeDriver) {
        try {
            Path downloadDir = DownloadUtils.getDownloadDir();

            Map<String, Object> browserParams = new HashMap<>();
            browserParams.put("behavior", "allow");
            browserParams.put("downloadPath", downloadDir.toString());
            browserParams.put("eventsEnabled", true);
            edgeDriver.executeCdpCommand("Browser.setDownloadBehavior", browserParams);

            TestUtils.log().info("CDP download behavior (Browser) applied for Edge: {}", downloadDir);
        } catch (Exception e) {
            TestUtils.log().warn("Failed to apply CDP download behavior for Edge. Falling back to prefs only.", e);
        }
    }
}
