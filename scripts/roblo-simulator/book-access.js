// Runs inside the disposable simulator fixture after the pinned client has loaded.
const endpoint = 'http://kiloblo3ibbey3wridofauizwb467y5osfaycxoecgsf66mggsrw6ryd.onion/api/book/';
const req = new Request(endpoint, {headers:{Accept:'application/json','Content-Type':'application/json'}});
const ok = await fetch(req);
const rows = await ok.json();
if (!ok.ok || !Array.isArray(rows) || rows.map(row => row.id).join(',') !== '910001,910002,910003')
  throw Error('Missing public fixture rows');
const checks = [
  ['write', endpoint, {method:'POST'}],
  ['credentialHeader', endpoint, {headers:{Authorization:'Synthetic'}}],
  ['otherHeader', endpoint, {headers:{'X-Preview':'synthetic'}}],
  ['browserCredentials', endpoint, {credentials:'include'}],
  ['query', endpoint + '?onchain=1', {}],
  ['otherPath', new URL('/api/info/', endpoint).href, {}],
  ['otherCoordinator', 'http://olwrnvnukon2lkgqmbdtjfm3vqzadhbtbjmi4abillzjmhwfhhp3mryd.onion/api/book/', {}],
  ['otherOrigin', 'https://preview.invalid/api/book/', {}]
];
const denied = [];
for (const [name, url, init] of checks) {
  let blocked = false;
  try { await fetch(url, init); } catch (e) { blocked = e instanceof TypeError; }
  if (!blocked) throw Error('Unexpected access: ' + name);
  denied.push(name);
}
return {publicIds:rows.map(row => row.id), denied};
