<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import type { State } from '../domain'
import { localSyncEntities, mergeCloudEntities, splitSyncOperations, stableSyncJSON, stripSyncPrivate, syncKey, type CloudEntity, type LocalSyncEntity, type SyncConsent } from '../domain/sync'
import { accountApi, AccountApiError, type FamilyWorkspace } from '../platform/account'
import { CloudApiError, createCloudClient, loadSyncMetadata, saveSyncMetadata, transformSyncImages, dataImageBytes, bytesDataImage, type CloudStatus, type SyncMetadata } from '../platform/cloud'
const props=defineProps<{state:State;persist:(next:State)=>boolean;accountId:string;family:FamilyWorkspace;sessionKey:string}>()
const emit=defineEmits<{close:[]}>()
const status=ref<CloudStatus|null>(null),error=ref(''),notice=ref(''),busy=ref(false),revoked=ref(false)
const metadata=ref<SyncMetadata>({accountId:props.accountId,familyId:props.family.id,cursor:'0',revisions:{}})
const selectedLocal=ref<string[]>([]),selectedRemote=ref<string[]>([]),photos=ref(false),observations=ref(false),downloadPhotos=ref(false)
const preview=ref<LocalSyncEntity[]>([]),preparedConsent=ref<SyncConsent>({photos:false,observations:false}),replaceConfirmed=ref<string[]>([])
const online=ref<CloudEntity[]>([]),decisions=ref<Record<string,'local'|'remote'>>({}),conflict=ref<any>(null),details=ref('')
const labels={profile:'使用人',card:'卡片',scene:'场景',room:'已结束历史',report:'报告'}
const local=computed(()=>localSyncEntities(props.state)),pending=computed(()=>metadata.value.pending?.operations||[])
const canWrite=computed(()=>props.family.role!=='helper'&&!revoked.value)
const enabled=computed(()=>status.value?.enabled&&status.value.configured&&!revoked.value)
let client=createCloudClient(props.family.id),generation=0,disposed=false
const operationId=()=>`sync-${Date.now().toString(36)}-${Math.random().toString(36).slice(2,14)}`
function active(token:number){if(disposed||token!==generation)throw new Error('操作已取消，未更改本机资料。')}
function persistMetadata(next:SyncMetadata){saveSyncMetadata(next);metadata.value=next}
function toggle(list:string[],key:string){const at=list.indexOf(key);at<0?list.push(key):list.splice(at,1)}
function cancel(){generation++;client.stop();client=createCloudClient(props.family.id);busy.value=false;online.value=[];selectedRemote.value=[];preview.value=[];details.value='';conflict.value=null;notice.value='联网操作已停止；已排队的操作保留，可明确重试。'}
onUnmounted(()=>{disposed=true;generation++;client.stop()})
async function run(action:(token:number)=>Promise<void>){if(busy.value||revoked.value)return;const token=++generation;busy.value=true;error.value='';notice.value='';try{await action(token)}catch(e){if(token===generation&&!disposed){if((e instanceof CloudApiError||e instanceof AccountApiError)&&[401,403].includes(e.status)){revoked.value=true;online.value=[];selectedRemote.value=[];preview.value=[];details.value='';conflict.value=null;client.stop();try{persistMetadata({...metadata.value,cursor:'0',revisions:{}})}catch{/* The original metadata is retained if storage itself fails. */}}error.value=e instanceof Error?e.message:'同步未完成，本机资料保留。'}}finally{if(token===generation)busy.value=false}}
onMounted(()=>{try{metadata.value=loadSyncMetadata(props.accountId,props.family.id)}catch(e){error.value=e instanceof Error?e.message:'同步状态无法读取';revoked.value=true;return}void run(async token=>{const result=await client.status();active(token);status.value=result})})
async function ensureOnline(token:number){const session=await accountApi.me();active(token);if(session.account.id!==props.accountId)throw new CloudApiError(403,{message:'登录账户已变化，请返回重新确认。'});const result=await client.status();active(token);status.value=result;if(!result.configured||!result.enabled)throw new Error('家庭云同步尚未配置或启用，本机资料未上传。')}
function showUploadPreview(){error.value='';if(!selectedLocal.value.length){error.value='请先逐项选择要上传的资料。';return}preview.value=JSON.parse(JSON.stringify(local.value.filter(item=>selectedLocal.value.includes(syncKey(item)))));preparedConsent.value={photos:photos.value,observations:observations.value};replaceConfirmed.value=[]}
function content(value:unknown){details.value=JSON.stringify(stripSyncPrivate(value,{photos:false,observations:true}),null,2)}
async function sendPending(token:number){
  if(props.family.role==='helper')throw new Error('协助者只能查看，不能上传。')
  if(metadata.value.pending?.sessionKey!==props.sessionKey)throw new Error('待处理操作来自先前页面或登录会话，请先拉取核对，再清除旧队列并重新预览；不会自动跨登录上传。')
  await ensureOnline(token)
  while(metadata.value.pending?.operations.length){
    const saved=metadata.value.pending,batch=splitSyncOperations(saved.operations,saved.consent)[0]!
    try{
      const result=await client.push(batch,saved.consent);active(token)
      if(!Array.isArray(result.results)||result.results.length!==batch.length||result.results.some(r=>!Number.isSafeInteger(r.revision)||r.revision<1||!batch.some(o=>o.operationId===r.operationId&&o.entityType===r.entityType&&o.entityId===r.entityId))||new Set(result.results.map(r=>r.operationId)).size!==batch.length)throw new Error('上传确认格式不正确，保留原操作编号等待核对。')
      const revisions={...metadata.value.revisions};for(const resultItem of result.results)revisions[`${resultItem.entityType}:${resultItem.entityId}`]={revision:resultItem.revision,deleted:false}
      const acknowledged=new Set(result.results.map(r=>r.operationId));const remaining=saved.operations.filter(o=>!acknowledged.has(o.operationId))
      // Push cursor is intentionally ignored: it is not a consumed pull cursor.
      persistMetadata({...metadata.value,revisions,pending:remaining.length?{...saved,operations:remaining}:undefined})
    }catch(e){if(e instanceof CloudApiError&&e.status===409&&e.details?.code==='SYNC_CONFLICT'){conflict.value=e.details;throw new Error('同一资料有更新或已被在线删除，请明确选择冲突处理方式。')}throw e}
  }
  notice.value='所选资料已上传。本机内容未被替换。';preview.value=[];selectedLocal.value=[];conflict.value=null
}
function upload(){void run(async token=>{
  if(!canWrite.value||!preview.value.length)throw new Error('请先预览需要上传的资料。')
  if(pending.value.length)throw new Error('请先处理现有待重试队列。')
  if(preview.value.some(e=>metadata.value.revisions[syncKey(e)]&&!replaceConfirmed.value.includes(syncKey(e))))throw new Error('已有在线版本的资料需要逐项确认覆盖或恢复。')
  await ensureOnline(token)
  if(preparedConsent.value.photos&&!status.value?.assetReferencesSupported)throw new Error('当前云端未开启图片同步，请关闭照片选项重新预览。')
  const consent={...preparedConsent.value},operations=[]
  for(const entity of preview.value){
    const cache=new Map<string,string>();const payload=await transformSyncImages(stripSyncPrivate(entity.payload,consent),consent.photos?'upload':'omit',async(image:string)=>{if(cache.has(image))return cache.get(image)!;active(token);const data=dataImageBytes(image),uploaded=await client.upload(entity.profileId||entity.entityId,data.bytes,data.mediaType);active(token);if(!/^[A-Za-z0-9_-]{1,100}$/.test(uploaded.id))throw new Error('图片上传确认格式不正确。');cache.set(image,uploaded.id);return uploaded.id});active(token)
    operations.push({operationId:operationId(),entityType:entity.entityType,entityId:entity.entityId,baseRevision:metadata.value.revisions[syncKey(entity)]?.revision||0,deleted:false,payload})
  }
  splitSyncOperations(operations,consent)
  persistMetadata({...metadata.value,pending:{sessionKey:props.sessionKey,consent,operations}})
  await sendPending(token)
})}
function pull(){void run(async token=>{
  online.value=[];selectedRemote.value=[];details.value='';await ensureOnline(token);const rows=new Map<string,CloudEntity>();const currentRevisions:SyncMetadata['revisions']={};let cursor='0',pages=0
  // Cloud payload previews are not stored. Every explicit refresh reads the full authorized scope.
  for(;;){if(++pages>2000)throw new Error('在线范围过大，请联系家庭管理员分批整理。');const result=await client.pull(cursor);active(token);if(BigInt(result.cursor)<BigInt(cursor)||result.hasMore&&result.cursor===cursor)throw new Error('在线游标没有正确推进，已停止拉取。');for(const entity of result.entities)rows.set(syncKey(entity),entity);for(const entity of result.entities)currentRevisions[syncKey(entity)]={revision:entity.revision,deleted:entity.deleted};cursor=result.cursor;if(!result.hasMore)break}
  active(token);persistMetadata({...metadata.value,cursor,revisions:{...currentRevisions}});online.value=[...rows.values()];selectedRemote.value=[];decisions.value={};notice.value=`已预览 ${online.value.length} 项在线资料。本机尚未改变。`
})}
function isImportable(entity:CloudEntity){return !entity.deleted&&!(entity.entityType==='room'&&entity.payload?.status!=='ended')}
function localDifference(entity:CloudEntity){const here=local.value.find(item=>syncKey(item)===syncKey(entity));return !!here&&stableSyncJSON(here.payload)!==stableSyncJSON(entity.payload)}
function importSelected(){void run(async token=>{
  const chosen=online.value.filter(e=>selectedRemote.value.includes(syncKey(e)));if(!chosen.length)throw new Error('请先逐项选择在线资料。')
  const result=await uni.showModal({title:'合并选中的在线资料？',content:`将合并 ${chosen.length} 项；同编号冲突按你的逐项选择处理。未选资料和当前未结束的沟通保留。${downloadPhotos.value?'会下载所选资料的图片。':'不下载图片。'}`,confirmText:'确认合并'});active(token);if(!result.confirm)return
  const session=await accountApi.me();active(token);if(session.account.id!==props.accountId)throw new CloudApiError(403,{message:'登录账户已变化，请重新进入同步。'});const families=await accountApi.families();active(token);const family=families.find(f=>f.id===props.family.id);if(!family)throw new CloudApiError(403,{message:'家庭访问权限已撤回，在线预览已清除。'})
  const prepared:CloudEntity[]=[];for(const entity of chosen){const payload=await transformSyncImages(entity.payload,downloadPhotos.value?'download':'omit',async(ref:{id:string})=>{active(token);const response=await client.download(ref.id);active(token);return bytesDataImage(response.bytes,response.mediaType)});active(token);prepared.push({...entity,payload})}
  const next=mergeCloudEntities(props.state,prepared,decisions.value,family.role==='helper'?family.profileAccess||[]:undefined);active(token)
  if(!props.persist(next))throw new Error('本机存储未成功，未替换内存资料，请检查空间后重试。')
  notice.value=`已合并 ${chosen.length} 项在线资料；当前沟通保持原状。`;selectedRemote.value=[]
})}
async function resolveConflict(localVersion:boolean){const detail=conflict.value;if(!detail)return;const result=await uni.showModal({title:localVersion?'用本机版本重新提交？':'保留在线版本？',content:localVersion?`将按在线修订 ${detail.currentRevision} 重新提交本机版本，包括恢复已删除资料；使用新的操作编号。`:'取消这一项待上传操作，本机资料保留；需要在线版本时请另行预览导入。',confirmText:'确认选择'});if(!result.confirm)return;void run(async token=>{active(token);const saved=metadata.value.pending;if(!saved)throw new Error('待处理操作不存在。');const operations=localVersion?saved.operations.map(o=>o.operationId===detail.operationId?{...o,operationId:operationId(),baseRevision:detail.currentRevision}:o):saved.operations.filter(o=>o.operationId!==detail.operationId);persistMetadata({...metadata.value,revisions:{...metadata.value.revisions,[`${detail.entityType}:${detail.entityId}`]:{revision:detail.currentRevision,deleted:!!detail.remote?.deleted}},pending:operations.length?{...saved,operations}:undefined});conflict.value=null;error.value='';notice.value=localVersion?'已建立新操作，请点击重试待处理上传。':'已保留在线版本，本机资料未被覆盖。'})}
async function discardPending(){const result=await uni.showModal({title:'清除本机待上传队列？',content:'网络中断时服务器可能已收到资料。清除队列不删除在线资料；请先拉取核对。本机原始资料保留。',confirmText:'清除队列'});if(result.confirm&&!disposed)try{persistMetadata({...metadata.value,pending:undefined});conflict.value=null;notice.value='待上传队列已清除，原始本机资料保留。'}catch(e){error.value=e instanceof Error?e.message:'清除失败'}}
</script>
<template>
  <view class="cloud-manager">
    <button role="button" tabindex="0" class="text-button" @click="emit('close')">← 返回家庭账户</button>
    <text class="section-title">{{family.name}} · 同步与在线资料</text>
    <text class="modal-copy">只同步你明确选择的资料。登录、拉取预览不会自动上传或合并。本机待重试队列可能包含所选私密内容，仅留在此设备；不保存密码或登录凭证。</text>
    <text v-if="error" role="alert" class="error-copy">{{error}}</text><text v-if="notice" role="status" class="modal-copy">{{notice}}</text>
    <text v-if="!enabled" class="modal-copy">{{revoked?'权限或会话已变化，请返回重新确认登录和家庭。':'云端尚未配置或启用，当前资料仍保存在本机。'}}</text>
    <button v-if="busy" role="button" tabindex="0" class="quiet-button" @click="cancel">停止联网操作</button>
    <template v-if="pending.length"><text>待处理上传：{{pending.length}} 项</text><text v-if="metadata.pending?.sessionKey!==sessionKey" class="error-copy">来自先前页面或登录会话，不会自动重试。请先拉取核对，再清除旧队列并重新选择。</text><button role="button" tabindex="0" class="quiet-button" :disabled="busy||!canWrite||metadata.pending?.sessionKey!==sessionKey" @click="run(sendPending)">重试待处理上传</button><button role="button" tabindex="0" class="text-button" :disabled="busy" @click="discardPending">清除待上传队列</button></template>
    <view v-if="conflict" class="cloud-conflict"><text>同步冲突 · {{labels[conflict.entityType as keyof typeof labels]}} {{conflict.entityId}}</text><text>本机待上传基准：{{pending.find(o=>o.operationId===conflict.operationId)?.baseRevision}} · 在线修订：{{conflict.currentRevision}}{{conflict.remote?.deleted?'（在线已删除）':''}}</text><button role="button" tabindex="0" @click="content(pending.find(o=>o.operationId===conflict.operationId)?.payload)">查看本机待上传内容</button><button role="button" tabindex="0" @click="content(conflict.remote?.payload)">查看在线冲突内容</button><button role="button" tabindex="0" :disabled="busy" @click="resolveConflict(false)">保留在线版本</button><button role="button" tabindex="0" :disabled="busy||!canWrite" @click="resolveConflict(true)">用本机版本重新提交</button></view>
    <template v-if="canWrite">
      <text class="section-title">选择本机上传范围</text><text class="modal-copy">全部默认不选。进行中的聊天室不上传；历史正文与报告可能包含私密信息。</text>
      <view class="cloud-list"><button v-for="item in local" :key="syncKey(item)" role="button" tabindex="0" :disabled="busy||!!pending.length" :aria-pressed="selectedLocal.includes(syncKey(item))" :class="{active:selectedLocal.includes(syncKey(item))}" :aria-label="`上传选择 ${labels[item.entityType]} ${item.label}`" @click="toggle(selectedLocal,syncKey(item))">{{selectedLocal.includes(syncKey(item))?'✓':'○'}} {{labels[item.entityType]}} · {{item.label}}</button></view>
      <view class="scene-options"><button role="button" tabindex="0" :disabled="busy||!!pending.length" :aria-pressed="photos" :class="{active:photos}" @click="photos=!photos">{{photos?'✓':'○'}} 同意上传所选资料的照片</button><button role="button" tabindex="0" :disabled="busy||!!pending.length" :aria-pressed="observations" :class="{active:observations}" @click="observations=!observations">{{observations?'✓':'○'}} 同意上传家人观察</button></view>
      <button role="button" tabindex="0" class="outline-button" :disabled="busy||!enabled||!!pending.length" @click="showUploadPreview">预览所选上传</button>
      <view v-if="preview.length" class="cloud-preview"><text>即将上传 {{preview.length}} 项 · {{preparedConsent.photos?'包括照片':'不含照片'}} · {{preparedConsent.observations?'包括家人观察':'不含家人观察'}}</text><text class="modal-copy">以下预览已固定，改变上面的选择后需要重新预览。</text><view v-for="item in preview" :key="syncKey(item)"><text>{{labels[item.entityType]}} · {{item.label}}</text><button role="button" tabindex="0" @click="content(stripSyncPrivate(item.payload,preparedConsent))">查看所选内容</button><button v-if="metadata.revisions[syncKey(item)]" role="button" tabindex="0" :aria-pressed="replaceConfirmed.includes(syncKey(item))" @click="toggle(replaceConfirmed,syncKey(item))">{{replaceConfirmed.includes(syncKey(item))?'✓':'○'}} 确认{{metadata.revisions[syncKey(item)]?.deleted?'恢复已删除资料':'替换在线资料'}}（修订 {{metadata.revisions[syncKey(item)]?.revision}}）</button></view><button role="button" tabindex="0" class="small-primary" :disabled="busy||!enabled||!!pending.length" @click="upload">确认上传这些资料</button></view>
    </template>
    <text v-else-if="family.role==='helper'" class="modal-copy">协助者仅可预览授权使用人的在线资料，不能上传或修改家庭云端。</text>
    <text class="section-title">手动拉取与导入</text><button role="button" tabindex="0" class="outline-button" :disabled="busy||!enabled" @click="pull">拉取在线资料预览</button><text class="modal-copy">拉取只显示在线版本。删除标记不会自动删除本机资料，进行中在线聊天室只能查看。</text>
    <view v-for="entity in online" :key="syncKey(entity)" class="cloud-entity"><button role="button" tabindex="0" :disabled="busy||!isImportable(entity)" :aria-pressed="selectedRemote.includes(syncKey(entity))" :aria-label="`导入选择 ${labels[entity.entityType]} ${entity.payload?.name||entity.payload?.label||entity.entityId}`" @click="toggle(selectedRemote,syncKey(entity))">{{selectedRemote.includes(syncKey(entity))?'✓':'○'}} {{labels[entity.entityType]}} · {{entity.payload?.name||entity.payload?.label||entity.entityId}} · 在线修订 {{entity.revision}} {{entity.deleted?'（已删除）':entity.entityType==='room'&&entity.payload?.status!=='ended'?'（进行中，仅查看）':''}}</button><button role="button" tabindex="0" class="text-button" @click="content(entity.payload)">查看在线内容</button><view v-if="!entity.deleted&&localDifference(entity)" class="scene-options"><text>与本机同编号资料有差异：</text><button role="button" tabindex="0" :class="{active:decisions[syncKey(entity)]==='local'}" @click="decisions[syncKey(entity)]='local'">保留本机这一项</button><button role="button" tabindex="0" :class="{active:decisions[syncKey(entity)]==='remote'}" @click="decisions[syncKey(entity)]='remote'">采用在线这一项</button></view></view>
    <button role="button" tabindex="0" :aria-pressed="downloadPhotos" :class="{active:downloadPhotos}" :disabled="busy" @click="downloadPhotos=!downloadPhotos">{{downloadPhotos?'✓':'○'}} 下载所选资料的图片</button><button role="button" tabindex="0" class="small-primary" :disabled="busy||!enabled||!selectedRemote.length" @click="importSelected">导入选中在线资料</button>
    <view v-if="details" class="cloud-details"><text class="section-title">资料内容（图片不在此显示）</text><textarea :value="details" disabled maxlength="-1" aria-label="同步资料内容"/><button role="button" tabindex="0" class="text-button" @click="details=''">收起内容</button></view>
  </view>
</template>
<style scoped>
.cloud-manager{display:flex;flex-direction:column;gap:16px;min-width:0}.cloud-list{display:flex;flex-direction:column;gap:6px;max-height:310px;overflow:auto}.cloud-list button,.cloud-entity>button{text-align:left;font-size:13px;background:#f5f8fc;border:1px solid #e1e8f1;overflow-wrap:anywhere}.cloud-manager button{min-height:44px}.cloud-manager .active{background:#e6effb;border-color:#7395c0;color:#234c81}.cloud-conflict,.cloud-preview{padding:16px;background:#f0f5fb;border:1px solid #d4e0ee;border-radius:10px;display:flex;flex-direction:column;gap:12px;font-size:13px;line-height:1.8}.cloud-conflict{background:#fff4ec;border-color:#efd3bc}.cloud-entity{border-bottom:1px solid #e3e8f0;padding:12px 0;display:flex;flex-direction:column;gap:8px;font-size:12px;overflow-wrap:anywhere}.cloud-details textarea{width:100%;height:260px;margin-top:12px;border:1px solid #d9e3f0;border-radius:8px;padding:12px;font-size:12px;box-sizing:border-box}.cloud-preview>view{display:flex;flex-direction:column;gap:4px}.cloud-manager .scene-options{flex-wrap:wrap}
</style>
