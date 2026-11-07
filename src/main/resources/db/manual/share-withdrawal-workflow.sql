-- MANUAL SQL SERVER SETUP for the existing ShareWithdrawal entity.
-- Review and execute manually; this file is never auto-executed by FundAxis.
-- This script does not drop/recreate an existing table or change feature flags.
-- It preserves existing rows and primary keys. The only possible row update is
-- an explicitly opted-in effective_date backfill from request_date (see below).

SET NOCOUNT ON;
SET XACT_ABORT ON;

-- Refuse to run against a database other than the configured FundAxis database.
IF DB_NAME() <> N'fundaxis'
BEGIN
    THROW 50001, 'Wrong database. Connect to fundaxis before running this setup.', 1;
END;

-- Fail closed when legacy effective dates must be inferred. After reviewing
-- legacy requests, an administrator may change this to 1 to accept request_date
-- as their effective date. No other existing business field is backfilled.
DECLARE @AllowRequestDateEffectiveDateBackfill bit = 0;

BEGIN TRY
    BEGIN TRANSACTION;

    IF OBJECT_ID(N'dbo.employees', N'U') IS NULL
       OR OBJECT_ID(N'dbo.share_holdings', N'U') IS NULL
       OR OBJECT_ID(N'dbo.share_transactions', N'U') IS NULL
    BEGIN
        THROW 50002, 'Required dbo employee/share tables are missing. No setup was applied.', 1;
    END;

    IF OBJECT_ID(N'dbo.share_withdrawals', N'U') IS NULL
    BEGIN
        CREATE TABLE dbo.share_withdrawals (
            id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_share_withdrawals PRIMARY KEY,
            employee_id BIGINT NOT NULL,
            share_holding_id BIGINT NOT NULL,
            status VARCHAR(20) NOT NULL CONSTRAINT df_share_withdrawals_status DEFAULT ('PENDING'),
            requested_quantity INT NOT NULL,
            request_date DATE NOT NULL,
            effective_date DATE NOT NULL,
            reason VARCHAR(500) NULL,
            gross_amount DECIMAL(20,2) NULL,
            deduction_amount DECIMAL(20,2) NULL,
            net_amount DECIMAL(20,2) NULL,
            approved_by VARCHAR(100) NULL,
            approved_at DATETIME2 NULL,
            rejection_reason VARCHAR(500) NULL,
            shares_posted_at DATETIME2 NULL,
            disbursed_at DATETIME2 NULL,
            payment_reference VARCHAR(150) NULL,
            requested_by VARCHAR(100) NULL,
            request_token VARCHAR(36) NULL,
            share_transaction_id BIGINT NULL,
            shares_posted_by VARCHAR(100) NULL,
            disbursed_by VARCHAR(100) NULL,
            resolved_by VARCHAR(100) NULL,
            resolved_at DATETIME2 NULL,
            cancellation_reason VARCHAR(500) NULL,
            created_at DATETIME2 NOT NULL,
            updated_at DATETIME2 NOT NULL,
            version BIGINT NOT NULL CONSTRAINT df_share_withdrawals_version DEFAULT (0)
        );
    END;

    DECLARE @table_id int = OBJECT_ID(N'dbo.share_withdrawals', N'U');

    -- Existing tables must have the entity's original columns and identity PK.
    -- Do not try to reconstruct or alter these base financial fields here.
    IF EXISTS (
        SELECT required.column_name
        FROM (VALUES
            (N'id'), (N'employee_id'), (N'share_holding_id'), (N'requested_quantity'),
            (N'request_date'), (N'status'), (N'version'), (N'approved_at'),
            (N'approved_by'), (N'shares_posted_at'), (N'disbursed_at'),
            (N'gross_amount'), (N'deduction_amount'), (N'net_amount'), (N'reason'),
            (N'rejection_reason'), (N'payment_reference'), (N'created_at'), (N'updated_at')
        ) required(column_name)
        WHERE COL_LENGTH(N'dbo.share_withdrawals', required.column_name) IS NULL
    )
    BEGIN
        THROW 50003, 'Existing share_withdrawals is missing a base entity column. No setup was applied.', 1;
    END;

    IF NOT EXISTS (SELECT 1 FROM sys.identity_columns WHERE object_id=@table_id AND name=N'id')
       OR NOT EXISTS (SELECT 1 FROM sys.key_constraints WHERE parent_object_id=@table_id AND type=N'PK')
    BEGIN
        THROW 50004, 'Existing withdrawal id must remain an IDENTITY primary key. Manual schema review is required.', 1;
    END;

    -- Reject incompatible base nullability rather than silently changing it.
    IF EXISTS (
        SELECT 1 FROM sys.columns
        WHERE object_id=@table_id AND name IN (N'employee_id',N'share_holding_id',N'requested_quantity',N'request_date',N'status',N'version',N'created_at',N'updated_at')
          AND is_nullable=1
    )
    BEGIN
        THROW 50005, 'A required base column is nullable. Review existing data/schema before migration.', 1;
    END;

    -- Additive audit/workflow columns; existing values and financial columns remain untouched.
    IF COL_LENGTH(N'dbo.share_withdrawals', N'requested_by') IS NULL
        ALTER TABLE dbo.share_withdrawals ADD requested_by VARCHAR(100) NULL;
    IF COL_LENGTH(N'dbo.share_withdrawals', N'request_token') IS NULL
        ALTER TABLE dbo.share_withdrawals ADD request_token VARCHAR(36) NULL;
    IF COL_LENGTH(N'dbo.share_withdrawals', N'share_transaction_id') IS NULL
        ALTER TABLE dbo.share_withdrawals ADD share_transaction_id BIGINT NULL;
    IF COL_LENGTH(N'dbo.share_withdrawals', N'shares_posted_by') IS NULL
        ALTER TABLE dbo.share_withdrawals ADD shares_posted_by VARCHAR(100) NULL;
    IF COL_LENGTH(N'dbo.share_withdrawals', N'disbursed_by') IS NULL
        ALTER TABLE dbo.share_withdrawals ADD disbursed_by VARCHAR(100) NULL;
    IF COL_LENGTH(N'dbo.share_withdrawals', N'resolved_by') IS NULL
        ALTER TABLE dbo.share_withdrawals ADD resolved_by VARCHAR(100) NULL;
    IF COL_LENGTH(N'dbo.share_withdrawals', N'resolved_at') IS NULL
        ALTER TABLE dbo.share_withdrawals ADD resolved_at DATETIME2 NULL;
    IF COL_LENGTH(N'dbo.share_withdrawals', N'cancellation_reason') IS NULL
        ALTER TABLE dbo.share_withdrawals ADD cancellation_reason VARCHAR(500) NULL;

    -- The entity requires a non-null DATE. Legacy request_date is only a proxy,
    -- so require explicit opt-in before filling any existing rows with it.
    IF COL_LENGTH(N'dbo.share_withdrawals', N'effective_date') IS NULL
        ALTER TABLE dbo.share_withdrawals ADD effective_date DATE NULL;

    -- These references are dynamic deliberately: the column may have been
    -- added earlier in this same batch, after SQL Server's outer batch compile.
    DECLARE @has_null_effective_date bit = 0;
    DECLARE @has_null_request_date bit = 0;
    EXEC sys.sp_executesql
        N'SELECT @has_effective=CASE WHEN EXISTS(SELECT 1 FROM dbo.share_withdrawals WHERE effective_date IS NULL) THEN 1 ELSE 0 END;
          SELECT @has_request=CASE WHEN EXISTS(SELECT 1 FROM dbo.share_withdrawals WHERE effective_date IS NULL AND request_date IS NULL) THEN 1 ELSE 0 END;',
        N'@has_effective bit OUTPUT, @has_request bit OUTPUT',
        @has_effective=@has_null_effective_date OUTPUT,
        @has_request=@has_null_request_date OUTPUT;

    IF @has_null_request_date = 1
    BEGIN
        THROW 50006, 'Cannot backfill effective_date because one or more request_date values are NULL.', 1;
    END;

    IF @has_null_effective_date = 1
    BEGIN
        IF @AllowRequestDateEffectiveDateBackfill <> 1
        BEGIN
            THROW 50007, 'Rows need effective_date values. Review legacy requests, then explicitly set @AllowRequestDateEffectiveDateBackfill=1 if request_date is an approved proxy.', 1;
        END;

        EXEC sys.sp_executesql
            N'UPDATE dbo.share_withdrawals SET effective_date=request_date WHERE effective_date IS NULL;';
    END;

    IF EXISTS (SELECT 1 FROM sys.columns WHERE object_id=@table_id AND name=N'effective_date' AND system_type_id<> CONVERT(tinyint, TYPE_ID(N'date')))
    BEGIN
        THROW 50008, 'effective_date exists with an incompatible SQL type. Review manually; the script will not convert it.', 1;
    END;

    IF EXISTS (SELECT 1 FROM sys.columns WHERE object_id=@table_id AND name=N'effective_date' AND is_nullable=1)
        EXEC sys.sp_executesql N'ALTER TABLE dbo.share_withdrawals ALTER COLUMN effective_date DATE NOT NULL;';

    -- Fail rather than silently accepting invalid legacy state before adding constraints.
    IF EXISTS (SELECT 1 FROM dbo.share_withdrawals WHERE requested_quantity IS NULL OR requested_quantity<=0)
    BEGIN
        THROW 50009, 'Existing requested_quantity values violate the positive-quantity rule. Review records before migration.', 1;
    END;
    IF EXISTS (SELECT 1 FROM dbo.share_withdrawals WHERE status IS NULL OR status NOT IN ('PENDING','APPROVED','REJECTED','CANCELLED','DISBURSED'))
    BEGIN
        THROW 50010, 'Existing status values are not supported by ShareWithdrawal. Review records before migration.', 1;
    END;

    IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE parent_object_id=@table_id AND name=N'ck_share_withdrawals_quantity')
        ALTER TABLE dbo.share_withdrawals WITH CHECK ADD CONSTRAINT ck_share_withdrawals_quantity CHECK (requested_quantity>0);
    ALTER TABLE dbo.share_withdrawals WITH CHECK CHECK CONSTRAINT ck_share_withdrawals_quantity;

    IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE parent_object_id=@table_id AND name=N'ck_share_withdrawals_status')
        ALTER TABLE dbo.share_withdrawals WITH CHECK ADD CONSTRAINT ck_share_withdrawals_status
            CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELLED','DISBURSED'));
    ALTER TABLE dbo.share_withdrawals WITH CHECK CHECK CONSTRAINT ck_share_withdrawals_status;

    -- Validate orphan rows before establishing the three entity relationships.
    IF EXISTS (SELECT 1 FROM dbo.share_withdrawals w LEFT JOIN dbo.employees e ON e.id=w.employee_id WHERE e.id IS NULL)
    BEGIN
        THROW 50011, 'Orphan employee_id values exist in share_withdrawals. Review before adding the foreign key.', 1;
    END;
    IF EXISTS (SELECT 1 FROM dbo.share_withdrawals w LEFT JOIN dbo.share_holdings h ON h.id=w.share_holding_id WHERE h.id IS NULL)
    BEGIN
        THROW 50012, 'Orphan share_holding_id values exist in share_withdrawals. Review before adding the foreign key.', 1;
    END;
    EXEC sys.sp_executesql N'
        IF EXISTS (
            SELECT 1 FROM dbo.share_withdrawals w
            LEFT JOIN dbo.share_transactions st ON st.id=w.share_transaction_id
            WHERE w.share_transaction_id IS NOT NULL AND st.id IS NULL
        )
        BEGIN
            THROW 50013, ''Orphan share_transaction_id values exist in share_withdrawals. Review before adding the foreign key.'', 1;
        END;';

    IF NOT EXISTS (
        SELECT 1 FROM sys.foreign_key_columns fkc
        WHERE fkc.parent_object_id=@table_id
          AND fkc.parent_column_id=(SELECT column_id FROM sys.columns WHERE object_id=@table_id AND name=N'employee_id')
          AND fkc.referenced_object_id=OBJECT_ID(N'dbo.employees')
          AND fkc.referenced_column_id=(SELECT column_id FROM sys.columns WHERE object_id=OBJECT_ID(N'dbo.employees') AND name=N'id')
    )
        ALTER TABLE dbo.share_withdrawals WITH CHECK ADD CONSTRAINT fk_share_withdrawals_employee FOREIGN KEY(employee_id) REFERENCES dbo.employees(id);
    IF NOT EXISTS (
        SELECT 1 FROM sys.foreign_key_columns fkc
        WHERE fkc.parent_object_id=@table_id
          AND fkc.parent_column_id=(SELECT column_id FROM sys.columns WHERE object_id=@table_id AND name=N'share_holding_id')
          AND fkc.referenced_object_id=OBJECT_ID(N'dbo.share_holdings')
          AND fkc.referenced_column_id=(SELECT column_id FROM sys.columns WHERE object_id=OBJECT_ID(N'dbo.share_holdings') AND name=N'id')
    )
        ALTER TABLE dbo.share_withdrawals WITH CHECK ADD CONSTRAINT fk_share_withdrawals_holding FOREIGN KEY(share_holding_id) REFERENCES dbo.share_holdings(id);
    IF NOT EXISTS (
        SELECT 1 FROM sys.foreign_key_columns fkc
        WHERE fkc.parent_object_id=@table_id
          AND fkc.parent_column_id=(SELECT column_id FROM sys.columns WHERE object_id=@table_id AND name=N'share_transaction_id')
          AND fkc.referenced_object_id=OBJECT_ID(N'dbo.share_transactions')
          AND fkc.referenced_column_id=(SELECT column_id FROM sys.columns WHERE object_id=OBJECT_ID(N'dbo.share_transactions') AND name=N'id')
    )
        EXEC sys.sp_executesql N'ALTER TABLE dbo.share_withdrawals WITH CHECK ADD CONSTRAINT fk_withdrawal_share_transaction FOREIGN KEY(share_transaction_id) REFERENCES dbo.share_transactions(id);';

    -- Unique filtered indexes allow any number of legacy NULLs while preventing
    -- duplicate idempotency tokens and linking a share transaction twice.
    EXEC sys.sp_executesql N'
        IF EXISTS (SELECT request_token FROM dbo.share_withdrawals WHERE request_token IS NOT NULL GROUP BY request_token HAVING COUNT_BIG(*)>1)
        BEGIN
            THROW 50014, ''Duplicate non-NULL request_token values exist. Review before creating the unique index.'', 1;
        END;
        IF EXISTS (SELECT share_transaction_id FROM dbo.share_withdrawals WHERE share_transaction_id IS NOT NULL GROUP BY share_transaction_id HAVING COUNT_BIG(*)>1)
        BEGIN
            THROW 50015, ''Duplicate non-NULL share_transaction_id values exist. Review before creating the unique index.'', 1;
        END;';

    IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id=@table_id AND name=N'ux_withdrawal_request_token')
        EXEC sys.sp_executesql N'CREATE UNIQUE INDEX ux_withdrawal_request_token ON dbo.share_withdrawals(request_token) WHERE request_token IS NOT NULL;';
    IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id=@table_id AND name=N'ux_withdrawal_transaction')
        EXEC sys.sp_executesql N'CREATE UNIQUE INDEX ux_withdrawal_transaction ON dbo.share_withdrawals(share_transaction_id) WHERE share_transaction_id IS NOT NULL;';
    IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id=@table_id AND name=N'ix_withdrawal_reservations')
        CREATE INDEX ix_withdrawal_reservations ON dbo.share_withdrawals(share_holding_id,status,shares_posted_at) INCLUDE(requested_quantity);
    IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id=@table_id AND name=N'idx_share_withdrawals_employee')
        CREATE INDEX idx_share_withdrawals_employee ON dbo.share_withdrawals(employee_id);
    IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id=@table_id AND name=N'idx_share_withdrawals_status')
        CREATE INDEX idx_share_withdrawals_status ON dbo.share_withdrawals(status);

    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF XACT_STATE()<>0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;

-- Verification only: run after the manual execution succeeds.
-- See the accompanying report for full read-only verification queries.
