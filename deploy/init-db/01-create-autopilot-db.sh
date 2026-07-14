#!/bin/sh
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    SELECT 'CREATE DATABASE afyasignal_autopilot'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'afyasignal_autopilot')\gexec
EOSQL
