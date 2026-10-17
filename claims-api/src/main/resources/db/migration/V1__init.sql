-- Claims and the transactional outbox (PLAN section 4, ADR 0002).

-- Source of the numeric part of claim numbers (HC-2026-000123). Never reset, so
-- numbers stay unique across years.
CREATE SEQUENCE claim_number_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE claim (
    id                      uuid          PRIMARY KEY,
    claim_number            varchar(20)   NOT NULL,
    policy_number           varchar(12)   NOT NULL,
    policyholder_first_name varchar(100)  NOT NULL,
    policyholder_last_name  varchar(100)  NOT NULL,
    policyholder_email      varchar(254)  NOT NULL,
    policyholder_phone      varchar(20),
    loss_date               date          NOT NULL,
    loss_type               varchar(20)   NOT NULL,
    loss_description        varchar(2000) NOT NULL,
    estimated_amount        numeric(10, 2),
    property_street         varchar(200)  NOT NULL,
    property_unit           varchar(20),
    property_city           varchar(100)  NOT NULL,
    property_province       varchar(2)    NOT NULL,
    property_postal_code    varchar(7)    NOT NULL,
    status                  varchar(20)   NOT NULL,
    submitted_at            timestamptz   NOT NULL,
    CONSTRAINT claim_claim_number_uk UNIQUE (claim_number)
);

-- Events waiting to be published to RabbitMQ. A row is written in the same
-- transaction as the claim; the relay sets published_at once the broker confirms.
CREATE TABLE outbox_event (
    id            uuid          PRIMARY KEY,   -- the event ID, also the AMQP messageId
    aggregate_id  uuid          NOT NULL,      -- the claim the event is about
    event_type    varchar(100)  NOT NULL,
    event_version integer       NOT NULL,
    payload       jsonb         NOT NULL,
    trace_parent  varchar(100),                -- W3C traceparent, so the trace continues after the async gap
    created_at    timestamptz   NOT NULL,
    published_at  timestamptz,
    attempts      integer       NOT NULL DEFAULT 0,
    last_error    varchar(1000)
);

-- The relay only ever looks for unpublished rows, oldest first.
CREATE INDEX outbox_event_unpublished_idx ON outbox_event (created_at) WHERE published_at IS NULL;
