package com.qa.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class RunnerJsonReader {

    private RunnerJsonReader(){
        throw new UnsupportedOperationException("Utility class - instantiation not allowed");
    }


    public static List<String> getFeatureList() {
        List<String> featureFiles = new ArrayList<>();
        try {
            File file = Paths.get("src", "test", "resources", "runner.json").toFile();
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(file);
            JsonNode features = root.get("features");

            if (features.isArray()) {
                for (JsonNode feature : features) {
                    featureFiles.add(feature.asText());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read runner.json", e);
        }
        return featureFiles;
    }
}
