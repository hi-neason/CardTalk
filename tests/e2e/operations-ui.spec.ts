import { test, expect, type Page } from '@playwright/test';
import { createState, createRoom, addCard, recordRawExpression, endRoom, CARDS } from '../../frontend/src/domain';
import type { PublicPackage } from '../../frontend/src/domain/resources';
const button = (page: Page, name: string) => page.getByRole('button', { name, exact: true });
const pack: PublicPackage = { id: 'daily-v1', title: '日常资源测试', source: 'CardTalk 自编测试文字', license: 'CC0-1.0', cards: [{ id: 'sip', label: '小口喝水', category: 'actions', icon: 'drink', kind: 'phrase', description: '表达饮水需求', sceneIds: ['break'] }], scenes: [{ id: 'break', name: '活动间歇', description: '休息与饮水', recommendedCardIds: ['sip'] }] };
const oldTicket = { id: 'old-submitted', kind: 'support', message: '最早仍待处理的反馈', contact: '', status: 'submitted', reply: '', createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z' };
async function setup(page: Page, sceneEnabled = true) {
  let state = createRoom(createState(), { preset: 'adult', communicatorName: '历史本人', sceneId: 'kitchen', participants: [], source: 'unknown' });
  state = endRoom(recordRawExpression(addCard(state, CARDS[0]!)));
  const controls = { state, supportAllowed: true, operatorStatus: 200, metricsStatus: 200, contentStatus: 200, publicGets: 0, revision: 1, current: pack, versions: [pack], releases: [] as any[], saves: [] as any[], audits: [{ revision: 1, versionId: pack.id, actorId: 'operator-fixture', createdAt: '2026-10-12T00:00:00Z' }], cursors: [] as string[], updates: [] as any[], ticket: { ...oldTicket }, unexpected: [] as string[] };
  await page.addInitScript(initial => { if (!localStorage.getItem('cardtalk.communication.v1')) localStorage.setItem('cardtalk.communication.v1', JSON.stringify(initial)); }, state);
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url()), path = url.pathname, method = route.request().method();
    if (path.endsWith('/health')) return route.fulfill({ json: { status: 'ok', llmConfigured: false, ttsConfigured: false } });
    if (path.endsWith('/trial-session')) return route.fulfill({ json: { token: 'operations-test', remaining: 10, limit: 10, capabilities: { customCards: true, scenarioManagement: sceneEnabled, profileManagement: true, analytics: true } } });
    if (path.endsWith('/accounts/me')) return route.fulfill({ json: { account: { id: 'operator-fixture', username: '运营测试' } } });
    if (path.endsWith('/support/status')) return route.fulfill({ json: { enabled: true, dailyLimit: 5, retentionDays: 90, actorScope: route.request().headers()['x-cardtalk-support-scope'], canOperate: controls.supportAllowed, privacyDeletionAutomatic: false } });
    if (path === '/api/v1/support/tickets') return route.fulfill({ json: { tickets: [], nextCursor: '0', hasMore: false } });
    if (path === '/api/v1/operations/tickets') {
      if (controls.operatorStatus !== 200) return route.fulfill({ status: controls.operatorStatus, json: { message: '支持权限已撤销' } });
      const cursor = url.searchParams.get('cursor') || '0'; controls.cursors.push(cursor);
      const recent = Array.from({ length: 50 }, (_, i) => ({ ...oldTicket, id: `new-${i}`, message: `较新反馈${i}`, status: i % 2 ? 'resolved' : 'submitted' }));
      return route.fulfill({ json: cursor === '0' ? { tickets: recent, nextCursor: '50', hasMore: true } : { tickets: [controls.ticket], nextCursor: '51', hasMore: false } });
    }
    if (path === '/api/v1/operations/tickets/old-submitted/updates') { const input = route.request().postDataJSON(); controls.updates.push(input); controls.ticket = { ...controls.ticket, reply: input.reply, status: input.status }; return route.fulfill({ json: controls.ticket }); }
    if (path === '/api/v1/operations/metrics') return route.fulfill(controls.metricsStatus !== 200 ? { status: controls.metricsStatus, json: { message: '没有指标运营权限' } } : { json: { storage: 'process_memory', startedAt: '2026-10-12T00:00:00Z', uptimeSeconds: 120, api: [], providers: [{ category: 'expansion_llm', started: 3, completed: 2, inFlight: 1, errors: 1, durationBuckets: { lt_100ms: 2 }, usage: { status: 'unknown', reportedCalls: 0 } }], cost: { status: 'unknown', amount: null } } });
    if (path === '/api/v1/resources/current') { controls.publicGets++; return route.fulfill({ json: { revision: controls.revision, package: controls.current } }); }
    if (path.startsWith('/api/v1/operations/content/')) {
      if (controls.contentStatus !== 200) return route.fulfill({ status: controls.contentStatus, json: { message: '公共内容运营权限已撤销' } });
      if (path.endsWith('/versions')) { if (method === 'POST') { const input = route.request().postDataJSON(); controls.saves.push(input); controls.versions.unshift(input); return route.fulfill({ json: { id: input.id } }); } return route.fulfill({ json: controls.versions.map(v => ({ id: v.id, createdAt: '2026-10-12T00:00:00Z' })) }); }
      if (path.endsWith('/audit')) return route.fulfill({ json: controls.audits });
      if (path.endsWith('/release')) { const input = route.request().postDataJSON(); controls.releases.push(input); controls.current = controls.versions.find(v => v.id === input.versionId)!; controls.revision++; controls.audits.unshift({ revision: controls.revision, versionId: input.versionId, actorId: 'operator-fixture', createdAt: '2026-10-12T01:00:00Z' }); return route.fulfill({ json: { revision: controls.revision, package: controls.current } }); }
    }
    controls.unexpected.push(path); return route.fulfill({ status: 503, json: { message: '未允许的测试接口；禁止真实外部调用' } });
  });
  await page.goto('/'); return controls;
}
async function open(page: Page, target: '公共资源' | '使用指南') { await button(page, '设置').click(); await button(page, target).click(); }
async function stored(page: Page) { return page.evaluate(() => JSON.parse(localStorage.getItem('cardtalk.communication.v1')!)); }
async function selectVersion(page: Page, id: string) { await page.locator('.content-operator .filter-picker').click(); await page.locator('.uni-picker-select').getByText(id, { exact: true }).click(); }

test('公共资源仅显式安装且保存来源许可，安装及服务端回滚不改变历史', async ({ page }) => {
  const controls = await setup(page); await open(page, '公共资源'); expect(controls.publicGets).toBe(0);
  await button(page, '获取公共资源预览').click(); await expect(page.locator('.resource-preview')).toContainText(pack.source); await expect(page.locator('.resource-preview')).toContainText(pack.license); await expect(button(page, '安装所选资源到本机')).toBeDisabled(); expect((await stored(page)).family.cards).toHaveLength(0);
  await page.getByRole('button', { name: /^卡片：小口喝水/ }).click(); await button(page, '安装所选资源到本机').click(); await expect(page.locator('.public-resources > [role=alert]')).toContainText('关联场景'); expect((await stored(page)).family.scenes).toHaveLength(0);
  await page.getByRole('button', { name: /^场景：活动间歇/ }).click(); await button(page, '安装所选资源到本机').click(); await expect(page.locator('.public-resources > [role=status]')).toContainText('已安装 1 张卡片与 1 个场景');
  const after = await stored(page), provenance = { packageId: pack.id, title: pack.title, source: pack.source, license: pack.license, revision: 1 };
  expect(after.rooms).toEqual(controls.state.rooms); expect(after.family.cards[0].provenance).toEqual(provenance); expect(after.family.scenes[0].provenance).toEqual(provenance);
  controls.current = { ...pack, id: 'older-v0', cards: [], scenes: [] }; controls.revision = 2; await button(page, '获取公共资源预览').click(); await expect(page.locator('.resource-preview')).toContainText('older-v0'); expect(await stored(page)).toEqual(after);
  await page.reload(); expect(await stored(page)).toEqual(after); expect(controls.unexpected).toEqual([]);
});

test('缺少场景权益仍可预览但不能自动安装卡片所依赖的场景', async ({ page }) => {
  const controls = await setup(page, false); await open(page, '公共资源'); await button(page, '获取公共资源预览').click(); await expect(page.getByRole('button', { name: /^场景：活动间歇/ })).toBeDisabled();
  await page.getByRole('button', { name: /^卡片：小口喝水/ }).click(); await button(page, '安装所选资源到本机').click(); await expect(page.locator('.public-resources > [role=alert]')).toContainText('关联场景'); expect(await stored(page)).toEqual(controls.state);
});

test('支持运营分页可找到50条较新记录后的待处理单并回复，再次刷新仍能翻页', async ({ page }) => {
  const controls = await setup(page); await open(page, '使用指南'); await button(page, '家庭账户').click(); await button(page, '查看支持单据').click(); await expect(page.locator('.support-ticket')).toHaveCount(50);
  await button(page, '查看更多支持单据').click(); await expect(page.locator('.support-ticket')).toHaveCount(51); await page.getByRole('button', { name: /support · 已提交，等待处理 · 最早仍待处理/ }).click(); await page.getByRole('textbox', { name: '支持处理回复', exact: true }).fill('已核对，将继续协助。'); await button(page, '保存回复与审计').click(); await expect(page.locator('.support-manager [role=status]')).toContainText('处理回复已保存');
  expect(controls.updates).toHaveLength(1); expect(controls.updates[0]).toMatchObject({ status: 'in_progress', reply: '已核对，将继续协助。' }); await expect(button(page, '查看更多支持单据')).toBeVisible(); await button(page, '查看更多支持单据').click(); await expect(page.getByRole('button', { name: /support · 处理中 · 最早仍待处理/ })).toBeVisible(); expect(controls.cursors).toEqual(['0', '50', '0', '50']);
  controls.operatorStatus = 403; await button(page, '查看支持单据').click(); await expect(page.locator('.support-manager [role=alert]')).toContainText('支持权限已撤销'); await expect(page.locator('.support-ticket')).toHaveCount(0); await expect(page.getByRole('textbox', { name: '支持处理回复', exact: true })).toHaveCount(0); await expect(button(page, '查看支持单据')).toHaveCount(0);
  controls.operatorStatus = 200; await button(page, '刷新我的单据').click(); await expect(button(page, '查看支持单据')).toBeVisible(); await expect(page.getByRole('textbox', { name: '支持处理回复', exact: true })).toHaveCount(0);
});

test('指标入口独立于支持运营权限，403清除先前指标', async ({ page }) => {
  const controls = await setup(page); controls.supportAllowed = false; await open(page, '使用指南'); await button(page, '家庭账户').click(); await expect(page.locator('.support-manager')).toContainText('已登录家庭账户'); await expect(button(page, '查看支持单据')).toHaveCount(0);
  await button(page, '运营服务指标（需授权）').click(); await button(page, '查看服务状态与指标').click(); await expect(page.locator('.service-metrics')).toContainText('本进程已运行 2 分钟'); await expect(page.locator('.service-metrics')).toContainText('金额未知');
  controls.metricsStatus = 403; await button(page, '查看服务状态与指标').click(); await expect(page.locator('.service-metrics [role=alert]')).toContainText('没有指标运营权限'); await expect(page.locator('.metric-row')).toHaveCount(0);
});

test('内容运营先核验再预览保存，发布可取消，切换与回滚记录修订审计，403清空权限', async ({ page }) => {
  const controls = await setup(page); await open(page, '公共资源'); await button(page, '公共内容运营入口').click(); await expect(page.getByRole('textbox', { name: '公共资源版本 JSON', exact: true })).toHaveCount(0); await button(page, '核验运营权限并刷新').click();
  const newer = { ...pack, id: 'daily-v2', title: '第二版资源' }; await page.getByRole('textbox', { name: '公共资源版本 JSON', exact: true }).fill(JSON.stringify(newer)); await button(page, '校验并预览版本').click(); await expect(page.locator('.content-preview')).toContainText(newer.source); expect(controls.saves).toEqual([]); await button(page, '保存此不可变版本').click(); await expect(page.locator('.content-operator [role=status]')).toContainText('尚未切换公开版本'); expect(controls.saves).toEqual([newer]); expect(controls.releases).toEqual([]);
  await selectVersion(page, newer.id); await button(page, '切换到所选版本').click(); await page.locator('.uni-modal__btn_default').click(); expect(controls.releases).toEqual([]);
  await button(page, '切换到所选版本').click(); await page.locator('.uni-modal__btn_primary').click(); await expect(page.locator('.content-operator')).toContainText('修订 2 → daily-v2'); expect(controls.releases[0]).toEqual({ versionId: newer.id, expectedRevision: 1 });
  await selectVersion(page, pack.id); await button(page, '切换到所选版本').click(); await page.locator('.uni-modal__btn_primary').click(); await expect(page.locator('.content-operator')).toContainText('修订 3 → daily-v1'); expect(controls.releases[1]).toEqual({ versionId: pack.id, expectedRevision: 2 });
  controls.contentStatus = 403; await button(page, '核验运营权限并刷新').click(); await expect(page.locator('.content-operator [role=alert]')).toContainText('公共内容运营权限已撤销'); await expect(page.getByRole('textbox', { name: '公共资源版本 JSON', exact: true })).toHaveCount(0); await expect(button(page, '切换到所选版本')).toHaveCount(0); await expect(page.locator('.content-operator')).not.toContainText('修订 3 →'); expect(controls.unexpected).toEqual([]);
});
