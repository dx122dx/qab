package com.billy65536.qab.planning;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simple parser for Minecraft SNBT (stringified NBT) format.
 * Handles extraction of enchantments and basic key-value matching
 * for matching shopping list items against shop entries.
 *
 * <p>SNBT format example:
 * <pre>{@code
 * {id: "minecraft:diamond_sword", Count: 1b, tag: {Enchantments: [{id: "minecraft:sharpness", lvl: 5s}], Damage: 0}}
 * }</pre>
 */
public class SnbtParser {

    // Pattern to extract Enchantments list from SNBT
    // Matches: Enchantments: [{id: "ench_id", lvl: 5s}, ...]
    private static final Pattern ENCHANTMENTS_PATTERN = Pattern.compile(
            "Enchantments\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL);

    // Pattern to extract a single enchantment entry: {id: "id", lvl: Ns}
    private static final Pattern ENCHANT_ENTRY_PATTERN = Pattern.compile(
            "\\{id\\s*:\\s*\"([^\"]+)\"\\s*,\\s*lvl\\s*:\\s*(\\d+)s?\\s*}");

    // Pattern to extract StoredEnchantments from SNBT (for enchanted books)
    private static final Pattern STORED_ENCHANTMENTS_PATTERN = Pattern.compile(
            "StoredEnchantments\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL);

    /**
     * Extracts all enchantments from an SNBT string.
     * Checks both Enchantments and StoredEnchantments tags.
     *
     * @param snbt the SNBT string to parse
     * @return map of enchantment ID to level, or empty map if none found
     */
    public static Map<String, Integer> extractEnchantments(String snbt) {
        if (snbt == null || snbt.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Integer> enchants = new LinkedHashMap<>();

        // Check Enchantments
        extractFromPattern(snbt, ENCHANTMENTS_PATTERN, enchants);
        // Check StoredEnchantments (for enchanted books)
        extractFromPattern(snbt, STORED_ENCHANTMENTS_PATTERN, enchants);

        return enchants;
    }

    private static void extractFromPattern(String snbt, Pattern listPattern, Map<String, Integer> result) {
        Matcher listMatcher = listPattern.matcher(snbt);
        while (listMatcher.find()) {
            String enchantList = listMatcher.group(1);
            Matcher entryMatcher = ENCHANT_ENTRY_PATTERN.matcher(enchantList);
            while (entryMatcher.find()) {
                String id = entryMatcher.group(1);
                int level = Integer.parseInt(entryMatcher.group(2));
                result.put(id, level);
            }
        }
    }

    /**
     * Checks whether an SNBT string satisfies the required enchantments.
     * All required enchantments must be present at or above the specified levels.
     *
     * @param snbt the SNBT string of the shop item
     * @param requiredEnchants the required enchantments (id -> minimum level)
     * @return true if all required enchantments are satisfied
     */
    public static boolean matchesEnchantments(String snbt, Map<String, Integer> requiredEnchants) {
        if (requiredEnchants == null || requiredEnchants.isEmpty()) {
            return true;
        }
        Map<String, Integer> actual = extractEnchantments(snbt);
        for (Map.Entry<String, Integer> required : requiredEnchants.entrySet()) {
            Integer actualLevel = actual.get(required.getKey());
            if (actualLevel == null || actualLevel < required.getValue()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks whether the tag portion of an SNBT string contains specific key-value pairs.
     * This is used for the matchNbt feature.
     *
     * <p>The matchNbt string is provided as a JSON object like {@code {"someValue":12345}}.
     * We check if each key-value pair appears in the SNBT tag section.
     *
     * @param snbt the full SNBT string of the shop item
     * @param matchNbtJson the JSON string to match (e.g. {"key": value})
     * @return true if all specified key-value pairs are found in the tag
     */
    public static boolean matchesNbt(String snbt, String matchNbtJson) {
        if (matchNbtJson == null || matchNbtJson.isEmpty()) {
            return true;
        }
        if (snbt == null || snbt.isEmpty()) {
            return false;
        }

        // Extract the tag portion from snbt
        String tagContent = extractTagContent(snbt);
        if (tagContent == null) {
            return false;
        }

        // Parse the matchNbt JSON to extract key-value pairs
        Map<String, String> requiredPairs = parseSimpleJsonPairs(matchNbtJson);

        // Check each pair exists in the tag content
        for (Map.Entry<String, String> entry : requiredPairs.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            // Build patterns to find the key-value in SNBT
            // SNBT format: key: value or key: "value" or key: 123b etc.
            boolean found = findKeyValueInSnbt(tagContent, key, value);
            if (!found) {
                return false;
            }
        }

        return true;
    }

    /**
     * Extracts the content of the "tag" compound from SNBT.
     */
    private static String extractTagContent(String snbt) {
        int tagIndex = snbt.indexOf("tag:");
        if (tagIndex < 0) {
            // No tag section - if matchNbt is required, can't match
            return null;
        }

        // Find the opening brace after "tag:"
        int braceStart = snbt.indexOf('{', tagIndex);
        if (braceStart < 0) {
            return null;
        }

        // Find the matching closing brace
        int depth = 0;
        int i = braceStart;
        for (; i < snbt.length(); i++) {
            char c = snbt.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) break;
            } else if (c == '"') {
                // Skip string literals
                i++;
                while (i < snbt.length() && snbt.charAt(i) != '"') {
                    if (snbt.charAt(i) == '\\') i++; // skip escaped chars
                    i++;
                }
            }
        }

        return snbt.substring(braceStart, i + 1);
    }

    /**
     * Parses a simple JSON object into key-value string pairs.
     * Only handles top-level key-value pairs with primitive values (numbers, strings, booleans).
     */
    private static Map<String, String> parseSimpleJsonPairs(String json) {
        Map<String, String> pairs = new LinkedHashMap<>();
        String trimmed = json.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return pairs;
        }

        // Remove outer braces
        String content = trimmed.substring(1, trimmed.length() - 1).trim();
        if (content.isEmpty()) {
            return pairs;
        }

        // Split by commas, but be careful with nested structures
        // For simple flat JSON this is sufficient
        String[] kvPairs = splitJsonPairs(content);
        for (String kv : kvPairs) {
            kv = kv.trim();
            int colonIdx = findUnquotedColon(kv);
            if (colonIdx < 0) continue;

            String key = kv.substring(0, colonIdx).trim();
            String value = kv.substring(colonIdx + 1).trim();

            // Strip quotes from key
            if (key.startsWith("\"") && key.endsWith("\"")) {
                key = key.substring(1, key.length() - 1);
            }
            // Strip quotes from string values
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }

            pairs.put(key, value);
        }

        return pairs;
    }

    private static String[] splitJsonPairs(String content) {
        List<String> pairs = new ArrayList<>();
        int depth = 0;
        int start = 0;
        boolean inString = false;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '"' && (i == 0 || content.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (!inString) {
                if (c == '{' || c == '[') depth++;
                else if (c == '}') depth--;
                else if (c == ']') depth--;
                else if (c == ',' && depth == 0) {
                    pairs.add(content.substring(start, i));
                    start = i + 1;
                }
            }
        }
        pairs.add(content.substring(start));
        return pairs.toArray(new String[0]);
    }

    private static int findUnquotedColon(String s) {
        boolean inString = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (c == ':' && !inString) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Searches for a key-value pair in SNBT content.
     * SNBT format: key: value (no quotes on key, variety of value types)
     *
     * @param snbtContent the SNBT content to search in
     * @param key the key to find
     * @param expectedValue the expected value as a string
     * @return true if found
     */
    private static boolean findKeyValueInSnbt(String snbtContent, String key, String expectedValue) {
        // Build a pattern: key followed by colon and the expected value
        // The value could be: number (123), string ("value"), or typed (123b, 5s, 1.0d, etc.)
        // We'll search for the key and then check if the value matches

        String escapedKey = Pattern.quote(key);
        String escapedValue = Pattern.quote(expectedValue);

        // Pattern 1: key: value (unquoted number/identifier)
        Pattern p1 = Pattern.compile(escapedKey + "\\s*:\\s*" + escapedValue + "(?=[,\\s}])");
        if (p1.matcher(snbtContent).find()) return true;

        // Pattern 2: key: "value" (quoted string)
        Pattern p2 = Pattern.compile(escapedKey + "\\s*:\\s*\"" + escapedValue + "\"");
        if (p2.matcher(snbtContent).find()) return true;

        // Pattern 3: key: value<NBT suffix> (e.g. 123b, 5s, 1.0d)
        Pattern p3 = Pattern.compile(escapedKey + "\\s*:\\s*" + escapedValue + "[bsldfBSLDF](?=[,\\s}])");
        if (p3.matcher(snbtContent).find()) return true;

        return false;
    }
}
