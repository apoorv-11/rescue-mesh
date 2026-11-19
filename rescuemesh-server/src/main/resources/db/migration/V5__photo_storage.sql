-- RescueMesh server schema V5 (photo storage moves into the database).
-- Photos used to be stored as files under PHOTO_DIR. On the production
-- host (Render free tier) the filesystem is ephemeral: every redeploy
-- wiped the uploaded photo files while the database rows (records,
-- cases, reviews) survived, so the dashboard showed "photo referenced
-- but not stored" for records whose photos had uploaded fine. Photos
-- are small (<= 500 KB) and are hash-verified before storage, so they
-- now live in the database next to their records and survive redeploys.
--   * message_id — the record the photo belongs to (its photoRef id).
--   * data       — the verified JPEG bytes.
create table photo_file (
    message_id varchar(255) primary key,
    data bytea not null,
    created_at timestamp not null
);
