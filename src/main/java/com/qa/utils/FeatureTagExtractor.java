package com.qa.utils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class FeatureTagExtractor {

    private FeatureTagExtractor() {
        throw new UnsupportedOperationException("Utility class should not be instantiated");
    }


    private static final String FEATURES_DIR = "src/test/resources/features";

    /**
     * Walks through all .feature files under FEATURES_DIR and collects all unique tags.
     * @return A Set of unique tags found across all feature files.
     */
    public static Set<String> extractAllTags() {
        Set<String> tags = new HashSet<>();
        try (Stream<Path> paths = Files.walk(Paths.get(FEATURES_DIR))) {
            paths.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".feature"))
                    .forEach(path -> tags.addAll(extractTagsFromFile(path)));
        } catch (IOException e) {
            throw new UncheckedIOException("Error reading feature files for tag extraction", e);
        }
        return tags;
    }

    /**
     * Extracts tags from a single feature file.
     * Tags are assumed to be on lines starting with '@' and separated by spaces.
     * @param path Path to the feature file.
     * @return A Set of tags found in the file.
     */
    private static Set<String> extractTagsFromFile(Path path) {
        Set<String> tags = new HashSet<>();
        Pattern tagLinePattern = Pattern.compile("^\\s*(@.+)$");  // Matches lines starting with @ and captures whole line
        try {
            List<String> lines = Files.readAllLines(path);
            for (String line : lines) {
                Matcher matcher = tagLinePattern.matcher(line);
                if (matcher.find()) {
                    String tagLine = matcher.group(1);  // whole tag line
                    // Split tags by whitespace, each tag starts with '@'
                    String[] lineTags = tagLine.trim().split("\\s+");
                    Collections.addAll(tags, lineTags);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Error reading file: " + path, e);
        }
        return tags;
    }
}
