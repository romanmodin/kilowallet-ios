// This driver only operates search filters in a disposable, offline preview.
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const textOf = el => (el?.innerText ?? '').replace(/\s+/g, ' ').trim().toLowerCase();
const visible = el => !!el && el.getClientRects().length > 0 &&
  el.getBoundingClientRect().width > 0 && el.getBoundingClientRect().height > 0 &&
  getComputedStyle(el).visibility === 'visible' && !el.closest('[aria-hidden="true"]');
const find = (selector, value, parent = document) => [...parent.querySelectorAll(selector)]
  .find(el => visible(el) && textOf(el) === value && !el.disabled);
const wait = async (name, check) => {
  for (let n = 0; n < 100; n++) { const result = check(); if (result) return result; await delay(100); }
  throw Error('Timed out: ' + name);
};
const start = await wait('landing', () => find('button', 'search for orders'));
start.click();
const dialog = await wait('order search dialog', () => [...document.querySelectorAll('[role="dialog"]')]
  .find(el => visible(el) && find('button', 'buy', el) && find('button', 'sell', el)));
find('button', 'buy', dialog).click(); // Chooses a search filter; does not create or take an order.
const choose = await wait('choose a currency', () => find('[role="combobox"]', 'choose a currency', dialog));
choose.dispatchEvent(new MouseEvent('mousedown', {bubbles:true, button:0}));
const usd = await wait('USD option', () => find('[role="option"]', 'usd'));
usd.click();
const matchingRow = (currency, id, amount) => {
  const grid = [...document.querySelectorAll('[role="grid"]')].find(visible);
  if (!grid) return null;
  const rows = [...grid.querySelectorAll('[role="row"][data-id]')].filter(visible);
  const row = rows[0];
  if (rows.length !== 1 || row.getAttribute('data-id') !== id) return null;
  const cur = row.querySelector('[data-field="currency"]');
  const amt = row.querySelector('[data-field="amount"]');
  if (!visible(cur) || !visible(amt) || textOf(cur) !== currency || textOf(amt) !== amount) return null;
  return {rowId: row.getAttribute('data-id'), currency: textOf(cur), amount: textOf(amt)};
};
const usdMatch = await wait('filtered USD offer', () => matchingRow('usd', 'kilobot/910001', '73'));
const inBook = await wait('USD currency selector', () => find('[role="combobox"]', 'usd'));
inBook.dispatchEvent(new MouseEvent('mousedown', {bubbles:true, button:0}));
(await wait('EUR option', () => find('[role="option"]', 'eur'))).click();
const eurMatch = await wait('filtered EUR offer', () => matchingRow('eur', 'kilobot/910003', '96'));
return {usd: usdMatch, eur: eurMatch};
