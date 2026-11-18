-- RescueMesh server schema V4 (dashboard case review).
-- A coordinator/admin reviews a Missing <-> Found case on the dashboard
-- (confirm or reject the reported match); the decision is persisted here
-- so it survives reloads. Review is a human judgement layered on top of
-- the sync-derived case status — it never changes `status`
-- (OPEN / FOUND_REPORTED), and nothing auto-closes a case.
--   * review_status — NULL until reviewed, then CONFIRMED or REJECTED.
--   * reviewed_by   — username of the coordinator/admin who reviewed.
--   * reviewed_at   — when the review happened.
-- Column names match Hibernate's default physical naming (camelCase -> snake_case).

alter table missing_person add column review_status varchar(20);
alter table missing_person add column reviewed_by varchar(120);
alter table missing_person add column reviewed_at timestamp;
