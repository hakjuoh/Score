package org.oagi.score.gateway.http.api.external.service;

import org.oagi.score.gateway.http.api.bie_management.model.TopLevelAsbiepId;
import org.oagi.score.gateway.http.api.bie_management.model.bie_package.BiePackageId;
import org.oagi.score.gateway.http.api.external.repository.ExternalBieRepository;
import org.oagi.score.gateway.http.common.model.Guid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
@Transactional(readOnly = true)
public class ExternalBieService {

    @Autowired
    ExternalBieRepository externalBieRepository;

    public BiePackageId getBiePackageId(String libraryName, String biePackageName, String biePackageVersionId) {
        return externalBieRepository.getBiePackageId(libraryName, biePackageName, biePackageVersionId);
    }

    public TopLevelAsbiepId getTopLevelAsbiepId(Guid guid) {
        return externalBieRepository.getTopLevelAsbiepId(guid);
    }

    /*
     * public BieGenerateExpressionResult generate(
     * ScoreUser requester,
     * TopLevelAsbiepId topLevelAsbiepId,
     * GenerateExpressionOption option) throws BieGenerateFailureException {
     * 
     * var topLevelAsbiepQuery =
     * repositoryFactory.topLevelAsbiepQueryRepository(requester);
     * TopLevelAsbiepSummaryRecord topLevelAsbiep =
     * topLevelAsbiepQuery.getTopLevelAsbiepSummary(topLevelAsbiepId);
     * List<TopLevelAsbiepSummaryRecord> topLevelAsbieps = new ArrayList<>();
     * topLevelAsbieps.add(topLevelAsbiep);
     * File file = bieGenerateService.generateSchema(requester, topLevelAsbieps,
     * option);
     * return bieGenerateService.toResult(file);
     * }
     */
    /*
     * 
     * public PageResponse<BieList> getBieList(BieListRequest request) {
     * PageRequest pageRequest = request.getPageRequest();
     * 
     * List<BieState> bieStates = request.getStates().isEmpty()
     * ? Arrays.asList(BieState.values()).stream().filter(e -> e.getLevel() >=
     * 2).collect(Collectors.toList())
     * : request.getStates().stream().filter(e -> e.getLevel() >=
     * 2).collect(Collectors.toList());
     * 
     * PageResponse<BieList> result = bieRepository.selectBieLists()
     * .setDen(request.getDen())
     * .setPropertyTerm(request.getPropertyTerm())
     * .setBusinessContext(request.getBusinessContext())
     * .setAsccpManifestId(request.getAsccpManifestId())
     * .setExcludePropertyTerms(request.getExcludePropertyTerms())
     * .setTopLevelAsbiepIds(request.getTopLevelAsbiepIds())
     * .setExcludeTopLevelAsbiepIds(request.getExcludeTopLevelAsbiepIds())
     * .setStates(bieStates)
     * .setReleaseIds(request.getReleaseIds())
     * .setOwnerLoginIdList(request.getOwnerLoginIdList())
     * .setUpdaterLoginIdList(request.getUpdaterLoginIdList())
     * .setUpdateDate(request.getUpdateStartDate(), request.getUpdateEndDate())
     * .setOwnedByDeveloper(request.getOwnedByDeveloper())
     * .setSort(pageRequest.sorts())
     * .setOffset(pageRequest.pageOffset(), pageRequest.pageSize())
     * .fetch();
     * 
     * List<BieList> bieLists = result.getList();
     * 
     * bieLists.forEach(bieList -> {
     * ULong topLevelAsbpieId =
     * ULong.valueOf(bieList.getTopLevelAsbiepId().value());
     * List<String> businessContextNames = dslContext
     * .select(BIZ_CTX.NAME)
     * .from(BIZ_CTX)
     * .join(BIZ_CTX_ASSIGNMENT)
     * .on(BIZ_CTX.BIZ_CTX_ID.eq(BIZ_CTX_ASSIGNMENT.BIZ_CTX_ID))
     * .where(BIZ_CTX_ASSIGNMENT.TOP_LEVEL_ASBIEP_ID.eq(topLevelAsbpieId))
     * .fetch(BIZ_CTX.NAME);
     * 
     * bieList.setBusinessContextNames(businessContextNames);
     * });
     * 
     * PageResponse<BieList> response = new PageResponse<BieList>();
     * response.setList(bieLists);
     * response.setPage(pageRequest.pageIndex());
     * response.setSize(pageRequest.pageSize());
     * response.setLength(result.getLength());
     * return response;
     * }
     */

}
