package com.rescuemesh.server;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Gate 1 backend criteria + auth v2 hardening, exercised end to end on the
 * H2 test profile: health is public, registration always creates an
 * unverified CITIZEN, login is blocked until the email is verified with the
 * code captured from the test mail service, the verified JWT unlocks /me,
 * /auth/device issues a token for a bound device, and /admin/** enforces
 * the ADMIN role.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestMailConfiguration.class)
class AuthFlowTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestMailConfiguration.CapturingMailService mail;

    @Test
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void protectedEndpointRejectsAnonymous() throws Exception {
        mockMvc.perform(get("/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registerLoginAndDeviceTokenFlow() throws Exception {
        // Register a citizen with a bound device.
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"gate1citizen","email":"c@example.org",
                                 "password":"secret123","role":"CITIZEN",
                                 "deviceId":"device-gate1-001"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("gate1citizen"))
                .andExpect(jsonPath("$.role").value("CITIZEN"));

        // Duplicate username is rejected (with a fresh, valid email, so the
        // username check is what fires).
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"gate1citizen","email":"other@example.org",
                                 "password":"whatever1"}
                                """))
                .andExpect(status().isConflict());

        // Correct password but UNVERIFIED email -> 403 (auth v2).
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"gate1citizen","password":"secret123"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("email not verified"));

        // Wrong password is still rejected first.
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"gate1citizen","password":"wrong-pass"}
                                """))
                .andExpect(status().isUnauthorized());

        // A wrong verification code is rejected and counted.
        String realCode = mail.lastVerificationCode("c@example.org");
        mockMvc.perform(post("/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"gate1citizen\",\"code\":\""
                                + wrongCode(realCode) + "\"}"))
                .andExpect(status().isBadRequest());

        // The real code verifies the email and returns an auto-login token.
        String token = verifyEmail("gate1citizen", "c@example.org");
        mockMvc.perform(get("/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("gate1citizen"))
                .andExpect(jsonPath("$.role").value("CITIZEN"))
                .andExpect(jsonPath("$.deviceId").value("device-gate1-001"));

        // Now that the email is verified, plain login works too.
        token = login("gate1citizen", "secret123");
        mockMvc.perform(get("/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // A citizen token must not pass the ADMIN gate.
        mockMvc.perform(get("/admin/ping").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        // /auth/device issues a token for the bound device...
        MvcResult deviceResult = mockMvc.perform(post("/auth/device")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"device-gate1-001"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        String deviceToken = JsonPath.read(deviceResult.getResponse().getContentAsString(),
                "$.token");
        mockMvc.perform(get("/me").header("Authorization", "Bearer " + deviceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("gate1citizen"));

        // ...but not for an unknown device.
        mockMvc.perform(post("/auth/device")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"no-such-device"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registerIgnoresRequestedRole() throws Exception {
        // Asking for ADMIN at registration must still produce a CITIZEN.
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"sneaky","email":"sneaky@example.org",
                                 "password":"secret123","role":"ADMIN"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("CITIZEN"));

        String token = verifyEmail("sneaky", "sneaky@example.org");
        mockMvc.perform(get("/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("CITIZEN"));
        mockMvc.perform(get("/admin/ping").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void duplicateEmailIsRejected() throws Exception {
        register("firstuser", "dup@example.org", "secret123", null);
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"seconduser","email":"dup@example.org",
                                 "password":"secret123"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void emailIsRequiredAndMustLookValid() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"noemail","password":"secret123"}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"bademail","email":"not-an-email",
                                 "password":"secret123"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resendVerificationIssuesFreshCodeAndInvalidatesOld() throws Exception {
        register("resender", "resend@example.org", "secret123", null);
        String firstCode = mail.lastVerificationCode("resend@example.org");

        mockMvc.perform(post("/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"resender"}
                                """))
                .andExpect(status().isOk());
        String freshCode = mail.lastVerificationCode("resend@example.org");

        // The superseded code no longer works...
        mockMvc.perform(post("/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"resender\",\"code\":\"" + firstCode + "\"}"))
                .andExpect(status().isBadRequest());

        // ...the fresh one does.
        verifyEmailWithCode("resender", freshCode);

        // And once verified, another resend is refused.
        mockMvc.perform(post("/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"resender"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void seededAdminCanReachAdminProbe() throws Exception {
        // Seeded by DataSeeder from the test profile (admin / admin123);
        // the seeded admin is created email-verified, so login works.
        String adminToken = login("admin", "admin123");
        mockMvc.perform(get("/admin/ping").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("admin-ok"));
    }

    // -- helpers -----------------------------------------------------------

    private void register(String username, String email, String password, String deviceId)
            throws Exception {
        String device = deviceId == null ? "" : ",\"deviceId\":\"" + deviceId + "\"";
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + email
                                + "\",\"password\":\"" + password + "\"" + device + "}"))
                .andExpect(status().isCreated());
    }

    /** Verifies with the code captured from the test mail service; returns the token. */
    private String verifyEmail(String username, String email) throws Exception {
        return verifyEmailWithCode(username, mail.lastVerificationCode(email));
    }

    private String verifyEmailWithCode(String username, String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username
                                + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    /** A 6-digit code that is definitely not {@code actual}. */
    private String wrongCode(String actual) {
        return "000000".equals(actual) ? "000001" : "000000";
    }
}
