@test
Feature: Verify if the application is accessible

  Scenario: Verify if admin is able to access the application
    Given User is on the login page
    When User enter the username
    And Enter the password
    Then Click on login button
    Then Verify if dashboard is visible
    Then Clicked on logout button

  Scenario: Verify if trainer is able to access the application
    Given User is on the login page
    When User enter the username
    And Enter the password
    Then Click on login button
    Then Verify if dashboard is visible
    Then Clicked on logout button