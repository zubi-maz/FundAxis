(() => {
  'use strict';
  const panel = document.querySelector('.cs-st-panel');
  if (!panel) return;
  const form = document.getElementById('cs-st-generator');
  const type = document.getElementById('cs-st-type');
  const id = document.getElementById('cs-st-employee-id');
  const verified = document.getElementById('cs-st-verified');
  const status = document.getElementById('cs-st-lookup-status');
  const generate = document.getElementById('cs-st-generate');
  const print = document.getElementById('cs-st-print');
  const start = form.elements.startDate;
  const end = form.elements.endDate;
  const yearGroup = panel.querySelector('[data-cs-st-year]');
  const monthGroup = panel.querySelector('[data-cs-st-month]');
  let timer, pending, revision = 0;
  const normalizeEmployeeId = value => /^\d+$/.test(value.trim()) ? `EMP-${value.trim()}` : value.trim();

  function updateType() {
    const monthly = type.value === 'MONTHLY';
    const custom = type.value === 'CUSTOM';
    yearGroup.hidden = custom;
    yearGroup.querySelectorAll('input').forEach(input => { input.disabled = custom; input.required = !custom; });
    monthGroup.hidden = !monthly;
    monthGroup.querySelectorAll('select').forEach(input => { input.disabled = !monthly; input.required = monthly; });
    for (const [selector, visible] of [['[data-cs-st-custom]', custom]]) {
      panel.querySelectorAll(selector).forEach(group => {
        group.hidden = !visible;
        group.querySelectorAll('input').forEach(input => { input.disabled = !visible; input.required = visible; input.setCustomValidity(''); });
      });
    }
  }
  updateType();
  const originalSelection = new URLSearchParams(new FormData(form)).toString();
  function updateDraft() {
    if (!print) return;
    const dirty = new URLSearchParams(new FormData(form)).toString() !== originalSelection;
    panel.dataset.dirty = String(dirty);
    panel.querySelector('.cs-st-draft-notice').hidden = !dirty;
    panel.querySelectorAll('[data-cs-report-export]').forEach(link => {
      if (dirty) link.setAttribute('aria-disabled', 'true');
      else if (!link.hasAttribute('aria-busy')) link.removeAttribute('aria-disabled');
    });
    print.disabled = dirty;
  }
  type.addEventListener('change', updateType);
  form.addEventListener('input', updateDraft);
  form.addEventListener('change', updateDraft);
  start.addEventListener('input', () => { start.setCustomValidity(''); end.setCustomValidity(''); });
  end.addEventListener('input', () => end.setCustomValidity(''));

  async function lookup() {
    clearTimeout(timer);
    pending?.abort();
    const current = ++revision;
    const typedValue = id.value.trim();
    const value = normalizeEmployeeId(typedValue);
    verified.hidden = true;
    status.textContent = '';
    if (!value) return;
    pending = new AbortController();
    status.textContent = 'Checking employee...';
    verified.setAttribute('aria-busy', 'true');
    try {
      const url = new URL(id.dataset.lookupUrl, window.location.origin);
      url.searchParams.set('employeeId', value);
      const response = await fetch(url, {signal: pending.signal, credentials: 'same-origin'});
      if (current !== revision) return;
      if (response.status === 404) { status.textContent = 'Employee not found. Check the business Employee ID.'; return; }
      if (!response.ok || response.redirected) throw new Error();
      const data = await response.json();
      if (current !== revision || normalizeEmployeeId(id.value) !== value) return;
      if (!data.employeeId || !data.fullName) throw new Error();
      for (const [element, value] of Object.entries({
        'cs-st-employee-name': data.fullName,
        'cs-st-verified-id': data.employeeId,
        'cs-st-verified-designation': data.designation || 'Not recorded',
        'cs-st-verified-status': data.employeeStatus || (data.active ? 'ACTIVE' : 'Not active'),
        'cs-st-verified-joined': data.fundJoinDate || 'Not recorded',
        'cs-st-verified-exited': data.fundExitDate || 'Not recorded'
      })) document.getElementById(element).textContent = value;
      const employeeStatus = document.getElementById('cs-st-verified-status');
      employeeStatus.classList.toggle('is-active', data.employeeStatus === 'ACTIVE');
      employeeStatus.classList.toggle('is-inactive', data.employeeStatus && data.employeeStatus !== 'ACTIVE');
      verified.hidden = false;
      status.textContent = 'Employee verified. Choose a period, then generate the statement.';
    } catch (error) {
      if (error.name !== 'AbortError' && current === revision) status.textContent = 'Employee lookup is unavailable. The Employee ID will be checked when you generate the statement.';
    } finally {
      if (current === revision) verified.removeAttribute('aria-busy');
    }
  }
  id.addEventListener('input', () => {
    clearTimeout(timer); pending?.abort(); revision++;
    verified.hidden = true; status.textContent = '';
    if (id.value.trim()) timer = setTimeout(lookup, 350);
  });
  document.getElementById('cs-st-find').addEventListener('click', lookup);

  function validatePeriod() {
    if (type.value !== 'CUSTOM') return true;
    start.setCustomValidity(''); end.setCustomValidity('');
    if (!start.value || !end.value) return form.reportValidity();
    const [sy, sm, sd] = start.value.split('-').map(Number);
    const [ey, em, ed] = end.value.split('-').map(Number);
    if (sd !== 1) start.setCustomValidity('Select the first day of the starting month.');
    if (ed !== new Date(Date.UTC(ey, em, 0)).getUTCDate()) end.setCustomValidity('Select the last day of the ending month.');
    if (start.value > end.value) end.setCustomValidity('End date must be on or after start date.');
    else if ((ey - sy) * 12 + em - sm > 119) end.setCustomValidity('Choose a period of no more than 120 months.');
    return form.reportValidity();
  }
  form.addEventListener('submit', event => {
    if (generate.disabled || !validatePeriod()) { event.preventDefault(); return; }
    generate.disabled = true;
    generate.setAttribute('aria-busy', 'true');
    generate.querySelector('span').textContent = 'Generating statement...';
  });
  window.addEventListener('pageshow', () => {
    generate.disabled = false; generate.removeAttribute('aria-busy');
    generate.querySelector('span').textContent = 'Generate Statement';
  });
  if (print) {
    document.body.classList.add('cs-st-print-mode');
    print.addEventListener('click', () => window.print());
    const creditDetails = panel.querySelectorAll('.cs-st-credit-history');
    window.addEventListener('beforeprint', () => creditDetails.forEach(detail => {
      if (!detail.open) { detail.dataset.openForPrint = 'true'; detail.open = true; }
    }));
    window.addEventListener('afterprint', () => creditDetails.forEach(detail => {
      if (detail.dataset.openForPrint) { detail.open = false; delete detail.dataset.openForPrint; }
    }));
  }
})();
