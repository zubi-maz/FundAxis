-- MANUAL SQL SERVER MIGRATION: review and apply before deploying the matching application build.
-- Extends the existing share transaction audit, including explicit withdrawal
-- posting classification, and validates valuation-only rows.
-- No table is recreated and no existing financial row is rewritten or deleted.
IF DB_NAME() <> N'fundaxis'
    THROW 50008, 'Run this migration only in the fundaxis database.', 1;

SET XACT_ABORT ON;
BEGIN TRANSACTION;

IF OBJECT_ID('dbo.share_transactions', 'U') IS NULL
    THROW 50002, 'Required dbo.share_transactions table is absent. Verify the FundAxis schema.', 1;

IF COL_LENGTH('dbo.share_transactions', 'share_value_before') IS NULL
    ALTER TABLE dbo.share_transactions ADD share_value_before DECIMAL(20,2) NULL;

IF COL_LENGTH('dbo.share_transactions', 'share_value_after') IS NULL
    ALTER TABLE dbo.share_transactions ADD share_value_after DECIMAL(20,2) NULL;

IF COL_LENGTH('dbo.share_transactions', 'share_value_before') IS NULL
   OR COL_LENGTH('dbo.share_transactions', 'share_value_after') IS NULL
    THROW 50009, 'Valuation snapshot columns are required before updating transaction checks.', 1;

-- Replace only the quantity and type checks. The new quantity branch keeps
-- the previous arithmetic checks for quantity transactions and adds a strict
-- zero-quantity / unchanged-balance branch for valuation-only history rows.
IF OBJECT_ID(N'dbo.ck_share_transactions_quantity', N'C') IS NOT NULL
    ALTER TABLE dbo.share_transactions DROP CONSTRAINT ck_share_transactions_quantity;

IF OBJECT_ID(N'dbo.ck_share_transactions_type', N'C') IS NOT NULL
    ALTER TABLE dbo.share_transactions DROP CONSTRAINT ck_share_transactions_type;

ALTER TABLE dbo.share_transactions WITH CHECK
ADD CONSTRAINT ck_share_transactions_quantity CHECK (
    (
        transaction_type IN ('INITIAL_ALLOCATION', 'INCREASE', 'DECREASE', 'WITHDRAWAL_POSTED')
        AND quantity_after >= 0
        AND quantity_before >= 0
        AND quantity_change <> 0
        AND quantity_after = quantity_before + quantity_change
    )
    OR
    (
        transaction_type = 'VALUATION_CHANGE'
        AND quantity_after >= 0
        AND quantity_before >= 0
        AND quantity_change = 0
        AND quantity_after = quantity_before
        AND (share_value_before IS NULL OR share_value_before > 0)
        AND share_value_after IS NOT NULL
        AND share_value_after > 0
    )
);

ALTER TABLE dbo.share_transactions WITH CHECK
ADD CONSTRAINT ck_share_transactions_type CHECK (
    transaction_type IN ('INITIAL_ALLOCATION', 'INCREASE', 'DECREASE', 'WITHDRAWAL_POSTED', 'VALUATION_CHANGE')
);

ALTER TABLE dbo.share_transactions CHECK CONSTRAINT ck_share_transactions_quantity;
ALTER TABLE dbo.share_transactions CHECK CONSTRAINT ck_share_transactions_type;

IF EXISTS (
    SELECT 1 FROM sys.check_constraints
    WHERE parent_object_id = OBJECT_ID(N'dbo.share_transactions')
      AND name IN (N'ck_share_transactions_quantity', N'ck_share_transactions_type')
      AND (is_disabled = 1 OR is_not_trusted = 1)
)
    THROW 50010, 'Transaction integrity checks must remain enabled and trusted.', 1;

COMMIT;
