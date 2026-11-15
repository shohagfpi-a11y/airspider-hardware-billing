/* Invoice grid: product search (name / number / barcode), inline product creation, per-line warranty popup,
   server-side totals preview, Cash/Bank choice, save/issue. */
(function () {
  'use strict';
  const S = window.SETTINGS, INIT = window.INITIAL;
  const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
  const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
  const $ = (id) => document.getElementById(id);
  const tbody = $('lines');
  let dueTouched = !!INIT.id;
  let calcTimer = null, calcSeq = 0;
  let acBox = null, acIndex = -1, acItems = [], acRow = null, acQuery = '', searchTimer = null, searchSeq = 0;

  const fmt = (v) => Number(v).toLocaleString('en', { minimumFractionDigits: S.scale, maximumFractionDigits: S.scale });
  const allRows = () => [...tbody.querySelectorAll('tr')];
  const isBlank = (tr) => !tr.querySelector('.desc').value.trim() && !tr.querySelector('.price').value.trim() && !tr.dataset.itemId;
  const usedRows = () => allRows().filter((tr) => !isBlank(tr));

  function api(method, url, body) {
    const h = { 'Content-Type': 'application/json', 'Accept': 'application/json' };
    if (csrfToken) h[csrfHeader] = csrfToken;
    return fetch(url, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body), credentials: 'same-origin' })
      .then(async (r) => {
        let data = null; try { data = await r.json(); } catch (e) { /* no body */ }
        if (!r.ok) throw new Error((data && data.error) || ('Request failed (' + r.status + ')'));
        return data;
      });
  }
  function showError(msg) { const el = $('formError'); el.textContent = msg; el.classList.remove('d-none'); window.scrollTo({ top: 0, behavior: 'smooth' }); }
  const clearError = () => $('formError').classList.add('d-none');

  function el(tag, attrs, children) {
    const e = document.createElement(tag);
    Object.entries(attrs || {}).forEach(([k, v]) => { if (k === 'class') e.className = v; else if (v !== undefined) e.setAttribute(k, v); });
    (children || []).forEach((c) => e.append(c));
    return e;
  }

  // ------------------------------------------------------------------ rows
  function addRow(data, focus) {
    data = data || {};
    const tr = el('tr');
    tr.dataset.itemId = data.itemId || '';
    tr.dataset.catalogPrice = '';
    tr.war = { on: !!data.warrantyEnabled, dur: data.warrantyDuration || '', unit: data.warrantyUnit || 'MONTHS', terms: data.warrantyTerms || '' };

    const no = el('td', { class: 'ln-no' });
    const desc = el('input', { class: 'form-control desc', placeholder: 'Product name, number or barcode...', autocomplete: 'off', enterkeyhint: 'next' });
    desc.value = data.description || '';
    const codeBadge = el('span', { class: 'badge text-bg-light border code-badge' }); codeBadge.textContent = data.itemCode || '';
    codeBadge.style.display = data.itemCode ? '' : 'none';
    tr.dataset.serial = data.serialNo || '';
    const serialTag = el('span', { class: 'serial-tag' });
    const warOn = el('input', { type: 'checkbox', class: 'form-check-input warOn', id: 'w' + Math.random().toString(36).slice(2) }); warOn.checked = tr.war.on;
    const warLbl = el('label', { class: 'form-check-label small', for: warOn.id }); warLbl.textContent = 'Warranty';
    const warSum = el('button', { type: 'button', class: 'btn btn-link btn-sm p-0 warSummary' });
    const updPrice = el('button', { type: 'button', class: 'btn btn-sm btn-outline-secondary updPrice py-0', title: 'Update the catalog price from this line', style: 'display:none' });
    updPrice.textContent = 'Set as catalog price';
    const meta = el('div', { class: 'prod-meta' }, [codeBadge, serialTag, updPrice]);
    // warranty: its own column after Tax %, before Total
    const warCell = el('td', { class: 'war-cell', 'data-label': 'Warranty' }, [el('span', { class: 'form-check m-0 war-check' }, [warOn, warLbl]), warSum]);
    const prod = el('td', { class: 'prod', style: 'position:relative' }, [desc, meta]);

    const qty = el('input', { class: 'form-control num qty', inputmode: 'decimal', enterkeyhint: 'next' }); qty.value = data.quantity ?? '1';
    const unit = el('input', { class: 'form-control unit' }); unit.value = data.unit || 'pcs';
    const price = el('input', { class: 'form-control num price', inputmode: 'decimal' }); price.value = data.unitPrice ?? '';
    const disc = el('input', { class: 'form-control num disc', inputmode: 'decimal' }); disc.value = data.discountPercent && Number(data.discountPercent) ? data.discountPercent : '';
    const tax = el('input', { class: 'form-control num tax', inputmode: 'decimal' }); tax.value = data.taxRate ?? S.defaultTaxRate;
    const total = el('td', { class: 'total fw-semibold', 'data-label': 'Total' }); total.textContent = fmt(0);
    const rm = el('button', { type: 'button', class: 'btn btn-sm btn-outline-danger rm', title: 'Remove line', 'aria-label': 'Remove line' }); rm.textContent = '×';

    tr.append(no, prod,
      el('td', { 'data-label': 'Qty' }, [qty]), el('td', { 'data-label': 'Unit' }, [unit]),
      el('td', { 'data-label': 'Price' }, [price]), el('td', { 'data-label': 'Disc %' }, [disc]),
      el('td', { 'data-label': 'Tax %' }, [tax]), warCell, total, el('td', { class: 'act' }, [rm]));
    tbody.append(tr);

    refreshWar(tr);
    refreshSerial(tr);
    [qty, price, disc, tax].forEach((i) => i.addEventListener('input', () => { scheduleCalc(); toggleUpd(tr); }));
    [qty, price, disc, tax].forEach((i) => i.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); nextProduct(tr); } }));
    rm.addEventListener('click', () => { tr.remove(); if (!allRows().length) addRow(null, true); renumber(); scheduleCalc(); });
    updPrice.addEventListener('click', () => updateCatalogPrice(tr));
    warOn.addEventListener('change', () => { if (warOn.checked) openWarranty(tr); else { tr.war = { on: false, dur: '', unit: 'MONTHS', terms: '' }; refreshWar(tr); } });
    warSum.addEventListener('click', () => openWarranty(tr));
    desc.addEventListener('input', () => { closeAc(); acRow = tr; scheduleSearch(desc.value); });
    desc.addEventListener('keydown', (e) => descKeys(e, tr));
    desc.addEventListener('blur', () => setTimeout(closeAc, 180));
    tr.addEventListener('keydown', (e) => { if (e.ctrlKey && e.key === 'Enter') { e.preventDefault(); addRow(null, true); } });
    if (data.itemId) tr.dataset.catalogPrice = data.catalogPrice ?? '';
    renumber();
    if (focus) desc.focus();
    return tr;
  }

  function refreshSerial(tr) {
    const t = tr.querySelector('.serial-tag'); const v = tr.dataset.serial;
    t.textContent = v ? 'S/N ' + v : ''; t.style.display = v ? '' : 'none';
  }

  function renumber() { allRows().forEach((tr, i) => { tr.querySelector('.ln-no').textContent = i + 1; }); }

  /** After a line is done the cursor always goes to the NEXT line's product box. */
  function nextProduct(tr) {
    const rows = allRows(); const i = rows.indexOf(tr);
    if (i === rows.length - 1) { if (!isBlank(tr)) addRow(null, true); else tr.querySelector('.desc').focus(); }
    else rows[i + 1].querySelector('.desc').focus();
  }

  function toggleUpd(tr) {
    const b = tr.querySelector('.updPrice'); const cat = tr.dataset.catalogPrice; const cur = tr.querySelector('.price').value;
    b.style.display = tr.dataset.itemId && cat !== '' && cur !== '' && Number(cat) !== Number(cur) ? '' : 'none';
  }
  function updateCatalogPrice(tr) {
    const id = tr.dataset.itemId, price = tr.querySelector('.price').value;
    if (!confirm('Change the catalog price of ' + tr.querySelector('.code-badge').textContent + ' to ' + price + '? This affects future invoices only.')) return;
    api('POST', '/api/items/' + id + '/price', { price: price }).then((it) => { tr.dataset.catalogPrice = it.price; toggleUpd(tr); }).catch((e) => showError(e.message));
  }

  // ------------------------------------------------------------------ warranty (small checkbox -> popup)
  const warModalEl = $('warModal'); const warModal = new bootstrap.Modal(warModalEl);
  let warRow = null, warSaved = false;
  function refreshWar(tr) {
    const w = tr.war; const sum = tr.querySelector('.warSummary');
    tr.querySelector('.warOn').checked = w.on;
    let unitWord = (w.unit || '').toLowerCase();
    if (Number(w.dur) === 1 && unitWord.endsWith('s')) unitWord = unitWord.slice(0, -1);
    sum.textContent = w.on ? ('✓ ' + w.dur + ' ' + unitWord) : '';              // short in the narrow column
    sum.title = w.on ? ((w.terms ? w.terms + ' - ' : '') + 'click to edit') : '';    // full terms on hover; printed in full on the invoice
    sum.style.display = w.on ? '' : 'none';
  }
  function openWarranty(tr) {
    warRow = tr; warSaved = false;
    let def = {}; try { def = JSON.parse(tr.dataset.itemWar || '{}'); } catch (e) { /* ignore */ }
    const w = tr.war.on ? tr.war : { dur: def.dur || '', unit: def.unit || 'MONTHS', terms: def.terms || '' };
    $('wmDur').value = w.dur; $('wmUnit').value = w.unit; $('wmTerms').value = w.terms; $('wmErr').classList.add('d-none');
    warModal.show();
  }
  warModalEl.addEventListener('shown.bs.modal', () => $('wmDur').focus());
  $('wmSave').addEventListener('click', () => {
    const dur = parseInt($('wmDur').value, 10);
    if (!dur || dur < 1) { $('wmErr').classList.remove('d-none'); return; }
    warRow.war = { on: true, dur: dur, unit: $('wmUnit').value, terms: $('wmTerms').value.trim() };
    warSaved = true; refreshWar(warRow); warModal.hide();
  });
  warModalEl.addEventListener('hidden.bs.modal', () => {
    if (!warSaved && warRow && !warRow.war.on) refreshWar(warRow);       // cancelled: stays unticked
    else if (!warSaved && warRow) refreshWar(warRow);                    // cancelled while editing: keep old values
    warRow = null;
  });

  // ------------------------------------------------------------------ product search
  function scheduleSearch(q) {
    clearTimeout(searchTimer); q = q.trim();
    if (!q) { closeAc(); return; }
    searchTimer = setTimeout(() => {
      const seq = ++searchSeq;
      api('GET', '/api/items/search?q=' + encodeURIComponent(q)).then((items) => { if (seq === searchSeq) openAc(items, q); }).catch(() => {});
    }, 130);
  }
  const isExact = (it, q) => it.code.toLowerCase() === q.toLowerCase() || (it.barcode && it.barcode === q)
    || (/^[0-9]{1,5}$/.test(q) && it.code === 'P' + q.padStart(5, '0'));

  function openAc(items, q) {
    closeAc(); acItems = items; acQuery = q; acIndex = items.length ? 0 : -1;
    acBox = el('div', { class: 'ac-list' });
    items.forEach((it, i) => {
      const row = el('div', { class: 'ac-item' + (i === 0 ? ' active' : '') }, [el('span', { class: 'c' }), el('span', { class: 'n' }), el('span', { class: 'p' })]);
      row.children[0].textContent = it.code;
      row.children[1].textContent = it.name + (it.barcode ? '  · ' + it.barcode : '');
      row.children[2].textContent = it.unit + ' · ' + Number(it.price).toLocaleString('en', { minimumFractionDigits: S.scale, maximumFractionDigits: 6 });
      row.addEventListener('mousedown', (e) => { e.preventDefault(); pick(i); });
      acBox.append(row);
    });
    const create = el('div', { class: 'ac-item ac-create' }); create.textContent = '+ Create new product "' + q + '"';
    create.addEventListener('mousedown', (e) => { e.preventDefault(); openCreate(q); });
    acBox.append(create);
    acRow.querySelector('.prod').append(acBox);
  }
  function closeAc() { if (acBox) { acBox.remove(); acBox = null; } acItems = []; acIndex = -1; }

  function descKeys(e, tr) {
    if (e.key === 'Enter') {
      e.preventDefault(); acRow = tr;
      if (acBox && acIndex >= 0 && acIndex < acItems.length) { pick(acIndex); return; }
      const q = tr.querySelector('.desc').value.trim();
      if (!q) { tr.querySelector('.qty').focus(); return; }
      if (tr.dataset.itemId) { tr.querySelector('.qty').focus(); tr.querySelector('.qty').select(); return; }
      // scanner / fast typist: search now, do not wait for the dropdown
      clearTimeout(searchTimer); const seq = ++searchSeq;
      api('GET', '/api/items/search?q=' + encodeURIComponent(q)).then((items) => {
        if (seq !== searchSeq) return;
        if (items.length && (isExact(items[0], q) || items.length === 1)) { acItems = items; acRow = tr; pick(0); }
        else if (items.length) openAc(items, q);
        else openCreate(q);
      }).catch((er) => showError(er.message));
      return;
    }
    if (!acBox) return;
    const rows = [...acBox.children];
    if (e.key === 'ArrowDown') { e.preventDefault(); acIndex = Math.min(acIndex + 1, rows.length - 1); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); acIndex = Math.max(acIndex - 1, 0); }
    else if (e.key === 'Escape') { closeAc(); return; }
    else return;
    rows.forEach((r, i) => r.classList.toggle('active', i === acIndex));
    rows[acIndex]?.scrollIntoView({ block: 'nearest' });
  }

  function pick(i) {
    const it = acItems[i]; if (!it) return;
    fillRow(acRow, it); closeAc();
    const q = acRow.querySelector('.qty'); q.focus(); q.select();
  }

  function fillRow(tr, it) {
    tr.dataset.itemId = it.id; tr.dataset.catalogPrice = it.price;
    const cb = tr.querySelector('.code-badge'); cb.textContent = it.code; cb.style.display = '';
    tr.querySelector('.desc').value = it.name;
    tr.querySelector('.unit').value = it.unit;
    tr.querySelector('.price').value = it.price;
    tr.querySelector('.tax').value = it.taxRate;
    tr.dataset.serial = it.serialNo || ''; refreshSerial(tr);
    // a catalog warranty is only a suggestion for the popup; the Warranty box itself stays unticked
    tr.dataset.itemWar = it.warrantyEnabled ? JSON.stringify({ dur: it.warrantyDuration, unit: it.warrantyUnit, terms: it.warrantyTerms }) : '';
    toggleUpd(tr); scheduleCalc();
  }

  // ------------------------------------------------------------------ create product modal
  const modalEl = $('itemModal'); const modal = new bootstrap.Modal(modalEl);
  let modalRow = null, dupTimer = null;
  function openCreate(text) {
    modalRow = acRow; closeAc();
    const looksLikeBarcode = /^[0-9]{6,}$/.test(text || '');
    $('niName').value = looksLikeBarcode ? '' : (text || ''); $('niBarcode').value = looksLikeBarcode ? text : '';
    $('niDesc').value = ''; $('niSerial').value = ''; $('niUnit').value = 'pcs'; $('niPrice').value = '0'; $('niTax').value = S.defaultTaxRate;
    $('itemErr').classList.add('d-none'); checkDup(); modal.show();
  }
  modalEl.addEventListener('shown.bs.modal', () => $('niName').focus());
  function checkDup() {
    clearTimeout(dupTimer);
    dupTimer = setTimeout(() => {
      const n = $('niName').value.trim(); const box = $('itemDup');
      if (n.length < 2) { box.classList.add('d-none'); return; }
      api('GET', '/api/items/similar?name=' + encodeURIComponent(n)).then((list) => {
        if (!list.length) { box.classList.add('d-none'); return; }
        box.textContent = 'Similar products already exist (you can still create a distinct variant): ' + list.map((i) => i.code + ' ' + i.name).join('; ');
        box.classList.remove('d-none');
      }).catch(() => {});
    }, 250);
  }
  $('niName').addEventListener('input', checkDup);
  $('niSave').addEventListener('click', () => {
    const body = { itemType: $('niType').value, name: $('niName').value.trim(), barcode: $('niBarcode').value.trim(), serialNo: $('niSerial').value.trim(), description: $('niDesc').value.trim(),
      unit: $('niUnit').value.trim(), defaultPrice: $('niPrice').value || '0', taxRate: $('niTax').value || '0', active: true, warrantyEnabled: false };
    api('POST', '/api/items', body).then((it) => {
      modal.hide(); const tr = modalRow || addRow(null, false); fillRow(tr, it);
      const q = tr.querySelector('.qty'); q.focus(); q.select();
    }).catch((e) => { const b = $('itemErr'); b.textContent = e.message; b.classList.remove('d-none'); });
  });

  // ------------------------------------------------------------------ totals (calculated by the server)
  function lineData(tr) {
    return {
      itemId: tr.dataset.itemId ? Number(tr.dataset.itemId) : null,
      itemCode: tr.querySelector('.code-badge').textContent || null,
      serialNo: tr.dataset.serial || null,
      description: tr.querySelector('.desc').value,
      quantity: tr.querySelector('.qty').value.trim() || null,
      unit: tr.querySelector('.unit').value,
      unitPrice: tr.querySelector('.price').value.trim() || null,
      discountPercent: tr.querySelector('.disc').value.trim() || null,
      taxRate: tr.querySelector('.tax').value.trim() || null,
      warrantyEnabled: tr.war.on,
      warrantyDuration: tr.war.on ? Number(tr.war.dur) : null,
      warrantyUnit: tr.war.on ? tr.war.unit : null,
      warrantyTerms: tr.war.on ? (tr.war.terms || null) : null
    };
  }
  function scheduleCalc() { clearTimeout(calcTimer); calcTimer = setTimeout(calc, 180); }
  function calc() {
    const rows = usedRows(); const seq = ++calcSeq;
    api('POST', '/api/invoices/calc', rows.map(lineData)).then((t) => {
      if (seq !== calcSeq) return;
      rows.forEach((tr, i) => { const r = t.lines[i]; if (r) tr.querySelector('.total').textContent = fmt(r.total); });
      $('tGross').textContent = fmt(t.gross); $('tDiscount').textContent = (Number(t.discount) > 0 ? '-' : '') + fmt(t.discount);
      $('tNet').textContent = fmt(t.net); $('tTax').textContent = fmt(t.tax); $('tGrand').textContent = fmt(t.grand);
      $('barTotal').textContent = fmt(t.grand);
    }).catch(() => { /* partial input while typing: ignore */ });
  }

  // ------------------------------------------------------------------ header: dates, Cash / Bank
  function addDays(iso, n) { const d = new Date(iso + 'T00:00:00'); d.setDate(d.getDate() + n); return d.toISOString().slice(0, 10); }
  function recomputeDue() {
    if (dueTouched || !$('invoiceDate').value) return;
    const opt = $('customerId').selectedOptions[0];
    const days = opt && opt.dataset.terms ? Number(opt.dataset.terms) : S.termsDays;
    $('dueDate').value = addDays($('invoiceDate').value, days);
  }
  $('dueDate').addEventListener('input', () => { dueTouched = true; });
  $('invoiceDate').addEventListener('change', recomputeDue);
  $('customerId').addEventListener('change', recomputeDue);

  const bankSel = $('bankAccountId');
  function bankOptions() { return [...bankSel.options].filter((o) => o.value); }
  function applyPayMode() {
    const bank = $('payBank').checked;
    $('bankWrap').classList.toggle('d-none', !bank);
    if (!bank) return;
    const opts = bankOptions();
    $('noBank').classList.toggle('d-none', opts.length > 0);
    if (!bankSel.value) {                              // pick automatically: the only bank, else the default one
      const def = opts.find((o) => o.dataset.default === 'true') || (opts.length === 1 ? opts[0] : null);
      if (def) bankSel.value = def.value;
    }
  }
  $('payCash').addEventListener('change', applyPayMode);
  $('payBank').addEventListener('change', applyPayMode);

  // ------------------------------------------------------------------ save
  function payload() {
    const bank = $('payBank').checked;
    return {
      id: INIT.id || null, version: INIT.version ?? null,
      customerId: $('customerId').value ? Number($('customerId').value) : null,
      invoiceDate: $('invoiceDate').value || null, dueDate: $('dueDate').value || null,
      deliveryNoteNo: $('deliveryNoteNo').value, lpoNo: $('lpoNo').value, deliveryDate: $('deliveryDate').value || null,
      bankAccountId: bank && bankSel.value ? Number(bankSel.value) : null,
      paymentTerms: $('paymentTerms').value, notes: $('notes').value, lines: usedRows().map(lineData)
    };
  }
  function save(thenIssue) {
    clearError();
    if ($('payBank').checked && !bankSel.value) { showError('Select the bank account to print, or switch Payment to Cash.'); return; }
    const btns = [$('saveDraft'), $('saveIssue')]; btns.forEach((b) => { b.disabled = true; });
    api('POST', '/api/invoices', payload()).then((r) => {
      if (!thenIssue) { window.location.href = r.redirect; return; }
      const f = el('form', { method: 'post', action: '/invoices/' + r.id + '/issue' });
      if (csrfToken) f.append(el('input', { type: 'hidden', name: '_csrf', value: csrfToken }));
      document.body.append(f); f.submit();
    }).catch((e) => { showError(e.message); btns.forEach((b) => { b.disabled = false; }); });
  }
  $('saveDraft').addEventListener('click', () => save(false));
  $('saveIssue').addEventListener('click', () => { if (confirm('Save and issue this invoice now? It gets its permanent number and can no longer be edited.')) save(true); });
  $('addLine').addEventListener('click', () => addRow(null, true));

  // ------------------------------------------------------------------ init
  $('customerId').value = INIT.customerId || '';
  $('invoiceDate').value = INIT.invoiceDate || ''; $('dueDate').value = INIT.dueDate || ''; $('deliveryDate').value = INIT.deliveryDate || '';
  $('deliveryNoteNo').value = INIT.deliveryNoteNo || ''; $('lpoNo').value = INIT.lpoNo || '';
  $('paymentTerms').value = INIT.paymentTerms || ''; $('notes').value = INIT.notes || '';
  if (INIT.deliveryNoteNo || INIT.lpoNo || INIT.deliveryDate) new bootstrap.Collapse($('moreRef'), { toggle: true });
  if (INIT.bankAccountId) { $('payBank').checked = true; bankSel.value = INIT.bankAccountId; }
  applyPayMode();
  (INIT.lines || []).forEach((l) => addRow(l));
  if (!allRows().length) addRow();
  calc();
  allRows()[allRows().length === 1 ? 0 : allRows().length - 1].querySelector('.desc').focus();   // cursor starts in the product box
  if (INIT.lines && INIT.lines.length) addRow(null, true);                                       // editing: ready for the next product
})();
