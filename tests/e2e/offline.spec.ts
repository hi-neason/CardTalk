import { test, expect } from '@playwright/test';
import { spawn, type ChildProcess } from 'node:child_process';
import { existsSync } from 'node:fs';
import path from 'node:path';

let server: ChildProcess | undefined;
const origin = 'http://127.0.0.1:4173';
test.beforeAll(async () => {
  if (!existsSync('frontend/dist/build/h5/service-worker.js')) throw new Error('Run npm run build before offline tests.');
  server = spawn(process.execPath, [path.resolve('node_modules/vite/bin/vite.js'), 'preview', '--outDir', 'dist/build/h5', '--host', '127.0.0.1', '--port', '4173', '--strictPort'], { cwd: path.resolve('frontend'), stdio: 'ignore' });
  for (let i=0;i<100;i++) {
    if (server.exitCode !== null) throw new Error('Production preview could not start on port 4173.');
    try { if ((await fetch(origin)).ok) return; } catch { /* Wait for the local production server. */ }
    await new Promise(resolve=>setTimeout(resolve,100));
  }
  throw new Error('Production preview did not become ready.');
});
test.afterAll(()=>{ server?.kill('SIGTERM'); });

test('生产离线缓存支持刷新、原始表达和固定音频，不缓存API或沟通数据', async ({ browser }) => {
  const context=await browser.newContext({ baseURL:origin }); const page=await context.newPage();
  try {
    await page.route('**/api/v1/**', route=>route.fulfill({status:503,json:{message:'offline fixture: no paid provider calls'}}));
    await page.goto(origin);
    await page.evaluate(async()=>{
      await navigator.serviceWorker.ready;
      if(!navigator.serviceWorker.controller) await new Promise<void>(resolve=>navigator.serviceWorker.addEventListener('controllerchange',()=>resolve(),{once:true}));
    });
    const manifest=await page.evaluate(async()=>{const response=await fetch('/static/brand/manifest.webmanifest');return response.json()});
    expect(manifest.display).toBe('standalone');
    await context.setOffline(true); await page.reload();
    await expect(page.getByRole('button',{name:'本机历史',exact:true})).toBeVisible();
    await page.getByRole('button',{name:/开始沟通/}).click();
    await page.getByRole('button',{name:/创建聊天室，开始选卡/}).click();
    await page.getByRole('button',{name:'选择水',exact:true}).click();
    await page.getByRole('button',{name:'使用原始表达',exact:true}).click();
    await expect(page.locator('.confirmed-text')).toContainText('水');
    const audio=await page.evaluate(async()=>{const r=await fetch('/static/audio/water.m4a');return {ok:r.ok,size:(await r.arrayBuffer()).byteLength}});
    expect(audio.ok).toBe(true);expect(audio.size).toBeGreaterThan(100);
    await page.reload();await expect(page.locator('.confirmed-text')).toContainText('水');
    const cachedPaths=await page.evaluate(async()=>{const paths:string[]=[];for(const key of await caches.keys()){for(const request of await(await caches.open(key)).keys())paths.push(new URL(request.url).pathname)}return paths});
    expect(cachedPaths.some(p=>p.startsWith('/api/'))).toBe(false);
    expect(cachedPaths).not.toContain('cardtalk.communication.v1');
    await page.screenshot({path:'test-results/offline-production.png',fullPage:true});
  } finally { await context.close(); }
});
