package org.oagi.score.gateway.http.common.util.string;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public abstract class AbstractCamelCaseStringConverter implements StringConverter {

    /**
     * Regex pattern for separators.
     * Default: multiple whitespace characters.
     */
    private final Pattern separatorPattern;

    /**
     * Whether to preserve acronyms (all-uppercase words)
     */
    private final boolean preserveAcronym;

    protected AbstractCamelCaseStringConverter() {
        this("\\s+", true); // default: split on one or more whitespace
    }

    protected AbstractCamelCaseStringConverter(String separatorRegex, boolean preserveAcronym) {
        this.separatorPattern = Pattern.compile(separatorRegex);
        this.preserveAcronym = preserveAcronym;
    }

    /**
     * Determines whether the first word should be capitalized.
     */
    protected abstract boolean capitalizeFirstWord();

    @Override
    public String convert(String term) {
        if (term == null || term.isBlank()) return term;

        List<String> words = splitIntoWords(term);
        if (words.isEmpty()) return "";

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            if (word.isEmpty()) continue;

            if (i == 0) { // first word
                if (capitalizeFirstWord()) {
                    result.append(Character.toUpperCase(word.charAt(0)));
                } else {
                    result.append(Character.toLowerCase(word.charAt(0)));
                }
                if (word.length() > 1) {
                    result.append(word.substring(1));
                }
            } else { // subsequent words
                if (preserveAcronym && word.chars().allMatch(c -> Character.isUpperCase(c) || Character.isDigit(c))) {
                    result.append(word); // preserve acronym
                } else {
                    result.append(word); // leave unchanged
                }
            }
        }

        return result.toString();
    }

    /**
     * Splits the term into words based only on separators
     */
    private List<String> splitIntoWords(String term) {
        List<String> words = new ArrayList<>();
        String[] parts = separatorPattern.split(term, -1);
        for (String part : parts) {
            if (!part.isEmpty()) words.add(part);
        }
        return words;
    }

}