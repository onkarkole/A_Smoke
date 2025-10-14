package com.qa.runner;

import io.cucumber.testng.CucumberOptions;

@CucumberOptions(
        features = { "src/test/resources/features" }, // dummy placeholder
        glue = { "com.qa.stepdefinitions", "com.qa.hooks" },
        plugin = {
                "pretty",
                "com.aventstack.extentreports.cucumber.adapter.ExtentCucumberAdapter:",
                "timeline:test-output-thread/",
                "rerun:target/failedscenarios.txt"
        }
)
public class TestngRunner extends TestRunnerBase {
}
