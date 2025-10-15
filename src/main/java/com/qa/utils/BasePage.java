package com.qa.utils;

import com.qa.common.DriverManager;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.PageFactory;

public class BasePage {

    protected WebDriver driver;
    private final Waits wait = new Waits();

    public BasePage() {
        new DriverManager();
        this.driver = DriverManager.getDriver();
        PageFactory.initElements(driver, this);
    }

    public void navigate(String role) {
        String url =SecureConfig.value(SecKeys.AMFIAPPLICATIONBASEURL);
        if (role.contains("beneficiary")) {
            url = url +SecureConfig.value(SecKeys.BENEFICIARYPORTAL);
        } else if (role.equalsIgnoreCase("trainer")) {
            url = url + SecureConfig.value(SecKeys.TRAINERPORTAL);
        } else {
            url = url + SecureConfig.value(SecKeys.ADMINPORTAL);
        }
        DriverManager.checkNavigationHealth(url);
    }

    public void click(WebElement element, String msg) {
        wait.waitForVisibilityOfElement(element);
        try {
            element.click();
        } catch (Exception e) {
            JavascriptExecutor executor = (JavascriptExecutor) driver;
            executor.executeScript("arguments[0].click();", element);
        }
        TestUtils.log().info(msg);
    }

    public void sendKeys(WebElement element, String value, String msg) {
        wait.waitForVisibilityOfElement(element);
        element.clear();
        element.sendKeys(value);
        TestUtils.log().info(msg);
    }

    public String getUsername(String role) {
        if (role.contains("beneficiary")) {
            return "";
        } else if (role.contains("trainer")) {
            return SecureConfig.value(SecKeys.AMFI_TRAINER_USERNAME);
        } else if (role.contains("traininggency")) {
            return "";
        } else if (role.contains("superadmin")
                || role.contains("contentmanager")
                || role.contains("complianceofficer")) {
            return "";
        } else {
            return SecureConfig.value(SecKeys.AMFI_ADMIN_USERNAME);
        }
    }

    public String getPassword(String role) {
        if (role.contains("beneficiary")) {
            return "";
        } else if (role.contains("trainer")) {
            return SecureConfig.value(SecKeys.AMFI_TRAINER_PASSWORD);
        } else if (role.contains("traininggency")) {
            return "";
        } else if (role.contains("superadmin")
                || role.contains("contentmanager")
                || role.contains("complianceofficer")) {
            return "";
        } else {
            return SecureConfig.value(SecKeys.AMFI_ADMIN_PASSWORD);
        }
    }

    /**
     * Checks whether the element is displayed on the page.
     */
    public boolean isElementDisplayed(WebElement element) {
        wait.waitForVisibilityOfElement(element);
        return element.isDisplayed();
    }

}
