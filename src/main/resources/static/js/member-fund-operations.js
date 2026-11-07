/* Shared context and focused Bootstrap forms. Financial writes remain on existing endpoints. */
(() => {
    'use strict';
    const workspace = document.querySelector('.mfo-workspace');
    if (!workspace) return;
    const context = document.getElementById('mfo-context');
    // One set of secondary links: inline on desktop, expandable on narrow screens.
    const narrowActions = window.matchMedia('(max-width: 767px)');
    const actionMenus = [...workspace.querySelectorAll('.mfo-action-menu')];
    const updateActionMenus = () => actionMenus.forEach(menu => { menu.open = !narrowActions.matches; });
    updateActionMenus();
    narrowActions.addEventListener('change', updateActionMenus);
    context?.addEventListener('submit', event => {
        if (event.defaultPrevented || !context.checkValidity()) return;
        const button = context.querySelector('button[type="submit"]');
        if (button) { button.disabled = true; button.textContent = 'Applying Context...'; }
    });
    window.addEventListener('pageshow', () => {
        const button = context?.querySelector('button[type="submit"]');
        if (button) { button.disabled = false; button.textContent = 'Apply Context'; }
        workspace.querySelectorAll('[data-mfo-bound-form]').forEach(form => { form.querySelector('[data-mfo-bound-submit]').disabled = !form.dataset.boundUrl; });
    });
    let stale = false;
    const withdrawalCategory = document.getElementById('mfo-withdrawal-category');
    const withdrawalQuantity = document.getElementById('mfo-withdrawal-quantity');
    const withdrawalAvailability = document.getElementById('mfo-withdrawal-availability');
    const updateWithdrawalLimit = () => {
        const option = withdrawalCategory?.selectedOptions[0];
        const available = Number(option?.dataset.available || 0);
        if (withdrawalQuantity) {
            withdrawalQuantity.max = String(available);
            withdrawalQuantity.setCustomValidity(withdrawalQuantity.value && Number(withdrawalQuantity.value) > available
                ? 'Requested quantity exceeds the available unreserved shares.' : '');
        }
        if (withdrawalAvailability) withdrawalAvailability.textContent = `Available quantity: ${available} share${available === 1 ? '' : 's'}. Pending and approved unposted requests are reserved.`;
    };
    withdrawalCategory?.addEventListener('change', updateWithdrawalLimit);
    withdrawalQuantity?.addEventListener('input', updateWithdrawalLimit);
    updateWithdrawalLimit();
    const transitionModalElement = document.querySelector('[data-mfo-transition-modal]');
    const transitionModal = transitionModalElement && window.bootstrap ? bootstrap.Modal.getOrCreateInstance(transitionModalElement) : null;
    const transitionButton = transitionModalElement?.querySelector('[data-mfo-transition-submit]');
    let activeTransitionForm = null;
    const transitionError = transitionModalElement?.querySelector('[data-mfo-transition-error]');
    workspace.querySelectorAll('[data-mfo-withdrawal-confirm]').forEach(form => {
        form.addEventListener('submit', event => {
            if (form.dataset.mfoTransitionConfirmed !== 'true') { event.preventDefault(); return; }
            form.dataset.mfoTransitionConfirmed = 'false';
            const opener = form.querySelector('[data-mfo-open-withdrawal-confirm]');
            if (opener) opener.disabled = true;
        });
        form.querySelector('[data-mfo-open-withdrawal-confirm]')?.addEventListener('click', () => {
            if (!transitionModalElement) return;
            activeTransitionForm = form;
            const row = form.closest('tr');
            const action = form.dataset.mfoWithdrawalConfirm;
            const post = action === 'post';
            transitionModalElement.querySelector('#mfo-withdrawal-transition-title').textContent = post ? 'Post Share Withdrawal' : 'Approve Withdrawal Request';
            transitionModalElement.querySelector('[data-mfo-transition-id]').textContent = row ? `WR-${row.dataset.withdrawalRowId}` : 'Unavailable';
            transitionModalElement.querySelector('[data-mfo-transition-employee]').textContent = row?.dataset.withdrawalEmployee || 'Employee information unavailable';
            transitionModalElement.querySelector('[data-mfo-transition-category]').textContent = row?.dataset.withdrawalCategory || '—';
            transitionModalElement.querySelector('[data-mfo-transition-quantity]').textContent = row?.dataset.withdrawalQuantity || '—';
            transitionModalElement.querySelector('[data-mfo-transition-date]').textContent = row?.cells[2]?.textContent.trim() || 'Not recorded';
            transitionModalElement.querySelector('[data-mfo-transition-status]').textContent = row?.dataset.withdrawalStatus || '—';
            transitionModalElement.querySelector('[data-mfo-transition-net]').textContent = row?.cells[5]?.textContent.trim() || 'Not authorized';
            transitionModalElement.querySelector('[data-mfo-transition-message]').textContent = post
                ? 'Posting creates the audited share quantity decrease using the request effective date. It does not record or transfer cash.'
                : 'Approval records an independent checker decision. Actual share holdings remain unchanged until the separate share posting action.';
            transitionButton.querySelector('[data-mfo-transition-label]').textContent = post ? 'Post Shares' : 'Approve Request';
            transitionButton.classList.toggle('mfo-btn-danger', post);
            transitionButton.classList.toggle('cs-btn-primary', !post);
            transitionButton.disabled = false;
            transitionButton.querySelector('[data-mfo-transition-spinner]')?.classList.add('d-none');
            transitionError.classList.add('d-none'); transitionError.textContent = '';
            transitionModal?.show();
        });
    });
    transitionButton?.addEventListener('click', () => {
        if (!activeTransitionForm || transitionButton.disabled) return;
        transitionButton.disabled = true;
        transitionButton.querySelector('[data-mfo-transition-spinner]')?.classList.remove('d-none');
        activeTransitionForm.dataset.mfoTransitionConfirmed = 'true';
        activeTransitionForm.requestSubmit();
    });

    const rejectModalElement = document.querySelector('[data-mfo-reject-modal]');
    const rejectModal = rejectModalElement && window.bootstrap ? bootstrap.Modal.getOrCreateInstance(rejectModalElement) : null;
    const rejectReason = rejectModalElement?.querySelector('[data-mfo-reject-reason]');
    const rejectSubmit = rejectModalElement?.querySelector('[data-mfo-reject-submit]');
    const rejectError = rejectModalElement?.querySelector('[data-mfo-reject-error]');
    const rejectCount = rejectModalElement?.querySelector('[data-mfo-reject-count]');
    let activeRejectForm = null;
    const updateRejectCount = () => { if (rejectCount && rejectReason) rejectCount.textContent = `${rejectReason.value.length} / 500`; };
    const setRejectError = message => {
        if (!rejectReason || !rejectError) return;
        rejectError.textContent = message;
        rejectReason.classList.toggle('is-invalid', Boolean(message));
        rejectReason.setAttribute('aria-invalid', message ? 'true' : 'false');
    };
    const populateRejectModal = form => {
        activeRejectForm = form;
        const row = form.closest('tr');
        if (!row || !rejectModalElement) return;
        rejectModalElement.querySelector('[data-mfo-reject-id]').textContent = `WR-${row.dataset.withdrawalRowId || form.dataset.withdrawalId}`;
        rejectModalElement.querySelector('[data-mfo-reject-employee]').textContent = row.dataset.withdrawalEmployee || 'Employee information unavailable';
        rejectModalElement.querySelector('[data-mfo-reject-category]').textContent = row.dataset.withdrawalCategory || '—';
        rejectModalElement.querySelector('[data-mfo-reject-quantity]').textContent = row.dataset.withdrawalQuantity || '—';
        const status = row.dataset.withdrawalStatus || '—';
        const statusBadge = rejectModalElement.querySelector('[data-mfo-reject-status]');
        statusBadge.textContent = status; statusBadge.className = `cs-status cs-status-${status.toLowerCase()}`;
        setRejectError(''); updateRejectCount();
    };
    workspace.querySelectorAll('[data-mfo-withdrawal-reject]').forEach(form => {
        form.addEventListener('submit', event => {
            if (form.dataset.mfoRejectConfirmed !== 'true') { event.preventDefault(); return; }
            form.dataset.mfoRejectConfirmed = 'false';
            const opener = form.querySelector('[data-mfo-open-withdrawal-reject]');
            if (opener) opener.disabled = true;
        });
        form.querySelector('[data-mfo-open-withdrawal-reject]')?.addEventListener('click', () => {
            populateRejectModal(form); if (rejectReason) rejectReason.value = '';
            updateRejectCount(); setRejectError(''); rejectModal?.show();
        });
    });
    rejectReason?.addEventListener('input', () => { setRejectError(''); updateRejectCount(); });
    rejectModalElement?.addEventListener('shown.bs.modal', () => rejectReason?.focus());
    rejectSubmit?.addEventListener('click', () => {
        if (!activeRejectForm || !rejectReason || rejectSubmit.disabled) return;
        const reason = rejectReason.value.trim();
        if (!reason) { setRejectError('Enter a rejection reason.'); rejectReason.focus(); return; }
        if (reason.length > 500) { setRejectError('The reason must be 500 characters or fewer.'); rejectReason.focus(); return; }
        const hiddenReason = activeRejectForm.elements.namedItem('reason');
        if (!hiddenReason) { setRejectError('The rejection form is unavailable. Reload and try again.'); return; }
        hiddenReason.value = reason;
        rejectSubmit.disabled = true;
        rejectSubmit.querySelector('[data-mfo-reject-spinner]')?.classList.remove('d-none');
        rejectSubmit.querySelector('[data-mfo-reject-label]').textContent = 'Rejecting...';
        activeRejectForm.dataset.mfoRejectConfirmed = 'true'; activeRejectForm.requestSubmit();
    });
    rejectModalElement?.addEventListener('hidden.bs.modal', () => {
        if (rejectSubmit?.disabled) return;
        activeRejectForm = null; if (rejectReason) rejectReason.value = '';
        updateRejectCount(); setRejectError('');
    });
    const reopenRejectId = rejectModalElement?.dataset.reopenRequestId;
    if (reopenRejectId && rejectModal) {
        const retryForm = [...workspace.querySelectorAll('[data-mfo-withdrawal-reject]')]
            .find(form => form.dataset.withdrawalId === reopenRejectId);
        if (retryForm) {
            populateRejectModal(retryForm);
            if (rejectReason) rejectReason.value = rejectModalElement.querySelector('[data-mfo-reject-server-error]') ? rejectReason.value : '';
            updateRejectCount(); rejectModal.show();
        }
    }
    const cancelModalElement = document.querySelector('[data-mfo-cancel-modal]');
    const cancelModal = cancelModalElement && window.bootstrap
        ? bootstrap.Modal.getOrCreateInstance(cancelModalElement) : null;
    const cancelReason = cancelModalElement?.querySelector('[data-mfo-cancel-reason]');
    const cancelError = cancelModalElement?.querySelector('[data-mfo-cancel-error]');
    const cancelCount = cancelModalElement?.querySelector('[data-mfo-cancel-count]');
    const cancelButton = cancelModalElement?.querySelector('[data-mfo-cancel-submit]');
    let activeCancelForm = null;
    const updateCancelCount = () => {
        if (cancelCount && cancelReason) cancelCount.textContent = `${cancelReason.value.length} / 500`;
    };
    const setCancelError = message => {
        if (!cancelReason || !cancelError) return;
        cancelError.textContent = message;
        cancelReason.classList.toggle('is-invalid', Boolean(message));
        cancelReason.setAttribute('aria-invalid', message ? 'true' : 'false');
    };
    const populateCancelModal = form => {
        activeCancelForm = form;
        const row = form.closest('tr');
        if (!row || !cancelModalElement) return;
        cancelModalElement.querySelector('[data-mfo-cancel-request-id]').textContent = `WR-${row.dataset.withdrawalRowId || form.dataset.withdrawalId}`;
        cancelModalElement.querySelector('[data-mfo-cancel-employee]').textContent = row.dataset.withdrawalEmployee || 'Employee information unavailable';
        cancelModalElement.querySelector('[data-mfo-cancel-category]').textContent = row.dataset.withdrawalCategory || '—';
        cancelModalElement.querySelector('[data-mfo-cancel-quantity]').textContent = row.dataset.withdrawalQuantity || '—';
        const status = cancelModalElement.querySelector('[data-mfo-cancel-status]');
        status.textContent = row.dataset.withdrawalStatus || '—';
        status.className = `cs-status cs-status-${(row.dataset.withdrawalStatus || '').toLowerCase()}`;
        setCancelError('');
        updateCancelCount();
    };
    workspace.querySelectorAll('[data-mfo-withdrawal-cancel]').forEach(form => {
        form.addEventListener('submit', event => {
            if (form.dataset.mfoCancelConfirmed === 'true') {
                form.dataset.mfoCancelConfirmed = 'false';
                form.dataset.mfoCancelSubmitting = 'true';
                const opener = form.querySelector('[data-mfo-open-cancel-withdrawal]');
                if (opener) opener.disabled = true;
                return;
            }
            event.preventDefault();
        });
        form.querySelector('[data-mfo-open-cancel-withdrawal]')?.addEventListener('click', () => {
            populateCancelModal(form);
            if (cancelReason) cancelReason.value = '';
            updateCancelCount();
            cancelModal?.show();
        });
    });
    cancelReason?.addEventListener('input', () => { setCancelError(''); updateCancelCount(); });
    cancelModalElement?.addEventListener('shown.bs.modal', () => cancelReason?.focus());
    cancelButton?.addEventListener('click', () => {
        if (!activeCancelForm || !cancelReason || !cancelButton) return;
        const reason = cancelReason.value.trim();
        if (!reason) { setCancelError('Enter a cancellation reason.'); cancelReason.focus(); return; }
        if (reason.length > 500) { setCancelError('The reason must be 500 characters or fewer.'); cancelReason.focus(); return; }
        const hiddenReason = activeCancelForm.elements.namedItem('reason');
        if (!hiddenReason) { setCancelError('The cancellation form is unavailable. Reload the page and try again.'); return; }
        if (activeCancelForm.dataset.mfoCancelSubmitting === 'true' || cancelButton.disabled) return;
        hiddenReason.value = reason;
        cancelButton.disabled = true;
        cancelButton.querySelector('[data-mfo-cancel-spinner]')?.classList.remove('d-none');
        const label = cancelButton.querySelector('[data-mfo-cancel-label]');
        if (label) label.textContent = 'Cancelling...';
        activeCancelForm.dataset.mfoCancelConfirmed = 'true';
        activeCancelForm.requestSubmit();
    });
    cancelModalElement?.addEventListener('hidden.bs.modal', () => {
        if (cancelButton?.disabled) return;
        activeCancelForm = null;
        if (cancelReason) cancelReason.value = '';
        updateCancelCount();
        setCancelError('');
    });
    const reopenCancelId = cancelModalElement?.dataset.reopenRequestId;
    if (reopenCancelId && cancelModal) {
        const retryForm = [...workspace.querySelectorAll('[data-mfo-withdrawal-cancel]')]
            .find(form => form.dataset.withdrawalId === reopenCancelId);
        if (retryForm) {
            populateCancelModal(retryForm);
            cancelReason?.focus({preventScroll:true});
            cancelModal.show();
        }
    }
    const valuationModalElement = document.querySelector('[data-mfo-valuation-modal]');
    const valuationModal = valuationModalElement && window.bootstrap
        ? bootstrap.Modal.getOrCreateInstance(valuationModalElement) : null;
    const valuationForm = valuationModalElement?.querySelector('[data-mfo-valuation-form]');
    const valuationError = valuationModalElement?.querySelector('[data-mfo-valuation-error]');
    const valuationResult = valuationModalElement?.querySelector('[data-mfo-valuation-result]');
    const valuationSubmit = valuationModalElement?.querySelector('[data-mfo-valuation-submit]');
    const valuationUnit = valuationModalElement?.querySelector('#mfo-valuation-unit');
    const valuationDeduction = valuationModalElement?.querySelector('#mfo-valuation-deduction');
    const valuationDate = valuationModalElement?.querySelector('#mfo-valuation-date');
    const valuationReason = valuationModalElement?.querySelector('#mfo-valuation-reason');
    const valuationText = (selector, value) => {
        const element = valuationModalElement?.querySelector(selector);
        if (element) element.textContent = value == null || value === '' ? 'Not recorded' : value;
    };
    const formatBdtText = value => {
        if (value == null || value === '') return 'Not recorded';
        const parts = String(value).split('.');
        let whole;
        try { whole = BigInt(parts[0] || '0').toLocaleString('en-US'); }
        catch { return 'BDT ' + String(value); }
        return `BDT ${whole}.${(parts[1] || '').padEnd(2, '0').slice(0, 2)}`;
    };
    workspace.querySelectorAll('[data-mfo-open-valuation-preview]').forEach(button => button.addEventListener('click', () => {
        if (!valuationModalElement || !valuationForm) return;
        valuationForm.dataset.previewUrl = button.dataset.previewUrl;
        valuationForm.elements.namedItem('version').value = button.dataset.requestVersion;
        valuationText('[data-mfo-valuation-employee]', button.dataset.requestEmployee);
        valuationText('[data-mfo-valuation-request]', `WR-${button.dataset.requestId}`);
        valuationText('[data-mfo-valuation-category]', button.dataset.requestCategory);
        valuationText('[data-mfo-valuation-quantity]', button.dataset.requestQuantity);
        valuationText('[data-mfo-valuation-available]', 'Calculated when preview is requested');
        valuationText('[data-mfo-valuation-reference]', 'Calculated for valuation date');
        if (valuationUnit) valuationUnit.value = '';
        if (valuationDeduction) valuationDeduction.value = '0.00';
        if (valuationDate) valuationDate.value = button.dataset.requestEffectiveDate || '';
        if (valuationReason) valuationReason.value = '';
        if (valuationError) { valuationError.textContent = ''; valuationError.classList.add('d-none'); }
        valuationResult?.classList.add('d-none');
        valuationModal?.show();
    }));
    valuationModalElement?.addEventListener('shown.bs.modal', () => valuationUnit?.focus());
    valuationForm?.addEventListener('submit', async event => {
        event.preventDefault();
        if (!valuationForm.checkValidity()) { valuationForm.reportValidity(); return; }
        if (!valuationForm.dataset.previewUrl || !valuationSubmit) return;
        if (valuationError) { valuationError.textContent = ''; valuationError.classList.add('d-none'); }
        valuationSubmit.disabled = true;
        valuationModalElement.querySelector('[data-mfo-valuation-spinner]')?.classList.remove('d-none');
        const label = valuationModalElement.querySelector('[data-mfo-valuation-label]');
        if (label) label.textContent = 'Calculating...';
        try {
            const body = new URLSearchParams(new FormData(valuationForm));
            const response = await fetch(valuationForm.dataset.previewUrl, {
                method:'POST', credentials:'same-origin',
                headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8','Accept':'application/json'},
                body
            });
            if (!response.ok) {
                const message = (await response.text()).trim();
                throw new Error(message || 'The preview could not be calculated. Refresh and try again.');
            }
            const preview = await response.json();
            valuationText('[data-mfo-valuation-available]', `${preview.availableQuantity} shares`);
            valuationText('[data-mfo-valuation-reference]', formatBdtText(preview.referenceValuePerShare));
            valuationText('[data-mfo-result-reference]', formatBdtText(preview.referenceValuePerShare));
            valuationText('[data-mfo-result-gross]', formatBdtText(preview.grossAmount));
            valuationText('[data-mfo-result-deduction]', formatBdtText(preview.proposedDeduction));
            valuationText('[data-mfo-result-net]', formatBdtText(preview.netAmount));
            valuationText('[data-mfo-result-basis]', `Proposed rate ${formatBdtText(preview.proposedValuePerShare)} × ${preview.requestedQuantity} share${preview.requestedQuantity === 1 ? '' : 's'}; valuation date ${preview.valuationDate}. Reason: ${preview.valuationReason}.`);
            valuationResult?.classList.remove('d-none');
        } catch (error) {
            if (valuationError) { valuationError.textContent = error.message || 'The preview could not be calculated. Please try again.'; valuationError.classList.remove('d-none'); }
        } finally {
            valuationSubmit.disabled = false;
            valuationModalElement.querySelector('[data-mfo-valuation-spinner]')?.classList.add('d-none');
            if (label) label.textContent = 'Calculate Preview';
        }
    });
    document.querySelector('[data-mfo-withdrawal-request-form]')?.addEventListener('submit', event => {
        const category = withdrawalCategory?.selectedOptions[0];
        const quantity = Number(withdrawalQuantity?.value);
        const available = Number(category?.dataset.available || 0);
        if (!Number.isSafeInteger(quantity) || quantity < 1 || quantity > available) { event.preventDefault(); withdrawalQuantity?.reportValidity(); return; }
        const button = event.currentTarget.querySelector('button[type="submit"]'); if (button) button.disabled = true;
    });
    const shareValueInput = document.getElementById('mfo-new-share-value');
    const shareValueTotal = document.getElementById('mfo-new-total-share-value');
    shareValueInput?.addEventListener('input', () => {
        const quantity = Number(shareValueInput.dataset.shareQuantity);
        const value = Number(shareValueInput.value);
        const valid = shareValueInput.value.trim() !== '' && Number.isSafeInteger(quantity) && quantity >= 0
            && Number.isFinite(value) && value > 0 && shareValueInput.checkValidity();
        shareValueTotal.textContent = valid
            ? 'BDT ' + (quantity * value).toLocaleString('en-US', {minimumFractionDigits:2, maximumFractionDigits:2})
            : 'Enter a valid new value.';
    });
    const openDetails = selector => {
        const section = workspace.querySelector(selector);
        if (!section) return;
        if (section.tagName === 'DETAILS') section.open = true;
        let parent = section.parentElement?.closest('details');
        while (parent) { parent.open = true; parent = parent.parentElement?.closest('details'); }
        section.scrollIntoView({block:'start'});
    };
    workspace.querySelectorAll('[data-mfo-open-details]').forEach(button => {
        button.addEventListener('click', () => {
            const panel = button.closest('.offcanvas');
            if (panel && window.bootstrap) {
                panel.addEventListener('hidden.bs.offcanvas', () => openDetails(button.dataset.mfoOpenDetails), {once:true});
                bootstrap.Offcanvas.getOrCreateInstance(panel).hide();
                return;
            }
            openDetails(button.dataset.mfoOpenDetails);
        });
    });
    // Reuse one focused form per action instead of a set of editable forms in every row.
    workspace.querySelectorAll('[data-mfo-bound-open]').forEach(trigger => {
        trigger.addEventListener('click', event => {
            if (stale) { event.preventDefault(); event.stopPropagation(); return; }
            const panel = document.getElementById('mfo-' + trigger.dataset.mfoBoundOpen);
            const form = panel?.querySelector('[data-mfo-bound-form]');
            const target = new URL(trigger.dataset.postUrl || '', location.href);
            const permitted = /\/admin\/contribution-workspace\/(?:credits\/(?:allocations\/)?\d+\/(?:apply|reverse)|payments\/\d+\/\d+\/reverse)$/.test(target.pathname);
            if (!form || target.origin !== location.origin || !permitted) {
                event.preventDefault(); event.stopPropagation(); return;
            }
            form.reset();
            form.action = target.href;
            form.dataset.boundUrl = target.href;
            const fields = form.querySelector('[data-mfo-bound-fields]');
            fields.disabled = false;
            const button = form.querySelector('[data-mfo-bound-submit]');
            button.disabled = false;
            panel.querySelector('[data-mfo-bound-reference]').textContent = trigger.dataset.reference;
            panel.querySelector('[data-mfo-bound-amount]').textContent = trigger.dataset.amountLabel || '';
            panel.querySelector('[data-mfo-bound-available]').textContent = trigger.dataset.availableLabel || '';
            const maximum = panel.querySelector('[data-mfo-bound-maximum]');
            if (maximum) {
                maximum.textContent = trigger.dataset.maximumLabel || '';
                maximum.hidden = !trigger.dataset.maximumLabel;
            }
            const fullOutstanding = panel.querySelector('[data-mfo-credit-full]');
            if (fullOutstanding) {
                fullOutstanding.dataset.amount = trigger.dataset.fullOutstanding || '';
                fullOutstanding.hidden = !trigger.dataset.fullOutstanding;
            }
            panel.querySelector('[data-mfo-bound-period]').textContent = trigger.dataset.period ? 'Contribution period: ' + trigger.dataset.period : '';
            const amount = form.elements.namedItem('amount');
            if (amount) amount.max = trigger.dataset.limit;
            const date = form.elements.namedItem('appliedDate') || form.elements.namedItem('reversalDate');
            if (date) { date.min = trigger.dataset.minDate; date.setCustomValidity(''); }
        });
    });
    workspace.querySelectorAll('[data-mfo-credit-full]').forEach(button => button.addEventListener('click', () => {
        if (stale || !button.dataset.amount) return;
        const amount = button.closest('form')?.elements.namedItem('amount');
        if (!amount) return;
        amount.value = button.dataset.amount;
        amount.dispatchEvent(new Event('input', {bubbles:true}));
        amount.focus();
    }));
    workspace.querySelectorAll('[data-mfo-bound-form]').forEach(form => {
        form.addEventListener('submit', event => {
            if (stale || !form.dataset.boundUrl || form.action !== form.dataset.boundUrl) event.preventDefault();
        }, true);
    });
    context?.addEventListener('input', () => {
        if (stale) return;
        stale = true;
        workspace.classList.add('mfo-stale');
        const note = document.createElement('p');
        note.className = 'mfo-stale-notice'; note.role = 'status';
        note.textContent = 'Context changed. Apply Context to load matching records before performing a financial action.';
        context.after(note);
        workspace.querySelectorAll('form[method="post"] button, [data-bs-toggle="offcanvas"]').forEach(button => { button.disabled = true; });
        workspace.querySelectorAll('form[method="post"]').forEach(form => form.addEventListener('submit', event => { if (stale) event.preventDefault(); }));
    });
    const change = document.getElementById('adjustQty');
    const current = document.getElementById('mfo-current-quantity');
    const result = document.getElementById('mfo-result-quantity');
    change?.addEventListener('input', () => {
        const delta = Number(change.value), before = Number(current?.textContent);
        const valid = change.value.trim() !== '' && Number.isSafeInteger(delta) && Number.isSafeInteger(before) && delta !== 0 && before + delta >= 0;
        change.setCustomValidity(valid ? '' : 'Enter a non-zero whole change that leaves a non-negative quantity.');
        if (result) result.textContent = valid ? String(before + delta) : 'Invalid quantity';
    });
    workspace.querySelectorAll('.offcanvas').forEach(panel => {
        panel.addEventListener('shown.bs.offcanvas', () => panel.querySelector('input:not([type="hidden"]):not([readonly]),select')?.focus());
    });
    const action = new URLSearchParams(location.search).get('mfoAction');
    if (['adjust','rate','value','payment','withdrawal'].includes(action) && window.bootstrap) {
        const panel = document.getElementById('mfo-' + action);
        if (panel) bootstrap.Offcanvas.getOrCreateInstance(panel).show();
    }
    if ((action === 'payment' && !document.getElementById('mfo-payment')) || (!action && new URLSearchParams(location.search).has('contributionId'))) { const history=workspace.querySelector('#mfo-collections .mfo-history'); if(history) { history.open=true; history.scrollIntoView({block:'start'}); } }
    if (action === 'credit') openDetails(new URLSearchParams(location.search).get('creditHistory') === 'allocations' ? '#mfo-credit-allocation-history' : '#mfo-credit-ledger');
    if (action === 'history') {
        workspace.querySelectorAll('.mfo-selected-holding .mfo-history').forEach(section => { section.open = true; });
        workspace.querySelector('.mfo-selected-holding')?.scrollIntoView({block:'start'});
    }
})();
