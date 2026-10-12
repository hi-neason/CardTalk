import { expect,test,type Page } from '@playwright/test';
const b=(p:Page,n:string|RegExp)=>p.getByRole('button',{name:n,exact:typeof n==='string'});
async function setup(page:Page) {
  await page.addInitScript(()=>{ (window as any).__calls=[]; Object.defineProperty(window,'speechSynthesis',{configurable:true,value:{getVoices:()=>[],cancel:()=>{},speak:(u:any)=>{(window as any).__calls.push(u.text);queueMicrotask(()=>{u.onstart?.();u.onend?.();});}}}); });
  await page.route('**/api/v1/**',r=>r.fulfill({json:r.request().url().endsWith('/health')?{status:'ok',llmConfigured:false,ttsConfigured:false}:{token:'test',remaining:10,limit:10,resetAt:'2099-01-01T00:00:00Z',capabilities:{profileManagement:true,scenarioManagement:true,customCards:true}}}));
  await page.goto('/');
}
async function start(page:Page){await b(page,/开始沟通/).click();await b(page,/创建聊天室，开始选卡/).click();}
test('收藏和最近卡可复用，核心始终可选；常用表达再次确认后才播放并保留独立记录',async({page})=>{
  await setup(page); await b(page,'收藏苹果').click(); await b(page,'收藏卡片').click(); await expect(page.locator('.word-card')).toHaveCount(1); await expect(b(page,'选择不要')).toBeVisible();
  await start(page);await b(page,'选择苹果').click();await b(page,'使用原始表达').click();await b(page,'保存为常用表达').click();await b(page,/开始下一句/).click();
  await b(page,'最近使用').click();await expect(b(page,'选择苹果')).toBeVisible();
  await b(page,'常用表达').click(); await b(page,'使用常用表达 苹果').click();
  await expect(b(page,'确认使用这句表达')).toBeVisible();await expect(b(page,/帮我整理成一句话/)).toHaveCount(0);
  expect(await page.evaluate(()=>(window as any).__calls)).toEqual([]);await b(page,'确认使用这句表达').click();await expect(page.locator('.confirmed-label')).toContainText('常用表达');expect(await page.evaluate(()=>(window as any).__calls)).toEqual([]);
  await b(page,/说出这句话/).click();await expect.poll(()=>page.evaluate(()=>(window as any).__calls)).toEqual(['苹果']);
  await b(page,/开始下一句/).click();await b(page,'常用表达').click();await b(page,'移除常用表达 苹果').click();await page.locator('.uni-modal__btn_primary').click();await expect(page.locator('.saved-phrase-row')).toHaveCount(0);await b(page,'关闭').click();
  await b(page,'本机历史').click();await page.locator('.history-row').click();await expect(page.locator('.history-turn')).toHaveCount(2);await expect(page.locator('.history-turn').last()).toContainText('常用表达确认');
  await b(page,'关闭').click();await page.reload();await b(page,'收藏卡片').click();await expect(b(page,'取消收藏苹果')).toBeVisible();
});
test('常用表达预览可返回选卡，不产生记录且指南可用',async({page})=>{
  await setup(page);await start(page);await b(page,'选择我').click();await b(page,'使用原始表达').click();await b(page,'保存为常用表达').click();await b(page,/开始下一句/).click();await b(page,'常用表达').click();await b(page,'使用常用表达 我').click();await b(page,'返回重新选卡').click();await expect(page.locator('.selected-word')).toHaveCount(0);await expect(page.locator('.saved-phrase-preview')).toHaveCount(0);
  await b(page,'本机历史').click();await page.locator('.history-row').click();await expect(page.locator('.history-turn')).toHaveCount(1);await b(page,'关闭').click();await b(page,'设置').click();await b(page,'使用指南').click();await expect(page.getByRole('dialog',{name:'使用指南'})).toBeVisible();await b(page,'尊重本人表达').click();await expect(page.locator('.help-content')).toContainText('不要为了礼貌而弱化');
});

test('表达过程区分失败、重试成功和候选编辑，确认后进入历史',async({page})=>{
  await setup(page);let calls=0;
  await page.route('**/api/v1/expansions',async route=>{calls++;if(calls===1)return route.fulfill({status:503,json:{code:'PROVIDER_UNAVAILABLE',message:'模拟扩写失败'}});const input=route.request().postDataJSON();return route.fulfill({json:{requestId:input.requestId,inputVersion:input.inputVersion,candidates:[{id:'process-choice',text:'我想休息',sourceCardIds:input.cards.map((c:any)=>c.id)}],needsClarification:false,clarification:null,promptVersion:'test',remaining:8}});});
  await start(page);await b(page,'选择我').click();await b(page,/帮我整理成一句话/).click();await expect(page.locator('.expression-panel .error-message')).toContainText('模拟扩写失败');await b(page,/帮我整理成一句话/).click();await b(page,'修改后确认').click();await page.getByRole('textbox',{name:'修改候选表达'}).fill('我想休息一下');await b(page,'确认修改').click();
  await b(page,'本机历史').click();await page.locator('.history-row').click();await expect(page.locator('.history-process')).toContainText('扩写失败');await expect(page.locator('.history-process')).toContainText('得到候选');await expect(page.locator('.history-process')).toContainText('开始修改候选');await expect(page.locator('.history-process')).toContainText('包含等待时间');expect(calls).toBe(2);
});
test('改卡取消扩写按原输入记录，迟到响应不恢复候选',async({page})=>{
  await setup(page);let received=false;let release!:()=>void;const pending=new Promise<void>(resolve=>release=resolve);
  await page.route('**/api/v1/expansions',async route=>{received=true;const input=route.request().postDataJSON();await pending;await route.fulfill({json:{requestId:input.requestId,inputVersion:input.inputVersion,candidates:[{id:'late',text:'我',sourceCardIds:input.cards.map((c:any)=>c.id)}],needsClarification:false,clarification:null,promptVersion:'test',remaining:8}}).catch(()=>{});});
  await start(page);await b(page,'选择我').click();await b(page,/帮我整理成一句话/).click();await expect.poll(()=>received).toBe(true);await b(page,'选择苹果').click();release();await b(page,'使用原始表达').click();await expect(page.locator('.candidate-item')).toHaveCount(0);await b(page,'本机历史').click();await page.locator('.history-row').click();await expect(page.locator('.history-process')).toContainText('取消扩写');await expect(page.locator('.history-process')).not.toContainText('得到候选');
});
