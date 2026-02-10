package org.oagi.score.gateway.http.common.util.string;

/**
 * PascalCase implementation (UpperCamelCase)
 */
public class PascalCaseStringConverter extends AbstractCamelCaseStringConverter {

    public PascalCaseStringConverter() {
        super();
    }

    public PascalCaseStringConverter(String separatorRegex, boolean preserveAcronym) {
        super(separatorRegex, preserveAcronym);
    }

    @Override
    protected boolean capitalizeFirstWord() {
        return true;
    }

    public static void main(String[] args) {
//        System.out.println(new PascalCaseStringConverter().convert("Master WIP"));
//        System.out.println(new CamelCaseStringConverter().convert("Master WIP"));

        System.out.println(new PascalCaseStringConverter().convert("clm63055D16B_AgencyIdentification"));
        System.out.println(new CamelCaseStringConverter().convert("clm63055D16B_AgencyIdentification"));
    }

}
