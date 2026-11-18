-- RescueMesh server schema V3 (photo transfer design — case link).
-- Missing <-> Found become one CASE on the server (photo-transfer-design
-- §3): the Found update's payload carries caseId = the Missing record's
-- messageId, and sync materialisation links the pair so the dashboard can
-- show the case (and its photos) side by side. A coordinator closes a
-- case — nothing here auto-closes anything.
--   * message_id — the envelope messageId of the MISSING_PERSON record
--     itself; this is the key the Found update's caseId points at. (V1
--     never stored it on missing_person; sos_record already had one.)
--   * found_message_id / found_at — the PERSON_FOUND_UPDATE that
--     reported this person found (its messageId / envelope createdAt);
--     a newer found update for the same case replaces an older link.
--   * status vocabulary becomes case semantics: OPEN -> FOUND_REPORTED.
--     V1/V2 values are migrated: MISSING -> OPEN, FOUND -> FOUND_REPORTED.
-- Column names match Hibernate's default physical naming (camelCase -> snake_case).

alter table missing_person add column message_id varchar(255);
alter table missing_person add column found_message_id varchar(255);
alter table missing_person add column found_at timestamptz;
create unique index idx_missing_person_message_id on missing_person (message_id);

update missing_person set status = 'FOUND_REPORTED' where status = 'FOUND';
update missing_person set status = 'OPEN' where status = 'MISSING';
alter table missing_person alter column status set default 'OPEN';
