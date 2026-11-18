package com.rescuemesh.server.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sync API (contracts §5): {@code GET /sync/pubkey} is public (phones need the
 * server's ack-verification key before they hold any token flow result), and
 * {@code POST /sync/batch} runs under the default authenticated rule.
 *
 * <p>Bodies are parsed/serialised here with a plain Jackson {@link ObjectMapper}
 * (not the MVC converter) so the envelope JSON is handled exactly as parsed —
 * no field renaming, no number reformatting.
 */
@RestController
public class SyncController {

    private final SyncService syncService;
    private final SyncSigningKey signingKey;
    private final ObjectMapper mapper = new ObjectMapper();

    public SyncController(SyncService syncService, SyncSigningKey signingKey) {
        this.syncService = syncService;
        this.signingKey = signingKey;
    }

    @GetMapping(value = "/sync/pubkey", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> pubkey() throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("keyId", signingKey.keyId());
        body.put("publicKey", signingKey.publicKeyB64Url());
        return ResponseEntity.ok(mapper.writeValueAsString(body));
    }

    @PostMapping(value = "/sync/batch", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> batch(@RequestBody String rawBody) throws Exception {
        JsonNode root;
        try {
            root = mapper.readTree(rawBody);
        } catch (Exception e) {
            return badRequest("request body is not valid JSON");
        }
        if (root == null || !root.isObject()
                || root.get("items") == null || !root.get("items").isArray()) {
            return badRequest("request must be an object with an items array");
        }
        ObjectNode response = syncService.processBatch(root);
        return ResponseEntity.ok(mapper.writeValueAsString(response));
    }

    private ResponseEntity<String> badRequest(String message) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("error", message);
        return ResponseEntity.badRequest().body(mapper.writeValueAsString(body));
    }
}
