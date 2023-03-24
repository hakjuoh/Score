package org.oagi.score.gateway.http.api.module_management.provider;

import org.jooq.types.ULong;
import org.oagi.score.export.model.ModuleCCID;
import org.oagi.score.repo.api.impl.jooq.entity.tables.records.*;
import org.oagi.score.repository.CoreComponentRepositoryForModuleSetRelease;
import org.oagi.score.repository.provider.DataProvider;
import org.oagi.score.repository.provider.ModuleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ModuleSetReleaseDataProvider implements DataProvider, ModuleProvider {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private CoreComponentRepositoryForModuleSetRelease coreComponentRepositoryForModuleSetRelease;
    private BigInteger moduleSetReleaseId;

    public ModuleSetReleaseDataProvider(CoreComponentRepositoryForModuleSetRelease coreComponentRepositoryForModuleSetRelease, BigInteger moduleSetReleaseId) {
        this.coreComponentRepositoryForModuleSetRelease = coreComponentRepositoryForModuleSetRelease;
        this.moduleSetReleaseId = moduleSetReleaseId;
        this.init();
    }

    public void init() {
        long s = System.currentTimeMillis();

        findAgencyIdListManifestMap = coreComponentRepositoryForModuleSetRelease.findAllAgencyIdListManifest(ULong.valueOf(moduleSetReleaseId)).stream()
                .collect(Collectors.toMap(AgencyIdListManifestRecord::getAgencyIdListManifestId, Function.identity()));
        findAgencyIdListList = coreComponentRepositoryForModuleSetRelease.findAllAgencyIdList(ULong.valueOf(moduleSetReleaseId));
        findModuleAgencyIdListManifestMap = coreComponentRepositoryForModuleSetRelease.findAllModuleAgencyIdListManifest(ULong.valueOf(moduleSetReleaseId))
                .stream().collect(Collectors.toMap(ModuleCCID::getCcId, Function.identity()));
        findAgencyIdListMap = findAgencyIdListList.stream()
                .collect(Collectors.toMap(AgencyIdListRecord::getAgencyIdListId, Function.identity()));

        findAgencyIdListValueByOwnerListIdMap = coreComponentRepositoryForModuleSetRelease.findAllAgencyIdListValue(ULong.valueOf(moduleSetReleaseId)).stream()
                .collect(Collectors.groupingBy(AgencyIdListValueRecord::getOwnerListId));

        findCodeListManifestMap = coreComponentRepositoryForModuleSetRelease.findAllCodeListManifest(ULong.valueOf(moduleSetReleaseId)).stream()
                .collect(Collectors.toMap(CodeListManifestRecord::getCodeListManifestId, Function.identity()));
        findCodeListList = coreComponentRepositoryForModuleSetRelease.findAllCodeList(ULong.valueOf(moduleSetReleaseId));
        findCodeListMap = findCodeListList.stream()
                .collect(Collectors.toMap(CodeListRecord::getCodeListId, Function.identity()));

        findCodeListValueByCodeListIdMap = coreComponentRepositoryForModuleSetRelease.findAllCodeListValue(ULong.valueOf(moduleSetReleaseId)).stream()
                .collect(Collectors.groupingBy(CodeListValueRecord::getCodeListId));

        findDtManifestList = coreComponentRepositoryForModuleSetRelease.findAllDtManifest(ULong.valueOf(moduleSetReleaseId));
        findDtManifestMap = findDtManifestList.stream()
                .collect(Collectors.toMap(DtManifestRecord::getDtManifestId, Function.identity()));

        findDtList = coreComponentRepositoryForModuleSetRelease.findAllDt(ULong.valueOf(moduleSetReleaseId));
        findDtMap = findDtList.stream()
                .collect(Collectors.toMap(DtRecord::getDtId, Function.identity()));

        findDtScByOwnerDtIdMap = coreComponentRepositoryForModuleSetRelease.findAllDtSc(ULong.valueOf(moduleSetReleaseId)).stream()
                .collect(Collectors.groupingBy(DtScRecord::getOwnerDtId));

        findDtScManifestByOwnerDtManifestIdMap = coreComponentRepositoryForModuleSetRelease.findAllDtScManifest(ULong.valueOf(moduleSetReleaseId)).stream()
                .collect(Collectors.groupingBy(DtScManifestRecord::getOwnerDtManifestId));
        findDtScMap = coreComponentRepositoryForModuleSetRelease.findAllDtSc(ULong.valueOf(moduleSetReleaseId)).stream()
                .collect(Collectors.toMap(DtScRecord::getDtScId, Function.identity()));

        List<BdtPriRestriRecord> bdtPriRestriList = coreComponentRepositoryForModuleSetRelease.findAllBdtPriRestri(ULong.valueOf(moduleSetReleaseId));
        findBdtPriRestriListByDtManifestIdMap = bdtPriRestriList.stream()
                .collect(Collectors.groupingBy(BdtPriRestriRecord::getBdtManifestId));

        cdtAwdPriXpsTypeMapMap = coreComponentRepositoryForModuleSetRelease.findAllCdtAwdPriXpsTypeMap(ULong.valueOf(moduleSetReleaseId)).stream()
                .collect(Collectors.toMap(CdtAwdPriXpsTypeMapRecord::getCdtAwdPriXpsTypeMapId, Function.identity()));

        findBdtScPriRestriListByDtScManifestIdMap = coreComponentRepositoryForModuleSetRelease.findAllBdtScPriRestri(ULong.valueOf(moduleSetReleaseId)).stream()
                .collect(Collectors.groupingBy(BdtScPriRestriRecord::getBdtScManifestId));

        findCdtScAwdPriXpsTypeMapMap = coreComponentRepositoryForModuleSetRelease.findAllCdtScAwdPriXpsTypeMap(ULong.valueOf(moduleSetReleaseId)).stream()
                .collect(Collectors.toMap(CdtScAwdPriXpsTypeMapRecord::getCdtScAwdPriXpsTypeMapId, Function.identity()));

        findCdtScAwdPriMap = coreComponentRepositoryForModuleSetRelease.findAllCdtScAwdPri(ULong.valueOf(moduleSetReleaseId)).stream()
                .collect(Collectors.toMap(CdtScAwdPriRecord::getCdtScAwdPriId, Function.identity()));

        List<CdtAwdPriRecord> cdtAwdPriList = coreComponentRepositoryForModuleSetRelease.findAllCdtAwdPri(ULong.valueOf(moduleSetReleaseId));
        findCdtAwdPriMap = cdtAwdPriList.stream()
                .collect(Collectors.toMap(CdtAwdPriRecord::getCdtAwdPriId, Function.identity()));

        List<CdtPriRecord> cdtPriList = coreComponentRepositoryForModuleSetRelease.findAllCdtPri();
        findCdtPriMap = cdtPriList.stream()
                .collect(Collectors.toMap(CdtPriRecord::getCdtPriId, Function.identity()));

        findXbtList = coreComponentRepositoryForModuleSetRelease.findAllXbt(ULong.valueOf(moduleSetReleaseId));
        findXbtMap = findXbtList.stream()
                .collect(Collectors.toMap(XbtRecord::getXbtId, Function.identity()));

        findACCList = coreComponentRepositoryForModuleSetRelease.findAllAcc(ULong.valueOf(moduleSetReleaseId));
        findAccMap = findACCList.stream()
                .collect(Collectors.toMap(AccRecord::getAccId, Function.identity()));

        findACCManifestList = coreComponentRepositoryForModuleSetRelease.findAllAccManifest(ULong.valueOf(moduleSetReleaseId));
        findAccManifestMap = findACCManifestList.stream()
                .collect(Collectors.toMap(AccManifestRecord::getAccManifestId, Function.identity()));

        findASCCPList = coreComponentRepositoryForModuleSetRelease.findAllAsccp(ULong.valueOf(moduleSetReleaseId));
        findAsccpMap = findASCCPList.stream()
                .collect(Collectors.toMap(AsccpRecord::getAsccpId, Function.identity()));
        findAsccpByGuidMap = findASCCPList.stream()
                .collect(Collectors.toMap(AsccpRecord::getGuid, Function.identity()));

        findASCCPManifestList = coreComponentRepositoryForModuleSetRelease.findAllAsccpManifest(ULong.valueOf(moduleSetReleaseId));
        findAsccpManifestMap = findASCCPManifestList.stream()
                .collect(Collectors.toMap(AsccpManifestRecord::getAsccpManifestId, Function.identity()));

        findBCCPManifestList = coreComponentRepositoryForModuleSetRelease.findAllBccpManifest(ULong.valueOf(moduleSetReleaseId));
        findBccpManifestMap = findBCCPManifestList.stream()
                .collect(Collectors.toMap(BccpManifestRecord::getBccpManifestId, Function.identity()));
        
        findBCCPList = coreComponentRepositoryForModuleSetRelease.findAllBccp(ULong.valueOf(moduleSetReleaseId));
        findBccpMap = findBCCPList.stream()
                .collect(Collectors.toMap(BccpRecord::getBccpId, Function.identity()));

        findACCManifestList = coreComponentRepositoryForModuleSetRelease.findAllAccManifest(ULong.valueOf(moduleSetReleaseId));
        findAccManifestMap = findACCManifestList.stream()
                .collect(Collectors.toMap(AccManifestRecord::getAccManifestId, Function.identity()));

        List<BccRecord> bccList = coreComponentRepositoryForModuleSetRelease.findAllBcc(ULong.valueOf(moduleSetReleaseId));

        findBCCByToBccpIdMap = bccList.stream()
                .collect(Collectors.groupingBy(BccRecord::getToBccpId));
        findBccByFromAccIdMap = bccList.stream()
                .collect(Collectors.groupingBy(BccRecord::getFromAccId));
        findBccMap = bccList.stream()
                .collect(Collectors.toMap(BccRecord::getBccId, Function.identity()));

        findBccManifestMap = coreComponentRepositoryForModuleSetRelease.findAllBccManifest(ULong.valueOf(moduleSetReleaseId)).stream()
                .collect(Collectors.toMap(BccManifestRecord::getBccManifestId, Function.identity()));
        findBccManifestByAccManifestIdMap = findBccManifestMap.values().stream()
                .collect(Collectors.groupingBy(BccManifestRecord::getFromAccManifestId));

        findAsccManifestMap = coreComponentRepositoryForModuleSetRelease.findAllAsccManifest(ULong.valueOf(moduleSetReleaseId)).stream()
                .collect(Collectors.toMap(AsccManifestRecord::getAsccManifestId, Function.identity()));
        findAsccManifestByAccManifestIdMap = findAsccManifestMap.values().stream()
                .collect(Collectors.groupingBy(AsccManifestRecord::getFromAccManifestId));

        List<AsccRecord> asccList = coreComponentRepositoryForModuleSetRelease.findAllAscc(ULong.valueOf(moduleSetReleaseId));
        findAsccByFromAccIdMap = asccList.stream()
                .collect(Collectors.groupingBy(AsccRecord::getFromAccId));

        findAsccMap = asccList.stream()
                .collect(Collectors.toMap(AsccRecord::getAsccId, Function.identity()));

        findModuleCodeListManifestMap = coreComponentRepositoryForModuleSetRelease.findAllModuleCodeListManifest(ULong.valueOf(moduleSetReleaseId))
                .stream().collect(Collectors.toMap(ModuleCCID::getCcId, Function.identity()));

        findModuleAccManifestMap = coreComponentRepositoryForModuleSetRelease.findAllModuleAccManifest(ULong.valueOf(moduleSetReleaseId))
                .stream().collect(Collectors.toMap(ModuleCCID::getCcId, Function.identity()));

        findModuleAsccpManifestMap = coreComponentRepositoryForModuleSetRelease.findAllModuleAsccpManifest(ULong.valueOf(moduleSetReleaseId))
                .stream().collect(Collectors.toMap(ModuleCCID::getCcId, Function.identity()));

        findModuleBccpManifestMap = coreComponentRepositoryForModuleSetRelease.findAllModuleBccpManifest(ULong.valueOf(moduleSetReleaseId))
                .stream().collect(Collectors.toMap(ModuleCCID::getCcId, Function.identity()));

        findModuleDtManifestMap = coreComponentRepositoryForModuleSetRelease.findAllModuleDtManifest(ULong.valueOf(moduleSetReleaseId))
                .stream().collect(Collectors.toMap(ModuleCCID::getCcId, Function.identity()));

        findModuleXbtManifestMap = coreComponentRepositoryForModuleSetRelease.findAllModuleXbtManifest(ULong.valueOf(moduleSetReleaseId))
                .stream().collect(Collectors.toMap(ModuleCCID::getCcId, Function.identity()));

        findBlobContentList = coreComponentRepositoryForModuleSetRelease.findAllBlobContent(ULong.valueOf(moduleSetReleaseId));

        findModuleBlobContentManifestMap = coreComponentRepositoryForModuleSetRelease.findAllModuleBlobContentManifest(ULong.valueOf(moduleSetReleaseId))
                .stream().collect(Collectors.toMap(ModuleCCID::getCcId, Function.identity()));

        findSeqKeyList = coreComponentRepositoryForModuleSetRelease.findAllSeqKeyRecord();

        findSeqKeyMap = findSeqKeyList.stream()
                .collect(Collectors.groupingBy(SeqKeyRecord::getFromAccManifestId));

        findReleaseList = coreComponentRepositoryForModuleSetRelease.findAllRelease();
        findReleaseMap = findReleaseList.stream()
                .collect(Collectors.toMap(ReleaseRecord::getReleaseId, Function.identity()));

        findNamespaceList = coreComponentRepositoryForModuleSetRelease.findAllNamespace();
        findNamespaceMap = findNamespaceList.stream()
                .collect(Collectors.toMap(NamespaceRecord::getNamespaceId, Function.identity()));


        logger.info("Ready for " + getClass().getSimpleName() + " in " + (System.currentTimeMillis() - s) / 1000d + " seconds");
    }

    private List<SeqKeyRecord> findSeqKeyList;
    private Map<ULong, List<SeqKeyRecord>> findSeqKeyMap;

    public List<SeqKeyRecord> getSeqKeys(ULong accManifestId) {
        return findSeqKeyMap.containsKey(accManifestId) ? findSeqKeyMap.get(accManifestId) : Collections.emptyList();
    }

    private Map<ULong, ModuleCCID> findModuleAgencyIdListManifestMap;
    private Map<ULong, ModuleCCID> findModuleCodeListManifestMap;
    private Map<ULong, ModuleCCID> findModuleAccManifestMap;
    private Map<ULong, ModuleCCID> findModuleDtManifestMap;
    private Map<ULong, ModuleCCID> findModuleAsccpManifestMap;
    private Map<ULong, ModuleCCID> findModuleBccpManifestMap;
    private Map<ULong, ModuleCCID> findModuleXbtManifestMap;
    private Map<ULong, ModuleCCID> findModuleBlobContentManifestMap;

    private Map<ULong, AgencyIdListManifestRecord> findAgencyIdListManifestMap;

    public AgencyIdListManifestRecord findAgencyIdListManifest(ULong agencyIdListManifestId) {
        return findAgencyIdListManifestMap.get(agencyIdListManifestId);
    }

    private List<AgencyIdListRecord> findAgencyIdListList;

    public List<AgencyIdListRecord> findAgencyIdList() {
        return Collections.unmodifiableList(findAgencyIdListList);
    }

    private Map<ULong, AgencyIdListRecord> findAgencyIdListMap;

    
    public AgencyIdListRecord findAgencyIdList(ULong agencyIdListId) {
        return findAgencyIdListMap.get(agencyIdListId);
    }

    private Map<ULong, List<AgencyIdListValueRecord>> findAgencyIdListValueByOwnerListIdMap;

    
    public List<AgencyIdListValueRecord> findAgencyIdListValueByOwnerListId(ULong ownerListId) {
        return findAgencyIdListValueByOwnerListIdMap.containsKey(ownerListId) ? findAgencyIdListValueByOwnerListIdMap.get(ownerListId) : Collections.emptyList();
    }

    private Map<ULong, CodeListManifestRecord> findCodeListManifestMap;

    public CodeListManifestRecord findCodeListManifest(ULong codeListManifestId) {
        return findCodeListManifestMap.get(codeListManifestId);
    }

    private List<CodeListRecord> findCodeListList;

    
    public List<CodeListRecord> findCodeList() {
        return Collections.unmodifiableList(findCodeListList);
    }

    private Map<ULong, CodeListRecord> findCodeListMap;

    
    public CodeListRecord findCodeList(ULong codeListId) {
        return findCodeListMap.get(codeListId);
    }

    private Map<ULong, List<CodeListValueRecord>> findCodeListValueByCodeListIdMap;

    
    public List<CodeListValueRecord> findCodeListValueByCodeListId(ULong codeListId) {
        return (findCodeListValueByCodeListIdMap.containsKey(codeListId)) ? findCodeListValueByCodeListIdMap.get(codeListId) : Collections.emptyList();
    }

    private List<DtManifestRecord> findDtManifestList;

    public List<DtManifestRecord> findDtManifest() {
        return Collections.unmodifiableList(findDtManifestList);
    }

    private Map<ULong, DtManifestRecord> findDtManifestMap;

    public DtManifestRecord findDtManifestByDtManifestId(ULong dtManifestId) {
        return findDtManifestMap.get(dtManifestId);
    }

    private List<DtRecord> findDtList;

    
    public List<DtRecord> findDT() {
        return Collections.unmodifiableList(findDtList);
    }

    private Map<ULong, DtRecord> findDtMap;

    
    public DtRecord findDT(ULong dtId) {
        return findDtMap.get(dtId);
    }

    private Map<ULong, List<DtScManifestRecord>> findDtScManifestByOwnerDtManifestIdMap;

    public List<DtScManifestRecord> findDtScManifestByOwnerDtManifestId(ULong ownerDtManifestId) {
        return (findDtScManifestByOwnerDtManifestIdMap.containsKey(ownerDtManifestId)) ? findDtScManifestByOwnerDtManifestIdMap.get(ownerDtManifestId) : Collections.emptyList();
    }

    private Map<ULong, DtScRecord> findDtScMap;

    public DtScRecord findDtSc(ULong dtScId) {
        return findDtScMap.get(dtScId);
    }

    private Map<ULong, List<DtScRecord>> findDtScByOwnerDtIdMap;


    public List<DtScRecord> findDtScByOwnerDtId(ULong ownerDtId) {
        return (findDtScByOwnerDtIdMap.containsKey(ownerDtId)) ? findDtScByOwnerDtIdMap.get(ownerDtId) : Collections.emptyList();
    }

    private Map<ULong, List<BdtPriRestriRecord>> findBdtPriRestriListByDtManifestIdMap;

    
    public List<BdtPriRestriRecord> findBdtPriRestriListByDtManifestId(ULong dtManifestId) {
        return (findBdtPriRestriListByDtManifestIdMap.containsKey(dtManifestId)) ? findBdtPriRestriListByDtManifestIdMap.get(dtManifestId) : Collections.emptyList();
    }

    private Map<ULong, CdtAwdPriXpsTypeMapRecord> cdtAwdPriXpsTypeMapMap;

    
    public CdtAwdPriXpsTypeMapRecord findCdtAwdPriXpsTypeMapById(ULong cdtAwdPriXpsTypeMapId) {
        return cdtAwdPriXpsTypeMapMap.get(cdtAwdPriXpsTypeMapId);
    }

    
    public List<CdtAwdPriXpsTypeMapRecord> findCdtAwdPriXpsTypeMapListByDtManifestId(ULong dtManifestId) {
        List<BdtPriRestriRecord> bdtPriRestriList = findBdtPriRestriListByDtManifestId(dtManifestId);
        List<CdtAwdPriXpsTypeMapRecord> cdtAwdPriXpsTypeMapList = bdtPriRestriList.stream()
                .filter(e -> e.getCdtAwdPriXpsTypeMapId() != null)
                .map(e -> cdtAwdPriXpsTypeMapMap.get(e.getCdtAwdPriXpsTypeMapId()))
                .collect(Collectors.toList());
        return (cdtAwdPriXpsTypeMapList != null) ? cdtAwdPriXpsTypeMapList : Collections.emptyList();
    }

    private Map<ULong, List<BdtScPriRestriRecord>> findBdtScPriRestriListByDtScManifestIdMap;

    
    public List<BdtScPriRestriRecord> findBdtScPriRestriListByDtScManifestId(ULong dtScManifestId) {
        return (findBdtScPriRestriListByDtScManifestIdMap.containsKey(dtScManifestId)) ? findBdtScPriRestriListByDtScManifestIdMap.get(dtScManifestId) : Collections.emptyList();
    }

    private Map<ULong, CdtScAwdPriXpsTypeMapRecord> findCdtScAwdPriXpsTypeMapMap;

    
    public CdtScAwdPriXpsTypeMapRecord findCdtScAwdPriXpsTypeMap(ULong cdtScAwdPriXpsTypeMapId) {
        return findCdtScAwdPriXpsTypeMapMap.get(cdtScAwdPriXpsTypeMapId);
    }

    private Map<ULong, CdtScAwdPriRecord> findCdtScAwdPriMap;

    
    public CdtScAwdPriRecord findCdtScAwdPri(ULong cdtScAwdPriId) {
        return findCdtScAwdPriMap.get(cdtScAwdPriId);
    }

    private List<XbtRecord> findXbtList;

    
    public List<XbtRecord> findXbt() {
        return findXbtList;
    }

    private Map<ULong, CdtAwdPriRecord> findCdtAwdPriMap;

    
    public CdtAwdPriRecord findCdtAwdPri(ULong cdtAwdPriId) {
        return findCdtAwdPriMap.get(cdtAwdPriId);
    }

    private Map<ULong, CdtPriRecord> findCdtPriMap;

    public CdtPriRecord findCdtPri(ULong cdtPriId) {
        return findCdtPriMap.get(cdtPriId);
    }

    private Map<ULong, XbtRecord> findXbtMap;

    
    public XbtRecord findXbt(ULong xbtId) {
        XbtRecord xbt = findXbtMap.get(xbtId);
        return xbt;
    }

    private List<AccRecord> findACCList;

    
    public List<AccRecord> findACC() {
        return Collections.unmodifiableList(findACCList);
    }

    
    public List<AccManifestRecord> findACCManifest() {
        return Collections.unmodifiableList(findACCManifestList);
    }

    private Map<ULong, AccRecord> findAccMap;

    
    public AccRecord findACC(ULong accId) {
        return findAccMap.get(accId);
    }

    private List<AccManifestRecord> findACCManifestList;

    private Map<ULong, AccManifestRecord> findAccManifestMap;

    
    public AccManifestRecord findACCManifest(ULong accManifestId) {
        return findAccManifestMap.get(accManifestId);
    }

    
    public List<AsccpManifestRecord> findASCCPManifest() {
        return Collections.unmodifiableList(findASCCPManifestList);
    }

    private List<AsccpManifestRecord> findASCCPManifestList;

    private Map<ULong, AsccpManifestRecord> findAsccpManifestMap;

    
    public AsccpManifestRecord findASCCPManifest(ULong asccpManifestId) {
        return findAsccpManifestMap.get(asccpManifestId);
    }

    
    public List<BccpManifestRecord> findBCCPManifest() {
        return Collections.unmodifiableList(findBCCPManifestList);
    }

    private List<BccpManifestRecord> findBCCPManifestList;

    private Map<ULong, BccpManifestRecord> findBccpManifestMap;

    
    public BccpManifestRecord findBCCPManifest(ULong bccpManifestId) {
        if (bccpManifestId == null) {
            return null;
        }
        return findBccpManifestMap.get(bccpManifestId);
    }

    private List<AsccpRecord> findASCCPList;

    
    public List<AsccpRecord> findASCCP() {
        return Collections.unmodifiableList(findASCCPList);
    }

    private Map<ULong, AsccpRecord> findAsccpMap;

    
    public AsccpRecord findASCCP(ULong asccpId) {
        return findAsccpMap.get(asccpId);
    }

    private Map<String, AsccpRecord> findAsccpByGuidMap;

    
    public AsccpRecord findASCCPByGuid(String guid) {
        return findAsccpByGuidMap.get(guid);
    }

    private List<BccpRecord> findBCCPList;

    
    public List<BccpRecord> findBCCP() {
        return Collections.unmodifiableList(findBCCPList);
    }

    private Map<ULong, BccpRecord> findBccpMap;

    
    public BccpRecord findBCCP(ULong bccpId) {
        return findBccpMap.get(bccpId);
    }

    private Map<ULong, List<BccRecord>> findBCCByToBccpIdMap;

    
    public List<BccRecord> findBCCByToBccpId(ULong toBccpId) {
        return (findBCCByToBccpIdMap.containsKey(toBccpId)) ? findBCCByToBccpIdMap.get(toBccpId) : Collections.emptyList();
    }

    private Map<ULong, List<BccRecord>> findBccByFromAccIdMap;

    
    public List<BccRecord> findBCCByFromAccId(ULong fromAccId) {
        return (findBccByFromAccIdMap.containsKey(fromAccId)) ? findBccByFromAccIdMap.get(fromAccId) : Collections.emptyList();
    }

    private Map<ULong, AsccRecord> findAsccMap;

    
    public AsccRecord findASCC(ULong asccId) {
        return findAsccMap.get(asccId);
    }

    private Map<ULong, BccRecord> findBccMap;

    
    public BccRecord findBCC(ULong bccId) {
        return findBccMap.get(bccId);
    }

    private Map<ULong, AsccManifestRecord> findAsccManifestMap;
    private Map<ULong, List<AsccManifestRecord>> findAsccManifestByAccManifestIdMap;

    
    public AsccManifestRecord findASCCManifest(ULong asccId) {
        return findAsccManifestMap.get(asccId);
    }

    public List<AsccManifestRecord> findASCCManifestByFromAccManifestId(ULong fromAccManifestId) {
        if (!findAsccManifestByAccManifestIdMap.containsKey(fromAccManifestId)) {
            return Collections.emptyList();
        }
        return findAsccManifestByAccManifestIdMap.get(fromAccManifestId);
    }

    private Map<ULong, BccManifestRecord> findBccManifestMap;
    private Map<ULong, List<BccManifestRecord>> findBccManifestByAccManifestIdMap;

    
    public BccManifestRecord findBCCManifest(ULong bccId) {
        return findBccManifestMap.get(bccId);
    }

    public List<BccManifestRecord> findBCCManifestByFromAccManifestId(ULong fromAccManifestId) {
        if (!findBccManifestByAccManifestIdMap.containsKey(fromAccManifestId)) {
            return Collections.emptyList();
        }
        return findBccManifestByAccManifestIdMap.get(fromAccManifestId);
    }

    private Map<ULong, List<AsccRecord>> findAsccByFromAccIdMap;

    
    public List<AsccRecord> findASCCByFromAccId(ULong fromAccId) {
        return (findAsccByFromAccIdMap.containsKey(fromAccId)) ? findAsccByFromAccIdMap.get(fromAccId) : Collections.emptyList();
    }

    
    public ModuleCCID findModuleAgencyIdList(ULong agencyIdListId) {
        return findModuleAgencyIdListManifestMap.get(agencyIdListId);
    }

    
    public ModuleCCID findModuleCodeList(ULong codeListId) {
        return findModuleCodeListManifestMap.get(codeListId);
    }

    
    public ModuleCCID findModuleAcc(ULong accId) {
        return findModuleAccManifestMap.get(accId);
    }

    
    public ModuleCCID findModuleAsccp(ULong asccpId) {
        return findModuleAsccpManifestMap.get(asccpId);
    }

    
    public ModuleCCID findModuleBccp(ULong bccpId) {
        return findModuleBccpManifestMap.get(bccpId);
    }

    
    public ModuleCCID findModuleDt(ULong dtId) {
        return findModuleDtManifestMap.get(dtId);
    }

    
    public ModuleCCID findModuleXbt(ULong xbtId) {
        return findModuleXbtManifestMap.get(xbtId);
    }

    private List<BlobContentRecord> findBlobContentList;

    
    public List<BlobContentRecord> findBlobContent() {
        return findBlobContentList;
    }

    
    public ModuleCCID findModuleBlobContent(ULong blobContentId) {
        return findModuleBlobContentManifestMap.get(blobContentId);
    }

    private List<ReleaseRecord> findReleaseList;
    private Map<ULong, ReleaseRecord> findReleaseMap;

    @Override
    public List<ReleaseRecord> findRelease() {
        return findReleaseList;
    }

    @Override
    public ReleaseRecord findRelease(ULong releaseId) {
        return findReleaseMap.get(releaseId);
    }

    private List<NamespaceRecord> findNamespaceList;
    private Map<ULong, NamespaceRecord> findNamespaceMap;

    @Override
    public List<NamespaceRecord> findNamespace() {
        return findNamespaceList;
    }

    @Override
    public NamespaceRecord findNamespace(ULong namespaceId) {
        return findNamespaceMap.get(namespaceId);
    }
}
