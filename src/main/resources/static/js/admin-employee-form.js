/* ==========================================================================
 * FundAxis - Add Employees (multi-row form)
 * File: admin-employee-form.js
 *
 * WHAT THIS FILE DOES
 * -------------------
 * 1. ADD / REMOVE ROWS
 *    - "Add Another Employee" inserts a new row from the hidden
 *      <template id="employeeRowTemplate">.
 *
 * 2. CLEAR ROW
 *    - Each row has a "Clear" button that empties that row's fields
 *      so the admin can retype them.
 *    - Does NOT remove the row (that's what Remove does).
 *
 * 3. EMPLOYEE ID
 *    - Admin types only the number (e.g. "001").
 *    - The "EMP-" prefix is fixed text beside the box.
 *    - On submit the full ID ("EMP-001") is written into the hidden
 *      field employees[i].employeeId.
 *    - A pasted "EMP-001" is stripped down to "001" so the ID can
 *      never become "EMP-EMP-001".
 *
 * 4. VALIDATION
 *    - Drops untouched blank rows before checking "required".
 *    - Blocks duplicate IDs inside one submission.
 *    - Re-indexes employees[i] so Spring binds a gap-free list.
 *    - Prevents double submit.
 *
 * NOTE: The backend (EmployeeController) remains authoritative for
 *       validation and uniqueness. This file is only for user convenience.
 * ========================================================================== */

(function () {
    'use strict';

    /* ------------------------------ Constants ------------------------------ */
    var MAX_ROWS   = 50;                         // Hard limit per submission (matches backend)
    var ID_PREFIX  = 'EMP-';                      // Employee IDs are always stored as EMP-<number>
    var ID_NUMBER  = /^[0-9]{1,10}$/;             // Visible box: digits only, 1–10 chars
    var FIELDS     = ['employeeId', 'fullName', 'designation', 'fundJoinDate'];

    /* ---------------------------- DOM references --------------------------- */
    var form       = document.getElementById('employeeForm');
    if (!form) { return; }                       // Not on this page → do nothing

    var rowsBox    = document.getElementById('employeeRows');           // Container for all rows
    var template   = document.getElementById('employeeRowTemplate');    // Hidden <template> for new rows
    var addButton  = document.getElementById('addRowButton');
    var saveButton = document.getElementById('saveEmployeesButton');
    var saveText   = document.getElementById('saveEmployeesButtonText'); // Button label ("Save N Employees")
    var rowCount   = document.getElementById('rowCount');               // Header badge ("3 rows")

    /* ---------------------------- State variables -------------------------- */
    // Unique index for names/ids of JS-added rows (employees[__INDEX__]).
    // Starts after any server-rendered rows so generated ids never collide.
    var nextIndex  = rowsBox.querySelectorAll('.fa-employee-row').length;
    var submitting = false;                      // true while the form is being posted (blocks double-submit)

    /* ------------------------------ Helpers -------------------------------- */

    /** Return all current rows as a plain array. */
    function rows() {
        return Array.prototype.slice.call(rowsBox.querySelectorAll('.fa-employee-row'));
    }

    /** Find one field inside a row by its data-field attribute. */
    function field(row, key) {
        return row.querySelector('[data-field="' + key + '"]');
    }

    /**
     * Normalize an Employee ID value:
     *   "  EMP-005 " → "005"
     *   "emp005"     → "005"
     *   "005"        → "005"
     * Prevents a pasted full ID from becoming "EMP-EMP-001".
     */
    function stripPrefix(value) {
        return String(value).trim().replace(/^EMP-?/i, '').trim();
    }

    /**
     * A row is blank when every field is empty/whitespace.
     * Blank rows are ignored by the counter and dropped on submit.
     */
    function isBlank(row) {
        return FIELDS.every(function (key) {
            var el = field(row, key);
            return !el || !String(el.value).trim();
        });
    }

    /**
     * Empty every field of one row and reset its validation state.
     * Called by the per-row "Clear" button.
     * Also clears the hidden full-ID field so a stale value is never posted.
     */
    function clearRow(row) {
        FIELDS.forEach(function (key) {
            var el = field(row, key);
            if (el) {
                el.value = '';
                el.setCustomValidity('');        // remove any "duplicate" / "digits only" flag
            }
        });

        var idFull = field(row, 'employeeIdFull'); // hidden posted field (EMP-xxx)
        if (idFull) {
            idFull.value = '';
        }

        refresh();                               // update row numbers and Save button label
    }

    /**
     * Re-number rows (SL badge), refresh the header badge ("N rows"),
     * enable/disable Add and Remove buttons, and update the Save button
     * label to "Save Employee" / "Save N Employees".
     * Call after any row change.
     */
    function refresh() {
        var list   = rows();
        var filled = list.filter(function (row) { return !isBlank(row); }).length;

        list.forEach(function (row, i) {
            row.querySelector('.fa-row-number').textContent = String(i + 1); // SL = position + 1

            var remove = row.querySelector('.fa-row-remove');
            remove.setAttribute('aria-label', 'Remove row ' + (i + 1));
            remove.disabled = list.length === 1; // never allow removing the last remaining row
        });

        rowCount.textContent = list.length + (list.length === 1 ? ' row' : ' rows');
        addButton.disabled   = list.length >= MAX_ROWS;

        var count = Math.max(filled, 1);         // label never shows "Save 0 Employees"
        saveText.textContent = count === 1 ? 'Save Employee' : 'Save ' + count + ' Employees';
    }

    /**
     * Clone the hidden template, replace __INDEX__ with a unique number,
     * append the new row, and optionally focus its Employee ID box.
     */
    function addRow(focus) {
        if (rows().length >= MAX_ROWS) {
            return null;
        }

        // innerHTML trick: split/join replaces EVERY occurrence of __INDEX__ in one pass
        var html = template.innerHTML.split('__INDEX__').join(String(nextIndex++));
        rowsBox.insertAdjacentHTML('beforeend', html);

        var row = rowsBox.lastElementChild;
        refresh();

        if (focus) {
            field(row, 'employeeId').focus();
        }

        return row;
    }

    /**
     * Copy each browser validation message into the matching .invalid-feedback
     * div so the admin sees *why* a field failed (required, duplicate, …).
     */
    function syncFeedback() {
        Array.prototype.forEach.call(form.querySelectorAll('input, select'), function (control) {
            var feedback = control.parentElement.querySelector('.invalid-feedback');
            if (feedback && control.validationMessage) {
                feedback.textContent = control.validationMessage;
            }
        });
    }

    /* ------------------------------- Events -------------------------------- */

    /** "Add Another Employee" → new blank row. */
    addButton.addEventListener('click', function () {
        addRow(true);
    });

    /**
     * One delegated click handler for BOTH per-row buttons (Clear and Remove).
     * Delegation works for rows added later; no per-button wiring needed.
     */
    rowsBox.addEventListener('click', function (event) {
        // --- CLEAR: empty this row's fields, keep the row, refocus ID box ---
        var clearBtn = event.target.closest('.fa-row-clear');
        if (clearBtn) {
            var row = clearBtn.closest('.fa-employee-row');
            clearRow(row);

            var first = field(row, 'employeeId');
            if (first) {
                first.focus();
            }
            return; // a row can't be cleared and removed at once
        }

        // --- REMOVE: delete the row (never the last one) ---
        var button = event.target.closest('.fa-row-remove');
        if (!button || button.disabled) {
            return;
        }

        button.closest('.fa-employee-row').remove();

        if (rows().length === 0) {
            addRow(false);                       // always keep at least one row
        }

        refresh();
        addButton.focus();
    });

    /**
     * While typing: clear any custom "duplicate"/"digits only" error flag
     * on the Employee ID box, and keep the Save label in sync.
     */
    rowsBox.addEventListener('input', function (event) {
        if (event.target.matches('[data-field="employeeId"]')) {
            event.target.setCustomValidity('');
        }
        refresh();
    });

    /**
     * When the admin leaves the Employee ID box: tidy a pasted
     * "EMP-005" down to "005" so only the number stays visible.
     */
    rowsBox.addEventListener('change', function (event) {
        if (event.target.matches('[data-field="employeeId"]')) {
            event.target.value = stripPrefix(event.target.value);
        }
    });

    /* ------------------------------- Submit -------------------------------- */

    form.addEventListener('submit', function (event) {
        if (submitting) {
            event.preventDefault();
            return;
        }

        // 1. Drop untouched blank rows first (keep at least one)
        //    so their empty "required" fields don't fail validation.
        rows().forEach(function (row) {
            if (rows().length > 1 && isBlank(row)) {
                row.remove();
            }
        });

        // 2. Build the full Employee ID (EMP- + number) into the hidden field,
        //    trim the name, and flag bad or duplicate IDs inside this submission.
        var seen = {}; // fullId (uppercase) → true

        rows().forEach(function (row) {
            var idInput = field(row, 'employeeId');     // visible box: number only, NEVER posted (no name)
            var idFull  = field(row, 'employeeIdFull'); // hidden field: employees[i].employeeId, posted
            var name    = field(row, 'fullName');

            name.value = name.value.trim();

            var number = stripPrefix(idInput.value);
            idInput.value = number;                     // show the tidied number back to the admin
            idInput.setCustomValidity('');
            idFull.value = '';                          // reset; only set again below if the number is valid

            if (!number) {
                return;                                 // empty box → let the "required" rule report it
            }

            if (!ID_NUMBER.test(number)) {
                idInput.setCustomValidity('Enter digits only, e.g. 001.');
                return;
            }

            var fullId = ID_PREFIX + number;
            idFull.value = fullId;                      // this is what Spring actually binds

            var key = fullId.toUpperCase();             // duplicates are case-insensitive (EMP-1 = emp-1)
            if (seen[key]) {
                idInput.setCustomValidity('Duplicate Employee ID in this form.');
            } else {
                seen[key] = true;
            }
        });

        refresh();

        // 3. Run built-in constraint validation (required, maxlength, …)
        //    plus the custom flags above. Stop and highlight if anything is invalid.
        if (!form.checkValidity()) {
            event.preventDefault();
            form.classList.add('was-validated');
            syncFeedback();

            var firstInvalid = form.querySelector(':invalid');
            if (firstInvalid) {
                firstInvalid.focus();
                firstInvalid.scrollIntoView({ block: 'center' });
            }
            return;
        }

        // 4. Re-index names to employees[0..n-1] with no gaps.
        //    (Needed because rows may have been removed at step 1.)
        rows().forEach(function (row, i) {
            Array.prototype.forEach.call(row.querySelectorAll('[name]'), function (el) {
                el.name = el.name.replace(/employees\[\d+\]/, 'employees[' + i + ']');
            });
        });

        // 5. Lock the button so a slow request can't be submitted twice.
        submitting = true;
        saveButton.setAttribute('aria-busy', 'true');
        saveButton.classList.add('disabled');
        saveText.textContent = 'Saving…';
    });

    /**
     * Back/forward cache (bfcache): if the user returns to this page after
     * submitting, unlock the Save button and refresh the labels.
     */
    window.addEventListener('pageshow', function (event) {
        if (event.persisted) {
            submitting = false;
            saveButton.removeAttribute('aria-busy');
            saveButton.classList.remove('disabled');
            refresh();
        }
    });

    /* -------------------------------- Init --------------------------------- */

    // Fresh page with no server-rendered rows → show one empty row
    if (rows().length === 0) {
        addRow(false);
    }

    refresh();

    var firstId = field(rows()[0], 'employeeId');
    if (firstId && !firstId.value) {
        firstId.focus();
    }
})();