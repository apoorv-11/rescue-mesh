package com.rescuemesh.server.sync;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * The SSE stream must sit behind the default authenticated rule — an
 * unauthenticated request is 401, proving {@code /records/stream} is not
 * accidentally public (SecurityConfig is unchanged; the path is simply not
 * in the permitAll list).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestMailConfiguration.class)
class RecordsStreamTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void streamWithoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/records/stream")).andExpect(status().isUnauthorized());
    }
}
