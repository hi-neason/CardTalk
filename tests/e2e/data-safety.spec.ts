import { expect, test, type Page } from '@playwright/test';
import { createState, createFamilyProfile } from '../../frontend/src/domain';
const stateKey = 'cardtalk.communication.v1', queueKey = 'cardtalk.cloud-sync.v1.fixture-account.fixture-family';
const button = (page: Page, name: string) => page.getByRole('button', { name, exact: true });
async function setup(page: Page) {
  const state = createFamilyProfile(createState(), { name: '本机删除测试', preset: 'adult' });
  const queue = { revision: 3, pending: { sessionKey: 'fixture-session', operations: [{ operationId: 'must-preserve', payload: { name: '本机删除测试' } }] } };
  await page.addInitScript(({ state, queue, stateKey, queueKey }) => { localStorage.setItem(stateKey, JSON.stringify(state)); localStorage.setItem(queueKey, JSON.stringify(queue)); }, { state, queue, stateKey, queueKey });
  await page.route('**/api/v1/**', route => route.fulfill(route.request().url().endsWith('/health') ? { json: { status: 'ok', llmConfigured: false, ttsConfigured: false } } : { json: { token: 'data-fixture', remaining: 10, limit: 10, resetAt: '2099-01-01', capabilities: {} } }));
  await page.goto('/'); await button(page, '设置').click(); return { state, queue };
}

test('删除确认未结束时关闭设置，不得迟到清空本机数据与同步队列', async ({ page }) => {
  const { state, queue } = await setup(page); await button(page, '清空全部本机产品数据').click(); await expect(page.locator('.uni-modal')).toBeVisible();
  await button(page, '关闭').dispatchEvent('click'); await expect(page.locator('.local-data-manager')).toHaveCount(0); await page.locator('.uni-modal__btn_primary').click();
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => resolve())));
  expect(await page.evaluate(key => JSON.parse(localStorage.getItem(key)!), stateKey)).toEqual(state); expect(await page.evaluate(key => JSON.parse(localStorage.getItem(key)!), queueKey)).toEqual(queue);
});

test('删除时本机主数据写入失败，回滚已清的同步队列并保留原数据', async ({ page }) => {
  const { state, queue } = await setup(page);
  await page.evaluate(key => { const original = Storage.prototype.setItem; Storage.prototype.setItem = function (name, value) { if (name === key) throw new DOMException('Fixture storage failure', 'QuotaExceededError'); original.call(this, name, value); }; }, stateKey);
  await button(page, '清空全部本机产品数据').click(); await page.locator('.uni-modal__btn_primary').click(); await expect(page.locator('.local-data-manager [role=alert]')).toContainText('未清空');
  expect(await page.evaluate(key => JSON.parse(localStorage.getItem(key)!), stateKey)).toEqual(state); expect(await page.evaluate(key => JSON.parse(localStorage.getItem(key)!), queueKey)).toEqual(queue);
});
