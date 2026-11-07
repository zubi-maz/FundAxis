(() => {
  'use strict';
  const panel = document.querySelector('.cs-yr-panel');
  if (!panel) return;
  const modal = document.getElementById('cs-yr-modal');
  const content = document.getElementById('cs-yr-detail-body');
  const year = document.getElementById('cs-yr-year');
  year?.addEventListener('change', () => {
    if (year.validity.valid) document.getElementById('cs-yr-filters').requestSubmit();
  });
  let pending;
  panel.addEventListener('click', async event => {
    const button = event.target.closest('.cs-yr-details');
    if (!button) return;
    if (pending) pending.abort();
    pending = new AbortController();
    content.textContent = 'Loading annual contribution details...';
    if (!window.bootstrap) { window.location.assign(button.dataset.url); return; }
    bootstrap.Modal.getOrCreateInstance(modal).show();
    try {
      const response = await fetch(button.dataset.url, {signal: pending.signal, credentials: 'same-origin', headers: {'X-Requested-With': 'XMLHttpRequest'}});
      if (!response.ok || response.redirected) throw new Error('Unable to load details. Refresh and try again.');
      const html = await response.text();
      const parsed = new DOMParser().parseFromString(html, 'text/html');
      const details = parsed.querySelector('.cs-yr-detail-content');
      if (!details) throw new Error('Unable to load details. Refresh and try again.');
      content.replaceChildren(details);
    } catch (error) { if (error.name !== 'AbortError') content.textContent = 'Unable to load details. Refresh and try again.'; }
  });
  modal.addEventListener('hidden.bs.modal', () => { if (pending) pending.abort(); });
})();
