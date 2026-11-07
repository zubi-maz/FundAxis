/*
   FundAxis: optional intended collection method on a contribution due.
   This is a reference only. It is not a receipt and does not settle a due.

   Safe for rerun; adds one nullable column and a value constraint. Existing
   contribution rows remain NULL and all payment/history rows are untouched.
   Run manually in SSMS after reviewing the live schema.
*/
SET NOCOUNT ON;
SET XACT_ABORT ON;

BEGIN TRY
    BEGIN TRANSACTION;

    IF DB_NAME() <> N'fundaxis'
        THROW 51000, 'Wrong database. Select fundaxis before running this migration.', 1;

    IF OBJECT_ID(N'dbo.contributions', N'U') IS NULL
        THROW 51001, 'Required table dbo.contributions does not exist.', 1;

    IF COL_LENGTH(N'dbo.contributions', N'intended_collection_method') IS NULL
        EXEC(N'ALTER TABLE dbo.contributions ADD intended_collection_method varchar(25) NULL;');

    IF NOT EXISTS (
        SELECT 1 FROM sys.columns c
        JOIN sys.types t ON t.user_type_id = c.user_type_id
        WHERE c.object_id = OBJECT_ID(N'dbo.contributions')
          AND c.name = N'intended_collection_method'
          AND t.name IN (N'varchar', N'nvarchar')
          AND c.max_length >= CASE WHEN t.name = N'nvarchar' THEN 50 ELSE 25 END
          AND c.is_nullable = 1
    )
        THROW 51005, 'dbo.contributions.intended_collection_method has an incompatible SQL type, length, or nullability.', 1;

    IF NOT EXISTS (
        SELECT 1 FROM sys.check_constraints
        WHERE parent_object_id = OBJECT_ID(N'dbo.contributions')
          AND name = N'ck_contributions_intended_collection_method'
    )
        EXEC(N'ALTER TABLE dbo.contributions WITH CHECK ADD CONSTRAINT ck_contributions_intended_collection_method
              CHECK (intended_collection_method IS NULL OR intended_collection_method IN (''HO_HAND'', ''BANK_FT'', ''ITD_HAND''));');
    ELSE IF EXISTS (
        SELECT 1 FROM sys.check_constraints
        WHERE parent_object_id = OBJECT_ID(N'dbo.contributions')
          AND name = N'ck_contributions_intended_collection_method'
          AND (is_disabled = 1 OR is_not_trusted = 1
               OR definition NOT LIKE N'%HO_HAND%'
               OR definition NOT LIKE N'%BANK_FT%'
               OR definition NOT LIKE N'%ITD_HAND%')
    )
        THROW 51002, 'Existing intended collection method constraint is incompatible or untrusted; review it manually.', 1;

    /* Stop safely if a payment-method check would reject the new enum values.
       Do not rewrite an existing financial constraint without DBA review. */
    IF EXISTS (
        SELECT 1 FROM sys.check_constraints
        WHERE parent_object_id = OBJECT_ID(N'dbo.contribution_payments')
          AND definition LIKE N'%payment_method%'
          AND (definition NOT LIKE N'%HO_HAND%'
               OR definition NOT LIKE N'%BANK_FT%'
               OR definition NOT LIKE N'%ITD_HAND%')
    )
        THROW 51003, 'A contribution payment method CHECK constraint must be reviewed and safely extended before deploying the new payment methods.', 1;

    IF COL_LENGTH(N'dbo.contributions', N'intended_collection_method') IS NULL
        THROW 51004, 'Column creation verification failed.', 1;

    IF NOT EXISTS (
        SELECT 1 FROM sys.columns c
        JOIN sys.types t ON t.user_type_id = c.user_type_id
        WHERE c.object_id = OBJECT_ID(N'dbo.contribution_payments')
          AND c.name = N'payment_method'
          AND t.name IN (N'varchar', N'nvarchar')
          AND c.max_length >= CASE WHEN t.name = N'nvarchar' THEN 50 ELSE 25 END
          AND c.is_nullable = 0
    )
        THROW 51006, 'dbo.contribution_payments.payment_method is missing or too short for the supported method values.', 1;

    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;
