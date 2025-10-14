package com.qa.utils;

import java.util.ArrayList;
import java.util.List;

public class TagUtils {

    private TagUtils(){
        throw new UnsupportedOperationException("Utility class - instantiation not allowed");
    }

    /**
     * Converts a comma-separated list like "event, EventApprove"
     * into a Cucumber tag expression like "@event or @EventApprove".
     *
     * @param rawTags Tags string from config (comma-separated)
     * @return formatted tag expression suitable for cucumber.filter.tags
     */
    public static String formatTags(String rawTags) {
        if (rawTags == null || rawTags.trim().isEmpty()) return "";

        String[] tagArray = rawTags.split(",");
        StringBuilder formatted = new StringBuilder();

        for (String tag : tagArray) {
            tag = normalizeTag(tag);
            if (!tag.isEmpty()) {
                if (!formatted.isEmpty()) {
                    formatted.append(" or ");
                }
                formatted.append(tag);
            }
        }
        return formatted.toString();
    }

    /**
     * Splits a formatted tag expression (e.g. "@smoke or @regression")
     * into a list of normalized tags ["@smoke", "@regression"].
     * Preserves the original order.
     *
     * @param formattedTags Tag expression string
     * @return ordered list of normalized tags
     */
    public static List<String> splitTags(String formattedTags) {
        // Handles cases like "@smoke and @regression or @login"
        String[] parts = formattedTags.split("\\s+(and|or)\\s+");
        List<String> tags = new ArrayList<>();
        for (String part : parts) {
            tags.add(normalizeTag(part));
        }
        return tags;
    }

    /**
     * Ensures a tag always starts with '@' and trims whitespace.
     *
     * @param tag tag string (may or may not start with @)
     * @return normalized tag (always prefixed with @)
     */
    private static String normalizeTag(String tag) {
        if (tag == null) return "";
        tag = tag.trim();
        if (tag.isEmpty()) return "";
        if (!tag.startsWith("@")) {
            tag = "@" + tag.replaceAll("^@+", "");
        }
        return tag;
    }
}