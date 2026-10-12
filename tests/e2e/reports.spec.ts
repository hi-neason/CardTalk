import { expect, test, type Page } from '@playwright/test';
import { createState, createFamilyProfile, createRoom, addCard, recordRawExpression, endRoom, setFeedback, addPlaybackEvent, validateState, CARDS, type Source } from '../../frontend/src/domain';

test.use({ timezoneId: 'Asia/Shanghai' });
const photo = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=';
const button = (page: Page, name: string) => page.getByRole('button', { name, exact: true });
const summary = (page: Page) => page.locator('.analytics-summary .analytics-number');
const select = (page: Page, name: string, value: string) => page.getByRole('combobox', { name, exact: true }).selectOption(value);
const fill = (page: Page, name: string, value: string) => page.getByRole('textbox', { name, exact: true }).fill(value);
function records() {
  let state = createFamilyProfile(createState(), { name: '报告使用人', preset: 'adult', image: photo });
  const apple = CARDS.find(card => card.label === '苹果')!, water = CARDS.find(card => card.label === '水')!;
  const sources: Source[] = ['independent', 'assisted', 'independent', 'unknown'];
  for (let index = 0; index < 4; index++) {
    state = createRoom(state, { profileId: state.family.profiles[0]!.id, preset: 'adult', communicatorName: '报告使用人', sceneId: 'kitchen', participants: [], source: sources[index]! });
    state = addCard(state, index === 1 ? water : apple); if (index === 0) state = addCard(state, apple);
    state = recordRawExpression(state); const room = state.rooms[index]!, turn = room.turns[0]!;
    if (index !== 3) state = setFeedback(state, room.id, turn.id, index === 1 ? 'clarify' : 'understood', '家人');
    if (index === 3) state = addPlaybackEvent(state, room.id, turn.id, 'completed');
    if (index === 2) state = addPlaybackEvent(state, room.id, turn.id, 'failed');
    if (index !== 3) state = endRoom(state);
  }
  state.rooms.forEach((room, index) => {
    const day = index < 2 ? '10' : '12', at = `2026-10-${day}T12:00:00.000Z`;
    room.startedAt = at; if (room.endedAt) room.endedAt = at; room.turns[0]!.createdAt = at; delete room.turns[0]!.processSnapshot;
  });
  const processAt = '2026-10-10T12:00:00.000Z';
  state.rooms[0]!.turns[0]!.processSnapshot = { startedAt: processAt, confirmedAt: '2026-10-10T12:00:10.000Z', events: [{ id: 'succeeded', type: 'generation-succeeded', at: processAt, inputVersion: 2 }, { id: 'rejected', type: 'candidates-rejected', at: processAt, inputVersion: 2 }] };
  expect(validateState(state).valid).toBe(true); return state;
}
async function setup(page: Page) {
  const fixture = records(); const unexpected: string[] = [];
  await page.addInitScript(state => { if (!localStorage.getItem('cardtalk.communication.v1')) localStorage.setItem('cardtalk.communication.v1', JSON.stringify(state)); }, fixture);
  await page.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/health')) return route.fulfill({ json: { status: 'ok', llmConfigured: false, ttsConfigured: false, promptVersion: 'report-fixture' } });
    if (path.endsWith('/trial-session')) return route.fulfill({ json: { token: 'report-fixture-token', remaining: 10, limit: 10, resetAt: '2099-01-01T00:00:00Z', capabilities: { analytics: true, profileManagement: true, scenarioManagement: true, customCards: true } } });
    unexpected.push(path); return route.fulfill({ status: 503, json: { message: 'No live provider is allowed in this test' } });
  });
  await page.goto('/'); await button(page, '分析报告').click(); await expect(summary(page)).toHaveText(['4', '4', '75%', '67%']); return { fixture, unexpected };
}
async function save(page: Page) { await button(page, '保存统计报告').click(); await expect(page.locator('.saved-report')).toHaveCount(1); }
async function stored(page: Page) { return page.evaluate(() => JSON.parse(localStorage.getItem('cardtalk.communication.v1')!)); }

test('分类来源状态共同筛选，过程缺失与播放结果独立列出且不等于理解', async ({ page }) => {
  await setup(page);
  await expect(page.locator('.analytics-content')).toContainText('过程已采集 1 条，未采集 3 条');
  await expect(page.locator('.analytics-row').filter({ hasText: '首卡至确认平均时间' })).toContainText('10.0 秒');
  await expect(page.locator('.analytics-row').filter({ hasText: '播放完成' })).toContainText('1 条');
  await expect(page.locator('.analytics-row').filter({ hasText: '播放失败' })).toContainText('1 条');
  await select(page, '分析卡片分类', 'fruit'); await expect(summary(page)).toHaveText(['3', '3', '67%', '100%']);
  await select(page, '分析操作来源', 'independent'); await expect(summary(page)).toHaveText(['2', '2', '100%', '100%']);
  await select(page, '分析聊天室状态', 'active'); await expect(summary(page)).toHaveText(['0', '0', '暂无反馈', '暂无反馈']);
  await select(page, '分析操作来源', 'all'); await expect(summary(page)).toHaveText(['1', '1', '0%', '暂无反馈']);
  await expect(page.locator('.analytics-content')).toContainText('过程已采集 0 条，未采集 1 条');
});

test('两个时间段对比使用理解率百分点差，缺反馈时不能计算', async ({ page }) => {
  await setup(page); await fill(page, '分析开始日期', '2026-10-12'); await fill(page, '分析结束日期', '2026-10-12');
  await fill(page, '对比开始日期', '2026-10-10'); await fill(page, '对比结束日期', '2026-10-10');
  await expect(page.locator('.analytics-content')).toContainText('当前 2 条 · 对比 2 条'); await expect(page.locator('.analytics-content')).toContainText('理解率差：50.0 个百分点');
  await select(page, '分析操作来源', 'unknown'); await expect(page.locator('.analytics-content')).toContainText('缺少明确反馈，无法对比');
});

test('保存报告刷新保留，修改反馈标记依据变更但冻结旧快照，移除不删历史', async ({ page }) => {
  await setup(page); await save(page); const original = (await stored(page)).reports[0];
  await page.reload(); await button(page, '分析报告').click(); await expect(page.locator('.saved-report')).toHaveCount(1); await expect(page.locator('.saved-report')).toContainText('依据未变更');
  await page.locator('.saved-report .analytics-evidence').click(); await expect(page.locator('.saved-report-detail')).toContainText('理解率 67%');
  await page.locator('.analytics-evidence').filter({ hasText: '苹果 苹果' }).click();
  await page.locator('.history-turn').getByRole('button', { name: '没理解', exact: true }).click(); await button(page, '关闭').click(); await button(page, '分析报告').click();
  await expect(summary(page)).toHaveText(['4', '4', '75%', '33%']); await expect(page.locator('.saved-report')).toContainText('依据已变更');
  await page.locator('.saved-report .analytics-evidence').click(); await expect(page.locator('.saved-report-detail')).toContainText('理解率 67%'); expect((await stored(page)).reports[0]).toEqual(original);
  const pending = page.waitForEvent('download'); await button(page, '导出已保存报告').click(); const download = await pending; const stream = await download.createReadStream(); const chunks: Buffer[] = []; for await (const chunk of stream!) chunks.push(Buffer.from(chunk)); const text = Buffer.concat(chunks).toString(), exported = JSON.parse(text);
  expect(exported.metrics.understoodRate).toBeCloseTo(2 / 3); expect(text).not.toContain('sourceSignature'); expect(text).not.toContain('evidenceIds'); expect(text).not.toContain('base64'); expect(text).not.toContain('苹果 苹果'); expect(text).not.toContain(photo);
  await button(page, '移除报告').click(); await page.locator('.uni-modal__btn_primary').click(); await expect(page.locator('.saved-report')).toHaveCount(0);
  const state = await stored(page); expect(state.reports).toHaveLength(0); expect(state.rooms).toHaveLength(4); expect(state.rooms.flatMap((room: { turns: unknown[] }) => room.turns)).toHaveLength(4);
  await button(page, '关闭').click(); await button(page, '本机历史').click(); await expect(page.locator('.history-row')).toHaveCount(4);
});

test('保存报告遇本机写入失败不产生假成功或内存报告，恢复存储后可重试', async ({ page }) => {
  await setup(page); const before = await stored(page);
  await page.evaluate(() => { const original = Storage.prototype.setItem; (window as unknown as { restoreStorage: () => void }).restoreStorage = () => { Storage.prototype.setItem = original; }; Storage.prototype.setItem = function (key, value) { if (key === 'cardtalk.communication.v1') throw new DOMException('Test quota full', 'QuotaExceededError'); original.call(this, key, value); }; });
  await button(page, '保存统计报告').click(); await expect(page.locator('.analytics-content [role=status]')).toContainText('报告未保存'); await expect(page.locator('.saved-report')).toHaveCount(0); expect(await stored(page)).toEqual(before);
  await page.evaluate(() => (window as unknown as { restoreStorage: () => void }).restoreStorage()); await save(page); await page.reload(); await button(page, '分析报告').click(); await expect(page.locator('.saved-report')).toHaveCount(1);
});
