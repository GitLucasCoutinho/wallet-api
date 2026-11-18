-- =====================================================================================
-- V4 - PKG_WALLET body
-- =====================================================================================

CREATE OR REPLACE PACKAGE BODY pkg_wallet AS

    -- Session state used to attribute the balance change to the transaction that caused
    -- it. The audit trigger reads it through CURRENT_TRANSACTION_ID. It is meaningless
    -- outside a PKG_WALLET write, and it is always reset before the procedure returns.
    g_current_transaction_id NUMBER;

    FUNCTION current_transaction_id RETURN NUMBER IS
    BEGIN
        RETURN g_current_transaction_id;
    END current_transaction_id;

    PROCEDURE fail(p_code IN PLS_INTEGER, p_message IN VARCHAR2) IS
    BEGIN
        RAISE_APPLICATION_ERROR(p_code, p_message, FALSE);
    END fail;

    FUNCTION normalize_type(p_type IN VARCHAR2) RETURN VARCHAR2 IS
        l_type VARCHAR2(4000) := UPPER(TRIM(p_type));
    BEGIN
        IF l_type IS NULL OR l_type NOT IN (c_credit, c_debit) THEN
            fail(e_invalid_type, 'INVALID_TRANSACTION_TYPE: type must be CREDIT or DEBIT');
        END IF;
        RETURN l_type;
    END normalize_type;

    FUNCTION normalize_amount(p_amount IN NUMBER) RETURN NUMBER IS
        l_amount NUMBER(19, 2);
    BEGIN
        IF p_amount IS NULL THEN
            fail(e_invalid_amount, 'INVALID_AMOUNT: amount is required');
        END IF;
        IF p_amount <= 0 THEN
            fail(e_invalid_amount, 'INVALID_AMOUNT: amount must be greater than zero');
        END IF;
        IF p_amount <> ROUND(p_amount, 2) THEN
            fail(e_invalid_amount, 'INVALID_AMOUNT: amount must have at most 2 decimal places');
        END IF;
        IF p_amount > c_max_amount THEN
            fail(e_invalid_amount, 'INVALID_AMOUNT: amount is above the maximum supported value');
        END IF;
        l_amount := ROUND(p_amount, 2);
        RETURN l_amount;
    END normalize_amount;

    -- Looks up the transaction already registered for this wallet/key.
    -- o_found=false means no row (the OUT record is left untouched).
    PROCEDURE find_transaction(
        p_wallet_id       IN  NUMBER,
        p_idempotency_key IN  VARCHAR2,
        o_row             OUT wallet_transaction%ROWTYPE,
        o_found           OUT BOOLEAN
    ) IS
        l_row wallet_transaction%ROWTYPE;
    BEGIN
        BEGIN
            SELECT * INTO l_row
              FROM wallet_transaction
             WHERE wallet_id = p_wallet_id
               AND idempotency_key = p_idempotency_key;
            o_row   := l_row;
            o_found := TRUE;
        EXCEPTION
            WHEN NO_DATA_FOUND THEN
                o_found := FALSE;
        END;
    END find_transaction;

    -- Same key + same fingerprint: return the original outcome.
    -- Same key + different fingerprint: the client is trying to apply a second, distinct
    -- operation under a key that already has a result. That is a conflict, not a retry.
    PROCEDURE return_original(
        p_existing       IN  wallet_transaction%ROWTYPE,
        p_request_hash   IN  VARCHAR2,
        o_transaction_id OUT NUMBER,
        o_balance_after  OUT NUMBER,
        o_replayed       OUT NUMBER
    ) IS
    BEGIN
        IF p_existing.request_hash <> p_request_hash THEN
            fail(e_idempotency_conflict,
                 'IDEMPOTENCY_CONFLICT: idempotency key already used with a different payload');
        END IF;
        o_transaction_id := p_existing.id;
        o_balance_after  := p_existing.balance_after;
        o_replayed       := 1;
    END return_original;

    PROCEDURE create_wallet(
        p_holder_name     IN  VARCHAR2,
        p_holder_document IN  VARCHAR2,
        o_wallet_id       OUT NUMBER
    ) IS
        l_holder_name     VARCHAR2(4000) := TRIM(p_holder_name);
        l_holder_document VARCHAR2(4000) := NULLIF(TRIM(p_holder_document), '');
    BEGIN
        IF l_holder_name IS NULL OR LENGTH(l_holder_name) = 0 THEN
            fail(e_invalid_holder, 'INVALID_HOLDER: holder name is required');
        END IF;
        IF LENGTH(l_holder_name) > c_max_holder_name_length THEN
            fail(e_invalid_holder, 'INVALID_HOLDER: holder name must have at most 120 characters');
        END IF;
        IF l_holder_document IS NOT NULL AND LENGTH(l_holder_document) > c_max_holder_document_length THEN
            fail(e_invalid_holder, 'INVALID_HOLDER: holder document must have at most 32 characters');
        END IF;

        o_wallet_id := seq_wallet.NEXTVAL;

        INSERT INTO wallet (id, holder_name, holder_document, currency, balance, created_at, updated_at)
        VALUES (o_wallet_id, l_holder_name, l_holder_document, 'BRL', 0, systimestamp, systimestamp);

        COMMIT;
    EXCEPTION
        WHEN OTHERS THEN
            ROLLBACK;
            RAISE;
    END create_wallet;

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
    ) IS
        l_type          VARCHAR2(10 CHAR);
        l_amount        NUMBER(19, 2);
        l_key           VARCHAR2(4000) := TRIM(p_idempotency_key);
        l_description   VARCHAR2(4000) := TRIM(p_description);
        l_wallet        wallet%ROWTYPE;
        l_existing      wallet_transaction%ROWTYPE;
        l_found         BOOLEAN;
        l_balance_after NUMBER(19, 2);
    BEGIN
        g_current_transaction_id := NULL;

        l_type   := normalize_type(p_type);
        l_amount := normalize_amount(p_amount);

        IF l_key IS NULL OR LENGTH(l_key) = 0 THEN
            fail(e_missing_idempotency, 'MISSING_IDEMPOTENCY_KEY: idempotency key is required');
        END IF;
        IF LENGTH(l_key) > c_max_idempotency_key_length THEN
            fail(e_invalid_idempotency,
                 'INVALID_IDEMPOTENCY_KEY: idempotency key must have at most 80 characters');
        END IF;
        IF p_request_hash IS NULL OR LENGTH(p_request_hash) = 0 THEN
            fail(e_idempotency_conflict, 'IDEMPOTENCY_CONFLICT: request fingerprint is required');
        END IF;
        IF p_wallet_id IS NULL THEN
            fail(e_wallet_not_found, 'WALLET_NOT_FOUND: wallet id is required');
        END IF;

        -- Fast path: retry of an operation already applied. No lock needed.
        find_transaction(p_wallet_id, l_key, l_existing, l_found);
        IF l_found THEN
            return_original(l_existing, p_request_hash, o_transaction_id, o_balance_after, o_replayed);
            RETURN;
        END IF;

        -- Serialization point: every write of one wallet passes through this row lock.
        -- Wallets are independent, so the lock does not limit overall throughput.
        BEGIN
            SELECT * INTO l_wallet
              FROM wallet
             WHERE id = p_wallet_id
               FOR UPDATE;
        EXCEPTION
            WHEN NO_DATA_FOUND THEN
                fail(e_wallet_not_found,
                     'WALLET_NOT_FOUND: wallet ' || p_wallet_id || ' does not exist');
        END;

        -- Re-check under the lock: a concurrent duplicate may have committed while this
        -- session was waiting for the row lock. This closes the check-then-act race.
        find_transaction(p_wallet_id, l_key, l_existing, l_found);
        IF l_found THEN
            return_original(l_existing, p_request_hash, o_transaction_id, o_balance_after, o_replayed);
            COMMIT;  -- nothing was changed, but the transaction holds the lock
            RETURN;
        END IF;

        IF l_type = c_debit AND l_wallet.balance < l_amount THEN
            fail(e_insufficient_funds,
                 'INSUFFICIENT_FUNDS: balance ' || TO_CHAR(l_wallet.balance, 'FM99999999999999990.00') ||
                 ' is lower than the requested debit of ' || TO_CHAR(l_amount, 'FM99999999999999990.00'));
        END IF;

        IF l_type = c_credit THEN
            l_balance_after := l_wallet.balance + l_amount;
        ELSE
            l_balance_after := l_wallet.balance - l_amount;
        END IF;

        o_transaction_id := seq_wallet_transaction.NEXTVAL;
        g_current_transaction_id := o_transaction_id;

        INSERT INTO wallet_transaction (
            id, wallet_id, type, amount, balance_before, balance_after,
            idempotency_key, request_hash, description, occurred_at
        ) VALUES (
            o_transaction_id, p_wallet_id, l_type, l_amount, l_wallet.balance, l_balance_after,
            l_key, p_request_hash, SUBSTR(l_description, 1, c_max_description_length), systimestamp
        );

        -- Fires TRG_WALLET_BALANCE_AUDIT, which writes the audit trail in WALLET_AUDIT.
        UPDATE wallet
           SET balance    = l_balance_after,
               updated_at = systimestamp
         WHERE id = p_wallet_id;

        COMMIT;

        g_current_transaction_id := NULL;

        o_balance_after := l_balance_after;
        o_replayed      := 0;
    EXCEPTION
        WHEN OTHERS THEN
            g_current_transaction_id := NULL;
            ROLLBACK;
            RAISE;
    END register_transaction;

END pkg_wallet;
/
