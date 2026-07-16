package org.oagi.score.gateway.http.api.application_management.controller;

import org.oagi.score.gateway.http.api.application_management.service.BrokerJwtService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/broker")
public class BrokerJwtController {

    @Autowired
    private BrokerJwtService brokerJwtService;

    @GetMapping("/.well-known/openid-configuration")
    public Map<String, Object> openIdConfiguration() {
        return brokerJwtService.openIdConfiguration();
    }

    @GetMapping({"/jwks", "/.well-known/jwks.json"})
    public Map<String, Object> jwks() {
        return brokerJwtService.jwks();
    }
}
