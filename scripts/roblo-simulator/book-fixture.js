// Test fixture for a newly created, offline simulator container. Never contacts a coordinator.
(() => {
  'use strict';
  const book = 'http://kiloblo3ibbey3wridofauizwb467y5osfaycxoecgsf66mggsrw6ryd.onion/api/book/';
  const ids = [910001, 910002, 910003];
  const order = (id, type, currency, amount) => ({
    id, type, currency, amount, has_range:false, min_amount:null, max_amount:null,
    premium:'0', base_price:64000, price:64000, payment_method:'Cash',
    created_at:'2026-01-01T00:00:00Z', expires_at:'2099-01-01T00:00:00Z',
    is_explicit:false, satoshis:100000, satoshis_now:100000, latitude:null, longitude:null,
    bond_size:'2', maker:null, maker_nick:'PreviewRobot', maker_hash_id:null, escrow_duration:10800
  });
  const rows = [order(ids[0],1,1,'73'), order(ids[1],0,1,'85'), order(ids[2],1,2,'96')];
  localStorage.setItem('settings_connection','api');
  let reads = 0;
  const original = window.fetch;
  window.fetch = function(input, init) {
    const req = input instanceof Request ? input : null;
    const url = new URL(req ? req.url : input, document.baseURI);
    const method = (init?.method || req?.method || 'GET').toUpperCase();
    const headers = new Headers(init?.headers ?? req?.headers);
    if (url.href === book && method === 'GET' && !headers.has('Authorization') &&
        !init?.body && !req?.body) {
      reads++;
      return Promise.resolve(new Response(JSON.stringify(rows), {headers:{'Content-Type':'application/json'}}));
    }
    if (url.protocol === 'http:' || url.protocol === 'https:') {
      return Promise.reject(new TypeError('Offline fixture: no network'));
    }
    return original.call(this, input, init);
  };
  // Count fixture reads, without exposing any Swift handler, wallet or underlying storage.
  Object.defineProperty(document, 'kwOfflineBookReads', {get:() => reads});
})();
