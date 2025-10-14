package com.qa.runner;

import io.cucumber.testng.CucumberOptions;

@CucumberOptions(
    features = "@target/failedscenarios.txt",  // Note the '@' for rerun file
    glue = { "com.qa.stepdefinitions", "com.qa.hooks" },
    plugin = {
        "pretty",
        "com.aventstack.extentreports.cucumber.adapter.ExtentCucumberAdapter:",
        "timeline:test-output-thread-retry/"
    }
)
public class RetryFailedRunner extends TestRunnerBase {
}
