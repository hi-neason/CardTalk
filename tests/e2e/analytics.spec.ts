import { expect, test, type Page } from '@playwright/test';
import { createState, createFamilyProfile, createRoom, addCard, recordRawExpression, nextTurn, applyExpansion, confirmCandidate, setFeedback, endRoom, addPlaybackEvent, validateState, CARDS } from '../../frontend/src/domain';

test.use({ timezoneId: 'Asia/Shanghai' });

const photo = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=';
const privateExpression = '这是一段不应该出现在统计导出里的完整表达';
const button = (page: Page, name: string | RegExp) => page.getByRole('button', { name, exact: typeof name === 'string' });
function historyFixture() {
  let state = createState();
  state = createFamilyProfile(state, { name: '小安', preset: 'children', image: photo });
  state = createFamilyProfile(state, { name: '爷爷', preset: 'adult' });
  const [child, adult] = state.family.profiles;
  const apple = CARDS.find(card => card.label === '苹果')!, water = CARDS.find(card => card.label === '水')!;
  state = createRoom(state, { profileId: child!.id, preset: 'children', communicatorName: '小安', sceneId: 'kitchen', participants: [], source: 'independent' });
  state = recordRawExpression(addCard(addCard(state, apple), apple));
  state = setFeedback(state, state.rooms[0]!.id, state.rooms[0]!.turns[0]!.id, 'understood', '妈妈');
  state = addCard(nextTurn(state), apple);
  state = applyExpansion(state, { requestId: 'analytics-fixture-request', inputVersion: state.rooms[0]!.draft.version, candidates: [{ id: 'fixture-candidate', text: '我想吃苹果', sourceCardIds: [apple.id] }], needsClarification: false, clarification: null, promptVersion: 'analytics-fixture', remaining: 10 });
  state = confirmCandidate(state, 'fixture-candidate', privateExpression); state = endRoom(state);
  state = createRoom(state, { profileId: adult!.id, preset: 'adult', communicatorName: '爷爷', sceneId: 'park', participants: [], source: 'assisted' });
  state = recordRawExpression(addCard(state, water)); state = setFeedback(state, state.rooms[1]!.id, state.rooms[1]!.turns[0]!.id, 'clarify', '家人'); state = endRoom(state);
  state = createRoom(state, { preset: 'adult', communicatorName: '体验者', sceneId: null, participants: [], source: 'unknown' });
  state = recordRawExpression(addCard(state, apple)); state = addPlaybackEvent(state, state.rooms[2]!.id, state.rooms[2]!.turns[0]!.id, 'completed'); state = endRoom(state);
  state = createRoom(state, { preset: 'children', communicatorName: '仅草稿', sceneId: 'kitchen', participants: [], source: 'unknown' }); state = addCard(state, water);
  state.rooms.forEach((room, index) => { const at = `2026-10-${10 + index}T12:00:00.000Z`; room.startedAt = at; if (room.endedAt) room.endedAt = at; room.turns.forEach(turn => { turn.createdAt = at; }); });
  expect(validateState(state).valid).toBe(true);
  return { state, childId: child!.id, adultId: adult!.id };
}
async function setup(page: Page, enabled = true) {
  const fixture = historyFixture(); const unexpected: string[] = [];
  await page.addInitScript(state => { localStorage.setItem('cardtalk.communication.v1', JSON.stringify(state)); }, fixture.state);
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/health')) return route.fulfill({ json: { status: 'ok', llmConfigured: false, ttsConfigured: false, promptVersion: 'analytics-fixture' } });
    if (path.endsWith('/trial-session')) return route.fulfill({ json: { token: 'analytics-fixture-token', remaining: 10, limit: 10, resetAt: '2099-01-01T00:00:00Z', capabilities: { analytics: enabled, profileManagement: true, scenarioManagement: true, customCards: true } } });
    unexpected.push(path); return route.fulfill({ status: 503, json: { message: 'Unexpected API call: test never invokes paid services' } });
  });
  await page.goto('/'); await button(page, '分析报告').click(); return { ...fixture, unexpected };
}
const summary = (page: Page) => page.locator('.analytics-summary .analytics-number');

test('分析只统计确认表达，重复卡片去重、播放与未反馈不算理解，并可钻取记录', async ({ page }) => {
  const api = await setup(page); await expect(summary(page)).toHaveText(['4', '3', '50%', '50%']);
  await expect(page.locator('.analytics-evidence')).toHaveCount(4);
  await expect(page.locator('.analytics-content')).toContainText('未记录反馈 2 条');
  await expect(page.locator('.analytics-row').filter({ hasText: '苹果' })).toContainText('3 条表达');
  await expect(page.locator('.analytics-row').filter({ hasText: '候选修改后确认' })).toContainText('1 条');
  await page.locator('.analytics-evidence').filter({ hasText: privateExpression }).click();
  await expect(page.getByRole('dialog', { name: '本机历史', exact: true })).toBeVisible(); await expect(page.locator('.history-turn-final')).toHaveText(['苹果 苹果', privateExpression]); expect(api.unexpected).toEqual([]);
});

test('档案、场景与本地日期筛选一致，无反馈显示暂无反馈，逆序日期明确提示', async ({ page }) => {
  const data = await setup(page); await expect(summary(page)).toHaveText(['4', '3', '50%', '50%']);
  await page.getByRole('combobox', { name: '分析使用人' }).selectOption(data.childId!); await expect(summary(page)).toHaveText(['2', '1', '50%', '100%']);
  await page.getByRole('combobox', { name: '分析场景' }).selectOption('park'); await expect(summary(page)).toHaveText(['0', '0', '暂无反馈', '暂无反馈']);
  await page.getByRole('combobox', { name: '分析使用人' }).selectOption(data.adultId!); await expect(summary(page)).toHaveText(['1', '1', '100%', '0%']);
  await page.getByRole('combobox', { name: '分析使用人' }).selectOption('all'); await page.getByRole('combobox', { name: '分析场景' }).selectOption('all');
  await page.getByRole('textbox', { name: '分析开始日期', exact: true }).fill('2026-10-12'); await page.getByRole('textbox', { name: '分析结束日期', exact: true }).fill('2026-10-12'); await expect(summary(page)).toHaveText(['1', '1', '0%', '暂无反馈']);
  await page.getByRole('combobox', { name: '分析使用人' }).selectOption('trial'); await page.getByRole('combobox', { name: '分析场景' }).selectOption('none'); await expect(page.locator('.analytics-evidence')).toHaveCount(1);
  await page.getByRole('textbox', { name: '分析结束日期', exact: true }).fill('2026-10-11'); await expect(page.getByRole('alert')).toContainText('结束日期不能早于开始日期'); await expect(summary(page)).toHaveCount(0); await expect(button(page, '导出统计报告')).toHaveCount(0);
});

test('下载JSON仅导出当前筛选统计，不含照片、表达正文或逐条记录', async ({ page }) => {
  const data = await setup(page); await page.getByRole('combobox', { name: '分析使用人' }).selectOption(data.childId!);
  const pending = page.waitForEvent('download'); await button(page, '导出统计报告').click(); const download = await pending;
  expect(download.suggestedFilename()).toMatch(/^cardtalk-report-.*\.json$/);
  const stream = await download.createReadStream(); const chunks: Buffer[] = []; for await (const chunk of stream!) chunks.push(Buffer.from(chunk)); const raw = Buffer.concat(chunks).toString(); const report = JSON.parse(raw);
  expect(report.filters.profileId).toBe(data.childId); expect(report.metrics.confirmedCount).toBe(2); expect(report.metrics.understoodRate).toBe(1);
  expect(raw).not.toContain(photo); expect(raw).not.toContain('base64'); expect(raw).not.toContain(privateExpression); expect(raw).not.toContain('苹果 苹果'); expect(report).not.toHaveProperty('rooms'); expect(report).not.toHaveProperty('entries');
  await expect(page.locator('.analytics-content [role=status]')).toContainText('不包含照片或表达正文');
});

test('关闭分析能力不展示统计，基础历史和已选草稿仍可使用', async ({ page }) => {
  await setup(page, false); await expect(page.locator('.analytics-content')).toContainText('分析功能未开启'); await expect(summary(page)).toHaveCount(0); await expect(button(page, '导出统计报告')).toHaveCount(0);
  await button(page, '关闭').click(); await button(page, '本机历史').click(); await expect(page.locator('.history-row')).toHaveCount(4); await page.locator('.history-row').last().click(); await expect(page.locator('.history-turn-final')).toHaveCount(2);
  await button(page, '关闭').click(); await button(page, /使用原始表达/).click(); await expect(page.locator('.confirmed-text')).toHaveText('水');
});

test('390px分析筛选、统计与记录列表无横向溢出', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 }); await setup(page); await expect(summary(page)).toHaveCount(4);
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1)).toBe(false);
  await expect.poll(() => page.locator('.modal-card').evaluate(el => el.scrollWidth > el.clientWidth + 1)).toBe(false);
  await expect(page.getByRole('combobox', { name: '分析使用人' })).toBeVisible(); await page.screenshot({ path: 'test-results/analytics-mobile.png', fullPage: true });
});

test('无效日期不能静默产生空报表，修正日期后恢复统计', async ({ page }) => {
  await setup(page);
  const start = page.getByRole('textbox', { name: '分析开始日期', exact: true });
  for (const value of ['2026-99-99', '2026-02-30', '错误日期']) {
    await start.fill(value); await expect(page.locator('.analytics-content [role=alert]')).toBeVisible();
    await expect(summary(page)).toHaveCount(0); await expect(button(page, '导出统计报告')).toHaveCount(0);
  }
  await start.fill('2026-10-10'); await expect(summary(page)).toHaveText(['4', '3', '50%', '50%']);
});

test('重新打开分析先验证权限，迟到成功前和验证失败后都不展示旧统计', async ({ page }) => {
  await setup(page); await expect(summary(page)).toHaveText(['4', '3', '50%', '50%']); await button(page, '关闭').click();
  let requested = false;
  let release: (() => void) | undefined;
  await page.route('**/api/v1/trial-session', async route => {
    requested = true; await new Promise<void>(resolve => { release = resolve; });
    await route.fulfill({ json: { token: 'analytics-fixture-token', remaining: 10, limit: 10, resetAt: '2099-01-01T00:00:00Z', capabilities: { analytics: true, profileManagement: true, scenarioManagement: true, customCards: true } } });
  });
  await button(page, '分析报告').click(); await expect.poll(() => requested).toBe(true); await expect(summary(page)).toHaveCount(0); await expect(button(page, '导出统计报告')).toHaveCount(0);
  release!(); await expect(summary(page)).toHaveText(['4', '3', '50%', '50%']); await button(page, '关闭').click();
  await page.route('**/api/v1/trial-session', route => route.fulfill({ status: 503, json: { message: 'fixture authorization unavailable' } }));
  const response = page.waitForResponse(r => r.url().endsWith('/trial-session') && r.status() === 503);
  await button(page, '分析报告').click(); await response;
  await expect(page.locator('.analytics-content')).toContainText('分析功能未开启'); await expect(summary(page)).toHaveCount(0); await expect(button(page, '导出统计报告')).toHaveCount(0);
  await button(page, '关闭').click(); await button(page, '本机历史').click(); await expect(page.locator('.history-row')).toHaveCount(4);
});
