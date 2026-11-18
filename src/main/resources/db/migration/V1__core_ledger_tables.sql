-- =====================================================================================
-- V1 - Core ledger model (schema WALLET_OWNER)
--
-- Design notes
--  * Money is NUMBER(19,2): never BINARY_FLOAT/BINARY_DOUBLE. 19 digits total with 2
--    decimals keeps ~1e17 cents, enough for BRL balances, and the scale is enforced by
--    a CHECK constraint so a buggy client cannot write 10.005.
--  * wallet.balance is the authoritative balance (single row per wallet, the row lock is
--    the serialization point). wallet_transaction is the append-only statement.
--  * Every transaction stores balance_before/balance_after plus a CHECK that proves the
--    arithmetic: a corrupted write is rejected by the database, not only by the app.
--  * (wallet_id, idempotency_key) is unique: the key is scoped to a wallet, so two
--    wallets may reuse the same client-supplied key.
-- =====================================================================================

CREATE TABLE wallet (
    id              NUMBER(19)          NOT NULL,
    holder_name     VARCHAR2(120 CHAR)  NOT NULL,
    holder_document VARCHAR2(32 CHAR),
    currency        CHAR(3 CHAR)        DEFAULT 'BRL' NOT NULL,
    balance         NUMBER(19,2)        DEFAULT 0 NOT NULL,
    created_at      TIMESTAMP(6) WITH TIME ZONE DEFAULT systimestamp NOT NULL,
    updated_at      TIMESTAMP(6) WITH TIME ZONE DEFAULT systimestamp NOT NULL,
    CONSTRAINT pk_wallet PRIMARY KEY (id),
    CONSTRAINT ck_wallet_currency_brl CHECK (currency = 'BRL'),
    CONSTRAINT ck_wallet_holder_name CHECK (LENGTH(TRIM(holder_name)) > 0),
    CONSTRAINT ck_wallet_balance_non_negative CHECK (balance >= 0),
    CONSTRAINT ck_wallet_balance_scale CHECK (balance = ROUND(balance, 2))
);

CREATE TABLE wallet_transaction (
    id              NUMBER(19)          NOT NULL,
    wallet_id       NUMBER(19)          NOT NULL,
    type            VARCHAR2(10 CHAR)   NOT NULL,
    amount          NUMBER(19,2)        NOT NULL,
    balance_before  NUMBER(19,2)        NOT NULL,
    balance_after   NUMBER(19,2)        NOT NULL,
    idempotency_key VARCHAR2(80 CHAR)   NOT NULL,
    request_hash    VARCHAR2(64 CHAR)   NOT NULL,
    description     VARCHAR2(255 CHAR),
    occurred_at     TIMESTAMP(6) WITH TIME ZONE DEFAULT systimestamp NOT NULL,
    CONSTRAINT pk_wallet_transaction PRIMARY KEY (id),
    CONSTRAINT fk_wallet_transaction_wallet FOREIGN KEY (wallet_id) REFERENCES wallet (id),
    CONSTRAINT ck_wallet_transaction_type CHECK (type IN ('CREDIT', 'DEBIT')),
    CONSTRAINT ck_wallet_transaction_amount CHECK (amount > 0),
    CONSTRAINT ck_wallet_transaction_amount_scale CHECK (amount = ROUND(amount, 2)),
    CONSTRAINT ck_wallet_transaction_balance CHECK (
        (type = 'CREDIT' AND balance_after = balance_before + amount) OR
        (type = 'DEBIT' AND balance_after = balance_before - amount)
    ),
    CONSTRAINT uk_wallet_transaction_idempotency UNIQUE (wallet_id, idempotency_key)
);

-- Statement query: newest first, filtered by wallet and period.
CREATE INDEX ix_wallet_transaction_wallet_occurred
    ON wallet_transaction (wallet_id, occurred_at DESC, id DESC);

COMMENT ON TABLE wallet IS 'Digital wallet: current balance is the authoritative value, protected by a row lock.';
COMMENT ON COLUMN wallet.balance IS 'Balance in BRL, always 2 decimals, never negative (CHECK).';
COMMENT ON COLUMN wallet.updated_at IS 'Last balance change, written by PKG_WALLET only.';
COMMENT ON TABLE wallet_transaction IS 'Append-only statement. Immutable by trigger TRG_WALLET_TRANSACTION_IMMUTABLE.';
COMMENT ON COLUMN wallet_transaction.balance_before IS 'Balance before this entry, part of the consistency CHECK.';
COMMENT ON COLUMN wallet_transaction.balance_after IS 'Balance after this entry, part of the consistency CHECK.';
COMMENT ON COLUMN wallet_transaction.idempotency_key IS 'Client supplied key, unique per wallet.';
COMMENT ON COLUMN wallet_transaction.request_hash IS 'SHA-256 of the normalized request, detects key reuse with a different payload.';
