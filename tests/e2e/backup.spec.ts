import { expect, test, type Page } from '@playwright/test';
import { createState, createFamilyProfile, createCustomScene, createCustomCard, createRoom, addCard, recordRawExpression, endRoom, CARDS, type State } from '../../frontend/src/domain';
const key = 'cardtalk.communication.v1';
const button = (page: Page, name: string) => page.getByRole('button', { name, exact: true });
function backup() {
  let state = createFamilyProfile(createState(), { name: '导入使用人', preset: 'adult' });
  state = createCustomScene(state, { name: '导入阳台', description: '看花' });
  state = createCustomCard(state, { label: '导入水杯', category: 'daily' });
  state = createRoom(state, { preset: 'adult', communicatorName: '导入使用人', profileId: state.family.profiles[0]!.id, sceneId: state.family.scenes[0]!.id, source: 'independent', participants: [] });
  return endRoom(recordRawExpression(addCard(state, CARDS[0]!)));
}
async function setup(page: Page, original: State = createFamilyProfile(createState(), { name: '原有使用人', preset: 'children' })) {
  const unexpected: string[] = [];
  await page.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/health')) return route.fulfill({ json: { status: 'ok', llmConfigured: false, ttsConfigured: false, promptVersion: 'backup-fixture' } });
    if (path.endsWith('/trial-session')) return route.fulfill({ json: { token: 'backup-fixture-token', remaining: 10, limit: 10, resetAt: '2099-01-01T00:00:00Z', capabilities: { analytics: true, profileManagement: true, scenarioManagement: true, customCards: true } } });
    unexpected.push(path); return route.fulfill({ status: 503, json: { message: 'No paid API calls in fixture' } });
  });
  await page.goto('/');
  await page.evaluate(({ key, state }) => localStorage.setItem(key, JSON.stringify(state)), { key, state: original });
  await page.reload(); await button(page, '设置').click();
  return { original, unexpected };
}
async function select(page: Page, contents: string) {
  const chooser = page.waitForEvent('filechooser'); await button(page, '选择备份文件').click();
  await (await chooser).setFiles({ name: 'cardtalk-backup.json', mimeType: 'application/json', buffer: Buffer.from(contents) });
}
async function stored(page: Page) { return page.evaluate(key => JSON.parse(localStorage.getItem(key)!), key); }
async function replace(page: Page) { await button(page, '确认替换本机数据').click(); await page.locator('.uni-modal__btn_primary').click(); }

test('备份预览与取消不改变本机数据，确认后持久化并在刷新后恢复', async ({ page }) => {
  const fixture = await setup(page), imported = backup();
  await select(page, JSON.stringify(imported));
  await expect(page.getByLabel('备份预览')).toContainText('使用人 1 · 自定义卡片 1 · 场景 1');
  await expect(page.getByLabel('备份预览')).toContainText('沟通 1 次 · 已记录表达 1 条');
  await button(page, '确认替换本机数据').click(); await page.locator('.uni-modal__btn_default').click();
  expect(await stored(page)).toEqual(fixture.original);
  await expect(page.getByLabel('备份预览')).toBeVisible();
  await page.screenshot({ path: '/tmp/cardtalk-backup.png', fullPage: true });
  await replace(page);
  await expect(page.getByText('备份已导入，本机数据已替换。', { exact: true })).toBeVisible();
  expect(await stored(page)).toEqual(imported);
  await page.reload(); expect(await stored(page)).toEqual(imported);
  await button(page, '家庭管理').click(); await expect(button(page, '编辑使用人 导入使用人')).toBeVisible();
  expect(fixture.unexpected).toEqual([]);
});

test('损坏 JSON 拒绝导入并保留原数据', async ({ page }) => {
  const fixture = await setup(page);
  await select(page, '{"version":');
  await expect(page.locator('.backup-import [role=alert]')).toContainText('不是有效的 JSON');
  await expect(page.getByLabel('备份预览')).toHaveCount(0);
  expect(await stored(page)).toEqual(fixture.original);
  expect(fixture.unexpected).toEqual([]);
});

test('活动沟通禁止导入，保留当前草稿', async ({ page }) => {
  let state = createRoom(createState(), { preset: 'children', communicatorName: '当前使用人', sceneId: null, participants: [], source: 'independent' });
  state = addCard(state, CARDS[0]!);
  const fixture = await setup(page, state);
  await expect(button(page, '选择备份文件')).toBeDisabled();
  await expect(page.locator('.backup-import')).toContainText('请先结束当前沟通');
  expect(await stored(page)).toEqual(state); expect(fixture.unexpected).toEqual([]);
});

test('写入失败保留原数据及备份预览，恢复存储后可以重试', async ({ page }) => {
  const fixture = await setup(page), imported = backup(); await select(page, JSON.stringify(imported));
  await expect(page.getByLabel('备份预览')).toBeVisible();
  await page.evaluate(() => {
    const original = Storage.prototype.setItem;
    (window as any).__restoreStorage = () => { Storage.prototype.setItem = original; };
    Storage.prototype.setItem = function (key, value) { if (key === 'cardtalk.communication.v1') throw new DOMException('fixture full', 'QuotaExceededError'); return original.call(this, key, value); };
  });
  await replace(page);
  await expect(page.getByRole('dialog').getByText('备份未导入，本机数据未更改。请检查存储空间后重试。', { exact: true })).toBeVisible();
  expect(await stored(page)).toEqual(fixture.original);
  await expect(page.getByLabel('备份预览')).toBeVisible();
  await page.evaluate(() => (window as any).__restoreStorage());
  await replace(page); await expect(page.getByLabel('备份预览')).toHaveCount(0);
  expect(await stored(page)).toEqual(imported); expect(fixture.unexpected).toEqual([]);
});
