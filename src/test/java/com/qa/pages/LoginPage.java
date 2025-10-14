package com.qa.pages;

import com.qa.utils.BasePage;
import com.qa.utils.EmailManager;
import com.qa.utils.Waits;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.FindBy;
import org.openqa.selenium.support.How;

public class LoginPage extends BasePage {

    private static String role;

    @FindBy(how = How.ID, using = "kc-page-title")
    private WebElement loginPageTitle;

    @FindBy(how = How.ID, using = "username")
    private WebElement usernameTextField;

    @FindBy(how = How.ID, using = "password")
    private WebElement passwordTextField;

    @FindBy(how = How.ID, using = "code")
    private WebElement otpTextField;

    @FindBy(how = How.ID, using = "kc-login")
    private WebElement loginButton;

    @FindBy(how = How.XPATH, using = "//input[@value='Submit']")
    private WebElement submitButton;

    @FindBy(how = How.XPATH, using = "//span[@class='pf-v5-c-alert__title kc-feedback-text']")
    private WebElement errorMessageForInvalidOTP;

    @FindBy(how = How.XPATH, using = "//button[@id='dropdown-basic-button ']")
    private WebElement settingIcon;

    @FindBy(how = How.XPATH, using = "//a[text()='Logout']")
    private WebElement logoutButton;

    @FindBy(how = How.XPATH, using = "//li[@class='pro-menu-item white']//a[contains(@href,'admin-app/Home')]")
    private WebElement dashboardIcon;

    @FindBy(how = How.XPATH, using = "//span[@class='bar']/i")
    private WebElement menuIconExpander;

    public void getRole(String roleName){
        role = roleName;
    }

    public void navigateToApplication(){
        navigate(role);
    }

    public void enterUsername() {
        sendKeys(usernameTextField, getUsername(role), "Username entered");
    }

    public void enterPassword() {
        sendKeys(passwordTextField, getPassword(role), "Password entered");
    }

    public void enterOTP(String otpStatus) {
        String otp;
        if(otpStatus.equalsIgnoreCase("invalid")) {
            otp="0000";
        }else {
            otp = EmailManager.readOtpFromInbox();
        }
        sendKeys(otpTextField, otp, "OTP entered as " + otp);
    }

    public void clickLoginButton() {
        click(loginButton, "Login button clicked");
    }

    public void clickSubmitButton() {
        click(submitButton, "Submit button clicked");
    }


    public boolean validateDashboardPage() {
        click(menuIconExpander, "Menu icon clicked");
        return isElementDisplayed(dashboardIcon);
    }

    public void clickOnLogout() {
        click(settingIcon, "Setting icon clicked");
        click(logoutButton, "Logout button clicked");
    }

    public boolean validateSmokeTest(){
        new Waits().staticWait();
        return isElementDisplayed(settingIcon);
    }

}
