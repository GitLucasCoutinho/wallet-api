-- =====================================================================================
-- V3 - PKG_WALLET specification
--
-- The package is the ONLY write path for the wallet domain. The runtime user
-- (WALLET_APP) has no INSERT/UPDATE/DELETE grants: it can only execute this package
-- and read the two tables.
--
-- Transaction ownership: each public procedure owns its transaction and commits at the
-- end (or rolls back and re-raises on any failure). Rationale in DECISIONS.md:
--   * the business invariant ("balance never negative, never twice") and the unit of
--     work that protects it live in the same place;
--   * no caller can accidentally commit a half-applied operation, or hold the wallet
--     row lock longer than the call itself;
--   * the procedure is safe to call from any client (batch, another system), not only
--     from this Spring application.
--
-- Concurrency: REGISTER_TRANSACTION locks the wallet row with SELECT ... FOR UPDATE
-- before reading the balance, so all writes for one wallet are serialized by the
-- database. Different wallets never block each other.
--
-- Idempotency: (wallet_id, idempotency_key) is unique. The same key with the same
-- request_hash returns the original result; the same key with a different hash is a
-- conflict (ORA-20003). The key is checked twice: before the lock (fast path) and again
-- after the lock is acquired, which closes the race between two identical requests.
--
-- Error contract (ORA-20xxx, RAISE_APPLICATION_ERROR):
--   -20001 WALLET_NOT_FOUND       -> HTTP 404
--   -20002 INSUFFICIENT_FUNDS     -> HTTP 422
--   -20003 IDEMPOTENCY_CONFLICT   -> HTTP 409
--   -20004 INVALID_AMOUNT         -> HTTP 400
--   -20005 INVALID_TRANSACTION_TYPE -> HTTP 400
--   -20006 INVALID_HOLDER         -> HTTP 400
--   -20008 INVALID_IDEMPOTENCY_KEY -> HTTP 400
--   -20009 MISSING_IDEMPOTENCY_KEY -> HTTP 400
--   -20010 LEDGER_IMMUTABLE       -> HTTP 500 (should never reach a client)
--   -20011 AUDIT_IMMUTABLE        -> HTTP 500 (audit schema, trigger only)
-- =====================================================================================

CREATE OR REPLACE PACKAGE pkg_wallet AUTHID DEFINER AS

    c_credit CONSTANT VARCHAR2(10 CHAR) := 'CREDIT';
    c_debit  CONSTANT VARCHAR2(10 CHAR) := 'DEBIT';

    c_max_holder_name_length     CONSTANT PLS_INTEGER := 120;
    c_max_holder_document_length CONSTANT PLS_INTEGER := 32;
    c_max_description_length     CONSTANT PLS_INTEGER := 255;
    c_max_idempotency_key_length CONSTANT PLS_INTEGER := 80;
    c_max_amount                 CONSTANT NUMBER := 99999999999999999.99;

    e_wallet_not_found     CONSTANT PLS_INTEGER := -20001;
    e_insufficient_funds   CONSTANT PLS_INTEGER := -20002;
    e_idempotency_conflict CONSTANT PLS_INTEGER := -20003;
    e_invalid_amount       CONSTANT PLS_INTEGER := -20004;
    e_invalid_type         CONSTANT PLS_INTEGER := -20005;
    e_invalid_holder       CONSTANT PLS_INTEGER := -20006;
    e_invalid_idempotency  CONSTANT PLS_INTEGER := -20008;
    e_missing_idempotency  CONSTANT PLS_INTEGER := -20009;

    -- Id of the transaction being applied in the current session, consumed by the
    -- audit trigger TRG_WALLET_BALANCE_AUDIT. NULL when the balance change did not
    -- come from this package (for example a manual DBA update).
    FUNCTION current_transaction_id RETURN NUMBER;

    -- Creates a wallet with balance 0.00 and returns its generated id.
    PROCEDURE create_wallet(
        p_holder_name     IN  VARCHAR2,
        p_holder_document IN  VARCHAR2,
        o_wallet_id       OUT NUMBER
    );

    -- Applies one credit or debit atomically and returns the resulting balance.
    -- o_replayed = 1 means the idempotency key had already been applied and the
    -- original result was returned without touching the balance.
    PROCEDURE register_transaction(
        p_wallet_id       IN  NUMBER,
        p_type            IN  VARCHAR2,
        p_amount          IN  NUMBER,
        p_idempotency_key IN  VARCHAR2,
        p_request_hash    IN  VARCHAR2,
        p_description     IN  VARCHAR2,
        o_transaction_id  OUT NUMBER,
        o_balance_after   OUT NUMBER,
        o_replayed        OUT NUMBER
    );

END pkg_wallet;
/
