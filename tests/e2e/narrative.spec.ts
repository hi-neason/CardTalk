import { expect, test, type Page } from '@playwright/test';
import { createState, createFamilyProfile, createRoom, addCard, recordRawExpression, CARDS } from '../../frontend/src/domain';
const text = '本次有1条已确认表达，尚无理解反馈。建议先补充反馈再回顾。';
const button = (page: Page, name: string) => page.getByRole('button', { name, exact: true });
async function setup(page: Page, enabled = true) {
  let state = createFamilyProfile(createState(), { name: '私密使用人姓名', preset: 'children', observations: '私密家人观察', observer: '私密记录人', observedAt: '2026-10-12', image: 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=' });
  state = createRoom(state, { profileId: state.family.profiles[0]!.id, communicatorName: '私密使用人姓名', preset: 'children', source: 'independent', sceneId: 'kitchen', participants: [] });
  const apple = CARDS.find(card => card.label === '苹果')!; state = recordRawExpression(addCard(addCard(state, apple), apple));
  await page.addInitScript(state => { if (!localStorage.getItem('cardtalk.communication.v1')) localStorage.setItem('cardtalk.communication.v1', JSON.stringify(state)); }, state);
  const controls = { inputs: [] as { requestId: string; facts: Record<string, number> }[], pause: false, release: () => {}, settled: Promise.resolve(), pauseTrial: false, trialRequested: false, releaseTrial: () => {}, status: 200, unexpected: [] as string[] };
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/health')) return route.fulfill({ json: { status: 'ok', llmConfigured: true, ttsConfigured: false } });
    if (path.endsWith('/trial-session')) { if (controls.pauseTrial) { controls.trialRequested = true; await new Promise<void>(resolve => controls.releaseTrial = resolve); } return route.fulfill({ json: { token: 'narrative-fixture', remaining: 10, limit: 10, resetAt: '2099-01-01', capabilities: { analytics: enabled } } }); }
    if (path.endsWith('/reports/narrative')) {
      const input = route.request().postDataJSON(); controls.inputs.push(input); let settle = () => {}; controls.settled = new Promise<void>(resolve => settle = resolve);
      if (controls.pause) await new Promise<void>(resolve => controls.release = resolve);
      try { await route.fulfill(controls.status === 200 ? { json: { requestId: input.requestId, text, promptVersion: 'narrative-fixture-v1', provider: 'deepseek', remaining: 4 } } : { status: controls.status, json: { code: 'LLM_NOT_CONFIGURED', message: '报告说明服务尚未配置' } }); } finally { settle(); }
      return;
    }
    controls.unexpected.push(path); return route.fulfill({ status: 503, json: { message: 'No real provider in this test' } });
  });
  await page.goto('/'); await button(page, '分析报告').click(); await expect(page.locator('.analytics-content')).toBeVisible();
  if (enabled) await expect(page.locator('.analytics-number')).toHaveText(['1', '1', '0%', '暂无反馈']); return controls;
}
async function generate(page: Page) { await button(page, '生成 AI 报告说明').click(); await expect(page.locator('.uni-modal')).toContainText('发送统计并生成'); await page.locator('.uni-modal__btn_primary').click(); }
async function flush(page: Page) { await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))); }

test('AI说明只发13个数字统计，明确采用后才保存，刷新和导出保留说明不含私密正文', async ({ page }) => {
  const controls = await setup(page); await generate(page); await expect(button(page, '采用 AI 说明并保存报告')).toBeVisible(); await expect(page.locator('.saved-report')).toHaveCount(0);
  expect(controls.inputs).toHaveLength(1); const input = controls.inputs[0]!; expect(Object.keys(input).sort()).toEqual(['facts', 'requestId']);
  expect(Object.keys(input.facts).sort()).toEqual(['confirmedCount', 'roomCount', 'activeDays', 'understood', 'clarify', 'notUnderstood', 'unrecorded', 'raw', 'ai', 'edited', 'saved', 'processCollected', 'processMissing'].sort());
  expect(Object.values(input.facts).every(value => Number.isSafeInteger(value) && value >= 0)).toBe(true); expect(input.facts.confirmedCount).toBe(1); expect(input.facts.unrecorded).toBe(1);
  for (const secret of ['私密', '苹果', 'base64', 'image', 'observations']) expect(JSON.stringify(input)).not.toContain(secret);
  await button(page, '采用 AI 说明并保存报告').click(); await expect(page.locator('.saved-report')).toHaveCount(1); await page.reload(); await button(page, '分析报告').click(); await page.locator('.saved-report .analytics-evidence').click(); await expect(page.locator('.saved-report-detail')).toContainText(text);
  const pending = page.waitForEvent('download'); await button(page, '导出已保存报告').click(); const stream = await (await pending).createReadStream(); const chunks: Buffer[] = []; for await (const chunk of stream!) chunks.push(Buffer.from(chunk)); const raw = Buffer.concat(chunks).toString();
  expect(JSON.parse(raw).narrative).toMatchObject({ text, provider: 'deepseek', promptVersion: 'narrative-fixture-v1' }); for (const secret of ['私密', '苹果 苹果', 'base64', 'sourceSignature']) expect(raw).not.toContain(secret); expect(controls.unexpected).toEqual([]);
});

test('筛选改变清除已生成说明，取消等待中的说明且迟到响应不恢复', async ({ page }) => {
  const controls = await setup(page); await generate(page); await expect(button(page, '采用 AI 说明并保存报告')).toBeVisible();
  await page.getByRole('combobox', { name: '分析操作来源' }).selectOption('independent'); await expect(button(page, '采用 AI 说明并保存报告')).toHaveCount(0);
  controls.pause = true; await generate(page); await expect.poll(() => controls.inputs.length).toBe(2);
  await page.getByRole('combobox', { name: '分析操作来源' }).selectOption('all'); controls.release(); await controls.settled; await flush(page);
  await expect(button(page, '采用 AI 说明并保存报告')).toHaveCount(0); await expect(button(page, '生成 AI 报告说明')).toBeEnabled(); await expect(page.locator('.saved-report')).toHaveCount(0);
});

test('等待鉴权时取消说明不发provider请求', async ({ page }) => {
  const controls = await setup(page); controls.pauseTrial = true; await generate(page); await expect.poll(() => controls.trialRequested).toBe(true); await button(page, '取消说明生成').click();
  const response = page.waitForResponse(r => r.url().endsWith('/trial-session')); controls.releaseTrial(); await (await response).finished(); await flush(page);
  expect(controls.inputs).toHaveLength(0); await expect(button(page, '采用 AI 说明并保存报告')).toHaveCount(0); await expect(button(page, '生成 AI 报告说明')).toBeEnabled();
});

test('未配置服务失败保留真实统计，不生成替代说明或保存报告', async ({ page }) => {
  const controls = await setup(page); controls.status = 503; await generate(page); await expect(page.locator('.analytics-content [role=alert]')).toHaveText('报告说明服务尚未配置');
  await expect(page.locator('.analytics-number')).toHaveText(['1', '1', '0%', '暂无反馈']); await expect(button(page, '采用 AI 说明并保存报告')).toHaveCount(0); await expect(page.locator('.saved-report')).toHaveCount(0); await expect(button(page, '生成 AI 报告说明')).toBeEnabled();
});

test('分析能力未开启时没有AI说明入口', async ({ page }) => {
  const controls = await setup(page, false); await expect(page.locator('.analytics-content')).toContainText('分析功能未开启'); await expect(button(page, '生成 AI 报告说明')).toHaveCount(0); expect(controls.inputs).toHaveLength(0);
});
