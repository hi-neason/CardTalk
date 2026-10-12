import { expect, test, type Page } from '@playwright/test';
const button = (page: Page, name: string | RegExp) => page.getByRole('button', { name, exact: typeof name === 'string' });
const field = (page: Page, name: string) => page.getByRole('textbox', { name, exact: true });
async function setup(page: Page) {
  const controls = { pauseTrial: false, requested: false, release: () => {}, speech: 0, expansions: 0 };
  await page.addInitScript(() => {
    const calls: string[] = []; Object.defineProperty(window, '__deviceCalls', { value: calls });
    Object.defineProperty(window, 'speechSynthesis', { configurable: true, value: { getVoices: () => [], cancel: () => {}, speak: (utterance: SpeechSynthesisUtterance) => { calls.push(utterance.text); queueMicrotask(() => utterance.onstart?.(new SpeechSynthesisEvent('start', { utterance }))); } } });
  });
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/health')) return route.fulfill({ json: { status: 'ok', llmConfigured: true, ttsConfigured: true, ttsEmotionSupported: true } });
    if (path.endsWith('/trial-session')) { if (controls.pauseTrial) { controls.requested = true; await new Promise<void>(resolve => { controls.release = resolve; }); } return route.fulfill({ json: { token: 'notes-fixture', remaining: 10, limit: 10, resetAt: '2099-01-01', capabilities: {} } }); }
    if (path.endsWith('/expansions')) { controls.expansions++; const input = route.request().postDataJSON(); return route.fulfill({ json: { requestId: input.requestId, inputVersion: input.inputVersion, candidates: [{ id: 'note-candidate', text: '我想吃苹果', sourceCardIds: input.cards.map((c: { id: string }) => c.id) }], needsClarification: false, clarification: null, promptVersion: 'notes-fixture', remaining: 9 } }); }
    if (path.endsWith('/speech')) controls.speech++;
    return route.fulfill({ status: 503, json: { message: 'No paid service called by this test' } });
  });
  await page.goto('/'); await button(page, /开始沟通/).click(); await button(page, /创建聊天室，开始选卡/).click(); await button(page, '选择苹果').click(); return controls;
}
async function generate(page: Page) { await button(page, /帮我整理成一句话/).click(); await expect(page.locator('.candidate-item')).toHaveCount(1); }

test('反馈备注保存刷新保留，历史更正与清空真实持久化', async ({ page }) => {
  await setup(page); await button(page, /使用原始表达/).click(); await expect(button(page, '保存反馈备注')).toBeDisabled();
  await field(page, '反馈者称呼').fill('妈妈'); await button(page, '需要再说明').click(); await field(page, '理解反馈备注').fill('需要再展示一次苹果卡片'); await button(page, '保存反馈备注').click(); await page.reload();
  await expect(field(page, '理解反馈备注')).toHaveValue('需要再展示一次苹果卡片');
  await button(page, '本机历史').click(); await page.locator('.history-row').click(); await expect(page.locator('.history-turn')).toContainText('反馈备注：需要再展示一次苹果卡片');
  await field(page, '反馈备注 苹果').fill('已通过再次展示理解'); await button(page, '保存这条备注').click(); await expect(page.locator('.history-turn')).toContainText('反馈备注：已通过再次展示理解');
  await page.locator('.history-turn').getByRole('button', { name: '理解了', exact: true }).click(); await expect(page.locator('.history-turn')).toContainText('反馈备注：已通过再次展示理解');
  await page.reload(); await button(page, '本机历史').click(); await page.locator('.history-row').click(); await expect(field(page, '反馈备注 苹果')).toHaveValue('已通过再次展示理解');
  await field(page, '反馈备注 苹果').fill(''); await button(page, '保存这条备注').click(); await expect(page.locator('.history-turn')).not.toContainText('反馈备注：');
  const note = await page.evaluate(() => JSON.parse(localStorage.getItem('cardtalk.communication.v1')!).rooms[0].turns[0].feedback); expect(note).toMatchObject({ value: 'understood', by: '妈妈' }); expect(note).not.toHaveProperty('note');
});

test('切换设置使候选失效不计主动拒绝，真正拒绝才记录过程事件', async ({ page }) => {
  await setup(page); await generate(page); await button(page, '设置').click(); await page.getByRole('switch', { name: '情绪识别', exact: true }).click(); await button(page, '关闭').click(); await expect(page.locator('.candidate-item')).toHaveCount(0);
  const rejected = () => page.evaluate(() => JSON.parse(localStorage.getItem('cardtalk.communication.v1')!).rooms[0].draft.process.events.filter((e: { type: string }) => e.type === 'candidates-rejected').length);
  expect(await rejected()).toBe(0); await generate(page); await button(page, /都不合适/).click(); expect(await rejected()).toBe(1);
});

test('设置试听停止声音且阻止先前迟到鉴权触发远程播放', async ({ page }) => {
  const controls = await setup(page); await button(page, /使用原始表达/).click(); await button(page, '设置').click(); await page.getByRole('switch', { name: '语音合成', exact: true }).click(); await button(page, '关闭').click();
  controls.pauseTrial = true; await button(page, /说出这句话/).click(); await expect.poll(() => controls.requested).toBe(true);
  await button(page, '设置').click(); await button(page, '试听设备声音').click(); await expect(button(page, '停止试听')).toBeVisible(); await button(page, '停止试听').click(); await expect(button(page, '停止试听')).toHaveCount(0);
  const response = page.waitForResponse(r => r.url().endsWith('/trial-session')); controls.release(); await (await response).finished();
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
  expect(controls.speech).toBe(0); expect(controls.expansions).toBe(0); expect(await page.evaluate(() => (window as unknown as { __deviceCalls: string[] }).__deviceCalls)).toEqual(['每一种表达，都值得被认真听见。']);
});

test('不同表达的反馈备注互不覆盖，历史改反馈保留对应表达自己的备注', async ({ page }) => {
  await setup(page); await button(page, /使用原始表达/).click(); await button(page, '理解了').click(); await field(page, '理解反馈备注').fill('苹果需要切成小块'); await button(page, '保存反馈备注').click();
  await button(page, /开始下一句/).click(); await button(page, '选择水').click(); await button(page, /使用原始表达/).click(); await button(page, '理解了').click(); await field(page, '理解反馈备注').fill('水需要温热'); await button(page, '保存反馈备注').click();
  await button(page, '本机历史').click(); await page.locator('.history-row').click(); const first = page.locator('.history-turn').first(); await first.getByRole('button', { name: '需要再说明', exact: true }).click();
  await expect(first).toContainText('反馈备注：苹果需要切成小块'); await expect(first).not.toContainText('水需要温热'); await expect(page.locator('.history-turn').last()).toContainText('反馈备注：水需要温热');
  const notes = await page.evaluate(() => JSON.parse(localStorage.getItem('cardtalk.communication.v1')!).rooms[0].turns.map((turn: { feedback: { note: string } }) => turn.feedback.note)); expect(notes).toEqual(['苹果需要切成小块', '水需要温热']);
});
