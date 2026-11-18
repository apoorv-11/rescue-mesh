-- RescueMesh server schema V2 (auth hardening v2).
-- Accounts, email verification and role access:
--   * users gain email_verified — login is refused until the email is proven;
--     new accounts start false, the seeded admin is created true by DataSeeder.
--   * email_verification — one row per issued 6-digit code challenge
--     (SHA-256 hash only, 15-minute expiry, attempt counter, consumed flag).
--   * role_application — a user's request for VOLUNTEER / COORDINATOR,
--     reviewed by an admin (PENDING -> APPROVED / REJECTED).
--   * role_access_code — one-time code (SHA-256 hash only, 7-day expiry)
--     granting a role when redeemed; application_id is null for codes an
--     admin created directly.
-- Column names match Hibernate's default physical naming (camelCase -> snake_case).

alter table users add column email_verified boolean not null default false;

-- Existing ADMIN rows predate verification (the V1-era seeder created them
-- without it) and have no mailbox to verify with — DataSeeder creates new
-- admins already verified, so migrated ones are marked verified here too,
-- otherwise an upgrade would lock the only admin out (login 403).
update users set email_verified = true where role = 'ADMIN';

create table email_verification (
    id         bigserial primary key,
    user_id    bigint       not null references users (id),
    code_hash  varchar(255) not null,
    expires_at timestamptz  not null,
    attempts   integer      not null,
    consumed   boolean      not null,
    created_at timestamptz  not null
);
create index idx_email_verification_user on email_verification (user_id);

create table role_application (
    id             bigserial primary key,
    user_id        bigint       not null references users (id),
    requested_role varchar(32)  not null,
    organization   varchar(255),
    reason         text,
    status         varchar(32)  not null,
    review_note    text,
    reviewed_by    varchar(255),
    created_at     timestamptz  not null,
    reviewed_at    timestamptz
);
create index idx_role_application_user on role_application (user_id);
create index idx_role_application_status on role_application (status);

create table role_access_code (
    id             bigserial primary key,
    user_id        bigint       not null references users (id),
    role           varchar(32)  not null,
    code_hash      varchar(255) not null unique,
    expires_at     timestamptz  not null,
    used_at        timestamptz,
    created_by     varchar(255),
    created_at     timestamptz  not null,
    application_id bigint references role_application (id)
);
create index idx_role_access_code_user on role_access_code (user_id);
