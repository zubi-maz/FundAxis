/* ==========================================================================
 * FundAxis - Admin bundle (merged)
 * = dashboard.js (sidebar behavior, all admin pages)
 * + admin-contribution-form.js (EDIT mode month sync + submit lock)
 * + bulk contribution rows (ADD mode multi-employee form)
 *
 * The file is loaded on EVERY admin page. Each feature lives in its own
 * IIFE and self-guards. Safe to load everywhere.
 * ========================================================================== */


/* ==========================================================================
 * FEATURE 1 - SIDEBAR
 * ========================================================================== */
(function () {
    'use strict';

    var sidebar = document.getElementById('faSidebar');
    var overlay = document.getElementById('faOverlay');
    var toggleBtn = document.getElementById('faMenuToggle');
    var closeBtn = document.getElementById('faSidebarClose');
    var mobileQuery = window.matchMedia('(max-width: 991.98px)');

    if (!sidebar || !toggleBtn) {
        return;
    }

    function isMobile() {
        return mobileQuery.matches;
    }

    function openSidebar() {
        sidebar.classList.add('is-open');

        if (overlay) {
            overlay.hidden = false;
        }

        toggleBtn.setAttribute('aria-expanded', 'true');
        document.body.classList.add('fa-no-scroll');

        var firstLink = sidebar.querySelector('a, button');

        if (firstLink) {
            firstLink.focus();
        }
    }

    function closeSidebar(returnFocus) {
        sidebar.classList.remove('is-open');

        if (overlay) {
            overlay.hidden = true;
        }

        toggleBtn.setAttribute('aria-expanded', 'false');
        document.body.classList.remove('fa-no-scroll');

        if (returnFocus) {
            toggleBtn.focus();
        }
    }

    toggleBtn.addEventListener('click', function () {
        if (sidebar.classList.contains('is-open')) {
            closeSidebar(true);
        } else {
            openSidebar();
        }
    });

    if (closeBtn) {
        closeBtn.addEventListener('click', function () {
            closeSidebar(true);
        });
    }

    if (overlay) {
        overlay.addEventListener('click', function () {
            closeSidebar(true);
        });
    }

    // Close with Escape.
    document.addEventListener('keydown', function (event) {
        if (
            event.key === 'Escape' &&
            sidebar.classList.contains('is-open')
        ) {
            closeSidebar(true);
        }
    });

    // Close sidebar after navigating on mobile.
    sidebar.querySelectorAll('a.fa-nav-link').forEach(function (link) {
        link.addEventListener('click', function () {
            if (isMobile()) {
                closeSidebar(false);
            }
        });
    });

    // Reset sidebar state when switching to desktop width.
    function handleBreakpointChange() {
        if (!isMobile()) {
            closeSidebar(false);
        }
    }

    if (mobileQuery.addEventListener) {
        mobileQuery.addEventListener('change', handleBreakpointChange);
    } else if (mobileQuery.addListener) {
        mobileQuery.addListener(handleBreakpointChange);
    }

    // Keep keyboard focus inside the open mobile sidebar.
    sidebar.addEventListener('keydown', function (event) {
        if (
            event.key !== 'Tab' ||
            !isMobile() ||
            !sidebar.classList.contains('is-open')
        ) {
            return;
        }

        var focusable = sidebar.querySelectorAll(
            'a[href], button:not([disabled])'
        );

        if (!focusable.length) {
            return;
        }

        var first = focusable[0];
        var last = focusable[focusable.length - 1];

        if (event.shiftKey && document.activeElement === first) {
            event.preventDefault();
            last.focus();
        } else if (
            !event.shiftKey &&
            document.activeElement === last
        ) {
            event.preventDefault();
            first.focus();
        }
    });

    // Highlight the current URL only when the server has not
    // already marked a navigation link as active.
    (function markActiveLink() {
        if (sidebar.querySelector('a.fa-nav-link.active')) {
            return;
        }

        var links = sidebar.querySelectorAll('a.fa-nav-link');
        var path = window.location.pathname.replace(/\/+$/, '') || '/';
        var best = null;

        links.forEach(function (link) {
            var linkPath = new URL(
                link.href,
                window.location.origin
            ).pathname.replace(/\/+$/, '') || '/';

            if (
                linkPath === path ||
                (linkPath !== '/' && path.indexOf(linkPath + '/') === 0)
            ) {
                if (!best || linkPath.length > best.len) {
                    best = {
                        el: link,
                        len: linkPath.length
                    };
                }
            }
        });

        if (best) {
            best.el.classList.add('active');
            best.el.setAttribute('aria-current', 'page');
        }
    })();
})();


/* ==========================================================================
 * FEATURE 2 - SINGLE CONTRIBUTION FORM, EDIT MODE ONLY
 * ========================================================================== */
(function () {
    'use strict';

    var form = document.getElementById('contributionForm');

    if (!form) {
        return;
    }

    var monthInput = document.getElementById('contributionMonthInput');
    var monthHidden = document.getElementById('contributionMonth');
    var employeeId = document.getElementById('employeeId');
    var submitBtn = document.getElementById('saveContributionButton');
    var submitText = document.getElementById('saveContributionButtonText');

    var MONTH_PATTERN = /^\d{4}-(0[1-9]|1[0-2])$/;
    var defaultText = submitText ? submitText.textContent : '';

    // Convert yyyy-MM into yyyy-MM-01 for the backend.
    function syncMonth() {
        if (!monthInput || !monthHidden) {
            return;
        }

        var value = monthInput.value.trim();

        monthHidden.value = MONTH_PATTERN.test(value)
            ? value + '-01'
            : '';
    }

    if (monthInput && monthHidden) {
        monthInput.addEventListener('input', syncMonth);
        monthInput.addEventListener('change', syncMonth);
        syncMonth();
    }

    form.addEventListener('submit', function () {
        syncMonth();

        if (employeeId) {
            employeeId.value = employeeId.value.trim();
        }

        // Prevent accidental double submission.
        if (submitBtn) {
            submitBtn.disabled = true;
        }

        if (submitText) {
            submitText.textContent = 'Saving...';
        }
    });

    // Restore the button when returning through the browser cache.
    window.addEventListener('pageshow', function (event) {
        if (event.persisted) {
            if (submitBtn) {
                submitBtn.disabled = false;
            }

            if (submitText) {
                submitText.textContent = defaultText;
            }

            syncMonth();
        }
    });
})();


/* ==========================================================================
 * FEATURE 3 - BULK ADD CONTRIBUTIONS
 * ========================================================================== */
(function () {
    'use strict';

    var form = document.getElementById('bulkContributionForm');

    if (!form) {
        return;
    }

    /* ------------------------------ Constants ------------------------------ */

    var MAX_ROWS = 50;
    var AMOUNT_PATTERN = /^\d+(\.\d{1,2})?$/;
    var MONTH_PATTERN = /^\d{4}-(0[1-9]|1[0-2])$/;
    var LOOKUP_DELAY = 350;

    /* ---------------------------- DOM references ---------------------------- */

    var rowsBox = document.getElementById('bulkRows');
    var template = document.getElementById('bulkRowTemplate');
    var monthInput = document.getElementById('batchMonthInput');
    var monthHidden = document.getElementById('batchMonth');

    var addBtn = document.getElementById('addBulkRowButton');
    var pasteBtn = document.getElementById('pasteIdsButton');
    var applyAmountBtn = document.getElementById('applyAmountButton');
    var clearInvalidBtn = document.getElementById('clearInvalidButton');
    var resetBtn = document.getElementById('resetBatchButton');
    var reviewBtn = document.getElementById('reviewBatchButton');

    var rowCountBadge = document.getElementById('bulkRowCount');
    var totalReadyEl = document.getElementById('bulkTotalReady');
    var totalAmountEl = document.getElementById('bulkTotalAmount');

    var reviewModalEl = document.getElementById('reviewBatchModal');
    var reviewTableBody = document.getElementById('reviewRows');
    var reviewSummary = document.getElementById('reviewSummary');
    var confirmBtn = document.getElementById('confirmSaveButton');

    var pasteModalEl = document.getElementById('pasteIdsModal');
    var pasteArea = document.getElementById('pasteIdsArea');
    var pasteGoBtn = document.getElementById('pasteIdsGoButton');

    /* ---------------------------- State variables --------------------------- */

    var nextIndex = rowsBox.querySelectorAll('.fa-bulk-row').length;
    var submitting = false;
    var dirty = false;

    /* ------------------------------ Helpers --------------------------------- */

    function rows() {
        return Array.prototype.slice.call(
            rowsBox.querySelectorAll('.fa-bulk-row')
        );
    }

    function field(row, key) {
        return row.querySelector('[data-field="' + key + '"]');
    }

    // Normalize IDs so EMP-001 and emp-001 are treated as identical.
    function normalizeId(value) {
        return String(value || '').trim().toUpperCase();
    }

    // Return yyyy-MM-01 or an empty string.
    function monthValue() {
        return monthHidden.value;
    }

    // Escape text before inserting it into HTML.
    function escapeHtml(value) {
        return String(value).replace(/[&<>"']/g, function (character) {
            return {
                '&': '&amp;',
                '<': '&lt;',
                '>': '&gt;',
                '"': '&quot;',
                "'": '&#39;'
            }[character];
        });
    }

    function collectionMethodLabel(value) {
        return {
            '': 'Not Specified',
            HO_HAND: 'HO-Hand',
            BANK_FT: 'Bank FT',
            ITD_HAND: 'ITD-Hand'
        }[value] || 'Not Specified';
    }

    /* -------------------------- NEW: Row status ----------------------------- */

    /**
     * Set the status of one bulk contribution row.
     *
     * status:
     * EMPTY     - No usable row status yet.
     * CHECKING  - Employee lookup is in progress.
     * READY     - Employee and amount are valid.
     * INVALID   - Unknown employee or invalid amount.
     * DUPLICATE - Employee ID appears more than once.
     * INACTIVE  - Employee exists but is inactive.
     *
     * Keeps the existing fa-status badge markup and styling.
     */

    function setRowStatus(row, status, message) {
        var statusElement = row.querySelector('.fa-row-status');

        if (!statusElement) {
            return;
        }

        statusElement.dataset.status = status;
        statusElement.textContent = message || '';
    }


    /* --------------------------- Shared month sync -------------------------- */

    function syncMonth() {
        var value = monthInput.value.trim();

        monthHidden.value = MONTH_PATTERN.test(value)
            ? value + '-01'
            : '';

        monthInput.classList.toggle('is-invalid', !monthHidden.value);

        dirty = true;
        refresh();
    }

    monthInput.addEventListener('input', syncMonth);
    monthInput.addEventListener('change', syncMonth);

    /* ------------------------------ Rows ------------------------------------ */

    function addRow(focus) {
        if (rows().length >= MAX_ROWS) {
            return null;
        }

        var html = template.innerHTML
            .split('__INDEX__')
            .join(String(nextIndex++));

        rowsBox.insertAdjacentHTML('beforeend', html);

        var row = rowsBox.lastElementChild;

        refresh();

        if (focus) {
            field(row, 'employeeId').focus();
        }

        return row;
    }

    // Clear only this row; do not remove it or change the shared month.
    function clearRow(row) {
        ['employeeId', 'amount'].forEach(function (key) {
            var element = field(row, key);

            if (element) {
                element.value = '';
                element.setCustomValidity('');
            }
        });

        var method = field(row, 'collectionMethod');
        if (method) method.value = '';

        delete row.dataset.lookupStatus;
        delete row.dataset.lookupName;

        var info = row.querySelector('.fa-lookup-info');

        if (info) {
            info.innerHTML = '';
        }

        setRowStatus(row, 'EMPTY', '');
        refresh();
    }

    /* --------------------------- Employee lookup ---------------------------- */

    var lookupTimer = null;

    function scheduleLookup(row) {
        clearTimeout(lookupTimer);

        lookupTimer = setTimeout(function () {
            lookup(row);
        }, LOOKUP_DELAY);
    }

    function lookup(row) {
        var input = field(row, 'employeeId');
        var infoEl = row.querySelector('.fa-lookup-info');
        var id = normalizeId(input.value);

        delete row.dataset.lookupStatus;
        delete row.dataset.lookupName;

        if (!id) {
            infoEl.innerHTML = '';
            setRowStatus(row, 'EMPTY', '');
            refresh();
            return;
        }

        infoEl.innerHTML =
            '<span class="text-secondary">Checking…</span>';

        setRowStatus(row, 'CHECKING', 'Checking employee…');

        fetch(
            '/admin/employees/lookup?employeeId=' + encodeURIComponent(id),
            {
                headers: {
                    'Accept': 'application/json'
                }
            }
        )
            .then(function (response) {
                if (response.status === 404) {
                    return null;
                }

                if (!response.ok) {
                    throw new Error('Employee lookup failed');
                }

                return response.json();
            })
            .then(function (employee) {
                // Ignore stale responses if the ID has changed.
                if (normalizeId(input.value) !== id) {
                    return;
                }

                if (!employee) {
                    row.dataset.lookupStatus = 'UNKNOWN';

                    infoEl.innerHTML =
                        '<span class="fa-status fa-status-danger">' +
                        'Unknown employee</span>';

                    setRowStatus(row, 'INVALID', 'Unknown employee');
                } else {
                    row.dataset.lookupStatus =
                        employee.active === false ? 'INACTIVE' : 'FOUND';
                    row.dataset.lookupName = employee.fullName || '';

                    var inactive = employee.active === false;

                    var fundBadge = inactive
                        ? '<span class="fa-status fa-status-danger">INACTIVE</span>'
                        : '<span class="fa-status fa-status-success">ACTIVE</span>';

                    infoEl.innerHTML =
                        '<span class="fw-semibold">' +
                        escapeHtml(employee.fullName || '') +
                        '</span> <span>· ' +
                        escapeHtml(employee.designation || '') +
                        '</span> ' + fundBadge;

                    if (inactive) {
                        setRowStatus(row, 'INACTIVE', 'Inactive employee');
                    }
                }

                refresh();
            })
            .catch(function () {
                if (normalizeId(input.value) !== id) {
                    return;
                }

                row.dataset.lookupStatus = '';

                infoEl.innerHTML =
                    '<span class="text-warning small">' +
                    'Lookup unavailable — the server will validate on save.' +
                    '</span>';

                setRowStatus(row, 'EMPTY', 'Lookup unavailable');
                refresh();
            });
    }

    /* --------------------- Status + totals (refresh) ------------------------ */

    /**
     * Re-number rows, compute status badges and calculate totals.
     *
     * Priority:
     * UNKNOWN EMPLOYEE > DUPLICATE IN BATCH > INVALID AMOUNT > READY
     */
    function refresh() {
        var list = rows();
        var idCounts = Object.create(null);

        // Count IDs first so duplicates can be detected.
        list.forEach(function (row) {
            var id = normalizeId(field(row, 'employeeId').value);

            if (id) {
                idCounts[id] = (idCounts[id] || 0) + 1;
            }
        });

        var readyCount = 0;
        var totalAmount = 0;

        list.forEach(function (row, index) {
            row.querySelector('.fa-row-number').textContent =
                String(index + 1);

            var removeBtn = row.querySelector('.fa-row-remove');

            removeBtn.setAttribute(
                'aria-label',
                'Remove row ' + (index + 1)
            );

            removeBtn.disabled = list.length === 1;

            var id = normalizeId(field(row, 'employeeId').value);
            var amountRaw = String(field(row, 'amount').value).trim();
            var amountOk =
                amountRaw !== '' &&
                AMOUNT_PATTERN.test(amountRaw) &&
                parseFloat(amountRaw) >= 0.01;

            var status = 'EMPTY';
            var statusType = 'EMPTY';

            if (row.dataset.lookupStatus === 'INACTIVE') {
                status = 'INACTIVE EMPLOYEE';
                statusType = 'INACTIVE';
            } else if (row.dataset.lookupStatus === 'UNKNOWN') {
                status = 'UNKNOWN EMPLOYEE';
                statusType = 'INVALID';
            } else if (id && idCounts[id] > 1) {
                status = 'DUPLICATE IN BATCH';
                statusType = 'DUPLICATE';
            } else if (amountRaw !== '' && !amountOk) {
                status = 'INVALID AMOUNT';
                statusType = 'INVALID';
            } else if (
                id &&
                row.dataset.lookupStatus === 'FOUND' &&
                amountOk
            ) {
                status = 'READY';
                statusType = 'READY';

                readyCount++;
                totalAmount += parseFloat(amountRaw);
            }

            // All row status rendering now goes through setRowStatus().
            setRowStatus(row, statusType, status);
        });

        rowCountBadge.textContent =
            list.length + (list.length === 1 ? ' row' : ' rows');

        addBtn.disabled = list.length >= MAX_ROWS;

        totalReadyEl.textContent = String(readyCount);
        totalAmountEl.textContent = totalAmount.toFixed(2);

        reviewBtn.disabled = readyCount === 0;
    }

    /* ------------------------------- Events --------------------------------- */

    // Add another employee row.
    addBtn.addEventListener('click', function () {
        addRow(true);
        dirty = true;
    });

    // Delegated handlers for row buttons.
    rowsBox.addEventListener('click', function (event) {
        var clearBtn = event.target.closest('.fa-row-clear');

        if (clearBtn) {
            var rowToClear = clearBtn.closest('.fa-bulk-row');

            var hasData =
                normalizeId(field(rowToClear, 'employeeId').value) !== '' ||
                String(field(rowToClear, 'amount').value).trim() !== '';

            if (
                hasData &&
                !window.confirm(
                    'Clear the entered data in this row? ' +
                    'The row stays and you can retype immediately.'
                )
            ) {
                return;
            }

            clearRow(rowToClear);

            if (hasData) {
                dirty = true;
            }

            field(rowToClear, 'employeeId').focus();
            return;
        }

        var removeBtn = event.target.closest('.fa-row-remove');

        if (removeBtn) {
            if (removeBtn.disabled) {
                return;
            }

            removeBtn.closest('.fa-bulk-row').remove();

            if (rows().length === 0) {
                addRow(false);
            }

            dirty = true;
            refresh();
            addBtn.focus();
            return;
        }

        var lookupBtn = event.target.closest('.fa-row-lookup');

        if (lookupBtn) {
            clearTimeout(lookupTimer);
            lookup(lookupBtn.closest('.fa-bulk-row'));
        }
    });

    // Typing invalidates the old lookup result.
    rowsBox.addEventListener('input', function (event) {
        dirty = true;

        var row = event.target.closest('.fa-bulk-row');

        if (!row) {
            return;
        }

        if (event.target.matches('[data-field="employeeId"]')) {
            delete row.dataset.lookupStatus;
            delete row.dataset.lookupName;

            row.querySelector('.fa-lookup-info').innerHTML = '';

            setRowStatus(row, 'EMPTY', '');
            scheduleLookup(row);
        }

        refresh();
    });

    // Normalize the employee ID when leaving its input.
    rowsBox.addEventListener('change', function (event) {
        if (event.target.matches('[data-field="employeeId"]')) {
            event.target.value = normalizeId(event.target.value);
            refresh();
        }
    });

    /* --------------------------- Quick actions ------------------------------ */

    // Paste employee IDs into a modal.
    pasteBtn.addEventListener('click', function () {
        pasteArea.value = '';

        bootstrap.Modal
            .getOrCreateInstance(pasteModalEl)
            .show();
    });

    // Convert pasted IDs into rows.
    pasteGoBtn.addEventListener('click', function () {
        var seen = Object.create(null);
        var lines = pasteArea.value.split(/\r?\n/);

        lines.forEach(function (line) {
            var id = normalizeId(line);

            if (!id || seen[id]) {
                return;
            }

            seen[id] = true;

            if (rows().length >= MAX_ROWS) {
                return;
            }

            var row = addRow(false);

            if (!row) {
                return;
            }

            field(row, 'employeeId').value = id;
            lookup(row);
        });

        bootstrap.Modal.getInstance(pasteModalEl).hide();

        dirty = true;
        refresh();
    });

    // Apply the first valid amount to rows with an employee ID.
    applyAmountBtn.addEventListener('click', function () {
        var source = null;

        rows().forEach(function (row) {
            if (source !== null) {
                return;
            }

            var value = String(field(row, 'amount').value).trim();

            if (
                value !== '' &&
                AMOUNT_PATTERN.test(value) &&
                parseFloat(value) >= 0.01
            ) {
                source = value;
            }
        });

        if (source === null) {
            window.alert('Enter an amount in at least one row first.');
            return;
        }

        if (
            !window.confirm(
                'Apply BDT ' + source +
                ' to all rows with an Employee ID?'
            )
        ) {
            return;
        }

        rows().forEach(function (row) {
            if (normalizeId(field(row, 'employeeId').value)) {
                field(row, 'amount').value = source;
            }
        });

        dirty = true;
        refresh();
    });

    // Remove rows with known-invalid employee IDs or invalid amounts.
    clearInvalidBtn.addEventListener('click', function () {
        rows().forEach(function (row) {
            if (rows().length <= 1) {
                return;
            }

            var amountRaw = String(field(row, 'amount').value).trim();

            var invalid =
                row.dataset.lookupStatus === 'UNKNOWN' ||
                (
                    amountRaw !== '' &&
                    (
                        !AMOUNT_PATTERN.test(amountRaw) ||
                        parseFloat(amountRaw) < 0.01
                    )
                );

            if (invalid) {
                row.remove();
            }
        });

        if (rows().length === 0) {
            addRow(false);
        }

        dirty = true;
        refresh();
    });

    // Reset the batch.
    resetBtn.addEventListener('click', function () {
        if (!window.confirm('Clear ALL rows and start over?')) {
            return;
        }

        rows().forEach(function (row) {
            row.remove();
        });

        addRow(false);

        dirty = false;
        refresh();
    });

    /* --------------------------- Batch review ------------------------------- */

    reviewBtn.addEventListener('click', function () {
        reviewTableBody.innerHTML = '';

        var count = 0;
        var total = 0;

        rows().forEach(function (row) {
            var id = normalizeId(field(row, 'employeeId').value);
            var amountRaw = String(field(row, 'amount').value).trim();

            var statusText = row
                .querySelector('.fa-row-status')
                .textContent
                .trim();

            if (!id || statusText !== 'READY') {
                return;
            }

            count++;
            total += parseFloat(amountRaw);

            var tr = document.createElement('tr');

            tr.innerHTML =
                '<td>' + count + '</td>' +
                '<td class="fw-semibold">' + escapeHtml(id) + '</td>' +
                '<td>' + escapeHtml(row.dataset.lookupName || '') + '</td>' +
                '<td>' + escapeHtml(monthValue().slice(0, 7)) + '</td>' +
                '<td class="text-end">' +
                parseFloat(amountRaw).toFixed(2) +
                '</td>' +
                '<td>' + escapeHtml(collectionMethodLabel(field(row, 'collectionMethod').value)) + '</td>';

            reviewTableBody.appendChild(tr);
        });

        reviewSummary.textContent =
            'Create ' + count +
            ' PENDING contributions totaling BDT ' +
            total.toFixed(2) +
            '? No payments will be recorded.';

        bootstrap.Modal
            .getOrCreateInstance(reviewModalEl)
            .show();
    });

    // Confirm review and submit the form.
    confirmBtn.addEventListener('click', function () {
        bootstrap.Modal.getInstance(reviewModalEl).hide();
        form.requestSubmit();
    });

    /* ------------------------------- Submit --------------------------------- */

    form.addEventListener('submit', function (event) {
        if (submitting) {
            event.preventDefault();
            return;
        }

        // Require the shared contribution month.
        if (!monthHidden.value) {
            event.preventDefault();

            monthInput.focus();
            monthInput.classList.add('is-invalid');
            return;
        }

        // Remove completely blank rows before validation.
        rows().forEach(function (row) {
            var empty =
                !normalizeId(field(row, 'employeeId').value) &&
                !String(field(row, 'amount').value).trim();

            if (rows().length > 1 && empty) {
                row.remove();
            }
        });

        // Re-index entries after rows have been removed.
        rows().forEach(function (row, index) {
            Array.prototype.forEach.call(
                row.querySelectorAll('[name]'),
                function (element) {
                    element.name = element.name.replace(
                        /entries\[\d+\]/,
                        'entries[' + index + ']'
                    );
                }
            );
        });

        // Run native validation manually because the form uses novalidate.
        if (!form.checkValidity()) {
            event.preventDefault();

            form.classList.add('was-validated');

            var firstInvalid = form.querySelector(':invalid');

            if (firstInvalid) {
                firstInvalid.focus();
                firstInvalid.scrollIntoView({
                    block: 'center'
                });
            }

            return;
        }

        // Prevent accidental double submission.
        submitting = true;
        dirty = false;

        confirmBtn.disabled = true;
        confirmBtn.textContent = 'Saving…';

        reviewBtn.classList.add('disabled');
    });

    // Warn about unsaved changes when leaving the page.
    window.addEventListener('beforeunload', function (event) {
        if (dirty && !submitting) {
            event.preventDefault();
            event.returnValue = '';
        }
    });

    /* -------------------------------- Init ---------------------------------- */

    // Default to the current month when no month has been selected.
    if (!monthInput.value) {
        var prefillYear = rowsBox.closest('form').dataset.prefillYear;
        var prefillMonth = rowsBox.closest('form').dataset.prefillMonth;
        if (prefillYear && prefillMonth && Number(prefillMonth) >= 1 && Number(prefillMonth) <= 12) {
            monthInput.value = prefillYear + '-' + String(prefillMonth).padStart(2, '0');
        } else {
            var now = new Date();
            monthInput.value = now.getFullYear() + '-' + String(now.getMonth() + 1).padStart(2, '0');
        }
    }

    syncMonth();

    if (rows().length === 0) {
        addRow(false);
    }

    var prefillEmployeeId = rowsBox.closest('form').dataset.prefillEmployeeId;
    if (prefillEmployeeId && rows().length) {
        var firstEmployeeField = field(rows()[0], 'employeeId');
        if (firstEmployeeField) {
            firstEmployeeField.value = prefillEmployeeId;
            firstEmployeeField.dispatchEvent(new Event('input', { bubbles: true }));
        }
    }

    refresh();
})();
