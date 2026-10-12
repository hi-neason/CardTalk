import { expect, test } from '@playwright/test';
import { createServer, type Server } from 'node:http';
import { cp, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

const origin = 'http://127.0.0.1:4174';
const mime: Record<string, string> = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css', '.json': 'application/json', '.webmanifest': 'application/manifest+json', '.svg': 'image/svg+xml', '.png': 'image/png', '.m4a': 'audio/mp4', '.woff2': 'font/woff2' };
async function serve(root: string): Promise<Server> {
  const server = createServer(async (request, response) => {
    const pathname = new URL(request.url || '/', origin).pathname;
    if (pathname.startsWith('/api/')) { response.writeHead(503, { 'Content-Type': 'application/json' }); response.end('{"message":"offline update fixture: no provider calls"}'); return; }
    try {
      const filename = path.resolve(root, `.${decodeURIComponent(pathname === '/' ? '/index.html' : pathname)}`);
      if (!filename.startsWith(root + path.sep)) { response.writeHead(403); response.end(); return; }
      const bytes = await readFile(filename);
      response.writeHead(200, { 'Content-Type': mime[path.extname(filename)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
      response.end(bytes);
    } catch { response.writeHead(404); response.end(); }
  });
  await new Promise<void>((resolve, reject) => { server.once('error', reject); server.listen(4174, '127.0.0.1', resolve); });
  return server;
}

test('真实双标签更新等待旧客户端退出，离线新版本保留未结束选卡草稿', async ({ browser }, testInfo) => {
  test.setTimeout(60000);
  const directory = await mkdtemp(path.join(tmpdir(), 'cardtalk-offline-update-'));
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  let server: Server | undefined;
  try {
    await cp(path.resolve('frontend/dist/build/h5'), directory, { recursive: true });
    const index = await readFile(path.join(directory, 'index.html'), 'utf8');
    const worker = await readFile(path.join(directory, 'service-worker.js'), 'utf8');
    // Exercise the production worker unchanged, apart from its generated version constant.
    expect(worker).toMatch(/const CACHE = 'cardtalk-shell-v1-[^']+';/);
    expect(worker).not.toContain('skipWaiting(');
    const version = (name: 'a' | 'b') => `cardtalk-shell-v1-update-test-${name}`;
    async function publish(name: 'a' | 'b') {
      await writeFile(path.join(directory, 'index.html'), index.replace('</head>', `<meta name="cardtalk-test-version" content="${name}"></head>`));
      await writeFile(path.join(directory, 'service-worker.js'), worker.replace(/const CACHE = '[^']+';/, `const CACHE = '${version(name)}';`));
    }
    await publish('a'); server = await serve(directory);
    await context.route('**/api/v1/**', route => route.fulfill({ status: 503, json: { message: 'offline fixture: no provider calls' } }));
    const first = await context.newPage(); await first.goto(origin);
    await first.evaluate(async () => {
      await navigator.serviceWorker.ready;
      if (!navigator.serviceWorker.controller) await new Promise<void>(resolve => navigator.serviceWorker.addEventListener('controllerchange', () => resolve(), { once: true }));
    });
    await first.getByRole('button', { name: /开始沟通/ }).click();
    await first.getByRole('button', { name: /创建聊天室，开始选卡/ }).click();
    await first.getByRole('button', { name: '选择我', exact: true }).click();
    await first.getByRole('button', { name: '选择苹果', exact: true }).click();
    await expect(first.locator('.selected-word')).toHaveCount(2);
    const second = await context.newPage(); await second.goto(origin);
    await expect(second.locator('meta[name="cardtalk-test-version"]')).toHaveAttribute('content', 'a');
    expect(await second.evaluate(() => !!navigator.serviceWorker.controller)).toBe(true);
    await publish('b');
    expect(await (await fetch(origin)).text()).toContain('name="cardtalk-test-version" content="b"');
    const newWorker = context.waitForEvent('serviceworker');
    await first.evaluate(async () => { const registration = await navigator.serviceWorker.getRegistration(); await registration!.update(); });
    const waitingWorker = await newWorker;
    await expect.poll(() => first.evaluate(async () => (await navigator.serviceWorker.getRegistration())?.waiting?.state), { timeout: 15000 }).toBe('installed');
    expect(await waitingWorker.evaluate('CACHE')).toBe(version('b'));
    expect(await first.evaluate(() => caches.keys())).toEqual(expect.arrayContaining([version('a'), version('b')]));
    // Even online, old controlled pages must not mix the newly published HTML into shell A.
    await second.reload();
    await expect(second.locator('meta[name="cardtalk-test-version"]')).toHaveAttribute('content', 'a');
    await context.setOffline(true); await second.reload();
    await expect(second.locator('meta[name="cardtalk-test-version"]')).toHaveAttribute('content', 'a');
    await expect(second.locator('.selected-word')).toHaveCount(2);
    await expect(first.getByRole('button', { name: '结束聊天室', exact: true })).toBeVisible();
    await first.screenshot({ path: testInfo.outputPath('offline-update-a-active-draft.png'), fullPage: true });
    await second.screenshot({ path: testInfo.outputPath('offline-update-a-second-tab-offline.png'), fullPage: true });
    // Closing only one tab must not activate B while another controlled tab remains.
    await first.close();
    expect(await second.evaluate(async () => (await navigator.serviceWorker.getRegistration())?.waiting?.state)).toBe('installed');
    await second.close();
    await expect.poll(() => waitingWorker.evaluate('self.registration.waiting === null && self.registration.active?.state === "activated"'), { timeout: 15000 }).toBe(true);
    await expect.poll(() => waitingWorker.evaluate(() => caches.keys()), { timeout: 15000 }).toEqual([version('b')]);
    const restored = await context.newPage(); await restored.goto(origin);
    await expect(restored.locator('meta[name="cardtalk-test-version"]')).toHaveAttribute('content', 'b');
    await expect(restored.getByRole('button', { name: '结束聊天室', exact: true })).toBeVisible();
    await expect(restored.locator('.selected-word')).toHaveCount(2);
    await expect(restored.locator('.selected-word').nth(0)).toContainText('我');
    await expect(restored.locator('.selected-word').nth(1)).toContainText('苹果');
    await expect(restored.locator('.confirmed-area')).toHaveCount(0);
    const cachedPaths = await restored.evaluate(async () => (await Promise.all((await caches.keys()).map(async key => (await (await caches.open(key)).keys()).map(request => new URL(request.url).pathname)))).flat());
    expect(cachedPaths.some(value => value.startsWith('/api/'))).toBe(false);
    await restored.screenshot({ path: testInfo.outputPath('offline-update-b-restored-draft.png'), fullPage: true });
  } finally {
    await context.close();
    if (server) { server.closeAllConnections(); await new Promise<void>(resolve => server!.close(() => resolve())); }
    await rm(directory, { recursive: true, force: true });
  }
});
