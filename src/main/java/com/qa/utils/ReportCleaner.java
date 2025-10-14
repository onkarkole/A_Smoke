package com.qa.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

public class ReportCleaner {

    private static final String REPORT_BASE_PATH = "extends_reports";
    private static final String FOLDER_PREFIX = "Reports ";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd-MMM-yy");

    // Private constructor to hide the implicit public one
    private ReportCleaner() {
        throw new UnsupportedOperationException("Utility class - instantiation not allowed");
    }

    public static void cleanOldReportFolders() {
        File baseDir = new File(REPORT_BASE_PATH);

        if (!baseDir.exists() || !baseDir.isDirectory()) {
            TestUtils.log().warn("Base folder not found or not a directory: {}", REPORT_BASE_PATH);
            return;
        }

        File[] folders = baseDir.listFiles((dir, name) ->
                name.startsWith(FOLDER_PREFIX) && new File(dir, name).isDirectory());

        if (folders == null || folders.length == 0) {
            TestUtils.log().info("No timestamped report folders found under: {}", REPORT_BASE_PATH);
            return;
        }

        LocalDate today = LocalDate.now();

        Arrays.stream(folders).forEach(folder -> {
            try {
                String folderName = folder.getName();
                String dateStr = folderName.substring(FOLDER_PREFIX.length()).split(" ")[0]; // e.g. "09-Apr-25"

                LocalDate folderDate = LocalDate.parse(dateStr, DATE_FORMATTER);
                long ageInDays = java.time.temporal.ChronoUnit.DAYS.between(folderDate, today);

                if (ageInDays > 7) {
                    deleteDirectoryRecursively(folder.toPath());
                    TestUtils.log().info("Deleted old report folder: {} (Age: {} days)", folderName, ageInDays);
                }

            } catch (Exception e) {
                TestUtils.log().warn("Skipping invalid folder: {} (Reason: {})", folder.getName(), e.getMessage());
            }
        });
    }

    private static void deleteDirectoryRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var entries = Files.list(path)) {
                for (Path entry : entries.toList()) {
                    deleteDirectoryRecursively(entry);
                }
            }
        }
        try {
            Files.delete(path);
        } catch (DirectoryNotEmptyException e) {
            throw new IOException("Directory not empty: " + path, e);
        } catch (IOException e) {
            throw new IOException("Failed to delete: " + path, e);
        }
    }
}
