-- V2__block_truncate.sql
-- Blocks TRUNCATE operations on ledger_entries and audit_log via statement-level triggers.

CREATE OR REPLACE FUNCTION prevent_truncate()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Table % is strictly append-only: TRUNCATE operations are forbidden.', TG_TABLE_NAME
        USING ERRCODE = '55000';
END;
$$ LANGUAGE plpgsql;

-- Apply TRUNCATE prevention trigger to ledger_entries
DROP TRIGGER IF EXISTS trg_ledger_entries_no_truncate ON ledger_entries;
CREATE TRIGGER trg_ledger_entries_no_truncate
BEFORE TRUNCATE ON ledger_entries
FOR EACH STATEMENT
EXECUTE FUNCTION prevent_truncate();

-- Apply TRUNCATE prevention trigger to audit_log
DROP TRIGGER IF EXISTS trg_audit_log_no_truncate ON audit_log;
CREATE TRIGGER trg_audit_log_no_truncate
BEFORE TRUNCATE ON audit_log
FOR EACH STATEMENT
EXECUTE FUNCTION prevent_truncate();

-- Revoke TRUNCATE permissions on append-only tables
REVOKE TRUNCATE ON ledger_entries FROM PUBLIC;
REVOKE TRUNCATE ON audit_log FROM PUBLIC;
