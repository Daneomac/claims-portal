-- Events this service has already handled (ADR 0003).
--
-- A row is inserted in the same transaction that sends the confirmation email, and
-- only committed once the email has been handed to the SMTP server. The primary key
-- is what makes a redelivered event harmless: the second insert finds the row and
-- the event is skipped.
CREATE TABLE processed_event (
    event_id     uuid         PRIMARY KEY,
    claim_number varchar(20)  NOT NULL,
    processed_at timestamptz  NOT NULL
);
