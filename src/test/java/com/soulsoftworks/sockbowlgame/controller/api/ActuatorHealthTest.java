package com.soulsoftworks.sockbowlgame.controller.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Only /actuator/health should be exposed (for compose/orchestrator
 * healthchecks), reachable without authentication, and without leaking
 * component details.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ActuatorHealthTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void health_isReachableAndReportsOnlyTopLevelStatus() throws Exception {
        var result = mockMvc.perform(get("/actuator/health"))
                .andExpect(content().string(containsString("\"status\"")))
                // show-details=never: no per-component breakdown (e.g. "redis", "diskSpace")
                .andExpect(content().string(not(containsString("\"components\""))))
                .andReturn();
        // UP (200) or DOWN (503) depending on whether Redis/Kafka are
        // reachable in this environment; either way the endpoint must be
        // reachable (unauthenticated) and report a top-level status.
        assertThat(result.getResponse().getStatus(), anyOf(is(200), is(503)));
    }

    @Test
    void otherActuatorEndpoints_areNotExposed() throws Exception {
        // The root /actuator discovery document is always present, but its
        // _links only ever point at endpoints that are actually exposed
        // (just health here) - it doesn't itself leak any endpoint's details.
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/beans"))
                .andExpect(status().isNotFound());
    }
}
