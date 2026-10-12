import { expect, test, type Page } from '@playwright/test';
import { createState, createFamilyProfile, createCustomScene, createCustomCard, createRoom, addCard, recordRawExpression, endRoom, CARDS, type State } from '../../frontend/src/domain';
const key = 'cardtalk.communication.v1';
const button = (page: Page, name: string) => page.getByRole('button', { name, exact: true });
function backup() {
  let state = createFamilyProfile(createState(), { name: '导入使用人', preset: 'adult' });
  state = createCustomScene(state, { name: '导入阳台', description: '看花' });
  state = createCustomCard(state, { label: '导入水杯', category: 'daily' });
  state = createRoom(state, { preset: 'adult', communicatorName: '导入使用人', profileId: state.family.profiles[0]!.id, sceneId: state.family.scenes[0]!.id, source: 'independent', participants: [] });
  return endRoom(recordRawExpression(addCard(state, CARDS[0]!)));
}
async function setup(page: Page, original: State = createFamilyProfile(createState(), { name: '原有使用人', preset: 'children' })) {
  const unexpected: string[] = [];
  await page.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/health')) return route.fulfill({ json: { status: 'ok', llmConfigured: false, ttsConfigured: false, promptVersion: 'backup-fixture' } });
    if (path.endsWith('/trial-session')) return route.fulfill({ json: { token: 'backup-fixture-token', remaining: 10, limit: 10, resetAt: '2099-01-01T00:00:00Z', capabilities: { analytics: true, profileManagement: true, scenarioManagement: true, customCards: true } } });
    unexpected.push(path); return route.fulfill({ status: 503, json: { message: 'No paid API calls in fixture' } });
  });
  await page.goto('/');
  await page.evaluate(({ key, state }) => localStorage.setItem(key, JSON.stringify(state)), { key, state: original });
  await page.reload(); await button(page, '设置').click();
  return { original, unexpected };
}

test('范围导出不包含草稿，默认移除照片与家人观察',async({page})=>{
 const state=backup();state.family.profiles[0]!.observations='不应导出的家人观察';state.family.profiles[0]!.observer='家人';state.family.profiles[0]!.observedAt=new Date().toISOString();
 const fixture=await setup(page,state);
 const download=page.waitForEvent('download');await button(page,'导出所选范围资料').click();
 const artifact=await download;const path=await artifact.path();
 const {readFile}=await import('node:fs/promises');const data=JSON.parse(await readFile(path!,'utf8'));
 expect(data.type).toBe('cardtalk-selected-data');expect(data.rooms).toHaveLength(1);expect(JSON.stringify(data)).not.toContain('不应导出的家人观察');expect(JSON.stringify(data)).not.toContain('draft');
 expect(await page.evaluate(key=>JSON.parse(localStorage.getItem(key)!),key)).toEqual(state);expect(fixture.unexpected).toEqual([]);
});
test('清空必须明确确认，取消保留资料，确认后刷新仍为空',async({page})=>{
 const fixture=await setup(page,backup());await button(page,'清空全部本机产品数据').click();await page.locator('.uni-modal__btn_default').click();
 expect(await page.evaluate(key=>JSON.parse(localStorage.getItem(key)!),key)).toEqual(fixture.original);
 await button(page,'清空全部本机产品数据').click();await page.locator('.uni-modal__btn_primary').click();
 await expect(page.getByText('本机产品数据已清空。',{exact:true})).toBeVisible();
 await page.reload();const state=await page.evaluate(key=>JSON.parse(localStorage.getItem(key)!),key);expect(state.family.profiles).toEqual([]);expect(state.rooms).toEqual([]);expect(fixture.unexpected).toEqual([]);
});
