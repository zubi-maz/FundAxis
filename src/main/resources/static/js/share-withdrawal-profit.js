(() => {
  'use strict';
  const workspace = document.querySelector('.swp-workspace');
  if (!workspace) return;
  const lookup = document.getElementById('swp-lookup-form');
  const form = document.getElementById('swp-request-form');
  const select = document.getElementById('swp-category');
  const quantity = document.getElementById('swp-quantity');
  let pending;
  function availability() {
    if (!select) return;
    const available = Number(select.selectedOptions[0]?.dataset.available || 0);
    quantity.max = String(available); quantity.setCustomValidity('');
    document.getElementById('swp-availability').textContent = select.value ? available + ' unreserved shares available. Availability is rechecked when submitting.' : 'Select a holding to see availability.';
    if (quantity.value && (!Number.isInteger(Number(quantity.value)) || Number(quantity.value) < 1 || Number(quantity.value) > available)) quantity.setCustomValidity('Enter a positive whole quantity within the available shares.');
  }
  select?.addEventListener('change', availability); quantity?.addEventListener('input', availability);
  if (lookup && !lookup.querySelector('button').disabled) lookup.addEventListener('submit', async event => {
    event.preventDefault(); pending?.abort(); pending = new AbortController();
    const message = document.getElementById('swp-lookup-message');
    const button = lookup.querySelector('button');
    const id = document.getElementById('swp-employee-id').value.trim();
    button.disabled = true; message.textContent = 'Loading employee and current reservations...';
    document.getElementById('swp-selected-employee').value = '';
    form.querySelectorAll('select,input:not([type="hidden"]),textarea,button').forEach(input => input.disabled = true);
    document.getElementById('swp-employee-card').hidden = true;
    document.getElementById('swp-holdings').replaceChildren();
    try {
      const url = new URL(lookup.action + '/employee', window.location.origin); url.searchParams.set('employeeId', id);
      const response = await fetch(url, {credentials:'same-origin', signal:pending.signal});
      if (response.redirected) throw new Error('Your session may have expired. Refresh and sign in again.');
      const data = await response.json();
      if (!response.ok) throw new Error(data.message || 'Employee lookup is unavailable.');
      const card = document.getElementById('swp-employee-card');
      for (const [selector,value] of Object.entries({'[data-employee-name]':data.fullName,'[data-employee-id]':data.employeeId,'[data-designation]':data.designation,'[data-member-status]':data.status,'[data-joined]':data.joined,'[data-exited]':data.exited})) card.querySelector(selector).textContent = value || 'Not recorded';
      card.hidden = false; select.replaceChildren(new Option('Select OLD or NEW holding',''));
      for (const holding of data.holdings) {
        const option = new Option(holding.category + ' - ' + holding.available + ' available', holding.category);
        option.dataset.available = holding.available; option.disabled = holding.available <= 0; select.appendChild(option);
        const block = document.createElement('div'); block.className = 'swp-holding';
        const title = document.createElement('strong'); title.textContent = holding.category + ' Shares'; block.appendChild(title);
        const dl = document.createElement('dl');
        for (const [name,value] of Object.entries({'Current quantity':holding.quantity,'Reserved':holding.reserved,'Available':holding.available,'Recorded value/share':holding.recordedValue == null ? 'Not recorded' : 'BDT ' + holding.recordedValue})) {
          const row = document.createElement('div'),dt = document.createElement('dt'),dd = document.createElement('dd');
          dt.textContent = name; dd.textContent = String(value); row.append(dt,dd); dl.appendChild(row);
        }
        block.appendChild(dl); document.getElementById('swp-holdings').appendChild(block);
      }
      document.getElementById('swp-selected-employee').value = data.employeeId;
      if (data.holdings.some(h => h.available > 0)) form.querySelectorAll('select,input:not([type="hidden"]),textarea,button').forEach(input => input.disabled = false);
      message.textContent = data.holdings.length ? 'Employee verified. Choose one holding and review your request.' : 'This employee has no share holdings.';
      const state = new URL(window.location.href); state.searchParams.set('employeeId',data.employeeId); state.searchParams.delete('requestId'); history.replaceState(null,'',state);
      workspace.querySelectorAll('.swp-tabs a').forEach(link => { const destination = new URL(link.href); destination.searchParams.set('employeeId',data.employeeId); link.href = destination; });
      const historySection = document.getElementById('swp-history-section');
      if (historySection) {
        historySection.querySelector('input[name="employeeId"]').value = data.employeeId;
        const previous = historySection.querySelector('.swp-history-results');
        if (previous) { previous.replaceChildren(); const hint = document.createElement('p'); hint.className = 'swp-empty'; hint.textContent = "Employee selection changed. Apply the history filters to load this employee's requests."; previous.appendChild(hint); }
      }
      availability();
    } catch (error) { if (error.name !== 'AbortError') message.textContent = error.message || 'The employee could not be loaded. Please retry.'; }
    finally { button.disabled = false; }
  });
  // Changing the employee invalidates the previously selected holding immediately.
  document.getElementById('swp-employee-id')?.addEventListener('input', () => {
    pending?.abort(); document.getElementById('swp-selected-employee').value = '';
    document.getElementById('swp-employee-card').hidden = true;
    document.getElementById('swp-holdings').replaceChildren();
    const oldDetails = document.getElementById('swp-request-details'); if (oldDetails) oldDetails.hidden = true;
    form.querySelectorAll('select,input:not([type="hidden"]),textarea,button').forEach(input => input.disabled = true);
  });
  workspace.querySelectorAll('form[method="post"]').forEach(post => {
    post.addEventListener('submit', event => {
      if (post === form) availability();
      if (!post.reportValidity() || post.dataset.submitting === 'true') { event.preventDefault(); return; }
      post.dataset.submitting = 'true'; post.setAttribute('aria-busy','true');
      post.querySelectorAll('button[type="submit"]').forEach(button => button.disabled = true);
    });
  });
  window.addEventListener('pageshow', () => {
    workspace.querySelectorAll('form[data-submitting]').forEach(post => {
      delete post.dataset.submitting; post.removeAttribute('aria-busy');
      post.querySelectorAll('button[type="submit"]').forEach(button => button.disabled = post === form && !document.getElementById('swp-selected-employee').value);
    });
  });
})();
