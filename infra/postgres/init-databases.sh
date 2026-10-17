#!/bin/sh
# Runs once, on first start of an empty data volume (docker-entrypoint-initdb.d).
# Creates one database and one owning login per service so neither service can
# read the other's tables. Passwords come from the environment, never this file.
set -eu

: "${CLAIMS_DB_PASSWORD:?CLAIMS_DB_PASSWORD must be set}"
: "${NOTIFICATIONS_DB_PASSWORD:?NOTIFICATIONS_DB_PASSWORD must be set}"

psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v claims_pw="$CLAIMS_DB_PASSWORD" \
     -v notifications_pw="$NOTIFICATIONS_DB_PASSWORD" <<'EOSQL'
CREATE ROLE claims LOGIN PASSWORD :'claims_pw';
CREATE DATABASE claims OWNER claims;
REVOKE ALL ON DATABASE claims FROM PUBLIC;

CREATE ROLE notifications LOGIN PASSWORD :'notifications_pw';
CREATE DATABASE notifications OWNER notifications;
REVOKE ALL ON DATABASE notifications FROM PUBLIC;
EOSQL
