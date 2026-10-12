import { expect, test, type Page } from '@playwright/test';
import { createState, createFamilyProfile, createCustomScene, createRoom, addCard, recordRawExpression, endRoom, saveCommunicationReport, CARDS } from '../../frontend/src/domain';
const button = (page: Page, name: string | RegExp) => page.getByRole('button', { name, exact: typeof name === 'string' });
async function setup(page: Page) {
  let state = createFamilyProfile(createState(), { name: '家庭表达者', preset: 'adult', interests: '喜欢散步' }); state = createCustomScene(state, { name: '家庭阳台', description: '阳台看花' });
  state = createRoom(state, { profileId: state.family.profiles[0]!.id, preset: 'adult', communicatorName: '家庭表达者', sceneId: state.family.scenes[0]!.id, participants: [], source: 'independent' }); state = saveCommunicationReport(endRoom(recordRawExpression(addCard(state, CARDS[0]!))));
  await page.addInitScript(state => { if (!localStorage.getItem('cardtalk.communication.v1')) localStorage.setItem('cardtalk.communication.v1', JSON.stringify(state)); }, state);
  const controls = { flags: { profiles: true, scenarios: true, analytics: true }, role: 'owner', narrativeStatus: 200, expansionStatus: 200, holdRights: false, rightsRequested: false, releaseRights: () => {}, rightsSettled: Promise.resolve(), trialOn: false, expiry: null as string | null, inputs: [] as { path: string; data: any; headers: Record<string, string> }[], holdExpansion: false, release: () => {}, settled: Promise.resolve() };
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/health')) return route.fulfill({ json: { status: 'ok', llmConfigured: true, ttsConfigured: false } });
    if (path.endsWith('/trial-session')) return route.fulfill({ json: { token: 'scope-fixture-token', remaining: 10, limit: 10, resetAt: '2099-01-01', capabilities: { profileManagement: controls.trialOn, scenarioManagement: controls.trialOn, analytics: controls.trialOn, customCards: controls.trialOn } } });
    if (path.endsWith('/accounts/me')) return route.fulfill({ json: { account: { id: 'account-scope', username: 'scope_user' } } });
    if (path === '/api/v1/families') return route.fulfill({ json: [{ id: 'family-scope', name: '正式家庭', role: 'owner' }] });
    if (path.endsWith('/members')) return route.fulfill({ json: [] });
    if (path.endsWith('/entitlements')) {
      const snapshot = { configured: true, role: controls.role, features: Object.entries(controls.flags).map(([feature, active]) => ({ feature, active, reason: 'purchase', expiresAt: controls.expiry, sources: active ? ['purchase'] : [], actions: { readExisting: true, writeAdvanced: active, purchase: true } })) };
      let settle = () => {}; if (controls.holdRights) { controls.rightsRequested = true; controls.rightsSettled = new Promise<void>(resolve => settle = resolve); await new Promise<void>(resolve => controls.releaseRights = resolve); }
      try { await route.fulfill({ json: snapshot }); } finally { settle(); } return;
    }
    if (path.endsWith('/billing/products')) return route.fulfill({ json: { products: [], providerConfigured: false, canPurchase: false } });
    if (path.endsWith('/billing/orders')) return route.fulfill({ json: [] });
    if (path.endsWith('/expansions') || path.endsWith('/reports/narrative')) {
      const data = route.request().postDataJSON(); controls.inputs.push({ path, data, headers: route.request().headers() });
      if (path.endsWith('/reports/narrative') && controls.narrativeStatus !== 200) return route.fulfill({ status: controls.narrativeStatus, json: { code: 'FAMILY_ACCESS_DENIED', message: '家庭权限已变更' } });
      if (path.endsWith('/reports/narrative')) return route.fulfill({ json: { requestId: data.requestId, text: '请结合已记录反馈回顾表达。', provider: 'deepseek', promptVersion: 'family-scope', remaining: 3 } });
      let settle = () => {}; controls.settled = new Promise<void>(resolve => settle = resolve); if (controls.holdExpansion) await new Promise<void>(resolve => controls.release = resolve);
      try { if (controls.expansionStatus !== 200) { await route.fulfill({ status: controls.expansionStatus, json: { code: 'FAMILY_ACCESS_DENIED', message: '家庭权限已变更' } }); return; } await route.fulfill({ json: { requestId: data.requestId, inputVersion: data.inputVersion, candidates: [{ id: 'scope-candidate', text: '我想吃苹果', sourceCardIds: data.cards.map((card: { id: string }) => card.id) }], needsClarification: false, clarification: null, promptVersion: 'scope-fixture', remaining: 9 } }); } finally { settle(); } return;
    }
    return route.fulfill({ status: 503, json: { message: 'Only deterministic family scope fixtures allowed' } });
  });
  await page.goto('/'); return controls;
}
async function account(page: Page) { await button(page, '设置').click(); await button(page, '账户与家庭成员').click(); await expect(button(page, '使用这个家庭的功能')).toBeEnabled(); }
async function choose(page: Page) { await account(page); await button(page, '使用这个家庭的功能').click(); await expect(page.locator('.account-manager [role=status]')).toContainText('已选择 正式家庭'); await button(page, '关闭').click(); }
async function manage(page: Page, tab: string) { await button(page, '家庭管理').click(); await button(page, tab).click(); }
async function start(page: Page) { await button(page, /开始沟通/).click(); await button(page, '家庭表达者').click(); await button(page, '家庭阳台').click(); await button(page, /创建聊天室，开始选卡/).click(); await button(page, '选择苹果').click(); }

test('体验功能全关时正式权益启用家庭管理、扩写背景与家庭AI报告路由', async ({ page }) => {
  const controls = await setup(page); await choose(page);
  await manage(page, '使用人'); await expect(button(page, '新增使用人')).toBeEnabled(); await button(page, '编辑使用人 家庭表达者').click(); await page.getByRole('textbox', { name: '兴趣与喜好', exact: true }).fill('喜欢散步和看花'); await button(page, '保存使用人').click(); await button(page, '场景').click(); await expect(button(page, '新增场景')).toBeEnabled(); await button(page, '关闭').click();
  await start(page); await button(page, /帮我整理成一句话/).click(); await expect(page.locator('.candidate-item')).toHaveCount(1);
  const expansion = controls.inputs[0]!; expect(expansion.path).toBe('/api/v1/families/family-scope/expansions'); expect(expansion.headers['x-cardtalk-trial-token']).toBe('scope-fixture-token'); expect(expansion.data.context.profile.interests).toBe('喜欢散步和看花'); expect(expansion.data.context.scene.description).toContain('阳台看花');
  await button(page, '分析报告').click(); await button(page, '生成 AI 报告说明').click(); await page.locator('.uni-modal__btn_primary').click(); await expect(button(page, '采用 AI 说明并保存报告')).toBeVisible();
  expect(controls.inputs[1]!.path).toBe('/api/v1/families/family-scope/reports/narrative'); expect(controls.inputs[1]!.headers['x-cardtalk-trial-token']).toBe('scope-fixture-token'); expect(Object.keys(controls.inputs[1]!.data)).toEqual(['requestId', 'facts']);
});

test('正式家庭各模块独立授权，不能借体验权限绕过；Billing刷新即更新当前权益', async ({ page }) => {
  const controls = await setup(page); controls.trialOn = true; controls.flags.scenarios = false; controls.flags.analytics = false; await choose(page);
  await manage(page, '使用人'); await expect(button(page, '新增使用人')).toBeEnabled(); await button(page, '场景').click(); await expect(button(page, '新增场景')).toBeDisabled(); await button(page, '关闭').click(); await button(page, '分析报告').click(); await expect(button(page, '生成 AI 报告说明')).toHaveCount(0); await expect(page.locator('.analytics-number')).toHaveCount(0); await button(page, '关闭').click();
  controls.flags.scenarios = true; await account(page); await button(page, '刷新权益与订单').click(); await expect(button(page, '刷新权益与订单')).toBeEnabled(); await button(page, '关闭').click(); await manage(page, '场景'); await expect(button(page, '新增场景')).toBeEnabled();
});

test('正式权益到期后禁止生成新统计但旧报告可查看导出', async ({ page }) => {
  const controls = await setup(page); await choose(page); controls.expiry = '2000-01-01T00:00:00Z'; await account(page); await button(page, '刷新权益与订单').click(); await expect(button(page, '刷新权益与订单')).toBeEnabled(); await button(page, '关闭').click();
  await button(page, '分析报告').click(); await expect(page.locator('.analytics-number')).toHaveCount(0); await expect(button(page, '生成 AI 报告说明')).toHaveCount(0); await page.locator('.saved-report .analytics-evidence').click(); await expect(page.locator('.saved-report-detail')).toContainText('已确认 1 条');
  const downloading = page.waitForEvent('download'); await button(page, '导出已保存报告').click(); const stream = await (await downloading).createReadStream(); const chunks: Buffer[] = []; for await (const chunk of stream!) chunks.push(Buffer.from(chunk)); expect(JSON.parse(Buffer.concat(chunks).toString()).metrics.confirmedCount).toBe(1);
});

test('切回体验范围取消正式家庭旧扩写，迟到结果不恢复', async ({ page }) => {
  const controls = await setup(page); await choose(page); await start(page); controls.holdExpansion = true; await button(page, /帮我整理成一句话/).click(); await expect.poll(() => controls.inputs.length).toBe(1);
  await account(page); await button(page, '返回本机体验').click(); await button(page, '关闭').click(); controls.release(); await controls.settled; await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
  await expect(page.locator('.candidate-item')).toHaveCount(0); await expect(page.locator('.selected-word')).toHaveCount(1); await expect(button(page, /帮我整理成一句话/)).toBeEnabled();
  controls.holdExpansion = false; await button(page, /帮我整理成一句话/).click(); await expect.poll(() => controls.inputs.length).toBe(2); expect(controls.inputs[1]!.path).toBe('/api/v1/expansions'); expect(controls.inputs[1]!.data.context).not.toHaveProperty('profile'); expect(controls.inputs[1]!.data.context.scene).toBeNull(); await expect(page.locator('.candidate-item')).toHaveCount(1);
});

test('成员降为协助者后正式卡片及高级管理不可新增，不沿用旧账户列表角色', async ({ page }) => {
  const controls = await setup(page); controls.role = 'member'; await choose(page); await manage(page, '自定义卡片'); await expect(button(page, '新增卡片')).toBeEnabled(); await button(page, '关闭').click();
  controls.role = 'helper'; await account(page); await button(page, '刷新权益与订单').click(); await expect(button(page, '刷新权益与订单')).toBeEnabled(); await button(page, '关闭').click();
  await manage(page, '自定义卡片'); await expect(button(page, '新增卡片')).toBeDisabled(); await button(page, '使用人').click(); await expect(button(page, '新增使用人')).toBeDisabled(); await button(page, '关闭').click(); await button(page, '分析报告').click(); await expect(button(page, '生成 AI 报告说明')).toHaveCount(0);
});

for (const status of [401, 403]) {
  test(`家庭AI报告 ${status} 暂停新功能，仍能导出原来保存的报告`, async ({ page }) => {
    const controls = await setup(page); await choose(page); controls.narrativeStatus = status; await button(page, '分析报告').click(); await button(page, '生成 AI 报告说明').click(); await page.locator('.uni-modal__btn_primary').click();
    await expect(page.locator('.analytics-number')).toHaveCount(0); await expect(button(page, '生成 AI 报告说明')).toHaveCount(0); await expect(button(page, '采用 AI 说明并保存报告')).toHaveCount(0);
    await page.locator('.saved-report .analytics-evidence').click(); const download = page.waitForEvent('download'); await button(page, '导出已保存报告').click(); expect((await download).suggestedFilename()).toMatch(/cardtalk-report/);
    await button(page, '关闭').click(); await button(page, /开始沟通/).click(); await expect(button(page, '家庭表达者')).toHaveCount(0);
  });
}

test('Billing旧权益请求在家庭权限暂停后返回，不能重新授予功能', async ({ page }) => {
  const controls = await setup(page); await choose(page); await start(page); controls.holdExpansion = true; await button(page, /帮我整理成一句话/).click(); await expect.poll(() => controls.inputs.length).toBe(1);
  controls.holdRights = true; await account(page); await expect.poll(() => controls.rightsRequested).toBe(true);
  controls.expansionStatus = 403; controls.release(); await controls.settled; await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
  controls.releaseRights(); await controls.rightsSettled; await expect(button(page, '刷新权益与订单')).toBeEnabled(); await button(page, '关闭').click();
  await button(page, '结束聊天室').click(); await page.locator('.uni-modal__btn_primary').click(); await button(page, /开始沟通/).click(); await expect(button(page, '家庭表达者')).toHaveCount(0);
});
