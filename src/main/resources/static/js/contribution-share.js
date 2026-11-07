/*
 * - FundAxis — Contribution & Share Management
 * - Common JavaScript
 *
 * - Supports:
 *   - Workspace tabs
 *   - Excel yearly / month-range export forms
 *   - Client-side date validation
 *   - Table search
 *   - Transaction confirmation
 *   - Duplicate-submit protection
 *   - Loading indicators
 *
 * - Security and financial calculations remain server-side.
 */

(() => {
    "use strict";

    const workspace = document.querySelector(".cs-workspace");

    if (!workspace) {
        return;
    }

    const TAB_SELECTOR = "[data-cs-tab-target]";
    const PANEL_SELECTOR = "[data-cs-panel]";

    const MONTH_PATTERN = /^(\d{4})-(0[1-9]|1[0-2])$/;

    const YEAR_MIN = 1900;
    const YEAR_MAX = 9999;
    const MAX_RANGE_MONTHS = 60;

    // ==================== Utilities ====================

    function find(selector, root = workspace) {
        return root.querySelector(selector);
    }

    function findAll(selector, root = workspace) {
        return Array.from(root.querySelectorAll(selector));
    }

    function setError(form, message) {
        const error = find("[data-cs-form-error]", form);

        if (!error) {
            return;
        }

        error.textContent = message || "";
        error.hidden = !message;
        error.setAttribute("role", "alert");
    }

    function clearInvalid(form) {
        findAll(".is-invalid", form).forEach((element) => {
            element.classList.remove("is-invalid");
            element.removeAttribute("aria-invalid");
        });

        setError(form, "");
    }

    function markInvalid(element) {
        if (!element) {
            return;
        }

        element.classList.add("is-invalid");
        element.setAttribute("aria-invalid", "true");
    }

    function validYear(value) {
        const year = String(value).trim();

        if (!/^\d{4}$/.test(year)) {
            return false;
        }

        const numericYear = Number(year);

        return numericYear >= YEAR_MIN && numericYear <= YEAR_MAX;
    }

    function parseMonth(value) {
        const normalized = String(value || "").trim();
        const match = MONTH_PATTERN.exec(normalized);

        if (!match) {
            return null;
        }

        const year = Number(match[1]);
        const month = Number(match[2]);

        if (year < YEAR_MIN || year > YEAR_MAX) {
            return null;
        }

        return {
            year,
            month,
            text: normalized,
            index: year * 12 + month - 1 // Fixed calculation
        };
    }

    function currentMonth() {
        const now = new Date();

        return String(now.getFullYear()) + "-" +
            String(now.getMonth() + 1).padStart(2, "0");
    }

    // ==================== Workspace Tabs ====================

    const tabs = findAll(TAB_SELECTOR);
    const panels = findAll(PANEL_SELECTOR);

    function activateTab(tabName, updateHash = true) {
        const selectedTab = tabs.find(
            (tab) => tab.dataset.csTabTarget === tabName
        );

        const selectedPanel = panels.find(
            (panel) => panel.dataset.csPanel === tabName
        );

        if (!selectedTab || !selectedPanel) {
            return false;
        }

        tabs.forEach((tab) => {
            const active = tab === selectedTab;

            tab.classList.toggle("is-active", active);
            tab.setAttribute("aria-selected", String(active));
            tab.setAttribute("tabindex", active ? "0" : "-1");
        });

        panels.forEach((panel) => {
            const active = panel === selectedPanel;

            panel.hidden = !active;
            panel.classList.toggle("is-active", active);
        });

        if (updateHash && window.history.replaceState) {
            const url = new URL(window.location.href);

            url.hash = "cs-" + tabName;

            window.history.replaceState(null, "", url.toString());
        }

        return true;
    }

    function initializeTabs() {
        if (!tabs.length || !panels.length) {
            return;
        }

        tabs.forEach((tab, index) => {
            tab.setAttribute("role", "tab");

            if (!tab.id) {
                tab.id = "cs-tab-" + index;
            }

            const name = tab.dataset.csTabTarget;

            const panel = panels.find(
                (item) => item.dataset.csPanel === name
            );

            if (panel) {
                if (!panel.id) {
                    panel.id = "cs-panel-" + index;
                }

                tab.setAttribute("aria-controls", panel.id);
                panel.setAttribute("role", "tabpanel");
                panel.setAttribute("aria-labelledby", tab.id);
            }

            tab.addEventListener("click", () => {
                activateTab(name);
            });

            tab.addEventListener("keydown", (event) => {
                const navigationKeys = [
                    "ArrowLeft",
                    "ArrowRight",
                    "Home",
                    "End"
                ];

                if (!navigationKeys.includes(event.key)) {
                    return;
                }

                event.preventDefault();

                let next = index;

                if (event.key === "ArrowRight") {
                    next = (index + 1) % tabs.length;
                }

                if (event.key === "ArrowLeft") {
                    next = (index - 1 + tabs.length) % tabs.length; // Fixed
                }

                if (event.key === "Home") {
                    next = 0;
                }

                if (event.key === "End") {
                    next = tabs.length - 1; // Fixed
                }

                tabs[next].focus();

                activateTab(
                    tabs[next].dataset.csTabTarget
                );
            });
        });

        const requestedTab = window.location.hash.replace(/^#cs-/, "");

        if (!activateTab(requestedTab, false)) {
            const initiallyActive = tabs.find(
                (tab) => tab.getAttribute("aria-selected") === "true"
            ) || tabs[0];

            activateTab(
                initiallyActive.dataset.csTabTarget,
                false
            );
        }
    }

    // ==================== Yearly Excel Export ====================

    function initializeYearExport() {
        const forms = findAll('form[data-cs-export="year"]');

        forms.forEach((form) => {
            const yearInput = find('[name="year"]', form);

            if (!yearInput) {
                return;
            }

            form.addEventListener("submit", (event) => {
                clearInvalid(form);

                if (!validYear(yearInput.value)) {
                    event.preventDefault();

                    markInvalid(yearInput);

                    setError(
                        form,
                        "Please enter a valid year between " +
                        YEAR_MIN + " and " + YEAR_MAX + "."
                    );

                    yearInput.focus();
                    return;
                }
            });

            yearInput.addEventListener("input", () => {
                clearInvalid(form);
            });
        });
    }

    // ==================== Month Range Excel Export ====================

    function initializeRangeExport() {
        const forms = findAll('form[data-cs-export="range"]');

        forms.forEach((form) => {
            const fromInput = find('[name="from"]', form);
            const toInput = find('[name="to"]', form);

            if (!fromInput || !toInput) {
                return;
            }

            form.addEventListener("submit", (event) => {
                clearInvalid(form);

                const from = parseMonth(fromInput.value);
                const to = parseMonth(toInput.value);

                if (!from) {
                    event.preventDefault();
                    markInvalid(fromInput);
                    setError(form, "Select a valid start month.");
                    fromInput.focus();
                    return;
                }

                if (!to) {
                    event.preventDefault();
                    markInvalid(toInput);
                    setError(form, "Select a valid end month.");
                    toInput.focus();
                    return;
                }

                if (from.index > to.index) {
                    event.preventDefault();
                    markInvalid(fromInput);
                    markInvalid(toInput);
                    setError(form, "Start month cannot be after end month.");
                    return;
                }

                const totalMonths = to.index - from.index + 1; // Fixed calculation

                if (totalMonths > MAX_RANGE_MONTHS) {
                    event.preventDefault();
                    markInvalid(fromInput);
                    markInvalid(toInput);
                    setError(
                        form,
                        "Maximum reporting range is " +
                        MAX_RANGE_MONTHS + " months."
                    );
                    return;
                }
            });

            [fromInput, toInput].forEach((input) => {
                input.addEventListener("input", () => {
                    clearInvalid(form);
                });
            });
        });
    }

    // ==================== Optional Default Dates ====================

    function initializeOptionalDefaults() {
        const now = new Date();

        findAll("[data-cs-default-year]").forEach((input) => {
            if (!input.value) {
                input.value = String(now.getFullYear());
            }
        });

        findAll("[data-cs-default-month]").forEach((input) => {
            if (!input.value) {
                input.value = currentMonth();
            }
        });
    }

    // ==================== Table Search ====================

    function initializeTableSearch() {
        findAll("[data-cs-search]").forEach((input) => {
            const targetId = input.dataset.csSearch;
            const table = document.getElementById(targetId);

            if (!table) {
                return;
            }

            const rows = findAll("tbody tr", table);
            const statusElement = find(`[data-cs-search-count="${targetId}"]`);

            function filterRows() {
                const query = input.value.trim().toLocaleLowerCase();
                let visible = 0;

                rows.forEach((row) => {
                    if (row.hasAttribute("data-cs-empty-row")) {
                        return;
                    }

                    const rowText = (row.textContent || "").toLocaleLowerCase();
                    const matches = !query || rowText.includes(query);

                    row.hidden = !matches;

                    if (matches) {
                        visible++;
                    }
                });

                if (statusElement) {
                    statusElement.textContent = visible + " visible records";
                }
            }

            input.addEventListener("input", filterRows);
            filterRows();
        });
    }

    // ==================== Confirm Financial Actions ====================

    function initializeConfirmations() {
        document.addEventListener("click", (event) => {
            const button = event.target.closest("[data-cs-confirm]");

            if (!button || !workspace.contains(button)) {
                return;
            }

            const message =
                button.dataset.csConfirm ||
                "Are you sure you want to continue?";

            if (!window.confirm(message)) {
                event.preventDefault();
                event.stopPropagation();
            }
        });
    }

    // ==================== Prevent Duplicate Form Submit ====================

    function initializeTransactionForms() {
        findAll("form[data-cs-transaction-form]").forEach((form) => {
            if (form.hasAttribute("data-cs-export")) {
                return;
            }

            form.addEventListener("submit", (event) => {
                if (form.dataset.csSubmitting === "true") {
                    event.preventDefault();
                    return;
                }

                if (!form.checkValidity()) {
                    return;
                }

                if (event.defaultPrevented) {
                    return;
                }

                const message = form.dataset.csSubmitConfirm;

                if (message && !window.confirm(message)) {
                    event.preventDefault();
                    return;
                }

                form.dataset.csSubmitting = "true";

                const submitButton = find('[type="submit"]', form);

                if (submitButton) {
                    submitButton.classList.add("is-loading");
                    submitButton.setAttribute("aria-busy", "true");
                }
            });
        });
    }

    // ==================== Form Input Reset ====================

    function initializeResetButtons() {
        findAll("[data-cs-reset-form]").forEach((button) => {
            button.addEventListener("click", () => {
                const selector = button.dataset.csResetForm;
                const form = document.querySelector(selector);

                if (!form) {
                    return;
                }

                form.reset();
                clearInvalid(form);
            });
        });
    }

    // ==================== Initialization ====================

    initializeTabs();
    initializeOptionalDefaults();
    initializeYearExport();
    initializeRangeExport();
    initializeTableSearch();
    initializeConfirmations();
    initializeTransactionForms();
    initializeResetButtons();

})();