import { expect,test,type Page } from '@playwright/test';
import { createState,createFamilyProfile,createRoom,addCard,CARDS } from '../../frontend/src/domain';
const photo='data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=';
const button=(p:Page,name:string|RegExp)=>p.getByRole('button',{name,exact:typeof name==='string'});
async function setup(page:Page,role='owner'){
  let state=createFamilyProfile(createState(),{name:'同步小林',preset:'children',image:photo,observations:'不应默认上传的观察',observer:'家人',observedAt:'2026-10-12'});
  state=addCard(createRoom(state,{profileId:state.family.profiles[0]!.id,preset:'children',communicatorName:'同步小林',sceneId:null,participants:[],source:'unknown'}),CARDS[0]!);
  await page.addInitScript(value=>{if(!localStorage.getItem('cardtalk.communication.v1'))localStorage.setItem('cardtalk.communication.v1',JSON.stringify(value));},state);
  const control={state,role,loggedIn:true,revoked:false,pushes:[] as any[],assets:[] as {scope:string;bytes:number}[],downloads:0,pulls:[] as string[],failPush:false,conflict:false,remote:[] as any[],defer:null as Promise<void>|null};
  await page.route('**/api/v1/**',async route=>{
    const url=new URL(route.request().url()),path=url.pathname;
    if(path.endsWith('/health'))return route.fulfill({json:{status:'ok',llmConfigured:false,ttsConfigured:false}});
    if(path.endsWith('/trial-session'))return route.fulfill({json:{token:'anonymous-not-cloud',remaining:10,limit:10,resetAt:'2099-01-01',capabilities:{profileManagement:true}}});
    if(path.endsWith('/accounts/me'))return route.fulfill(control.loggedIn?{json:{account:{id:'sync-account',username:'sync_user'}}}:{status:401,json:{message:'请登录'}});
    if(path.endsWith('/accounts/logout')){control.loggedIn=false;return route.fulfill({status:204});}
    if(path==='/api/v1/families')return route.fulfill({json:control.revoked?[]:[{id:'sync-family',name:'同步测试家庭',role:control.role,profileAccess:[state.family.profiles[0]!.id]},{id:'other-family',name:'另一家庭',role:'owner',profileAccess:[]}]});
    if(path.endsWith('/members'))return route.fulfill({json:[]});
    if(path.includes('/sync/')&&control.revoked)return route.fulfill({status:403,json:{message:'访问权限已撤回'}});
    if(path.endsWith('/sync/status'))return route.fulfill({json:{configured:true,enabled:true,workspace:'cloud',familyId:'sync-family',assetReferencesSupported:true}});
    if(path.endsWith('/sync/push')){const data=route.request().postDataJSON();control.pushes.push(data);if(control.failPush){control.failPush=false;return route.abort('failed');}if(control.conflict){control.conflict=false;const op=data.operations[0];return route.fulfill({status:409,json:{code:'SYNC_CONFLICT',operationId:op.operationId,entityType:op.entityType,entityId:op.entityId,currentRevision:7,remote:{entityType:op.entityType,entityId:op.entityId,revision:7,profileId:op.entityId,deleted:true,payload:null}}});}return route.fulfill({json:{results:data.operations.map((op:any)=>({operationId:op.operationId,entityType:op.entityType,entityId:op.entityId,revision:8})),cursor:'99'}});}
    if(path.endsWith('/sync/pull')){control.pulls.push(url.searchParams.get('cursor')!);if(control.defer)await control.defer;return route.fulfill({json:{entities:control.remote,cursor:'12',hasMore:false}}).catch(()=>{});}
    if(path.endsWith('/assets')){control.assets.push({scope:url.searchParams.get('profileId')!,bytes:route.request().postDataBuffer()?.length||0});return route.fulfill({json:{id:'asset-one'}});}
    if(path.endsWith('/assets/asset-one')){control.downloads++;return route.fulfill({contentType:'image/png',body:Buffer.from(photo.split(',')[1]!,'base64')});}
    return route.fulfill({status:503,json:{message:'No real provider calls in sync fixture'}});
  });
  await page.goto('/');await button(page,'设置').click();await button(page,'账户与家庭成员').click();await button(page,'同步与在线资料').click();await expect(button(page,'拉取在线资料预览')).toBeEnabled();
  return control;
}
async function selectUpload(page:Page){await button(page,'上传选择 使用人 同步小林').click();await button(page,'预览所选上传').click();}
async function localState(page:Page){return page.evaluate(()=>JSON.parse(localStorage.getItem('cardtalk.communication.v1')!));}
test('默认不传私密内容，失败重试绑定相同操作和同意且push游标不推进pull',async({page})=>{
  const c=await setup(page);const original=await localState(page);expect(c.pushes).toHaveLength(0);expect(c.assets).toHaveLength(0);expect(c.pulls).toHaveLength(0);
  c.failPush=true;await selectUpload(page);await button(page,'确认上传这些资料').click();await expect(page.locator('.cloud-manager')).toContainText('待处理上传：1 项');await expect(button(page,'重试待处理上传')).toBeEnabled();await button(page,'重试待处理上传').click();await expect(page.locator('.cloud-manager')).toContainText('所选资料已上传');
  expect(c.pushes).toHaveLength(2);expect(c.pushes[1]).toEqual(c.pushes[0]);expect(c.pushes[0].consent).toEqual({photos:false,observations:false});expect(c.pushes[0].operations[0].payload).toMatchObject({observations:'',observer:'',observedAt:null});expect(c.pushes[0].operations[0].payload).not.toHaveProperty('image');expect(c.assets).toHaveLength(0);expect(await localState(page)).toEqual(original);
  const metadata=await page.evaluate(()=>JSON.parse(localStorage.getItem('cardtalk.cloud-sync.v1.sync-account.sync-family')!));expect(metadata.cursor).toBe('0');expect(metadata.pending).toBeUndefined();
});
test('拉取仅预览，导入冲突明确选择且保留当前未结束会话',async({page})=>{
  const c=await setup(page);const original=await localState(page);const profile={...c.state.family.profiles[0]!,name:'在线称呼'};delete profile.image;
  c.remote=[{entityType:'profile',entityId:profile.id,revision:12,profileId:profile.id,deleted:false,payload:profile}];await button(page,'拉取在线资料预览').click();await expect(page.locator('.cloud-entity')).toHaveCount(1);expect(await localState(page)).toEqual(original);
  await button(page,'导入选择 使用人 在线称呼').click();await button(page,'导入选中在线资料').click();await page.locator('.uni-modal__btn_primary').click();await expect(page.locator('.cloud-manager [role=alert]')).toContainText('逐项选择');expect(await localState(page)).toEqual(original);
  await button(page,'采用在线这一项').click();await button(page,'导入选中在线资料').click();await page.locator('.uni-modal__btn_primary').click();await expect(page.locator('.cloud-manager')).toContainText('已合并 1 项');const next=await localState(page);expect(next.family.profiles[0].name).toBe('在线称呼');expect(next.rooms).toEqual(original.rooms);expect(next.activeRoomId).toBe(original.activeRoomId);expect(c.downloads).toBe(0);
});
test('在线删除冲突不能默默恢复，明确恢复使用新操作编号和当前修订',async({page})=>{
  const c=await setup(page);c.conflict=true;await selectUpload(page);await button(page,'确认上传这些资料').click();await expect(page.locator('.cloud-conflict')).toContainText('在线已删除');await button(page,'用本机版本重新提交').click();await page.locator('.uni-modal__btn_primary').click();await button(page,'重试待处理上传').click();await expect(page.locator('.cloud-manager')).toContainText('所选资料已上传');expect(c.pushes[1].operations[0].operationId).not.toBe(c.pushes[0].operations[0].operationId);expect(c.pushes[1].operations[0].baseRevision).toBe(7);expect(c.pushes[1].operations[0].payload).toEqual(c.pushes[0].operations[0].payload);
});
test('照片需单独同意，上传二进制资源并仅传同scope引用',async({page})=>{
  const c=await setup(page);await button(page,/同意上传所选资料的照片/).click();await selectUpload(page);await button(page,'确认上传这些资料').click();await expect(page.locator('.cloud-manager')).toContainText('所选资料已上传');expect(c.assets).toEqual([{scope:c.state.family.profiles[0]!.id,bytes:Buffer.from(photo.split(',')[1]!,'base64').length}]);expect(c.pushes[0].operations[0].payload.assetRef).toEqual({id:'asset-one'});expect(JSON.stringify(c.pushes)).not.toContain('data:image');
});
test('撤权清除在线预览，协助者没有上传入口',async({page})=>{
  const c=await setup(page,'helper');await expect(button(page,'预览所选上传')).toHaveCount(0);c.remote=[{entityType:'profile',entityId:c.state.family.profiles[0]!.id,revision:12,profileId:c.state.family.profiles[0]!.id,deleted:false,payload:c.state.family.profiles[0]}];await button(page,'拉取在线资料预览').click();await expect(page.locator('.cloud-entity')).toHaveCount(1);c.revoked=true;await button(page,'拉取在线资料预览').click();await expect(page.locator('.cloud-entity')).toHaveCount(0);await expect(page.locator('.cloud-manager')).toContainText('权限或会话已变化');expect(c.pushes).toHaveLength(0);
});
test('退出云预览后迟到拉取不覆盖另一家庭，也不触发导入',async({page})=>{
  const c=await setup(page);let release!:()=>void;c.defer=new Promise<void>(resolve=>release=resolve);c.remote=[{entityType:'profile',entityId:c.state.family.profiles[0]!.id,revision:12,profileId:c.state.family.profiles[0]!.id,deleted:false,payload:c.state.family.profiles[0]}];const original=await localState(page);await button(page,'拉取在线资料预览').click();await expect.poll(()=>c.pulls.length).toBe(1);await button(page,'← 返回家庭账户').click();await button(page,'另一家庭 · 家庭创建者').click();await button(page,'同步与在线资料').click();release();await expect(page.locator('.cloud-manager')).toContainText('另一家庭');await expect(page.locator('.cloud-entity')).toHaveCount(0);expect(await localState(page)).toEqual(original);
});
