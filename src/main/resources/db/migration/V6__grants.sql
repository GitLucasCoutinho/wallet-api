-- =====================================================================================
-- V6 - Privileges (least privilege, versioned)
--
-- WALLET_APP (the connection Spring uses):
--   SELECT  on wallet, wallet_transaction  -> read model (balance, statement)
--   EXECUTE on pkg_wallet                  -> the only way to write
--   NO insert/update/delete on any table, no DDL, no access to WALLET_AUDIT.
--
-- WALLET_AUDIT (compliance / reconciliation):
--   SELECT on the ledger only; it owns the audit trail, which is append-only by trigger.
--
-- WALLET_OWNER keeps the objects (it is the definer of the package): its rights are
-- implicit as owner, never granted through roles (roles do not apply inside
-- definer-rights PL/SQL).
-- =====================================================================================

GRANT SELECT ON wallet TO wallet_app;
GRANT SELECT ON wallet_transaction TO wallet_app;
GRANT EXECUTE ON pkg_wallet TO wallet_app;

-- Read access for reconciliation/audit jobs. No DML.
GRANT SELECT ON wallet TO wallet_audit;
GRANT SELECT ON wallet_transaction TO wallet_audit;
