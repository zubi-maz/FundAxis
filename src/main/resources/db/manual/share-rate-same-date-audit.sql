-- MANUAL SQL SERVER MIGRATION: apply before deploying same-date rate corrections.
-- Retains every rate history row; effective date ties resolve by highest identity ID.
IF DB_NAME() <> N'fundaxis'
    THROW 50005, 'Run this migration only in the fundaxis database.', 1;

SET XACT_ABORT ON;
BEGIN TRANSACTION;

IF OBJECT_ID('dbo.share_rate_history', 'U') IS NULL
    THROW 50003, 'Required dbo.share_rate_history table is absent. Verify the FundAxis schema.', 1;

IF OBJECT_ID('dbo.share_transactions', 'U') IS NULL
    THROW 50004, 'Required dbo.share_transactions table is absent. Verify the FundAxis schema.', 1;

IF EXISTS (
    SELECT 1 FROM sys.key_constraints
    WHERE parent_object_id = OBJECT_ID('dbo.share_rate_history')
      AND name = 'uk_share_rate_history_holding_date'
)
    ALTER TABLE dbo.share_rate_history DROP CONSTRAINT uk_share_rate_history_holding_date;

IF EXISTS (
    SELECT 1 FROM sys.key_constraints
    WHERE parent_object_id = OBJECT_ID('dbo.share_rate_history')
      AND name = 'uk_share_rate_history_holding_date'
)
    THROW 50006, 'The same-date rate-history constraint is still present after the drop attempt.', 1;

IF COL_LENGTH('dbo.share_rate_history', 'recorded_by') IS NULL
    ALTER TABLE dbo.share_rate_history ADD recorded_by NVARCHAR(120) NULL;

IF COL_LENGTH('dbo.share_transactions', 'recorded_by') IS NULL
    ALTER TABLE dbo.share_transactions ADD recorded_by NVARCHAR(120) NULL;

IF COL_LENGTH('dbo.share_rate_history', 'recorded_by') IS NULL
   OR COL_LENGTH('dbo.share_transactions', 'recorded_by') IS NULL
    THROW 50007, 'Required rate-history audit columns are missing after migration.', 1;

COMMIT;
