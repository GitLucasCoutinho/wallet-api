-- =====================================================================================
-- V5 - Database-enforced invariants (triggers)
--
--  * TRG_WALLET_TRANSACTION_IMMUTABLE: the statement is append-only. Not even a direct
--    SQL executed by the schema owner can change or delete a posted entry.
--  * TRG_WALLET_BALANCE_AUDIT: every balance change produces one row in the audit trail,
--    owned by the WALLET_AUDIT schema. It is written by the database, in the same
--    transaction as the change, so a balance can never move without a matching trail.
--    The transaction that caused the change is read from PKG_WALLET session state; when
--    the change did not come from the package (manual DBA update) transaction_id is NULL,
--    which still leaves the previous/new balance pair recorded.
-- =====================================================================================

CREATE OR REPLACE TRIGGER trg_wallet_transaction_immutable
BEFORE UPDATE OR DELETE ON wallet_transaction
BEGIN
    RAISE_APPLICATION_ERROR(-20010, 'LEDGER_IMMUTABLE: wallet_transaction is append-only');
END;
/

CREATE OR REPLACE TRIGGER trg_wallet_balance_audit
AFTER UPDATE OF balance ON wallet
FOR EACH ROW
BEGIN
    IF :OLD.balance <> :NEW.balance THEN
        INSERT INTO wallet_audit.balance_audit (
            id, wallet_id, transaction_id, previous_balance, new_balance, delta, changed_at, changed_by
        ) VALUES (
            wallet_audit.seq_balance_audit.NEXTVAL,
            :NEW.id,
            pkg_wallet.current_transaction_id(),
            :OLD.balance,
            :NEW.balance,
            :NEW.balance - :OLD.balance,
            systimestamp,
            SYS_CONTEXT('USERENV', 'SESSION_USER')
        );
    END IF;
END;
/
