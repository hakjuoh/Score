package org.oagi.score.gateway.http.common.util.string;

/**
 * Naming strategy interface
 */
public interface StringConverter {

    /**
     * Converts a string to the specific naming convention
     *
     * @param term the input term to convert
     * @return the converted string following this naming strategy
     */
    String convert(String term);

}
