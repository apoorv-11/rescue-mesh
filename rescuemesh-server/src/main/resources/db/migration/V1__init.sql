-- RescueMesh server schema V1 (Prompt 3 skeleton).
-- Flyway owns the schema; JPA runs with ddl-auto=validate against these tables.
-- Column names match Hibernate's default physical naming (camelCase -> snake_case).

create table users (
    id            bigserial primary key,
    username      varchar(255) not null unique,
    email         varchar(255) unique,
    password_hash varchar(255) not null,
    role          varchar(32)  not null,
    device_id     varchar(255) unique,
    created_at    timestamptz  not null
);

create table message (
    id               bigserial primary key,
    message_id       varchar(255) not null unique,
    event_type       varchar(32)  not null,
    origin_device_id varchar(255) not null,
    created_at       timestamptz  not null,
    expires_at       timestamptz,
    priority         integer      not null,
    hop_limit        integer      not null,
    hop_count        integer      not null,
    hop_trace        text,
    payload          text,
    signature        text
);
create index idx_message_origin_device on message (origin_device_id);
create index idx_message_event_type on message (event_type);

create table sos_record (
    id               bigserial primary key,
    message_id       varchar(255),
    origin_device_id varchar(255),
    status           varchar(32) not null,
    latitude         double precision,
    longitude        double precision,
    note             text,
    created_at       timestamptz not null,
    updated_at       timestamptz not null
);

create table missing_person (
    id                    bigserial primary key,
    name                  varchar(255) not null,
    age                   integer,
    description           text,
    last_seen_area        varchar(255),
    last_seen_at          timestamptz,
    status                varchar(32) not null,
    reported_by_device_id varchar(255),
    created_at            timestamptz not null,
    updated_at            timestamptz not null
);

create table announcement (
    id         bigserial primary key,
    title      varchar(255) not null,
    body       text         not null,
    priority   integer      not null,
    created_by varchar(255),
    expires_at timestamptz,
    created_at timestamptz  not null
);

create table sync_audit (
    id         bigserial primary key,
    device_id  varchar(255),
    message_id varchar(255),
    action     varchar(64) not null,
    hop_count  integer,
    detail     text,
    created_at timestamptz not null
);
create index idx_sync_audit_device on sync_audit (device_id);
