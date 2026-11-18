#!/bin/sh
#
# Wallet API - database bootstrap (runs once, at first database creation, as SYSDBA).
#
# This is the ONLY place that needs DBA rights. It creates the three schemas and the
# objects that the runtime schema owner is not allowed to create itself (the audit trail
# lives in another schema, so its table and the cross-schema grant must be created by a
# privileged user). Everything else - tables, sequences, package, triggers, grants -
# is applied by Flyway from src/main/resources/db/migration.
#
# Passwords come from environment variables (see .env.example); none is committed.
# The script is idempotent: it may be re-executed against an existing database.
#
set -eu

PDB="${ORACLE_DATABASE:-FREEPDB1}"

: "${WALLET_OWNER_PASSWORD:?WALLET_OWNER_PASSWORD is required}"
: "${WALLET_AUDIT_PASSWORD:?WALLET_AUDIT_PASSWORD is required}"
: "${WALLET_APP_PASSWORD:?WALLET_APP_PASSWORD is required}"
: "${ORACLE_PASSWORD:?ORACLE_PASSWORD is required}"

BODY="$(mktemp /tmp/wallet-bootstrap-XXXXXX.sql)"
trap 'rm -f "${BODY}"' EXIT

cat > "${BODY}" <<SQL
SET SERVEROUTPUT ON
SET DEFINE OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE

DECLARE
  PROCEDURE ensure_user(p_user IN VARCHAR2, p_password IN VARCHAR2) IS
    l_exists NUMBER;
  BEGIN
    SELECT COUNT(*) INTO l_exists FROM dba_users WHERE username = p_user;
    IF l_exists = 0 THEN
      EXECUTE IMMEDIATE 'CREATE USER ' || p_user || ' IDENTIFIED BY "' || p_password || '"';
    ELSE
      EXECUTE IMMEDIATE 'ALTER USER ' || p_user || ' IDENTIFIED BY "' || p_password || '"';
    END IF;
  END;
BEGIN
  ensure_user('WALLET_OWNER', q'[${WALLET_OWNER_PASSWORD}]');
  ensure_user('WALLET_AUDIT', q'[${WALLET_AUDIT_PASSWORD}]');
  ensure_user('WALLET_APP',   q'[${WALLET_APP_PASSWORD}]');
END;
/

-- WALLET_OWNER: DDL + PL/SQL owner, used by Flyway only.
-- WALLET_APP:   runtime user, no DDL and no direct DML (grants issued by Flyway).
-- WALLET_AUDIT: owns the balance audit trail, not reachable from WALLET_APP.
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE PROCEDURE, CREATE TRIGGER TO wallet_owner;
GRANT CREATE SESSION TO wallet_app;
GRANT CREATE SESSION TO wallet_audit;

ALTER USER wallet_owner QUOTA UNLIMITED ON users;
ALTER USER wallet_audit QUOTA UNLIMITED ON users;

DECLARE
  PROCEDURE run_ddl(p_sql IN VARCHAR2) IS
  BEGIN
    EXECUTE IMMEDIATE p_sql;
  EXCEPTION
    WHEN OTHERS THEN
      -- 955  name already used by an existing object
      -- 4080 trigger already exists
      IF SQLCODE NOT IN (-955, -4080) THEN
        RAISE;
      END IF;
  END;
BEGIN
  run_ddl(q'[
    CREATE TABLE wallet_audit.balance_audit (
      id               NUMBER(19)          NOT NULL,
      wallet_id        NUMBER(19)          NOT NULL,
      transaction_id   NUMBER(19),
      previous_balance NUMBER(19,2)        NOT NULL,
      new_balance      NUMBER(19,2)        NOT NULL,
      delta            NUMBER(19,2)        NOT NULL,
      changed_at       TIMESTAMP(6) WITH TIME ZONE DEFAULT systimestamp NOT NULL,
      changed_by       VARCHAR2(128 CHAR)  NOT NULL,
      CONSTRAINT pk_balance_audit PRIMARY KEY (id)
    )
  ]');

  run_ddl('CREATE INDEX wallet_audit.ix_balance_audit_wallet ON wallet_audit.balance_audit (wallet_id, changed_at)');
  run_ddl('CREATE SEQUENCE wallet_audit.seq_balance_audit START WITH 1 INCREMENT BY 1 NOCACHE NOORDER NOCYCLE');

  -- The definer-rights trigger in WALLET_OWNER writes here; direct grants, not roles.
  EXECUTE IMMEDIATE 'GRANT INSERT ON wallet_audit.balance_audit TO wallet_owner';
  EXECUTE IMMEDIATE 'GRANT SELECT ON wallet_audit.seq_balance_audit TO wallet_owner';

  run_ddl(q'[
    CREATE OR REPLACE TRIGGER wallet_audit.trg_balance_audit_immutable
    BEFORE UPDATE OR DELETE ON wallet_audit.balance_audit
    BEGIN
      RAISE_APPLICATION_ERROR(-20011, 'AUDIT_IMMUTABLE: balance_audit is append-only');
    END;
  ]');
END;
/

PROMPT Wallet API bootstrap finished.
EXIT
SQL

# Prefer local OS authentication (works even before the listener is up); fall back to a
# TCP SYSDBA connection against the pluggable database service.
if sqlplus -s -L "/ as sysdba" <<'PROBE' >/dev/null 2>&1
SELECT 1 FROM dual;
EXIT
PROBE
then
  { echo "ALTER SESSION SET CONTAINER = ${PDB};"; cat "${BODY}"; } | sqlplus -s -L "/ as sysdba"
else
  sqlplus -s -L "sys/${ORACLE_PASSWORD}@//localhost:1521/${PDB} as sysdba" < "${BODY}"
fi
