package org.oagi.score.export.model;

import org.jooq.types.ULong;
import org.oagi.score.repo.api.impl.jooq.entity.tables.records.BccpRecord;
import org.oagi.score.repo.api.impl.jooq.entity.tables.records.DtRecord;

public class BCCP implements Component {

    private BccpRecord bccp;
    private DtRecord bdt;

    public BCCP(BccpRecord bccp, DtRecord bdt) {
        this.bccp = bccp;
        this.bdt = bdt;
    }

    public String getGuid() {
        return bccp.getGuid();
    }

    public String getName() {
        String propertyTerm = bccp.getPropertyTerm();
        return propertyTerm.replaceAll(" ", "").replace("Identifier", "ID");
    }

    public String getTypeName() {
        return ModelUtils.getTypeName(bdt);
    }

    public boolean isNillable() {
        return bccp.getIsNillable() == 1;
    }

    public String getDefaultValue() {
        return bccp.getDefaultValue();
    }

    public String getDefinition() {
        return bccp.getDefinition();
    }

    public String getDefinitionSource() {
        return bccp.getDefinitionSource();
    }

    public ULong getNamespaceId() {
        return bccp.getNamespaceId();
    }

    public ULong getTypeNamespaceId() {
        return bdt.getNamespaceId();
    }
}
