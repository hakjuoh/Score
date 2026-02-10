package org.oagi.score.gateway.http.common.util.string;

import java.util.regex.Pattern;

public class LiteralCaseStringConverter implements StringConverter {

    private final Pattern separatorPattern;

    /**
     * Default constructor: splits on whitespace.
     */
    public LiteralCaseStringConverter() {
        this("\\s+"); // default: split on one or more whitespace
    }

    /**
     * Constructor with custom separator regex.
     *
     * @param separatorRegex the regex for separators
     */
    public LiteralCaseStringConverter(String separatorRegex) {
        this.separatorPattern = Pattern.compile(separatorRegex);
    }

    @Override
    public String convert(String term) {
        if (term == null || term.isBlank()) return term;

        String[] parts = separatorPattern.split(term, -1);
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) result.append(part);
        }
        return result.toString();
    }

}
