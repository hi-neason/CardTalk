import { test, expect, type Page } from '@playwright/test';
const button = (page: Page, name: string | RegExp) => page.getByRole('button', { name, exact: typeof name === 'string' });
async function setup(page: Page) {
  await page.addInitScript(() => {
    (window as any).__voiceCalls = [];
    (window as any).SpeechSynthesisUtterance = class { text: string; constructor(text: string) { this.text = text; } };
    Object.defineProperty(window, 'speechSynthesis', { configurable: true, value: { getVoices: () => [{ voiceURI: 'test-chinese', name: '测试中文', lang: 'zh-CN', localService: true }], cancel: () => {}, speak: (u: any) => { (window as any).__voiceCalls.push({ text: u.text, rate: u.rate, volume: u.volume, voice: u.voice?.voiceURI }); queueMicrotask(() => { u.onstart?.(); u.onend?.(); }); } } });
  });
  await page.route('**/api/v1/**', route => route.fulfill({ json: route.request().url().endsWith('/health') ? { status:'ok',llmConfigured:false,ttsConfigured:false } : { token:'fixture',remaining:10,limit:10,resetAt:'2099-01-01T00:00:00Z',capabilities:{profileManagement:true,customCards:true,scenarioManagement:true,analytics:true} } }));
  await page.goto('/');
}
async function start(page: Page, profile?: string) { await button(page, /开始沟通/).click(); if(profile) await button(page,profile).click(); await button(page,/创建聊天室，开始选卡/).click(); }
test('全局显示及防连点刷新保留，停止卡不受拦截，播放使用设备音量与速度', async ({ page }) => {
  await setup(page); await button(page,'设置').click();
  await button(page,'仅图片').click(); await button(page,'紧凑').click(); await button(page,'大字').click(); await button(page,'1000 毫秒').click();
  await button(page,'朗读速度 0.7 倍').click(); await button(page,'播放音量 50%').click(); await button(page,'测试中文 (zh-CN)').click();
  await button(page,'试听设备声音').click();
  await expect.poll(() => page.evaluate(() => (window as any).__voiceCalls.at(-1))).toMatchObject({rate:0.7,volume:0.5,voice:'test-chinese'});
  await button(page,'关闭').click(); await page.reload(); await start(page);
  await expect(page.locator('.app-shell')).toHaveClass(/cards-images-only/); await expect(page.locator('.app-shell')).toHaveClass(/cards-compact/); await expect(page.locator('.app-shell')).toHaveClass(/large-text/);
  await button(page,'选择我').click(); await button(page,'选择我').click(); await expect(page.locator('.selected-word')).toHaveCount(1);
  await button(page,'选择停一下').click(); await button(page,'选择停一下').click(); await expect(page.locator('.selected-word')).toHaveCount(3);
  await expect(page.locator('.notice-message')).toContainText('忽略快速重复');
  await button(page,'使用原始表达').click(); await button(page,/说出这句话/).click();
  await expect.poll(() => page.evaluate(() => (window as any).__voiceCalls.at(-1))).toMatchObject({rate:0.7,volume:0.5});
});
test('个人偏好独立于全局且活动聊天室保持旧快照，关闭防连点允许重复', async ({ page }) => {
  await setup(page); await button(page,'家庭管理').click(); await button(page,'新增使用人').click(); await page.getByRole('textbox',{name:'使用人姓名',exact:true}).fill('小林');
  await button(page,'仅文字').click(); await button(page,'播放音量 25%').click(); await button(page,'保存使用人').click(); await button(page,'关闭').click();
  await start(page,'小林'); await expect(page.locator('.app-shell')).toHaveClass(/cards-text-only/);
  await button(page,'选择我').click(); await button(page,'选择我').click(); await expect(page.locator('.selected-word')).toHaveCount(2);
  await button(page,'家庭管理').click(); await button(page,'编辑使用人 小林').click(); await button(page,'仅图片').click(); await button(page,'保存使用人').click(); await button(page,'关闭').click();
  await button(page,'设置').click(); await button(page,'图片与文字').click(); await button(page,'关闭').click(); await page.reload();
  await expect(page.locator('.app-shell')).toHaveClass(/cards-text-only/);
  await button(page,'使用原始表达').click(); await button(page,/说出这句话/).click();
  await expect.poll(() => page.evaluate(() => (window as any).__voiceCalls.at(-1))).toMatchObject({volume:0.25});
  await button(page,'结束聊天室').click(); await page.locator('.uni-modal__btn_primary').click(); await start(page,'小林'); await expect(page.locator('.app-shell')).toHaveClass(/cards-images-only/);
});
