package com.qa.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Simplified download utility:
 * - Always waits up to 60 seconds for a new completed file.
 * - Cleans the folder automatically if >= MAX_FILES completed files exist (before each download).
 * - Works with dynamic filenames by using time-based detection.
 * Typical usage from BasePage:
 *   Instant start = Instant.now();
 *   click(downloadButton, "Clicked Download");
 *   Path file = DownloadUtils.waitForNewDownloadSince(start); // auto timeout 60s
 */
public final class DownloadUtils {

    private DownloadUtils() {}

    /** Clean when there are this many completed files or more. Keep as 1 to ensure a single fresh file per run. */
    private static final int MAX_FILES = 1;

    /** Global max wait for a download to complete. */
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    /** Poll interval while waiting. */
    private static final long POLL_MS = 300L;

    /** Temp/in-progress suffixes to ignore while waiting. */
    private static final String[] IN_PROGRESS_SUFFIXES = { ".crdownload", ".part", ".tmp" };

    // =========================================================
    // Public API for BasePage orchestration
    // =========================================================

    /** Returns the canonical download directory path. */
    public static Path getDownloadDir() {
        return ensureDownloadDir();
    }

    /**
     * Ensure download directory exists; if completed files >= MAX_FILES, clear it.
     * Call this right BEFORE triggering a new download.
     */
    public static void prepareDownloadDir() {
        Path dir = ensureDownloadDir();
        if (countCompletedFiles(dir) >= MAX_FILES) {
            cleanDownloadDir(dir);
        }
    }

    /**
     * Wait up to TIMEOUT for a new completed file that appeared/finished AFTER the given start time.
     * Ideal for dynamic filenames. Ignores in-progress files (.crdownload/.part/.tmp).
     * Returns any file type.
     */
    public static Path waitForNewDownloadSince(Instant start) {
        Path dir = ensureDownloadDir();
        Instant deadline = Instant.now().plus(TIMEOUT);

        while (Instant.now().isBefore(deadline)) {
            Optional<Path> newest = newestDownloadedFile(dir);
            if (newest.isPresent()) {
                Path file = newest.get();
                if (isCompleted(file) && isNewerThan(file, start)) {
                    return file;
                }
            }
            sleep(); // fixed-interval poll
        }
        throw new IllegalStateException("Timed out waiting for a new completed download since " + start);
    }

    // =========================================================
    // Internals
    // =========================================================

    private static Path ensureDownloadDir() {
        Path dir = Paths.get(System.getProperty("user.dir"),
                "src", "test", "resources", "downloads").toAbsolutePath();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create downloads directory: " + dir, e);
        }
        return dir;
    }

    private static int countCompletedFiles(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return (int) stream.filter(DownloadUtils::isCompleted).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static void cleanDownloadDir(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            stream.forEach(p -> {
                try {
                    if (Files.isRegularFile(p)) {
                        Files.deleteIfExists(p);
                    }
                } catch (IOException e) {
                    TestUtils.log().error("Failed to delete file: {}", p, e);
                }
            });
        } catch (IOException e) {
            TestUtils.log().error("Failed to clean download directory: {}", dir, e);
        }
    }

    private static Optional<Path> newestDownloadedFile(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(DownloadUtils::isCompleted)
                    .max(Comparator.comparingLong(DownloadUtils::fileTimestampMillis));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static boolean isCompleted(Path p) {
        return Files.isRegularFile(p) && !isInProgress(p);
    }

    private static boolean isInProgress(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        for (String suf : IN_PROGRESS_SUFFIXES) {
            if (name.endsWith(suf)) return true;
        }
        return false;
    }

    /** Consider a file “new” if its best-available timestamp is after the start time. */
    private static boolean isNewerThan(Path p, Instant since) {
        return fileTimestampMillis(p) >= since.toEpochMilli();
    }

    /**
     * Use the max of creationTime and lastModifiedTime to avoid FS/clock quirks.
     * Some systems write lastModified earlier than creation or vice versa.
     */
    private static long fileTimestampMillis(Path p) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
            long created = attrs.creationTime().toMillis();
            long modified = attrs.lastModifiedTime().toMillis();
            return Math.max(created, modified);
        } catch (IOException e) {
            // fallback
            try {
                return Files.getLastModifiedTime(p).toMillis();
            } catch (IOException ex) {
                return 0L;
            }
        }
    }

    /** Fixed-interval sleep using POLL_MS to satisfy static analysis (no unused parameter). */
    private static void sleep() {
        try {
            Thread.sleep(POLL_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}