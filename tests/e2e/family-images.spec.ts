import { expect, test, type Page, type Locator } from '@playwright/test';

const button = (page: Page, name: string | RegExp) => page.getByRole('button', { name, exact: typeof name === 'string' });
const field = (page: Page, name: string) => page.getByRole('textbox', { name, exact: true });
async function fixtures(page: Page) {
  const inputs: Record<string, unknown>[] = [];
  const unexpected: string[] = [];
  // Intercept every application API, including unexpected routes: no live paid services.
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/health')) return route.fulfill({ json: { status: 'ok', llmConfigured: true, ttsConfigured: false, promptVersion: 'image-fixture' } });
    if (path.endsWith('/trial-session')) return route.fulfill({ json: { token: 'image-fixture-token', remaining: 10, limit: 10, resetAt: '2099-01-01T00:00:00Z', capabilities: { profileManagement: true, scenarioManagement: true, customCards: true, analytics: false } } });
    if (path.endsWith('/expansions')) {
      const input = route.request().postDataJSON(); inputs.push(input);
      return route.fulfill({ json: { requestId: input.requestId, inputVersion: input.inputVersion, candidates: [{ id: 'image-fixture-candidate', text: '我想吃苹果', sourceCardIds: input.cards.map((card: { id: string }) => card.id) }], needsClarification: false, clarification: null, promptVersion: 'image-fixture', remaining: 9 } });
    }
    unexpected.push(path); return route.fulfill({ status: 503, json: { message: 'Unexpected fixture request' } });
  });
  return { inputs, unexpected };
}
async function png(page: Page, color: string) {
  return page.evaluate(color => { const canvas = document.createElement('canvas'); canvas.width = canvas.height = 60; const context = canvas.getContext('2d')!; context.fillStyle = color; context.fillRect(0, 0, 60, 60); return canvas.toDataURL('image/png'); }, color);
}
async function upload(page: Page, noun: string, buffer: Buffer) {
  const chooser = page.waitForEvent('filechooser'); await button(page, `上传${noun}图片`).click();
  await (await chooser).setFiles({ name: 'family.png', mimeType: 'image/png', buffer });
}
async function uploadPng(page: Page, noun: string, data: string) { await upload(page, noun, Buffer.from(data.split(',')[1]!, 'base64')); await expect(button(page, `移除${noun}图片`)).toBeVisible(); }
async function open(page: Page, noun: string) { await button(page, '家庭管理').click(); await button(page, noun).click(); }
async function create(page: Page, noun: '使用人' | '场景', data: string) {
  await open(page, noun); await button(page, `新增${noun}`).click();
  await field(page, noun === '使用人' ? '使用人姓名' : '场景名称').fill(noun === '使用人' ? '小安' : '奶奶家');
  if (noun === '场景') await field(page, '场景描述').fill('和奶奶一起休息');
  await uploadPng(page, noun, data); await button(page, `保存${noun}`).click();
  await expect(page.locator('.family-row .family-thumb img')).toHaveAttribute('src', data); await button(page, '关闭').click();
}
async function expectPictures(locator: Locator, pictures: string[]) {
  await expect(locator).toHaveCount(pictures.length);
  expect(await locator.evaluateAll(nodes => nodes.map(node => node.getAttribute('src')))).toEqual(pictures);
}
async function start(page: Page, pictures: string[]) {
  await button(page, /开始沟通/).click();
  await expectPictures(page.locator('.context-choice-image img'), pictures);
  await button(page, '小安').click(); await button(page, '奶奶家').click(); await button(page, /创建聊天室，开始选卡/).click();
  await expectPictures(page.locator('.context-image img'), pictures);
}
async function end(page: Page) { await button(page, '结束聊天室').click(); await page.locator('.uni-modal__btn_primary').click(); }
async function showHistory(page: Page) { await button(page, '本机历史').click(); await page.locator('.history-row').last().click(); }

test('使用人与场景PNG随沟通历史持久保存，但扩写请求不上传图片', async ({ page }) => {
  const api = await fixtures(page); await page.goto('/'); const photos = [await png(page, '#ff7722'), await png(page, '#336699')];
  await create(page, '使用人', photos[0]!); await create(page, '场景', photos[1]!); await start(page, photos);
  await button(page, '选择苹果').click(); await button(page, /帮我整理成一句话/).click(); await expect(page.locator('.candidate-item')).toHaveCount(1);
  expect(api.inputs).toHaveLength(1); const payload = JSON.stringify(api.inputs[0]); expect(payload).not.toContain('image'); expect(payload).not.toContain('base64'); expect(payload).not.toContain(photos[0]!.split(',')[1]); expect(payload).not.toContain(photos[1]!.split(',')[1]);
  await page.locator('.candidate-button').click(); await end(page); await page.reload(); await showHistory(page);
  await expectPictures(page.locator('.history-context-image img'), photos); await expect(page.locator('.history-turn-final')).toHaveText('我想吃苹果'); expect(api.unexpected).toEqual([]);
});

test('替换及移除家庭照片保留旧聊天室与历史图片，新沟通采用更新后的资料', async ({ page }) => {
  await fixtures(page); await page.goto('/'); const original = [await png(page, '#aa1122'), await png(page, '#1122aa')]; const replacement = await png(page, '#11aa22');
  await create(page, '使用人', original[0]!); await create(page, '场景', original[1]!); await start(page, original);
  await button(page, '选择我').click(); await button(page, /使用原始表达/).click();
  for (const noun of ['使用人', '场景']) { await open(page, noun); await button(page, `编辑${noun} ${noun === '使用人' ? '小安' : '奶奶家'}`).click(); await uploadPng(page, noun, replacement); await button(page, `保存${noun}`).click(); await expect(page.locator('.family-thumb img')).toHaveAttribute('src', replacement); await button(page, '关闭').click(); }
  await expectPictures(page.locator('.context-image img'), original); await page.reload(); await expectPictures(page.locator('.context-image img'), original);
  for (const noun of ['使用人', '场景']) { await open(page, noun); await button(page, `编辑${noun} ${noun === '使用人' ? '小安' : '奶奶家'}`).click(); await button(page, `移除${noun}图片`).click(); await button(page, `保存${noun}`).click(); await expect(page.locator('.family-thumb')).toHaveCount(0); await button(page, '关闭').click(); }
  await expectPictures(page.locator('.context-image img'), original); await end(page); await start(page, []); await showHistory(page); await expectPictures(page.locator('.history-context-image img'), original);
});

for (const noun of ['使用人', '场景'] as const) {
  test(`${noun}拒绝伪装SVG和无效照片，保留表单及先前有效图片`, async ({ page }) => {
    await fixtures(page); await page.goto('/'); const valid = await png(page, '#553311'); await open(page, noun); await button(page, `新增${noun}`).click();
    const nameField = field(page, noun === '使用人' ? '使用人姓名' : '场景名称'); await nameField.fill('保留的名称'); if (noun === '场景') await field(page, '场景描述').fill('保留的描述');
    await uploadPng(page, noun, valid);
    await upload(page, noun, Buffer.from('<svg xmlns="http://www.w3.org/2000/svg"><rect width="60" height="60"/></svg>')); await expect(page.locator('.family-error')).toContainText('实际格式'); await expect(nameField).toHaveValue('保留的名称');
    await upload(page, noun, Buffer.alloc(201 * 1024)); await expect(page.locator('.family-error')).toContainText('实际格式'); await expect(nameField).toHaveValue('保留的名称');
    const large = Buffer.concat([Buffer.from(valid.split(',')[1]!, 'base64'), Buffer.alloc(201 * 1024)]);
    await upload(page, noun, large); await expect(page.locator('.family-error')).toBeHidden();
    const largeData = 'data:image/png;base64,' + large.toString('base64');
    await button(page, `保存${noun}`).click(); await expect(page.locator('.family-thumb img')).toHaveAttribute('src', largeData); await expect(page.locator('.family-row-title')).toContainText('保留的名称');
  });
}
