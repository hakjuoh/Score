package org.oagi.score.gateway.http.api.export.model;

import org.oagi.score.gateway.http.api.agency_id_management.model.AgencyIdListSummaryRecord;
import org.oagi.score.gateway.http.api.cc_management.model.dt.DtSummaryRecord;
import org.oagi.score.gateway.http.api.code_list_management.model.CodeListSummaryRecord;
import org.oagi.score.gateway.http.common.util.string.CamelCaseStringConverter;
import org.oagi.score.gateway.http.common.util.string.LiteralCaseStringConverter;
import org.oagi.score.gateway.http.common.util.string.PascalCaseStringConverter;
import org.oagi.score.gateway.http.common.util.string.StringConverter;

import java.util.function.Function;

public abstract class ConnectSpecNameResolvers {

    private ConnectSpecNameResolvers() {}

    public static StringConverter connectSpecStringConverter(StringConverter delegate) {
        return str -> delegate.convert(str.replaceAll("Identifier", "ID"));
    }

    public static String literalCase(String str) {
        return connectSpecStringConverter(new LiteralCaseStringConverter()).convert(str);
    }

    public static String pascalCase(String str) {
        return connectSpecStringConverter(new PascalCaseStringConverter()).convert(str);
    }

    public static String pascalCase(String str, String separatorRegex) {
        return connectSpecStringConverter(new PascalCaseStringConverter(separatorRegex, true)).convert(str);
    }

    public static String camelCase(String str) {
        return connectSpecStringConverter(new CamelCaseStringConverter()).convert(str);
    }

    public static Function<AgencyIdListSummaryRecord, String> agencyIdListNameResolver =
            (agencyIdList) -> literalCase(agencyIdList.name());

    public static Function<CodeListSummaryRecord, String> codeListNameResolver =
            (codeList) -> literalCase(codeList.name());

    public static Function<DtSummaryRecord, String> dtNameResolver =
            (dt) -> pascalCase(dt.den(), "(\\. |_ |\\s)") + ((dt.sixDigitId() != null) ? "_" + dt.sixDigitId() : "");

}
