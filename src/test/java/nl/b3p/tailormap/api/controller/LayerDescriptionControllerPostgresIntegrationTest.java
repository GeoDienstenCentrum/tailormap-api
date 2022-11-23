/*
 * Copyright (C) 2022 B3Partners B.V.
 *
 * SPDX-License-Identifier: MIT
 */
package nl.b3p.tailormap.api.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import nl.b3p.tailormap.api.JPAConfiguration;
import nl.b3p.tailormap.api.security.AuthorizationService;
import nl.b3p.tailormap.api.security.SecurityConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        classes = {
            JPAConfiguration.class,
            LayerDescriptionController.class,
            SecurityConfig.class,
            AuthorizationService.class
        })
@AutoConfigureMockMvc
@EnableAutoConfiguration
@ActiveProfiles("postgresql")
@Execution(ExecutionMode.CONCURRENT)
class LayerDescriptionControllerPostgresIntegrationTest {
    @Autowired private MockMvc mockMvc;

    @Test
    @SuppressWarnings("PMD.JUnitTestsShouldIncludeAssert")
    void app_not_found_404() throws Exception {
        mockMvc.perform(get("/app/1234/layer/76/describe"))
                .andExpect(status().isNotFound())
                .andExpect(
                        content()
                                .string(
                                        "Requested an application or appLayer that does not exist"));
    }

    @Test
    @SuppressWarnings("PMD.JUnitTestsShouldIncludeAssert")
    void public_app() throws Exception {
        mockMvc.perform(get("/app/1/layer/6/describe"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.featureTypeName").value("begroeidterreindeel"))
                .andExpect(jsonPath("$.geometryAttribute").value("geom"))
                .andExpect(jsonPath("$.id").value("6"))
                .andExpect(jsonPath("$.serviceId").value(6))
                .andExpect(jsonPath("$.attributes").isArray())
                .andExpect(jsonPath("$.attributes[?(@.id == 48)].name").value("geom"))
                .andExpect(jsonPath("$.attributes[?(@.id == 48)].type").value("geometry"));
    }

    @Test
    @SuppressWarnings("PMD.JUnitTestsShouldIncludeAssert")
    @WithMockUser(username = "noproxyuser")
    void test_wms_secured_app_denied() throws Exception {
        mockMvc.perform(get("/app/6/layer/19/describe"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("{\"code\":401,\"url\":\"/login\"}"));
    }

    @Test
    @SuppressWarnings("PMD.JUnitTestsShouldIncludeAssert")
    @WithMockUser(
            username = "proxyuser",
            authorities = {"ProxyGroup"})
    void test_wms_secured_app_granted_but_no_feature_type() throws Exception {
        mockMvc.perform(get("/app/6/layer/19/describe"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("Layer does not have feature type"));
    }
}
