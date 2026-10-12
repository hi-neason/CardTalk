import { expect, test, type Page } from '@playwright/test';

// Provider responses below are deterministic test doubles, not a live DeepSeek verification.
const trialFixture = { token: 'e2e-fixture-token', remaining: 10, limit: 10, resetAt: '2099-01-01T00:00:00Z', capabilities: { profileManagement: false, scenarioManagement: false, analytics: false } };
async function installFixtures(page: Page) {
  const controls = {
    pauseTrial: false, trialRequested: false, releaseTrial: () => {}, speechRequests: 0,
    pauseExpansion: false, releaseExpansion: () => {}, expansionInputs: [] as { requestId: string; context: { emotion?: string } }[],
    pauseSpeech: false, speechSuccess: false, releaseSpeech: () => {}, speechInputs: [] as { requestId: string; text: string; emotion?: string }[],
    speechCancelIds: [] as string[], apiPaths: [] as string[],
  };
  page.on('request', request => { if (request.url().includes('/api/v1/')) controls.apiPaths.push(new URL(request.url()).pathname); });
  await page.addInitScript(() => {
    const calls: string[] = [];
    Object.defineProperty(window, '__speechCalls', { value: calls });
    Object.defineProperty(window, 'speechSynthesis', { value: {
      getVoices: () => [], cancel: () => {},
      speak: (utterance: SpeechSynthesisUtterance) => {
        calls.push(utterance.text);
        queueMicrotask(() => { utterance.onstart?.(new SpeechSynthesisEvent('start', { utterance })); utterance.onend?.(new SpeechSynthesisEvent('end', { utterance })); });
      },
    }, configurable: true });
    const mediaCalls: string[] = []; Object.defineProperty(window, '__mediaCalls', { value: mediaCalls });
    HTMLMediaElement.prototype.play = function () { mediaCalls.push(this.src); return Promise.resolve(); };
  });
  await page.route('**/api/v1/health', route => route.fulfill({ json: { status: 'ok', llmConfigured: true, ttsConfigured: true, ttsEmotionSupported: true, promptVersion: 'e2e-fixture' } }));
  await page.route('**/api/v1/trial-session', async route => {
    if (controls.pauseTrial) { controls.trialRequested = true; await new Promise<void>(resolve => { controls.releaseTrial = resolve; }); }
    await route.fulfill({ json: trialFixture });
  });
  await page.route('**/api/v1/expansions', async route => {
    const input = route.request().postDataJSON();
    controls.expansionInputs.push(input);
    if (controls.pauseExpansion) await new Promise<void>(resolve => { controls.releaseExpansion = resolve; });
    await route.fulfill({ json: { requestId: input.requestId, inputVersion: input.inputVersion, candidates: ['我想吃苹果', '我想要苹果'].map((text, i) => ({ id: `fixture-${i}`, text, sourceCardIds: input.cards.map((card: { id: string }) => card.id) })), needsClarification: false, clarification: null, promptVersion: 'e2e-fixture', remaining: 9 } });
  });
  await page.route('**/api/v1/speech', async route => {
    controls.speechRequests++; controls.speechInputs.push(route.request().postDataJSON());
    if (controls.pauseSpeech) await new Promise<void>(resolve => { controls.releaseSpeech = resolve; });
    if (controls.speechSuccess) await route.fulfill({ status: 200, contentType: 'audio/mpeg', body: Buffer.from('e2e-placeholder-audio-never-played') });
    else await route.fulfill({ status: 503, json: { code: 'E2E_STUB', message: 'No real audio provider is used by this test' } });
  });
  await page.route('**/api/v1/speech/*/cancel', async route => {
    controls.speechCancelIds.push(new URL(route.request().url()).pathname.split('/').at(-2)!);
    await route.fulfill({ status: 204 });
  });
  return controls;
}
const button = (page: Page, name: string | RegExp) => page.getByRole('button', { name, exact: typeof name === 'string' });
const field = (page: Page, name: string) => page.getByRole('textbox', { name, exact: true });
async function startRoom(page: Page, scene = '厨房', name = '测试表达者') {
  await button(page, /开始沟通/).click();
  await field(page, '表达者称呼').fill(name);
  await page.getByRole('dialog').getByRole('button', { name: scene, exact: true }).click();
  await field(page, '沟通参与者').fill('妈妈,爸爸');
  await button(page, '本人操作').click();
  await button(page, /创建聊天室，开始选卡/).click();
  await expect(button(page, '结束聊天室')).toBeVisible();
}
async function pickAndGenerate(page: Page) {
  await button(page, '选择我').click();
  await button(page, '选择苹果').click();
  await button(page, /帮我整理成一句话/).click();
  await expect(page.locator('.candidate-item')).toHaveCount(2);
}
async function endRoom(page: Page) { await button(page, '结束聊天室').click(); await page.locator('.uni-modal__btn_primary').click(); }

test('模拟API完整沟通闭环，拒绝保留选卡、修改、反馈、历史与重载', async ({ page }) => {
  await installFixtures(page); await page.goto('/'); await startRoom(page); await pickAndGenerate(page);
  await button(page, /都不合适/).click();
  await expect(page.locator('.selected-word')).toHaveCount(2);
  await expect(page.locator('.candidate-item')).toHaveCount(0);
  await button(page, /帮我整理成一句话/).click();
  await page.locator('.candidate-item').first().locator('.candidate-edit').click();
  await field(page, '修改候选表达').fill('我想吃一小块苹果');
  await button(page, '确认修改').click();
  await expect(page.locator('.confirmed-text')).toHaveText('我想吃一小块苹果');
  await button(page, /说出这句话/).click();
  await expect.poll(() => page.evaluate(() => (window as unknown as { __speechCalls: string[] }).__speechCalls)).toEqual(['我想吃一小块苹果']);
  await field(page, '反馈者称呼').fill('妈妈'); await button(page, '理解了').click();
  await button(page, '本机历史').click(); await page.locator('.history-row').click();
  await expect(page.locator('.history-turn-final')).toHaveText('我想吃一小块苹果');
  await expect(page.locator('.history-turn')).toContainText('候选修改后确认'); await expect(page.locator('.history-turn')).toContainText('理解了（妈妈）');
  await button(page, '关闭').click(); await button(page, /开始下一句/).click();
  await button(page, '选择不要').click(); await button(page, /使用原始表达/).click();
  await expect(page.locator('.confirmed-text')).toHaveText('不要'); await endRoom(page); await page.reload();
  await expect(button(page, '结束聊天室')).toHaveCount(0);
  await button(page, '本机历史').click(); await expect(page.locator('.history-row')).toContainText('2 条表达 · 已结束');
  await page.locator('.history-row').click(); await expect(page.locator('.history-turn')).toHaveCount(2);
});

test('第21张不进入表达，重复卡片保存，选择不限场景可正确筛选历史', async ({ page }) => {
  await installFixtures(page); await page.goto('/'); await startRoom(page, '不限场景', '无场景测试');
  for (let i = 0; i < 21; i++) await button(page, '选择苹果').click();
  await expect(page.locator('.selected-word')).toHaveCount(20); await expect(page.locator('.error-message')).toContainText('最多选择20张');
  await button(page, /使用原始表达/).click(); await endRoom(page); await startRoom(page, '厨房', '厨房测试'); await endRoom(page);
  await button(page, '本机历史').click(); await expect(page.locator('.history-row')).toHaveCount(2);
  await page.locator('.filter-picker').click();
  await page.locator('.uni-picker-select').getByText('不限场景', { exact: true }).click();
  await expect(page.locator('.history-row')).toHaveCount(1); await expect(page.locator('.history-row')).toContainText('无场景测试');
});

for (const action of ['停止声音', '开始下一句'] as const) {
  test(`延迟体验鉴权：${action}后不会迟到调用远端语音`, async ({ page }) => {
    const controls = await installFixtures(page); await page.goto('/'); await startRoom(page); await pickAndGenerate(page);
    await page.locator('.candidate-button').first().click(); await button(page, '设置').click(); await page.getByRole('switch', { name: '语音合成', exact: true }).click(); await button(page, '关闭').click();
    controls.pauseTrial = true; await button(page, /说出这句话/).click(); await expect.poll(() => controls.trialRequested).toBe(true);
    await button(page, new RegExp(action)).click(); controls.releaseTrial();
    await page.waitForTimeout(300);
    expect(controls.speechRequests).toBe(0);
    expect(await page.evaluate(() => (window as unknown as { __speechCalls: string[] }).__speechCalls)).toEqual([]);
    if (action === '开始下一句') await expect(page.locator('.confirmed-text')).toHaveCount(0);
    else await expect(page.locator('.playback-controls')).toContainText('已停止');
  });
}

test('390px手机布局沟通及创建表单没有页面横向溢出', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 }); await installFixtures(page); await page.goto('/');
  const overflow = () => page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
  await expect.poll(overflow).toBe(false); await button(page, /开始沟通/).click(); await expect.poll(overflow).toBe(false); await button(page, '关闭').click(); await startRoom(page); await button(page, '选择苹果').click();
  await expect.poll(overflow).toBe(false);
  await expect(page.locator('.mobile-expression-link')).toBeVisible();
  await page.locator('.mobile-expression-link').click();
  await expect(page.locator('#expression-panel .section-title')).toBeInViewport();
  await expect(page.locator('#expression-panel .selected-tray')).toBeInViewport();
  await expect.poll(overflow).toBe(false);
  await page.screenshot({ path: 'test-results/mobile-communication.png', fullPage: true });
});

test('结束后历史播放可停止，关闭历史时迟到鉴权不触发语音', async ({ page }) => {
  const controls = await installFixtures(page); await page.goto('/'); await startRoom(page); await pickAndGenerate(page);
  await page.locator('.candidate-button').first().click(); await endRoom(page);
  await button(page, '设置').click(); await page.getByRole('switch', { name: '语音合成', exact: true }).click(); await button(page, '关闭').click();
  await button(page, '本机历史').click(); await page.locator('.history-row').click();
  controls.pauseTrial = true; await button(page, '重新播放').click(); await expect.poll(() => controls.trialRequested).toBe(true);
  await expect(page.getByRole('dialog').getByRole('button', { name: '停止声音', exact: true })).toBeVisible();
  await page.getByRole('dialog').getByRole('button', { name: '停止声音', exact: true }).click();
  controls.releaseTrial(); await page.waitForTimeout(100); expect(controls.speechRequests).toBe(0);
  controls.trialRequested = false;
  await button(page, '重新播放').click(); await expect.poll(() => controls.trialRequested).toBe(true);
  await button(page, '关闭').click(); controls.releaseTrial(); await page.waitForTimeout(300);
  expect(controls.speechRequests).toBe(0); await expect(page.getByRole('dialog')).toHaveCount(0);
});

test('键盘打开创建对话框，输入具备名称并可Escape退出', async ({ page }) => {
  await installFixtures(page); await page.goto('/');
  const start = page.getByRole('button', { name: /开始沟通/ }); await start.focus(); await page.keyboard.press('Enter');
  await expect(page.getByRole('dialog', { name: '创建聊天室' })).toBeVisible();
  await page.getByRole('textbox', { name: '表达者称呼', exact: true }).fill('键盘使用者');
  await page.keyboard.press('Escape'); await expect(page.getByRole('dialog')).toHaveCount(0);
});

test('真实本机未配置模型接口失败后仍可记录原始表达', async ({ page }) => {
  // This case intentionally calls the local backend; it does not assert provider generation.
  const healthResponse = await page.request.get('/api/v1/health');
  expect(healthResponse.ok()).toBe(true);
  test.skip((await healthResponse.json()).llmConfigured, 'This failure-path test never calls a configured paid model provider');
  await page.addInitScript(() => { HTMLMediaElement.prototype.play = function () { return Promise.resolve(); }; });
  await page.goto('/'); await startRoom(page); await button(page, '选择我').click(); await button(page, '选择苹果').click();
  const responsePromise = page.waitForResponse(response => response.url().endsWith('/api/v1/expansions'));
  await button(page, /帮我整理成一句话/).click();
  const response = await responsePromise;
  expect(response.status()).toBe(503); expect((await response.json()).code).toBe('LLM_NOT_CONFIGURED');
  await expect(page.locator('.error-message')).toContainText('尚未配置表达服务'); await expect(page.locator('.selected-word')).toHaveCount(2);
  await button(page, /使用原始表达/).click(); await expect(page.locator('.confirmed-text')).toHaveText('我 苹果');
});


test('模拟扩写响应丢失后重试复用请求ID，成功后主动重新生成使用新ID', async ({ page }) => {
  await installFixtures(page);
  await page.unroute('**/api/v1/expansions');
  const requests: { requestId: string; inputVersion: number; cardIds: string[] }[] = [];
  await page.route('**/api/v1/expansions', async route => {
    const input = route.request().postDataJSON();
    const cardIds = input.cards.map((card: { id: string }) => card.id);
    requests.push({ requestId: input.requestId, inputVersion: input.inputVersion, cardIds });
    // Simulate transport loss after dispatch. The client cannot know whether billing occurred.
    if (requests.length === 1) { await route.abort('connectionreset'); return; }
    await route.fulfill({ json: { requestId: input.requestId, inputVersion: input.inputVersion, candidates: [{ id: 'fixture-retry', text: '我想要苹果', sourceCardIds: cardIds }], needsClarification: false, clarification: null, promptVersion: 'e2e-request-id-fixture', remaining: 9 } });
  });
  await page.goto('/'); await startRoom(page); await button(page, '选择我').click(); await button(page, '选择苹果').click();
  await button(page, /帮我整理成一句话/).click();
  await expect(page.locator('.error-message')).toContainText('连接不到服务');
  await expect(page.locator('.selected-word')).toHaveCount(2);
  await button(page, /帮我整理成一句话/).click();
  await expect(page.locator('.candidate-item')).toHaveCount(1);
  expect(requests).toHaveLength(2);
  expect(requests[1]).toEqual(requests[0]);
  const completedRequestId = requests[1]!.requestId;
  const newResult = page.waitForResponse(response => response.url().endsWith('/api/v1/expansions'));
  await button(page, /帮我整理成一句话/).click(); await newResult;
  await expect(button(page, /帮我整理成一句话/)).toBeEnabled();
  expect(requests).toHaveLength(3);
  expect(requests[2]!.requestId).not.toBe(completedRequestId);
  expect(requests[2]!.inputVersion).toBe(requests[1]!.inputVersion);
  expect(requests[2]!.cardIds).toEqual(requests[1]!.cardIds);
});


async function setCapability(page: Page, name: 'AI扩写' | '语音合成' | '情绪识别', enabled: boolean) {
  await button(page, '设置').click();
  const toggle = page.getByRole('switch', { name, exact: true });
  if ((await toggle.getAttribute('aria-checked')) !== String(enabled)) await toggle.click();
  await expect(toggle).toHaveAttribute('aria-checked', String(enabled));
  await button(page, '关闭').click();
}

test('能力开关持久保存且相互独立，关闭AI仍可使用原始表达与主动合成', async ({ page }) => {
  const controls = await installFixtures(page); await page.goto('/'); await startRoom(page);
  await setCapability(page, 'AI扩写', false); await setCapability(page, '语音合成', true); await setCapability(page, '情绪识别', true);
  await page.reload(); await button(page, '设置').click();
  await expect(page.getByRole('switch', { name: 'AI扩写', exact: true })).toHaveAttribute('aria-checked', 'false');
  for (const name of ['语音合成', '情绪识别']) await expect(page.getByRole('switch', { name, exact: true })).toHaveAttribute('aria-checked', 'true');
  await button(page, '关闭').click(); await button(page, '选择开心').click(); await button(page, '选择苹果').click();
  await expect(button(page, /帮我整理成一句话/)).toHaveCount(0);
  await button(page, /使用原始表达/).click(); await expect(page.locator('.confirmed-text')).toHaveText('开心 苹果');
  expect(controls.expansionInputs).toHaveLength(0); expect(controls.speechRequests).toBe(0);
  await button(page, /说出这句话/).click(); await expect.poll(() => controls.speechRequests).toBe(1);
  expect(controls.speechInputs[0]).toMatchObject({ text: '开心 苹果', emotion: 'happy' });
  expect(controls.expansionInputs).toHaveLength(0);
});

test('情绪只来自选卡：开启传开心/难过，冲突传neutral，关闭完全省略且不自动合成', async ({ page }) => {
  const controls = await installFixtures(page); await page.goto('/'); await startRoom(page);
  await setCapability(page, '情绪识别', true);
  const generate = async (expected: string | undefined) => {
    const count = controls.expansionInputs.length;
    await button(page, /帮我整理成一句话/).click(); await expect(page.locator('.candidate-item')).toHaveCount(2);
    await expect.poll(() => controls.expansionInputs.length).toBe(count + 1);
    const context = controls.expansionInputs[count]!.context;
    if (expected === undefined) expect(context).not.toHaveProperty('emotion'); else expect(context.emotion).toBe(expected);
  };
  await button(page, '选择开心').click(); await generate('happy');
  await button(page, '清空').click(); await button(page, '选择难过').click(); await generate('sad');
  await button(page, '选择开心').click(); await expect(page.locator('.emotion-hint')).toContainText('多种情绪'); await generate('neutral');
  await setCapability(page, '情绪识别', false); await expect(page.locator('.candidate-item')).toHaveCount(0);
  await expect(page.locator('.selected-word')).toHaveCount(2); await generate(undefined);
  await setCapability(page, '语音合成', true); await page.locator('.candidate-button').first().click();
  expect(controls.speechRequests).toBe(0);
  await button(page, /说出这句话/).click(); await expect.poll(() => controls.speechRequests).toBe(1);
  expect(controls.speechInputs[0]).not.toHaveProperty('emotion');
  expect(controls.apiPaths.every(path => ['/api/v1/health', '/api/v1/trial-session', '/api/v1/expansions', '/api/v1/speech'].includes(path))).toBe(true);
});

for (const setting of ['AI扩写', '情绪识别'] as const) {
  test(`切换${setting}取消等待中的扩写，旧候选不恢复`, async ({ page }) => {
    const controls = await installFixtures(page); await page.goto('/'); await startRoom(page); await button(page, '选择开心').click();
    controls.pauseExpansion = true; await button(page, /帮我整理成一句话/).click(); await expect.poll(() => controls.expansionInputs.length).toBe(1);
    await setCapability(page, setting, setting === '情绪识别');
    controls.releaseExpansion(); await page.waitForTimeout(200);
    await expect(page.locator('.candidate-item')).toHaveCount(0); await expect(page.locator('.selected-word')).toHaveCount(1);
    if (setting === 'AI扩写') await expect(button(page, /帮我整理成一句话/)).toHaveCount(0);
    else await expect(button(page, /帮我整理成一句话/)).toBeEnabled();
    expect(controls.speechRequests).toBe(0);
  });
}

test('关闭语音合成取消已发出的合成请求，迟到响应不会播放', async ({ page }) => {
  const controls = await installFixtures(page); await page.goto('/'); await startRoom(page); await pickAndGenerate(page); await page.locator('.candidate-button').first().click();
  await setCapability(page, '语音合成', true); expect(controls.speechRequests).toBe(0);
  controls.pauseSpeech = true; controls.speechSuccess = true; await button(page, /说出这句话/).click(); await expect.poll(() => controls.speechRequests).toBe(1);
  await button(page, '设置').click(); await page.getByRole('switch', { name: '语音合成', exact: true }).click();
  // Keep the dialog open: closing it also stops speech and must not mask a broken toggle.
  await expect.poll(() => controls.speechCancelIds.length).toBe(1);
  expect(controls.speechCancelIds[0]).toBe(controls.speechInputs[0]!.requestId);
  controls.releaseSpeech(); await page.waitForTimeout(200);
  await expect(button(page, '停止声音')).toHaveCount(0);
  expect(await page.evaluate(() => (window as unknown as { __speechCalls: string[] }).__speechCalls)).toEqual([]);
  expect(await page.evaluate(() => (window as unknown as { __mediaCalls: string[] }).__mediaCalls)).toEqual([]);
  await expect(page.locator('.confirmed-text')).toHaveText('我想吃苹果');
});


test('否定情绪组合上传neutral，不把不开心当开心', async ({ page }) => {
  const controls = await installFixtures(page); await page.goto('/'); await startRoom(page);
  await setCapability(page, '情绪识别', true); await setCapability(page, '语音合成', true);
  await button(page, '选择我').click(); await button(page, '选择不').click(); await button(page, '选择开心').click();
  await button(page, /帮我整理成一句话/).click(); await expect(page.locator('.candidate-item')).toHaveCount(2);
  expect(controls.expansionInputs[0]!.context.emotion).toBe('neutral');
  await button(page, /使用原始表达/).click(); await button(page, /说出这句话/).click();
  await expect.poll(() => controls.speechRequests).toBe(1);
  expect(controls.speechInputs[0]!.emotion).toBe('neutral');
  expect(controls.speechInputs[0]!.text).toBe('我 不 开心');
});

test('修改候选不沿用原始开心卡情绪，合成使用neutral', async ({ page }) => {
  const controls = await installFixtures(page); await page.goto('/'); await startRoom(page);
  await setCapability(page, '情绪识别', true); await setCapability(page, '语音合成', true);
  await button(page, '选择我').click(); await button(page, '选择开心').click();
  await button(page, /帮我整理成一句话/).click(); await expect(page.locator('.candidate-item')).toHaveCount(2);
  expect(controls.expansionInputs[0]!.context.emotion).toBe('happy');
  await page.locator('.candidate-item').first().locator('.candidate-edit').click();
  await field(page, '修改候选表达').fill('我不开心'); await button(page, '确认修改').click();
  await expect(page.locator('.confirmed-area .emotion-hint')).toContainText('自然语气');
  expect(controls.speechRequests).toBe(0);
  await button(page, /说出这句话/).click(); await expect.poll(() => controls.speechRequests).toBe(1);
  expect(controls.speechInputs[0]).toMatchObject({ text: '我不开心', emotion: 'neutral' });
});
