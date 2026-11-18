package com.rescuemesh.server.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rescuemesh.server.TestMailConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The web dashboard is served from a different origin (e.g.
 * {@code http://localhost:5173}), so the browser sends a CORS preflight
 * before any API call. These tests pin that the server answers preflight
 * and simple cross-origin requests with an
 * {@code Access-Control-Allow-Origin} header; without it the dashboard
 * cannot call the API at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestMailConfiguration.class)
class CorsTests {

    private static final String DASHBOARD_ORIGIN = "http://localhost:5173";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void preflightFromDashboardOriginIsAllowed() throws Exception {
        mockMvc.perform(options("/records/sos")
                        .header("Origin", DASHBOARD_ORIGIN)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Access-Control-Allow-Origin"));
    }

    @Test
    void simpleRequestFromDashboardOriginCarriesAllowOriginHeader() throws Exception {
        mockMvc.perform(get("/health").header("Origin", DASHBOARD_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().exists("Access-Control-Allow-Origin"));
    }
}
