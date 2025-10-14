package com.qa.runner;

import com.qa.utils.ConfigManager;
import com.qa.utils.FeatureTagExtractor;
import com.qa.utils.RunnerJsonReader;
import com.qa.utils.TagUtils;
import io.cucumber.testng.FeatureWrapper;
import io.cucumber.testng.PickleWrapper;
import io.cucumber.testng.TestNGCucumberRunner;
import org.apache.logging.log4j.ThreadContext;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class TestRunnerBase {

    private static final Properties CONFIG = new ConfigManager().getConfigProps();
    private static final ThreadLocal<TestNGCucumberRunner> testNGCucumberRunner = new ThreadLocal<>();

    public static TestNGCucumberRunner getRunner() {
        return testNGCucumberRunner.get();
    }

    private static void setRunner(TestNGCucumberRunner runner) {
        testNGCucumberRunner.set(runner);
    }

    @BeforeClass(alwaysRun = true)
    public void setUpClass() {
        String logsDir = Paths.get(System.getProperty("user.dir"), "Logs").toString();
        createDirectoryIfNotExists(logsDir);
        ThreadContext.put("ROUTINGKEY", logsDir);
        System.out.println("Default log directory for class setup: " + logsDir);

        String executionMode = CONFIG.getProperty("execution.mode", "tags").trim();

        if (executionMode.equalsIgnoreCase("features")) {
            String[] featuresToRun = featuresToRun();
            String featurePaths = String.join(",", featuresToRun);
            System.setProperty("cucumber.features", featurePaths);
            System.clearProperty("cucumber.filter.tags");
            System.out.println("Running via feature files from JSON: " + featurePaths);
        } else {
            validateAndSetTags();
        }

        setRunner(new TestNGCucumberRunner(this.getClass()));
    }

    /**
     * Validates tags from config, sets cucumber.filter.tags,
     * and also persists the ordered list so we can sort scenarios later.
     */
    private void validateAndSetTags() {
        String rawTags = CONFIG.getProperty("Tags", "").trim();

        if (rawTags.isEmpty()) {
            System.out.println("'Tags' property is missing or empty in config.properties.");
            throw new RuntimeException("'Tags' property is missing or empty in config.properties.");
        }

        String formattedTags = TagUtils.formatTags(rawTags); // e.g. "@event or @EventApprove"
        if (formattedTags.isEmpty()) {
            System.out.println("No valid tags could be derived from 'Tags'. Please check syntax.");
            throw new RuntimeException("No valid tags could be derived from 'Tags'.");
        }

        Set<String> availableTags = FeatureTagExtractor.extractAllTags();
        Map<String, String> availableTagMap = availableTags.stream()
                .collect(Collectors.toMap(String::toLowerCase, t -> t));

        // Preserve order exactly as in config (via formattedTags split)
        List<String> requestedTags = TagUtils.splitTags(formattedTags); // ["@event", "@EventApprove"]

        List<String> finalValidTags = new ArrayList<>();
        List<String> invalidTags = new ArrayList<>();

        for (String tag : requestedTags) {
            String lowerTag = tag.toLowerCase();
            if (availableTagMap.containsKey(lowerTag)) {
                // keep original case from available set
                finalValidTags.add(availableTagMap.get(lowerTag));
            } else {
                invalidTags.add(tag);
            }
        }

        if (!invalidTags.isEmpty()) {
            System.out.println("Invalid tag(s) found: " + invalidTags +
                    "\n Available tags: " + availableTags +
                    "\n Aborting test execution due to tag mismatch.");
            System.exit(1);
        }

        // 1) Set filter for Cucumber
        String finalTagString = String.join(" or ", finalValidTags);
        System.setProperty("cucumber.filter.tags", finalTagString);
        System.out.println("Validated Tags from config: " + finalTagString);

        // 2) Persist ORDER for DataProvider sorting (comma-separated, includes "@", order preserved)
        System.setProperty("ordered.tags.sequence", String.join(",", finalValidTags));
    }

    @Test(groups = "cucumber", description = "Runs Cucumber Scenarios", dataProvider = "scenarios")
    public void scenario(PickleWrapper pickleWrapper, FeatureWrapper cucumberFeature) {
        System.out.println("Running scenario: " + pickleWrapper.getPickle().getName());
        String scenarioName = pickleWrapper.getPickle().getName()
                .replaceAll("[^a-zA-Z0-9-_]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "")
                .trim();
        System.out.println("Scenario name: " + scenarioName);
        String routingDir = Paths.get(System.getProperty("user.dir"), "Logs", scenarioName).toString();
        System.out.println("Scenario log directory: " + routingDir);
        createDirectoryIfNotExists(routingDir);
        System.out.println("Created log directory: " + routingDir);
        ThreadContext.put("ROUTINGKEY", routingDir);
        System.out.println("Scenario log directory: " + routingDir);

        try {
            getRunner().runScenario(pickleWrapper.getPickle());
        } catch (Throwable e) {
            throw new RuntimeException("Scenario failed: " + pickleWrapper.getPickle().getName(), e);
        }
    }

    @DataProvider
    public Object[][] scenarios() {
        Object[][] allScenarios = getRunner().provideScenarios();
        String executionMode = CONFIG.getProperty("execution.mode", "tags").trim();

        // If running by feature order, keep your existing filtering
        if (executionMode.equalsIgnoreCase("features")) {
            List<String> orderedFeatures = RunnerJsonReader.getFeatureList();
            List<Object[]> filteredScenarios = new ArrayList<>();

            for (String featureName : orderedFeatures) {
                for (Object[] scenario : allScenarios) {
                    PickleWrapper pw = (PickleWrapper) scenario[0];
                    String uri = pw.getPickle().getUri().toString();
                    if (uri.endsWith(featureName)) {
                        filteredScenarios.add(scenario);
                    }
                }
            }
            return filteredScenarios.toArray(new Object[0][]);
        }

        // ---- ORDER BY TAGS (default execution.mode=tags) ----
        String ordered = System.getProperty("ordered.tags.sequence", "").trim();
        if (ordered.isEmpty()) {
            // No explicit order persisted; return as-is
            return allScenarios;
        }

        // Example persisted: "@event,@EventApprove"
        List<String> orderedTags = Arrays.stream(ordered.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        // Build rank map (@tag -> index)
        Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < orderedTags.size(); i++) {
            rank.put(orderedTags.get(i).toLowerCase(), i);
        }

        // Sort scenarios by the earliest matching tag's rank
        List<Object[]> list = new ArrayList<>(Arrays.asList(allScenarios));
        list.sort((a, b) -> {
            int ra = scenarioRank((PickleWrapper) a[0], rank);
            int rb = scenarioRank((PickleWrapper) b[0], rank);
            return Integer.compare(ra, rb);
        });

        return list.toArray(new Object[0][]);
    }

    /**
     * Computes a scenario's rank based on the first matching tag
     * in the ordered tag list. Unknown or unmatched scenarios are pushed to the end.
     */
    private static int scenarioRank(PickleWrapper pw, Map<String, Integer> rank) {
        int best = Integer.MAX_VALUE;

        for (String t : getTagNames(pw)) {
            String normalized = t == null ? "" : t.trim();
            if (!normalized.startsWith("@")) {
                normalized = "@" + normalized.replaceAll("^@+", "");
            }
            Integer r = rank.get(normalized.toLowerCase());
            if (r != null && r < best) {
                best = r;
            }
        }
        return best;
    }

    /**
     * Attempts to extract tag names from the PickleWrapper across Cucumber versions.
     * Supports:
     * - List<String> (newer versions)
     * - List<...> where element has getName() (older or different versions)
     */
    @SuppressWarnings("unchecked")
    private static List<String> getTagNames(PickleWrapper pw) {
        try {
            Object pickle = pw.getPickle();
            // Try method: getTags() -> List<String>
            Method m = pickle.getClass().getMethod("getTags");
            Object tagsObj = m.invoke(pickle);
            if (tagsObj instanceof List) {
                List<?> raw = (List<?>) tagsObj;
                if (raw.isEmpty()) return Collections.emptyList();

                // Case 1: List<String>
                if (raw.get(0) instanceof String) {
                    return (List<String>) raw;
                }

                // Case 2: List<SomeTagObject> having getName()
                Method getName = null;
                try {
                    getName = raw.get(0).getClass().getMethod("getName");
                } catch (NoSuchMethodException ignore) {
                    // Some versions expose "name()" instead of "getName()"
                    try {
                        getName = raw.get(0).getClass().getMethod("name");
                    } catch (NoSuchMethodException ignore2) { /* fallthrough */ }
                }

                if (getName != null) {
                    List<String> names = new ArrayList<>();
                    for (Object o : raw) {
                        Object val = getName.invoke(o);
                        if (val != null) names.add(val.toString());
                    }
                    return names;
                }
            }
        } catch (Exception e) {
            // Fallback below
        }
        return Collections.emptyList();
    }

    @AfterClass(alwaysRun = true)
    public void tearDownClass() {
        if (testNGCucumberRunner.get() != null) {
            getRunner().finish();
        }
    }

    public static String[] featuresToRun() {
        String mode = CONFIG.getProperty("execution.mode", "tags").trim();

        if (mode.equalsIgnoreCase("features")) {
            List<String> featureFiles = RunnerJsonReader.getFeatureList();
            return featureFiles.stream()
                    .map(name -> Paths.get("src", "test", "resources", "features", name).toString())
                    .toArray(String[]::new);
        }

        return new String[]{"src/test/resources/features"};
    }

    private static void createDirectoryIfNotExists(String path) {
        File dir = new File(path);
        if (!dir.exists()) {
            dir.mkdirs();
            System.out.println("Created directory: " + path);
        }
    }
}