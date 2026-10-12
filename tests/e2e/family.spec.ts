import { expect, test, type Page } from '@playwright/test';

// All provider and entitlement responses are test fixtures; no paid API is invoked.
const btn = (page: Page, name: string | RegExp) => page.getByRole('button', { name, exact: typeof name === 'string' });
const field = (page: Page, name: string) => page.getByRole('textbox', { name, exact: true });
async function fixtures(page: Page) {
  const controls = { trialRequests: 0, flags: { profileManagement: true, scenarioManagement: true, customCards: true, analytics: false }, inputs: [] as any[], audio: [] as string[], unexpected: [] as string[] };
  await page.addInitScript(() => {
    Object.defineProperty(window, '__speechCalls', { value: [] });
    Object.defineProperty(window, 'speechSynthesis', { configurable: true, value: { getVoices: () => [], cancel: () => {}, speak: (u: SpeechSynthesisUtterance) => { (window as any).__speechCalls.push(u.text); queueMicrotask(() => { u.onstart?.(new SpeechSynthesisEvent('start', { utterance: u })); u.onend?.(new SpeechSynthesisEvent('end', { utterance: u })); }); } } });
  });
  page.on('request', r => { if (r.url().includes('/static/audio/')) controls.audio.push(r.url()); });
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/health')) return route.fulfill({ json: { status: 'ok', llmConfigured: true, ttsConfigured: false, promptVersion: 'family-fixture' } });
    if (path.endsWith('/trial-session')) { controls.trialRequests++; return route.fulfill({ json: { token: 'family-fixture-token', remaining: 10, limit: 10, resetAt: '2099-01-01T00:00:00Z', capabilities: controls.flags } }); }
    if (path.endsWith('/expansions')) {
      const input = route.request().postDataJSON(); controls.inputs.push(input);
      return route.fulfill({ json: { requestId: input.requestId, inputVersion: input.inputVersion, candidates: [{ id: 'family-candidate', text: '我想抱小熊', sourceCardIds: input.cards.map((c: { id: string }) => c.id) }], needsClarification: false, clarification: null, promptVersion: 'family-fixture', remaining: 9 } });
    }
    controls.unexpected.push(path); return route.fulfill({ status: 503, json: { message: 'Unexpected fixture request' } });
  });
  return controls;
}
async function family(page: Page, tab = '使用人') { await btn(page, '家庭管理').click(); await btn(page, tab).click(); }
async function profile(page: Page) {
  await family(page); await btn(page, '新增使用人').click(); await field(page, '使用人姓名').fill('小安');
  await field(page, '兴趣与喜好').fill('喜欢小熊'); await field(page, '性格与交流节奏').fill('请耐心等待'); await field(page, '表达习惯').fill('使用短句');
  await field(page, '家人观察').fill('私密观察不应上传'); await field(page, '观察记录人').fill('妈妈'); await field(page, '观察日期').fill('2026-10-12');
  await btn(page, '保存使用人').click(); await expect(page.locator('.family-manager [role=status]')).toContainText('已保存使用人'); await btn(page, '关闭').click();
}
async function card(page: Page) {
  await family(page, '自定义卡片'); await btn(page, '新增卡片').click(); await field(page, '卡片名称').fill('小熊'); await field(page, '卡片含义说明').fill('家里的毛绒玩具');
  await btn(page, '保存卡片').click(); await expect(page.locator('.family-manager [role=status]')).toContainText('已保存卡片'); await btn(page, '关闭').click();
}
async function scene(page: Page) {
  await family(page, '场景'); await btn(page, '新增场景').click(); await field(page, '场景名称').fill('奶奶家'); await field(page, '场景描述').fill('和奶奶一起休息');
  await field(page, '常见活动').fill('看相册'); await field(page, '常见物品').fill('沙发'); await field(page, '搜索推荐卡片').fill('小熊'); await btn(page, '小熊').click();
  await btn(page, '保存场景').click(); await expect(page.locator('.family-manager [role=status]')).toContainText('已保存场景'); await btn(page, '关闭').click();
}
async function start(page: Page, custom = true) {
  await btn(page, /开始沟通/).click(); if (custom) { await btn(page, '小安').click(); await btn(page, '奶奶家').click(); await field(page, '当前状态').fill('刚睡醒'); }
  await btn(page, /创建聊天室，开始选卡/).click(); await expect(btn(page, '结束聊天室')).toBeVisible();
}
async function generate(page: Page) { await btn(page, /帮我整理成一句话/).click(); await expect(page.locator('.candidate-item')).toHaveCount(1); }
async function end(page: Page) { await btn(page, '结束聊天室').click(); await page.locator('.uni-modal__btn_primary').click(); }

test('家庭资料用于扩写，观察不上传，编辑不改已有沟通快照且刷新保留', async ({ page }) => {
  const controls = await fixtures(page); await page.goto('/'); await profile(page); await card(page); await scene(page); await start(page);
  await btn(page, '推荐小熊').click(); await generate(page);
  expect(controls.inputs[0].context.profile).toMatchObject({ name: '小安', interests: '喜欢小熊', currentState: '刚睡醒', personality: '请耐心等待' });
  expect(controls.inputs[0].context.scene.description).toContain('看相册'); expect(controls.inputs[0].context.scene.description).toContain('沙发');
  expect(controls.inputs[0].cards[0]).toMatchObject({ label: '小熊', description: '家里的毛绒玩具' });
  expect(JSON.stringify(controls.inputs[0])).not.toContain('私密观察'); expect(controls.inputs[0].cards[0]).not.toHaveProperty('image');
  await page.locator('.candidate-button').click(); await family(page); await btn(page, '编辑使用人 小安').click(); await field(page, '兴趣与喜好').fill('现在喜欢火车'); await btn(page, '保存使用人').click();
  await btn(page, '场景').click(); await btn(page, '编辑场景 奶奶家').click(); await field(page, '场景描述').fill('新的场景描述'); await btn(page, '保存场景').click(); await btn(page, '关闭').click();
  await page.reload(); await btn(page, /开始下一句/).click(); await btn(page, '选择小熊').click(); await generate(page);
  expect(controls.inputs[1].context).toEqual(controls.inputs[0].context);
  await end(page); await start(page); await btn(page, '推荐小熊').click(); await generate(page);
  expect(controls.inputs[2].context.profile.interests).toBe('现在喜欢火车'); expect(controls.inputs[2].context.scene.description).toContain('新的场景描述');
  await btn(page, '本机历史').click(); await page.locator('.history-row').last().click(); await expect(page.locator('.history-turn-final')).toHaveText('我想抱小熊');
  expect(controls.unexpected).toEqual([]);
});

test('使用人归档后新沟通不可选，恢复后重新可选', async ({ page }) => {
  await fixtures(page); await page.goto('/'); await profile(page); await family(page); await btn(page, '归档使用人 小安').click(); await page.locator('.uni-modal__btn_primary').click();
  await expect(page.locator('.family-row')).toHaveCount(0); await btn(page, '关闭').click(); await btn(page, /开始沟通/).click(); await expect(btn(page, '小安')).toHaveCount(0); await btn(page, '关闭').click();
  await family(page); await btn(page, '显示已归档').click(); await btn(page, '恢复使用人 小安').click(); await btn(page, '关闭').click(); await btn(page, /开始沟通/).click(); await expect(btn(page, '小安')).toBeVisible();
});

for (const [key, tab, noun, label] of [['profileManagement', '使用人', '使用人', '小安'], ['scenarioManagement', '场景', '场景', '奶奶家'], ['customCards', '自定义卡片', '卡片', '小熊']] as const) {
  test(`${tab}独立关闭后资料只读，其他模块与基础表达仍可用`, async ({ page }) => {
    const controls = await fixtures(page); await page.goto('/'); await profile(page); await card(page); await scene(page); controls.flags[key] = false; await page.reload();
    await family(page, tab); await expect(btn(page, `新增${noun}`)).toBeDisabled(); await btn(page, `查看${noun} ${label}`).click();
    await expect(field(page, noun === '使用人' ? '使用人姓名' : noun === '卡片' ? '卡片名称' : '场景名称')).toBeDisabled(); await expect(btn(page, `保存${noun}`)).toHaveCount(0);
    await page.locator('.family-editor-heading').getByRole('button', { name: '返回列表' }).click();
    const other = key === 'profileManagement' ? ['自定义卡片', '卡片'] : ['使用人', '使用人']; await btn(page, other[0]!).click(); await expect(btn(page, `新增${other[1]}`)).toBeEnabled();
    await btn(page, '关闭').click(); await start(page, false); await btn(page, '选择我').click(); await btn(page, /使用原始表达/).click(); await expect(page.locator('.confirmed-text')).toHaveText('我');
  });
}

test('真实PNG本机图片保存和历史复现，伪装SVG及过大图片拒绝且保留表单', async ({ page }) => {
  const controls = await fixtures(page); await page.goto('/'); await family(page, '自定义卡片'); await btn(page, '新增卡片').click(); await field(page, '卡片名称').fill('照片小熊');
  const upload = async (buffer: Buffer) => { const chooser = page.waitForEvent('filechooser'); await btn(page, '上传本机图片').click(); await (await chooser).setFiles({ name: 'picture.png', mimeType: 'image/png', buffer }); };
  await upload(Buffer.from('<svg xmlns="http://www.w3.org/2000/svg">' + ' '.repeat(2500) + '</svg>')); await expect(page.getByRole('alert')).toContainText('实际格式'); await expect(field(page, '卡片名称')).toHaveValue('照片小熊');
  await upload(Buffer.alloc(201 * 1024)); await expect(page.getByRole('alert')).toContainText('200 KB');
  const data = await page.evaluate(() => { const c = document.createElement('canvas'); c.width = 100; c.height = 100; const ctx = c.getContext('2d')!; for (let x = 0; x < 100; x++) for (let y = 0; y < 100; y++) { ctx.fillStyle = `rgb(${x * 2},${y * 2},${(x * y) % 255})`; ctx.fillRect(x, y, 1, 1); } return c.toDataURL('image/png').split(',')[1]!; });
  await upload(Buffer.from(data, 'base64')); await expect(btn(page, '移除照片')).toBeVisible(); await btn(page, '保存卡片').click(); await btn(page, '关闭').click(); await start(page, false);
  await btn(page, '选择照片小熊').click(); await generate(page); expect(controls.inputs[0].cards[0]).not.toHaveProperty('image'); expect(JSON.stringify(controls.inputs[0])).not.toContain('data:image'); await btn(page, /都不合适/).click(); await btn(page, /使用原始表达/).click(); await btn(page, /说出这句话/).click(); await expect.poll(() => page.evaluate(() => (window as any).__speechCalls)).toEqual(['照片小熊']);
  expect(controls.audio.filter(url => url.includes('custom-'))).toEqual([]); expect(controls.unexpected).toEqual([]);
  await end(page); await page.reload(); await btn(page, '本机历史').click(); await page.locator('.history-row').click(); await expect(page.locator('.history-card-images img')).toHaveAttribute('src', /^data:image\/png;base64,/);
});

test('本机写入失败不宣称保存成功，填写内容保留且可重试', async ({ page }) => {
  await fixtures(page); await page.goto('/'); await family(page); await btn(page, '新增使用人').click(); await field(page, '使用人姓名').fill('待保存');
  await page.evaluate(() => { const original = Storage.prototype.setItem; (window as any).__restoreStorage = () => { Storage.prototype.setItem = original; }; Storage.prototype.setItem = function (key, value) { if (key === 'cardtalk.communication.v1') throw new DOMException('fixture quota full', 'QuotaExceededError'); return original.call(this, key, value); }; });
  await btn(page, '保存使用人').click(); await expect(page.locator('.family-error')).toContainText('未保存'); await expect(field(page, '使用人姓名')).toHaveValue('待保存'); await expect(page.locator('.family-manager [role=status]')).toHaveCount(0);
  await page.evaluate(() => (window as any).__restoreStorage()); await btn(page, '保存使用人').click(); await expect(btn(page, '编辑使用人 待保存')).toBeVisible(); await page.reload(); await family(page); await expect(btn(page, '编辑使用人 待保存')).toBeVisible();
});

test('390px家庭表单无横向溢出，文字字段均具备可访问名称', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 }); await fixtures(page); await page.goto('/'); await family(page);
  for (const [tab, noun] of [['使用人', '使用人'], ['自定义卡片', '卡片'], ['场景', '场景']]) {
    await btn(page, tab!).click(); await btn(page, `新增${noun}`).click();
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1)).toBe(false);
    const fields = page.locator('.family-editor input, .family-editor textarea');
    for (const input of await fields.all()) await expect(input).toHaveAccessibleName(/.+/);
    if (noun === '使用人') await page.screenshot({ path: 'test-results/family-mobile.png', fullPage: true });
    await page.locator('.family-editor-heading').getByRole('button', { name: '返回列表' }).click();
  }
});

test('卡片试听始终使用设备声音，即使沟通已开启拟人合成', async ({ page }) => {
  const controls = await fixtures(page); await page.goto('/'); await btn(page, '设置').click();
  await page.getByRole('switch', { name: '语音合成', exact: true }).click(); await btn(page, '关闭').click();
  await family(page, '自定义卡片'); await btn(page, '新增卡片').click(); await field(page, '卡片名称').fill('试听小熊'); const trialsBefore = controls.trialRequests; await btn(page, '预览设备发声').click();
  await expect.poll(() => page.evaluate(() => (window as any).__speechCalls)).toEqual(['试听小熊']);
  expect(controls.trialRequests).toBe(trialsBefore); expect(controls.unexpected).toEqual([]); expect(controls.audio.filter(url => url.includes('custom-'))).toEqual([]);
});
