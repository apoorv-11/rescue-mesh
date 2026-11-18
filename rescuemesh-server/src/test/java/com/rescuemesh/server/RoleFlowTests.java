package com.rescuemesh.server;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
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
 * Auth v2 role flow, end to end on the H2 test profile: a verified citizen
 * applies for a role, an admin reviews the queue and approves (which issues
 * a one-time access code), the user redeems the code and their fresh token
 * carries the new role. Plus the guards: no applying for ADMIN, no duplicate
 * pending application, no double redeem, admin queue is ADMIN-only, and
 * device binding rules.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestMailConfiguration.class)
class RoleFlowTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestMailConfiguration.CapturingMailService mail;

    @Test
    void fullRoleFlowApplyApproveRedeem() throws Exception {
        String citizenToken = registerVerified("volunteer1", "vol1@example.org", null);

        // Apply for VOLUNTEER.
        MvcResult applyResult = mockMvc.perform(post("/roles/apply")
                        .header("Authorization", "Bearer " + citizenToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestedRole":"VOLUNTEER","organization":"City NGO",
                                 "reason":"I run a first-aid post"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.requestedRole").value("VOLUNTEER"))
                .andReturn();
        Number applicationId = JsonPath.read(
                applyResult.getResponse().getContentAsString(), "$.id");

        // A second application while one is pending -> 409.
        mockMvc.perform(post("/roles/apply")
                        .header("Authorization", "Bearer " + citizenToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestedRole":"COORDINATOR","organization":"City NGO",
                                 "reason":"second one"}
                                """))
                .andExpect(status().isConflict());

        // The user can see their own application.
        mockMvc.perform(get("/roles/my-applications")
                        .header("Authorization", "Bearer " + citizenToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PENDING"));

        // The admin queue contains the application.
        String adminToken = login("admin", "admin123");
        MvcResult queue = mockMvc.perform(get("/admin/role-applications?status=PENDING")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        List<Object> ids = JsonPath.read(queue.getResponse().getContentAsString(),
                "$[?(@.username == 'volunteer1')].id");
        assertFalse(ids.isEmpty(), "application should be in the admin PENDING queue");
        assertEquals(applicationId.longValue(),
                ((Number) ids.get(0)).longValue());

        // Approve -> APPROVED, and the one-time code is returned once AND emailed.
        MvcResult approveResult = mockMvc.perform(
                        post("/admin/role-applications/" + applicationId + "/approve")
                                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application.status").value("APPROVED"))
                .andExpect(jsonPath("$.application.username").value("volunteer1"))
                .andExpect(jsonPath("$.accessCode").value(startsWith("RM-")))
                .andReturn();
        String accessCode = JsonPath.read(
                approveResult.getResponse().getContentAsString(), "$.accessCode");
        assertEquals(accessCode, mail.lastRoleAccessCode("vol1@example.org"),
                "the emailed code and the admin-response code must be the same");

        // Redeem -> fresh token whose /me shows the new role.
        String volunteerToken = redeem(citizenToken, accessCode);
        mockMvc.perform(get("/me").header("Authorization", "Bearer " + volunteerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("volunteer1"))
                .andExpect(jsonPath("$.role").value("VOLUNTEER"));

        // Redeeming the same code a second time -> 409.
        mockMvc.perform(post("/profile/role-code")
                        .header("Authorization", "Bearer " + volunteerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + accessCode + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void applyingForAdminIsRejected() throws Exception {
        String token = registerVerified("wannabe", "wannabe@example.org", null);
        mockMvc.perform(post("/roles/apply")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestedRole":"ADMIN","organization":"-",
                                 "reason":"trust me"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void citizenCannotReadAdminQueue() throws Exception {
        String token = registerVerified("plaincitizen", "plain@example.org", null);
        mockMvc.perform(get("/admin/role-applications?status=PENDING")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/role-applications")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void unverifiedUserCannotApply() throws Exception {
        // Register (with a device) but never verify the email. The only token
        // such a user can hold is a device token — applying must still 403.
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"unverified","email":"unv@example.org",
                                 "password":"secret123","deviceId":"device-unv-001"}
                                """))
                .andExpect(status().isCreated());
        MvcResult deviceResult = mockMvc.perform(post("/auth/device")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"device-unv-001"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        String deviceToken = JsonPath.read(
                deviceResult.getResponse().getContentAsString(), "$.token");
        mockMvc.perform(post("/roles/apply")
                        .header("Authorization", "Bearer " + deviceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestedRole":"VOLUNTEER","organization":"NGO",
                                 "reason":"help"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectionFlow() throws Exception {
        String token = registerVerified("rejected1", "rej@example.org", null);
        MvcResult applyResult = mockMvc.perform(post("/roles/apply")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestedRole":"COORDINATOR","organization":"Ward office",
                                 "reason":"I coordinate the ward"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        Number applicationId = JsonPath.read(
                applyResult.getResponse().getContentAsString(), "$.id");

        String adminToken = login("admin", "admin123");
        mockMvc.perform(post("/admin/role-applications/" + applicationId + "/reject")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"insufficient detail"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reviewNote").value("insufficient detail"));

        // A reviewed application cannot be approved afterwards.
        mockMvc.perform(post("/admin/role-applications/" + applicationId + "/approve")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict());
    }

    @Test
    void adminCanIssueDirectCodeForAnyRole() throws Exception {
        String token = registerVerified("coord1", "coord@example.org", null);
        String adminToken = login("admin", "admin123");

        MvcResult codeResult = mockMvc.perform(post("/admin/role-codes")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"coord1","role":"COORDINATOR"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("coord1"))
                .andExpect(jsonPath("$.role").value("COORDINATOR"))
                .andExpect(jsonPath("$.accessCode").value(startsWith("RM-")))
                .andReturn();
        String accessCode = JsonPath.read(
                codeResult.getResponse().getContentAsString(), "$.accessCode");
        assertEquals(accessCode, mail.lastRoleAccessCode("coord@example.org"));

        String coordToken = redeem(token, accessCode);
        mockMvc.perform(get("/me").header("Authorization", "Bearer " + coordToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("COORDINATOR"));

        // Citizens cannot mint codes.
        mockMvc.perform(post("/admin/role-codes")
                        .header("Authorization", "Bearer " + coordToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"coord1","role":"ADMIN"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void bindDeviceHappyPathAndConflict() throws Exception {
        String tokenA = registerVerified("binder1", "bind1@example.org", null);
        String tokenB = registerVerified("binder2", "bind2@example.org", null);

        // A binds a device after registration (new phone / reinstall).
        mockMvc.perform(post("/profile/bind-device")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"device-bind-001"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("binder1"))
                .andExpect(jsonPath("$.deviceId").value("device-bind-001"));

        // Rebinding your own device is fine...
        mockMvc.perform(post("/profile/bind-device")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"device-bind-001"}
                                """))
                .andExpect(status().isOk());

        // ...but another user cannot take it.
        mockMvc.perform(post("/profile/bind-device")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"device-bind-001"}
                                """))
                .andExpect(status().isConflict());

        // And the binding is live: /auth/device now resolves to user A.
        MvcResult deviceResult = mockMvc.perform(post("/auth/device")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"device-bind-001"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        String deviceToken = JsonPath.read(
                deviceResult.getResponse().getContentAsString(), "$.token");
        mockMvc.perform(get("/me").header("Authorization", "Bearer " + deviceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("binder1"));
    }

    // -- helpers -----------------------------------------------------------

    /** Registers a user and verifies the email; returns the auto-login token. */
    private String registerVerified(String username, String email, String deviceId)
            throws Exception {
        String device = deviceId == null ? "" : ",\"deviceId\":\"" + deviceId + "\"";
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + email
                                + "\",\"password\":\"secret123\"" + device + "}"))
                .andExpect(status().isCreated());
        String code = mail.lastVerificationCode(email);
        MvcResult result = mockMvc.perform(post("/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    /** Redeems a role access code; returns the fresh token. */
    private String redeem(String token, String accessCode) throws Exception {
        MvcResult result = mockMvc.perform(post("/profile/role-code")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + accessCode + "\"}"))
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
}
