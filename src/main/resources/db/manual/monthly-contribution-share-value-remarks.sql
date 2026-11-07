/*
   FundAxis: optional contribution-level Share Value and administrator Remarks.
   Additive and rerunnable. Existing contribution rows receive NULL values;
   no contribution, payment, credit, or history record is rewritten.
   Review and run manually in SSMS against fundaxis before deploying the entity.
*/
SET NOCOUNT ON;
SET XACT_ABORT ON;

BEGIN TRY
    BEGIN TRANSACTION;

    IF DB_NAME() <> N'fundaxis'
        THROW 51100, 'Wrong database. Select fundaxis before running this migration.', 1;

    IF OBJECT_ID(N'dbo.contributions', N'U') IS NULL
        THROW 51101, 'Required table dbo.contributions does not exist.', 1;

    IF COL_LENGTH(N'dbo.contributions', N'share_value') IS NULL
        EXEC(N'ALTER TABLE dbo.contributions ADD share_value decimal(20,2) NULL;');

    IF COL_LENGTH(N'dbo.contributions', N'remarks') IS NULL
        EXEC(N'ALTER TABLE dbo.contributions ADD remarks nvarchar(500) NULL;');

    IF NOT EXISTS (
        SELECT 1 FROM sys.columns c
        JOIN sys.types t ON t.user_type_id = c.user_type_id
        WHERE c.object_id = OBJECT_ID(N'dbo.contributions')
          AND c.name = N'share_value'
          AND t.name IN (N'decimal', N'numeric')
          AND c.precision = 20 AND c.scale = 2 AND c.is_nullable = 1
    )
        THROW 51102, 'dbo.contributions.share_value has an incompatible SQL type, precision, scale, or nullability.', 1;

    IF NOT EXISTS (
        SELECT 1 FROM sys.columns c
        JOIN sys.types t ON t.user_type_id = c.user_type_id
        WHERE c.object_id = OBJECT_ID(N'dbo.contributions')
          AND c.name = N'remarks'
          AND t.name = N'nvarchar' AND c.max_length = 1000 AND c.is_nullable = 1
    )
        THROW 51103, 'dbo.contributions.remarks has an incompatible SQL type, length, or nullability.', 1;

    IF NOT EXISTS (
        SELECT 1 FROM sys.check_constraints
        WHERE parent_object_id = OBJECT_ID(N'dbo.contributions')
          AND name = N'ck_contributions_share_value_nonnegative'
    )
        EXEC(N'ALTER TABLE dbo.contributions WITH CHECK ADD CONSTRAINT ck_contributions_share_value_nonnegative
              CHECK (share_value IS NULL OR share_value >= 0);');
    ELSE IF EXISTS (
        SELECT 1 FROM sys.check_constraints
        WHERE parent_object_id = OBJECT_ID(N'dbo.contributions')
          AND name = N'ck_contributions_share_value_nonnegative'
          AND (is_disabled = 1 OR is_not_trusted = 1
               OR definition NOT LIKE N'%share_value%'
               OR definition NOT LIKE N'%>=%0%')
    )
        THROW 51104, 'Existing Share Value CHECK constraint is incompatible or untrusted; review it manually.', 1;

    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;
