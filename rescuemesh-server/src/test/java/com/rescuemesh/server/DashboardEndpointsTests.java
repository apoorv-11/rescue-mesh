package com.rescuemesh.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.rescuemesh.server.domain.MissingPerson;
import com.rescuemesh.server.domain.SyncAudit;
import com.rescuemesh.server.repository.MissingPersonRepository;
import com.rescuemesh.server.repository.SyncAuditRepository;
import java.util.List;
import java.util.Map;
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
 * The dashboard endpoints added beside the sync record views:
 * {@code POST /records/announcements} (COORDINATOR/ADMIN publish),
 * {@code POST /records/missing/{messageId}/review} (persisted case review,
 * V4) and {@code GET /records/audit} (sync audit strip). Cases and audit
 * rows are seeded through the repositories — the sync flow that normally
 * creates them is covered by SyncFlowTests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestMailConfiguration.class)
class DashboardEndpointsTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestMailConfiguration.CapturingMailService mail;

    @Autowired
    private MissingPersonRepository missingPersonRepository;

    @Autowired
    private SyncAuditRepository syncAuditRepository;

    // -- announcements ------------------------------------------------------

    @Test
    void coordinatorCanCreateAnnouncementAndItIsListed() throws Exception {
        String token = coordinatorToken("dashcoord", "dashcoord@example.org");
        long validUntil = 1_800_000_000_000L;

        MvcResult created = mockMvc.perform(post("/records/announcements")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Boil water advisory","body":"Boil water before drinking",
                                 "priority":2,"validUntil":%d}
                                """.formatted(validUntil)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Boil water advisory"))
                .andExpect(jsonPath("$.body").value("Boil water before drinking"))
                .andExpect(jsonPath("$.priority").value(2))
                .andExpect(jsonPath("$.createdBy").value("dashcoord"))
                .andReturn();
        String createdJson = created.getResponse().getContentAsString();
        assertEquals(validUntil,
                ((Number) JsonPath.read(createdJson, "$.expiresAt")).longValue());
        assertNotNull(JsonPath.read(createdJson, "$.id"));
        assertNotNull(JsonPath.read(createdJson, "$.createdAt"));

        // ...and it shows up in the list, in the same shape.
        MvcResult list = mockMvc.perform(get("/records/announcements")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        List<Map<String, Object>> matches = JsonPath.read(
                list.getResponse().getContentAsString(),
                "$[?(@.title == 'Boil water advisory')]");
        assertFalse(matches.isEmpty(), "created announcement must be listed");
        assertEquals("dashcoord", matches.get(0).get("createdBy"));
        assertEquals(validUntil,
                ((Number) matches.get(0).get("expiresAt")).longValue());
    }

    @Test
    void citizenCannotCreateAnnouncement() throws Exception {
        String token = registerVerified("dashcitizen", "dashcitizen@example.org");
        mockMvc.perform(post("/records/announcements")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Citizen bulletin","body":"should not persist"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void blankTitleOrBodyIsRejected() throws Exception {
        String token = coordinatorToken("dashcoord2", "dashcoord2@example.org");
        mockMvc.perform(post("/records/announcements")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"   ","body":"some body"}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/records/announcements")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Some title","body":""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unauthenticatedCreateAnnouncementIsRejected() throws Exception {
        mockMvc.perform(post("/records/announcements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Anon","body":"no token"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    // -- case review --------------------------------------------------------

    @Test
    void coordinatorReviewPersistsAndLeavesStatusAlone() throws Exception {
        seedCase("case-review-msg-001", MissingPerson.Status.OPEN);
        String token = coordinatorToken("reviewcoord", "reviewcoord@example.org");

        MvcResult reviewed = mockMvc.perform(
                        post("/records/missing/case-review-msg-001/review")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"decision":"CONFIRMED"}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").value("case-review-msg-001"))
                .andExpect(jsonPath("$.reviewStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.reviewedBy").value("reviewcoord"))
                // The sync-derived case status is NOT the review: untouched.
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn();
        assertNotNull(JsonPath.read(
                reviewed.getResponse().getContentAsString(), "$.reviewedAt"));

        // The decision is persisted: the list view shows it too.
        MvcResult list = mockMvc.perform(get("/records/missing")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        List<Map<String, Object>> matches = JsonPath.read(
                list.getResponse().getContentAsString(),
                "$[?(@.messageId == 'case-review-msg-001')]");
        assertFalse(matches.isEmpty(), "seeded case must be listed");
        assertEquals("CONFIRMED", matches.get(0).get("reviewStatus"));
        assertEquals("reviewcoord", matches.get(0).get("reviewedBy"));
        assertNotNull(matches.get(0).get("reviewedAt"));
        assertEquals("OPEN", matches.get(0).get("status"));
    }

    @Test
    void reviewOfUnknownCaseIs404() throws Exception {
        String token = coordinatorToken("reviewcoord2", "reviewcoord2@example.org");
        mockMvc.perform(post("/records/missing/no-such-case-id/review")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"CONFIRMED"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void citizenCannotReviewCase() throws Exception {
        seedCase("case-review-msg-002", MissingPerson.Status.FOUND_REPORTED);
        String token = registerVerified("reviewcitizen", "reviewcitizen@example.org");
        mockMvc.perform(post("/records/missing/case-review-msg-002/review")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"REJECTED"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void badReviewDecisionIsRejected() throws Exception {
        seedCase("case-review-msg-003", MissingPerson.Status.OPEN);
        String token = coordinatorToken("reviewcoord3", "reviewcoord3@example.org");
        mockMvc.perform(post("/records/missing/case-review-msg-003/review")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"MAYBE"}
                                """))
                .andExpect(status().isBadRequest());
    }

    // -- audit --------------------------------------------------------------

    @Test
    void auditReturnsSeededRowsWithContractFields() throws Exception {
        SyncAudit row = new SyncAudit();
        row.setDeviceId("device-audit-001");
        row.setMessageId("audit-msg-001");
        row.setAction("SYNC_PUSH");
        row.setHopCount(2);
        row.setDetail("seeded audit row");
        syncAuditRepository.save(row);

        String token = registerVerified("auditreader", "auditreader@example.org");
        MvcResult result = mockMvc.perform(get("/records/audit")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        List<Map<String, Object>> matches = JsonPath.read(
                result.getResponse().getContentAsString(),
                "$[?(@.messageId == 'audit-msg-001')]");
        assertFalse(matches.isEmpty(), "seeded audit row must be returned");
        Map<String, Object> item = matches.get(0);
        assertEquals("device-audit-001", item.get("deviceId"));
        assertEquals("SYNC_PUSH", item.get("action"));
        assertEquals(2, ((Number) item.get("hopCount")).intValue());
        assertEquals("seeded audit row", item.get("detail"));
        assertNotNull(item.get("id"));
        assertNotNull(item.get("createdAt"));
    }

    @Test
    void unauthenticatedAuditIsRejected() throws Exception {
        mockMvc.perform(get("/records/audit"))
                .andExpect(status().isUnauthorized());
    }

    // -- helpers ------------------------------------------------------------

    private void seedCase(String messageId, MissingPerson.Status status) {
        MissingPerson person = new MissingPerson();
        person.setMessageId(messageId);
        person.setName("Seeded Case Person");
        person.setStatus(status);
        person.setReportedByDeviceId("device-seed-001");
        missingPersonRepository.save(person);
    }

    /**
     * Registers a verified citizen and promotes them to COORDINATOR via an
     * admin-issued direct role code (the RoleFlowTests pattern); returns
     * the fresh COORDINATOR token.
     */
    private String coordinatorToken(String username, String email) throws Exception {
        String citizenToken = registerVerified(username, email);
        String adminToken = login("admin", "admin123");
        MvcResult codeResult = mockMvc.perform(post("/admin/role-codes")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username
                                + "\",\"role\":\"COORDINATOR\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String accessCode = JsonPath.read(
                codeResult.getResponse().getContentAsString(), "$.accessCode");
        MvcResult redeemed = mockMvc.perform(post("/profile/role-code")
                        .header("Authorization", "Bearer " + citizenToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + accessCode + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(redeemed.getResponse().getContentAsString(), "$.token");
    }

    /** Registers a user and verifies the email; returns the auto-login token. */
    private String registerVerified(String username, String email) throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + email
                                + "\",\"password\":\"secret123\"}"))
                .andExpect(status().isCreated());
        String code = mail.lastVerificationCode(email);
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
}
