package org.oagi.score.gateway.http.api.oas_management.controller;

import org.oagi.score.gateway.http.api.oas_management.service.OpenAPIDocService;
import org.oagi.score.repo.api.impl.utils.StringUtils;
import org.oagi.score.repo.api.openapidoc.model.*;
import org.oagi.score.service.authentication.AuthenticationService;
import org.oagi.score.service.common.data.PageRequest;
import org.oagi.score.service.common.data.PageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.parameters.P;
import org.springframework.web.bind.annotation.*;

import org.springframework.http.MediaType;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.stream.Collectors;

import static org.oagi.score.repo.api.base.SortDirection.ASC;
import static org.oagi.score.repo.api.base.SortDirection.DESC;

@RestController
public class OpenAPIDocController {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    @Autowired
    private AuthenticationService authenticationService;
    @Autowired
    private OpenAPIDocService oasDocService;

    @RequestMapping(value = "/oas_docs", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public PageResponse<OasDoc> getOasDocList(
            @AuthenticationPrincipal AuthenticatedPrincipal requester,
            @RequestParam(name = "openAPIVersion", required = false) String openAPIVersion,
            @RequestParam(name = "title", required = false) String title,
            @RequestParam(name = "description", required = false) String description,
            @RequestParam(name = "termsOfService", required = false) String termsOfService,
            @RequestParam(name = "version", required = false) String version,
            @RequestParam(name = "updaterUsernameList", required = false) String updaterUsernameList,
            @RequestParam(name = "updateStart", required = false) String updateStart,
            @RequestParam(name = "updateEnd", required = false) String updateEnd,
            @RequestParam(name = "contactName", required = false) String contactName,
            @RequestParam(name = "contactUrl", required = false) String contactUrl,
            @RequestParam(name = "contactEmail", required = false) String contactEmail,
            @RequestParam(name = "licenseName", required = false) String licenseName,
            @RequestParam(name = "licenseUrl", required = false) String licenseUrl,
            @RequestParam(name = "sortActive") String sortActive,
            @RequestParam(name = "sortDirection") String sortDirection,
            @RequestParam(name = "pageIndex") int pageIndex,
            @RequestParam(name = "pageSize") int pageSize
    ){
        GetOasDocListRequest request =  new GetOasDocListRequest(authenticationService.asScoreUser(requester));

        request.setOpenAPIVersion(openAPIVersion);
        request.setTitle(title);
        request.setLicenseName(licenseName);
        request.setVersion(version);
        request.setUpdaterUsernameList(!StringUtils.hasLength(updaterUsernameList) ? Collections.emptyList() :
                Arrays.asList(updaterUsernameList.split(",")).stream().map(e -> e.trim())
                        .filter(e -> StringUtils.hasLength(e)).collect(Collectors.toList()));
        if (StringUtils.hasLength(updateStart)) {
            request.setUpdateStartDate(new Timestamp(Long.valueOf(updateStart)).toLocalDateTime());
        }
        if (StringUtils.hasLength(updateEnd)) {
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(Long.valueOf(updateEnd));
            calendar.add(Calendar.DATE, 1);
            request.setUpdateEndDate(new Timestamp(calendar.getTimeInMillis()).toLocalDateTime());
        }

        request.setPageIndex(pageIndex);
        request.setPageSize(pageSize);
        request.setSortActive(sortActive);
        request.setSortDirection("asc".equalsIgnoreCase(sortDirection) ? ASC : DESC);

        GetOasDocListResponse response = oasDocService.getOasDocList(request);
        PageResponse<OasDoc> pageResponse = new PageResponse<>();
        pageResponse.setList(response.getResults());
        pageResponse.setPage(response.getPage());
        pageResponse.setSize(response.getSize());
        pageResponse.setLength(response.getLength());

        return pageResponse;
    }

    @RequestMapping(value = "/oas_doc", method = RequestMethod.PUT)
    public ResponseEntity create(
            @AuthenticationPrincipal AuthenticatedPrincipal requester,
            @RequestBody OasDoc oasDoc){
        CreateOasDocRequest request = new CreateOasDocRequest(authenticationService.asScoreUser(requester));
        request.setOpenAPIVersion(oasDoc.getOpenAPIVersion());
        request.setTitle(oasDoc.getTitle());
        request.setDescription(oasDoc.getDescription());
        request.setVersion(oasDoc.getVersion());
        request.setTermsOfService(oasDoc.getTermsOfService());
        request.setContactEmail(oasDoc.getContactEmail());
        request.setContactName(oasDoc.getContactName());
        request.setContactUrl(oasDoc.getContactUrl());
        request.setLicenseName(oasDoc.getLicenseName());
        request.setLicenseUrl(oasDoc.getLicenseUrl());

        CreateOasDocResponse response = oasDocService.createOasDoc(request);

        if (response.getOasDocId() != null){
            return ResponseEntity.noContent().build();
        } else{
            return ResponseEntity.badRequest().build();
        }
    }






}
