-- MANUAL SQL SERVER MIGRATION. Review and apply to the intended FundAxis database
-- before deploying application code that maps these columns.
-- Adds nullable workflow metadata only. Existing loans, IDs and financial rows
-- are preserved; no existing values are backfilled or rewritten.
IF DB_NAME() <> N'fundaxis'
    THROW 50101, 'Run this migration only in the fundaxis database.', 1;

SET XACT_ABORT ON;
BEGIN TRANSACTION;

IF OBJECT_ID(N'dbo.loans', N'U') IS NULL
    THROW 50102, 'Required dbo.loans table is absent.', 1;
IF OBJECT_ID(N'dbo.employees', N'U') IS NULL
    THROW 50103, 'Required dbo.employees table is absent.', 1;
IF COL_LENGTH(N'dbo.employees', N'id') IS NULL
    THROW 50104, 'Required dbo.employees.id key is absent.', 1;

-- Use dynamic DDL so SQL Server does not bind references to columns before
-- ALTER TABLE has added them to an existing database.
IF COL_LENGTH(N'dbo.loans', N'guarantor_employee_id') IS NULL
    EXEC sys.sp_executesql N'ALTER TABLE dbo.loans ADD guarantor_employee_id BIGINT NULL;';

IF COL_LENGTH(N'dbo.loans', N'legacy_guarantor_name') IS NULL
    EXEC sys.sp_executesql N'ALTER TABLE dbo.loans ADD legacy_guarantor_name NVARCHAR(200) NULL;';

IF COL_LENGTH(N'dbo.loans', N'approved_by') IS NULL
    EXEC sys.sp_executesql N'ALTER TABLE dbo.loans ADD approved_by NVARCHAR(100) NULL;';

IF COL_LENGTH(N'dbo.loans', N'approved_at') IS NULL
    EXEC sys.sp_executesql N'ALTER TABLE dbo.loans ADD approved_at DATETIME2(7) NULL;';

IF COL_LENGTH(N'dbo.loans', N'created_by') IS NULL
    EXEC sys.sp_executesql N'ALTER TABLE dbo.loans ADD created_by NVARCHAR(100) NULL;';

IF COL_LENGTH(N'dbo.loans', N'rejected_by') IS NULL
    EXEC sys.sp_executesql N'ALTER TABLE dbo.loans ADD rejected_by NVARCHAR(100) NULL;';

IF COL_LENGTH(N'dbo.loans', N'rejected_at') IS NULL
    EXEC sys.sp_executesql N'ALTER TABLE dbo.loans ADD rejected_at DATETIME2(7) NULL;';

IF COL_LENGTH(N'dbo.loans', N'approved_tenure_months') IS NULL
    EXEC sys.sp_executesql N'ALTER TABLE dbo.loans ADD approved_tenure_months INT NULL;';

IF COL_LENGTH(N'dbo.loans', N'guarantor_employee_id') IS NULL
   OR COL_LENGTH(N'dbo.loans', N'legacy_guarantor_name') IS NULL
   OR COL_LENGTH(N'dbo.loans', N'approved_by') IS NULL
   OR COL_LENGTH(N'dbo.loans', N'approved_at') IS NULL
   OR COL_LENGTH(N'dbo.loans', N'created_by') IS NULL
   OR COL_LENGTH(N'dbo.loans', N'rejected_by') IS NULL
   OR COL_LENGTH(N'dbo.loans', N'rejected_at') IS NULL
   OR COL_LENGTH(N'dbo.loans', N'approved_tenure_months') IS NULL
    THROW 50105, 'Required nullable Loan workflow columns were not created.', 1;

IF EXISTS (
    SELECT 1 FROM sys.columns c
    WHERE c.object_id = OBJECT_ID(N'dbo.loans')
      AND ((c.name = N'guarantor_employee_id' AND (TYPE_NAME(c.user_type_id) <> N'bigint' OR c.is_nullable <> 1))
        OR (c.name = N'legacy_guarantor_name' AND (TYPE_NAME(c.user_type_id) <> N'nvarchar' OR c.max_length <> 400 OR c.is_nullable <> 1))
        OR (c.name = N'approved_by' AND (TYPE_NAME(c.user_type_id) <> N'nvarchar' OR c.max_length <> 200 OR c.is_nullable <> 1))
        OR (c.name = N'approved_at' AND (TYPE_NAME(c.user_type_id) <> N'datetime2' OR c.is_nullable <> 1))
        OR (c.name IN (N'created_by', N'rejected_by') AND (TYPE_NAME(c.user_type_id) <> N'nvarchar' OR c.max_length <> 200 OR c.is_nullable <> 1))
        OR (c.name IN (N'rejected_at') AND (TYPE_NAME(c.user_type_id) <> N'datetime2' OR c.is_nullable <> 1))
        OR (c.name = N'approved_tenure_months' AND (TYPE_NAME(c.user_type_id) <> N'int' OR c.is_nullable <> 1)))
)
    THROW 50107, 'An existing Loan workflow column has an incompatible type or nullability; no migration changes should be committed.', 1;

IF NOT EXISTS (
    SELECT 1 FROM sys.foreign_keys
    WHERE parent_object_id = OBJECT_ID(N'dbo.loans')
      AND name = N'fk_loan_guarantor_employee'
)
    EXEC sys.sp_executesql N'ALTER TABLE dbo.loans WITH CHECK ADD CONSTRAINT fk_loan_guarantor_employee FOREIGN KEY (guarantor_employee_id) REFERENCES dbo.employees(id);';

IF EXISTS (
    SELECT 1
    FROM sys.foreign_keys fk
    WHERE fk.parent_object_id = OBJECT_ID(N'dbo.loans')
      AND fk.name = N'fk_loan_guarantor_employee'
      AND (fk.referenced_object_id <> OBJECT_ID(N'dbo.employees')
        OR fk.delete_referential_action <> 0
        OR fk.update_referential_action <> 0
        OR (SELECT COUNT(*) FROM sys.foreign_key_columns fkc WHERE fkc.constraint_object_id = fk.object_id) <> 1
        OR NOT EXISTS (
          SELECT 1
          FROM sys.foreign_key_columns fkc
          JOIN sys.columns pc ON pc.object_id = fkc.parent_object_id AND pc.column_id = fkc.parent_column_id
          JOIN sys.columns rc ON rc.object_id = fkc.referenced_object_id AND rc.column_id = fkc.referenced_column_id
          WHERE fkc.constraint_object_id = fk.object_id
            AND fkc.parent_object_id = OBJECT_ID(N'dbo.loans')
            AND pc.name = N'guarantor_employee_id'
            AND fkc.referenced_object_id = OBJECT_ID(N'dbo.employees')
            AND rc.name = N'id'
        ))
)
    THROW 50108, 'The existing guarantor foreign key name is mapped to an unexpected relationship.', 1;

IF NOT EXISTS (
    SELECT 1 FROM sys.check_constraints
    WHERE parent_object_id = OBJECT_ID(N'dbo.loans')
      AND name = N'ck_loans_approved_tenure_positive'
)
    EXEC sys.sp_executesql N'ALTER TABLE dbo.loans WITH CHECK ADD CONSTRAINT ck_loans_approved_tenure_positive CHECK (approved_tenure_months IS NULL OR approved_tenure_months >= 1);';

EXEC sys.sp_executesql N'ALTER TABLE dbo.loans CHECK CONSTRAINT ck_loans_approved_tenure_positive;';

IF EXISTS (
    SELECT 1 FROM sys.check_constraints
    WHERE parent_object_id = OBJECT_ID(N'dbo.loans')
      AND name = N'ck_loans_approved_tenure_positive'
      AND (is_disabled = 1 OR is_not_trusted = 1
        OR REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(LOWER(definition), N'[', N''), N']', N''), N' ', N''), N'(', N''), N')', N'')
            <> N'approved_tenure_monthsisnullorapproved_tenure_months>=1')
)
    THROW 50109, 'Approved tenure validation must remain enabled and trusted.', 1;

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
    WHERE object_id = OBJECT_ID(N'dbo.loans')
      AND name = N'ix_loans_guarantor_employee_id'
)
    EXEC sys.sp_executesql N'CREATE INDEX ix_loans_guarantor_employee_id ON dbo.loans(guarantor_employee_id) WHERE guarantor_employee_id IS NOT NULL;';

IF EXISTS (
    SELECT 1
    FROM sys.indexes i
    WHERE i.object_id = OBJECT_ID(N'dbo.loans')
      AND i.name = N'ix_loans_guarantor_employee_id'
      AND (i.is_unique = 1 OR i.is_disabled = 1 OR i.has_filter = 0
        OR REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(LOWER(i.filter_definition), N'[', N''), N']', N''), N' ', N''), N'(', N''), N')', N'')
            <> N'guarantor_employee_idisnotnull'
        OR (SELECT COUNT(*) FROM sys.index_columns ic WHERE ic.object_id = i.object_id AND ic.index_id = i.index_id AND ic.key_ordinal > 0) <> 1
        OR NOT EXISTS (
            SELECT 1
            FROM sys.index_columns ic
            JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
            WHERE ic.object_id = i.object_id AND ic.index_id = i.index_id
              AND ic.key_ordinal = 1 AND c.name = N'guarantor_employee_id'
        ))
)
    THROW 50110, 'The guarantor index name is mapped to an unexpected index definition.', 1;

EXEC sys.sp_executesql N'ALTER TABLE dbo.loans CHECK CONSTRAINT fk_loan_guarantor_employee;';

IF EXISTS (
    SELECT 1 FROM sys.foreign_keys
    WHERE parent_object_id = OBJECT_ID(N'dbo.loans')
      AND name = N'fk_loan_guarantor_employee'
      AND is_not_trusted = 1
)
    THROW 50106, 'Guarantor foreign key must be trusted.', 1;

COMMIT;

-- Read-only post-migration verification.
SELECT c.name, TYPE_NAME(c.user_type_id) AS sql_type, c.max_length,
       c.precision, c.scale, c.is_nullable
FROM sys.columns c
WHERE c.object_id = OBJECT_ID(N'dbo.loans')
  AND c.name IN (N'guarantor_employee_id', N'legacy_guarantor_name', N'created_by', N'approved_by', N'approved_at', N'approved_tenure_months', N'rejected_by', N'rejected_at')
ORDER BY c.column_id;

SELECT fk.name, fk.is_disabled, fk.is_not_trusted
FROM sys.foreign_keys fk
WHERE fk.parent_object_id = OBJECT_ID(N'dbo.loans')
  AND fk.name = N'fk_loan_guarantor_employee';

SELECT cc.name, cc.definition, cc.is_disabled, cc.is_not_trusted
FROM sys.check_constraints cc
WHERE cc.parent_object_id = OBJECT_ID(N'dbo.loans')
  AND cc.name = N'ck_loans_approved_tenure_positive';

SELECT i.name, i.is_unique, i.has_filter, i.filter_definition
FROM sys.indexes i
WHERE i.object_id = OBJECT_ID(N'dbo.loans')
  AND i.name = N'ix_loans_guarantor_employee_id';
