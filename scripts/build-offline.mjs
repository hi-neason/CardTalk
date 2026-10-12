import { readdir, readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../frontend/dist/build/h5/', import.meta.url));
async function files(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  return (await Promise.all(entries.map(entry => entry.isDirectory() ? files(path.join(directory, entry.name)) : [path.join(directory, entry.name)]))).flat();
}
const assets = (await files(root)).filter(file => !file.endsWith('service-worker.js') && !file.endsWith('.map')).sort();
const hash = createHash('sha256');
for (const file of assets) { hash.update(path.relative(root, file)); hash.update(await readFile(file)); }
const version = hash.digest('hex').slice(0, 16);
const urls = assets.map(file => '/' + path.relative(root, file).split(path.sep).map(encodeURIComponent).join('/'));
const worker = `// Generated from the production build. API responses and user data are never cached.
const CACHE = 'cardtalk-shell-v1-${version}';
const URLS = ${JSON.stringify(urls)};
const paths = new Set(URLS);
self.addEventListener('install', event => event.waitUntil((async () => {
  try { const cache = await caches.open(CACHE); await cache.addAll(URLS); }
  catch (error) { await caches.delete(CACHE); throw error; }
})()));
// Updates wait for old tabs to close, preserving ongoing communication.
self.addEventListener('activate', event => event.waitUntil((async () => {
  for (const key of await caches.keys()) if (key.startsWith('cardtalk-shell-v1-') && key !== CACHE) await caches.delete(key);
  await self.clients.claim();
})()));
self.addEventListener('fetch', event => {
  const request = event.request, url = new URL(request.url);
  if (request.method !== 'GET' || url.origin !== self.location.origin || url.pathname.startsWith('/api/')) return;
  if (request.mode === 'navigate') {
    event.respondWith((async () => (await (await caches.open(CACHE)).match('/index.html')) || fetch(request))());
  } else if (paths.has(url.pathname)) {
    event.respondWith((async () => (await (await caches.open(CACHE)).match(url.pathname)) || fetch(request))());
  }
});
`;
await writeFile(path.join(root, 'service-worker.js'), worker);
console.log(`Offline shell prepared: ${urls.length} resources, version ${version}`);
