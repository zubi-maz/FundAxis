
/* ============================================================================
 * FundAxis | Contribution & Share Management Workspace
 * File: src/main/resources/static/js/contribution-workspace.js
 *
 * For /admin/contribution-workspace only.
 * Server/Thymeleaf controls tabs, financial data, roles, validation and CSRF.
 * No DOM cloning, template generation, payments calculation, or sidebar setup.
 * Keep /js/dashboard.js loaded separately for the common admin shell.
 * ============================================================================ */

(() => {
    'use strict';

    const workspace = document.querySelector('.cs-workspace');
    if (!workspace) return;

    const MONTH_RE = /^(\d{4})-(0[1-9]|1[0-2])$/;
    const YEAR_MIN = 1900;
    const YEAR_MAX = 9999;
    const MAX_EXPORT_MONTHS = 60;

    const select = (selector, root = workspace) =>
        root.querySelector(selector);

    const selectAll = (selector, root = workspace) =>
        Array.from(root.querySelectorAll(selector));

    function showError(form, message, fields = []) {

        selectAll('.is-invalid', form).forEach(input => {
            input.classList.remove('is-invalid');
            input.removeAttribute('aria-invalid');
        });

        const error = select('[data-cs-form-error]', form);

        if (error) {
            error.textContent = message || '';
            error.hidden = !message;

            if (message) {
                error.setAttribute('role', 'alert');
            }
        }

        fields.forEach(input => {
            if (!input) return;

            input.classList.add('is-invalid');
            input.setAttribute('aria-invalid', 'true');
        });
    }

    function monthIndex(value) {

        const match = MONTH_RE.exec(
            String(value || '').trim()
        );

        if (!match) return null;

        const year = Number(match[1]);
        const month = Number(match[2]);

        if (year < YEAR_MIN || year > YEAR_MAX) {
            return null;
        }

        return year * 12 + month - 1;
    }

    /* ============================================================
       OVERVIEW REPORT PERIOD
       ============================================================ */

    const periodInput = select('#overviewPeriod');

    if (periodInput && periodInput.form) {

        const form = periodInput.form;

        form.addEventListener('submit', event => {

            const index = monthIndex(periodInput.value);

            if (index === null) {

                event.preventDefault();

                periodInput.classList.add('is-invalid');
                periodInput.focus();

                return;
            }

            const year = select(
                'input[name="year"]',
                form
            );

            const month = select(
                'input[name="month"]',
                form
            );

            if (year) {
                year.value = periodInput.value.slice(0, 4);
            }

            if (month) {
                month.value = String(
                    Number(periodInput.value.slice(5, 7))
                );
            }
        });

        periodInput.addEventListener('input', () => {
            periodInput.classList.remove('is-invalid');
        });
    }

    /* ============================================================
       YEARLY EXCEL EXPORT VALIDATION
       ============================================================ */

    selectAll('form[data-cs-export="year"]').forEach(form => {

        const input = select(
            'input[name="year"]',
            form
        );

        if (!input) return;

        form.addEventListener('submit', event => {

            const raw = input.value.trim();

            const valid =
                /^\d{4}$/.test(raw) &&
                Number(raw) >= YEAR_MIN &&
                Number(raw) <= YEAR_MAX;

            if (!valid) {

                event.preventDefault();

                showError(
                    form,
                    'Enter a valid four-digit reporting year.',
                    [input]
                );

                input.focus();

            } else {
                showError(form, '');
            }
        });

        input.addEventListener('input', () => {
            showError(form, '');
        });
    });

    /* ============================================================
       MONTH-RANGE EXCEL EXPORT VALIDATION
       ============================================================ */

    selectAll('form[data-cs-export="range"]').forEach(form => {

        const from = select(
            'input[name="from"]',
            form
        );

        const to = select(
            'input[name="to"]',
            form
        );

        if (!from || !to) return;

        form.addEventListener('submit', event => {

            const start = monthIndex(from.value);
            const end = monthIndex(to.value);

            let message = '';
            let invalid = [];

            if (start === null) {

                message = 'Select a valid start month.';
                invalid = [from];

            } else if (end === null) {

                message = 'Select a valid end month.';
                invalid = [to];

            } else if (start > end) {

                message = 'Start month cannot be after end month.';
                invalid = [from, to];

            } else if (
                end - start + 1 > MAX_EXPORT_MONTHS
            ) {

                message = 'The maximum export range is 60 months.';
                invalid = [from, to];
            }

            if (message) {

                event.preventDefault();

                showError(
                    form,
                    message,
                    invalid
                );

                invalid[0].focus();

            } else {
                showError(form, '');
            }
        });

        [from, to].forEach(input => {

            input.addEventListener('input', () => {
                showError(form, '');
            });

        });
    });

    /* ============================================================
       TABLE SEARCH AND FILTERING
       ============================================================ */

    selectAll('input[data-cs-search]').forEach(input => {

        const tableId = input.dataset.csSearch;

        const table = document.getElementById(tableId);

        if (!table || !workspace.contains(table)) {
            return;
        }

        const count = selectAll(
            '[data-cs-search-count]'
        ).find(element =>
            element.dataset.csSearchCount === tableId
        );

        const update = () => {

            const term = input.value
                .trim()
                .toLocaleLowerCase();

            let visible = 0;

            selectAll('tbody tr', table).forEach(row => {

                if (row.hasAttribute('data-cs-empty-row')) {
                    return;
                }

                const matches =
                    !term ||
                    (row.textContent || '')
                        .toLocaleLowerCase()
                        .includes(term);

                row.hidden = !matches;

                if (matches) {
                    visible++;
                }
            });

            if (count) {
                count.textContent =
                    `${visible} visible records`;
            }
        };

        input.addEventListener('input', update);

        update();
    });

    /* ============================================================
       FINANCIAL TRANSACTION CONFIRMATION
       DOUBLE SUBMISSION PROTECTION
       ============================================================ */

    selectAll(
        'form[data-cs-transaction-form]'
    ).forEach(form => {

        form.addEventListener('submit', event => {

            if (form.dataset.csSubmitting === 'true') {

                event.preventDefault();
                return;
            }

            if (
                event.defaultPrevented ||
                !form.checkValidity()
            ) {
                return;
            }

            const message =
                form.dataset.csSubmitConfirm;

            if (
                message &&
                form.dataset.csSkipSubmitConfirm !== 'true' &&
                !window.confirm(message)
            ) {

                event.preventDefault();
                return;
            }

            form.dataset.csSubmitting = 'true';

            const button = select(
                'button[type="submit"]',
                form
            );

            if (button) {

                button.classList.add('is-loading');

                button.setAttribute(
                    'aria-busy',
                    'true'
                );

                // Do not disable submit controls.
                // Allow the browser to submit the POST.
            }
        });
    });

    /* ============================================================
       OPTIONAL FORM RESET BUTTONS
       ============================================================ */

    selectAll('[data-cs-reset-form]').forEach(button => {

        button.addEventListener('click', () => {

            const form = document.querySelector(
                button.dataset.csResetForm
            );

            if (!form) return;

            form.reset();

            showError(form, '');
        });
    });

    // Read-only report downloads share one handler. Other workspace tabs have no matching controls.
    selectAll('[data-cs-report-export]').forEach(link => {
        link.addEventListener('click', async event => {
            event.preventDefault();
            const panel = link.closest('.cs-yr-panel, .cs-st-panel');
            if (!panel || link.getAttribute('aria-disabled') === 'true') return;
            const feedback = panel.querySelector('[data-cs-report-feedback]');
            link.setAttribute('aria-disabled', 'true');
            link.setAttribute('aria-busy', 'true');
            if (feedback) { feedback.classList.remove('is-error'); feedback.textContent = 'Preparing your Excel export...'; }
            try {
                const response = await fetch(link.href, {credentials: 'same-origin'});
                if (!response.ok || response.redirected || !response.headers.get('content-type')?.includes('spreadsheetml')) throw new Error();
                const file = await response.blob();
                const match = /filename="?([^";]+)"?/i.exec(response.headers.get('content-disposition') || '');
                const download = document.createElement('a');
                const objectUrl = URL.createObjectURL(file);
                download.href = objectUrl;
                download.download = (match ? match[1] : 'FundAxis_Report.xlsx').replace(/[\\/\r\n]/g, '_');
                document.body.appendChild(download); download.click(); download.remove();
                setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
                if (feedback) feedback.textContent = 'Excel export is ready. Your browser will download the file.';
            } catch {
                if (feedback) { feedback.classList.add('is-error'); feedback.textContent = 'The export could not be downloaded. Refresh the report and try again. If the problem continues, contact support.'; }
            } finally {
                link.removeAttribute('aria-busy');
                if (panel.dataset.dirty !== 'true') link.removeAttribute('aria-disabled');
            }
        });
    });

})();
