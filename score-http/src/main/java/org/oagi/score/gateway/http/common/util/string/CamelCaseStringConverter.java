package org.oagi.score.gateway.http.common.util.string;

/**
 * camelCase implementation (lowerCamelCase)
 */
public class CamelCaseStringConverter extends AbstractCamelCaseStringConverter {

    @Override
    protected boolean capitalizeFirstWord() {
        return false;
    }

}
