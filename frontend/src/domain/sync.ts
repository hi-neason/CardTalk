import type { State } from './types'
import { validateState } from './validation'
import { ensureStateCapacity } from './capacity'
import { CARDS, CORE_CARDS, SCENES } from './content'
export type SyncEntityType = 'profile'|'card'|'scene'|'room'|'report'
export interface SyncConsent { photos: boolean; observations: boolean }
export interface CloudEntity { entityType: SyncEntityType; entityId: string; revision: number; profileId: string|null; deleted: boolean; payload: Record<string, any>|null }
export interface LocalSyncEntity { entityType: SyncEntityType; entityId: string; profileId: string|null; label: string; payload: Record<string, any> }
export interface SyncOperation { operationId: string; entityType: SyncEntityType; entityId: string; baseRevision: number; deleted: boolean; payload: Record<string, any>|null }
export const syncKey = (entity: {entityType: string;entityId:string}) => `${entity.entityType}:${entity.entityId}`
export function stableSyncJSON(value: any): string { if(Array.isArray(value))return `[${value.map(stableSyncJSON).join(',')}]`;if(value&&typeof value==='object')return `{${Object.keys(value).sort().map(key=>`${JSON.stringify(key)}:${stableSyncJSON(value[key])}`).join(',')}}`;return JSON.stringify(value) }
export function localSyncEntities(state: State): LocalSyncEntity[] {
  return [
    ...state.family.profiles.map(p=>({entityType:'profile' as const,entityId:p.id,profileId:p.id,label:p.name,payload:p})),
    ...state.family.cards.map(p=>({entityType:'card' as const,entityId:p.id,profileId:null,label:p.label,payload:p})),
    ...state.family.scenes.map(p=>({entityType:'scene' as const,entityId:p.id,profileId:null,label:p.name,payload:p})),
    ...state.rooms.filter(p=>p.status==='ended').map(p=>({entityType:'room' as const,entityId:p.id,profileId:p.context.profileId||null,label:`${p.context.communicatorName} · ${p.turns.length} 条表达`,payload:p})),
    ...(state.reports||[]).map(p=>({entityType:'report' as const,entityId:p.id,profileId:p.filters.profileId&&!['all','trial'].includes(p.filters.profileId)?p.filters.profileId:null,label:`报告 · ${p.createdAt.slice(0,10)}`,payload:p}))
  ]
}
/** Removing images also removes references. Observation consent never implies photo consent. */
function legacySnapshot(card:any):any { if(!card||typeof card!=='object'||Array.isArray(card))return card;const {sceneIds,archived,updatedAt,...snapshot}=card;return snapshot }
export function stripSyncPrivate(value: any, consent: SyncConsent): any {
  if(Array.isArray(value))return value.map(v=>stripSyncPrivate(v,consent))
  if(!value||typeof value!=='object')return value
  const result:Record<string,any>={}
  for(const [key,item] of Object.entries(value)) {
    if(key==='cardSnapshot'&&Array.isArray(item)){result[key]=item.map(card=>stripSyncPrivate(legacySnapshot(card),consent));continue}
    if(key==='draft'&&item&&typeof item==='object'&&!Array.isArray(item)){const draft=item as Record<string,any>;result[key]=stripSyncPrivate({...draft,...(Array.isArray(draft.cards)?{cards:draft.cards.map(legacySnapshot)}:{})},consent);continue}
    if(!consent.photos&&['image','assetRef'].includes(key))continue
    if(!consent.observations&&['observations','observer','observedAt'].includes(key)){result[key]=key==='observedAt'?null:'';continue}
    result[key]=key==='observedAt'&&typeof item==='string'&&Number.isFinite(Date.parse(item))?new Date(item).toISOString():stripSyncPrivate(item,consent)
  }
  return result
}
export function validateCloudEntity(value:any):value is CloudEntity {
  return !!value&&['profile','card','scene','room','report'].includes(value.entityType)&&/^[A-Za-z0-9_-]{1,100}$/.test(value.entityId)&&Number.isSafeInteger(value.revision)&&value.revision>=1&&(value.profileId===null||typeof value.profileId==='string')&&typeof value.deleted==='boolean'&&(value.deleted?value.payload===null:!!value.payload&&typeof value.payload==='object'&&!Array.isArray(value.payload)&&value.payload.id===value.entityId)
}
export function splitSyncOperations(operations:SyncOperation[],consent:SyncConsent):SyncOperation[][] {
  const bytes=(value:unknown)=>new TextEncoder().encode(JSON.stringify(value)).byteLength
  const batches:SyncOperation[][]=[];let batch:SyncOperation[]=[]
  for(const operation of operations){if(bytes(operation.payload)>512*1024)throw new Error('单条资料超过云端 512 KiB 限制，请缩小记录范围。');if(batch.length===50||bytes({consent,operations:[...batch,operation]})>2*1024*1024){batches.push(batch);batch=[]}batch.push(operation)}
  if(batch.length)batches.push(batch);return batches
}
/** Preserve ids and snapshots; importing never changes or ends the active local room. */
export function mergeCloudEntities(state:State,entities:CloudEntity[],decisions:Record<string,'local'|'remote'>={},allowedProfiles?:string[]):State {
  const next=JSON.parse(JSON.stringify(state)) as State
  const ids=new Set<string>()
  for(const entity of entities){
    if(!validateCloudEntity(entity)||entity.deleted)throw new Error('已删除或格式不正确的在线资料不能导入。')
    const derivedScope=entity.entityType==='profile'?entity.entityId:entity.entityType==='room'?entity.payload?.context?.profileId||null:entity.entityType==='report'&&entity.payload?.filters?.profileId&&!['all','trial'].includes(entity.payload.filters.profileId)?entity.payload.filters.profileId:null
    if(derivedScope!==entity.profileId)throw new Error('在线资料的使用人范围不一致，未导入。')
    if(ids.has(syncKey(entity)))throw new Error('在线资料重复，请重新拉取。');ids.add(syncKey(entity))
    if(allowedProfiles&&(!entity.profileId||!allowedProfiles.includes(entity.profileId)||['card','scene'].includes(entity.entityType)))throw new Error('资料不在当前协助者的授权范围内。')
    const payload=JSON.parse(JSON.stringify(entity.payload))
    if(entity.entityType==='room'&&(payload.status!=='ended'||payload.id===state.activeRoomId))throw new Error('进行中的聊天室只能查看，不能导入或替换本机当前沟通。')
    const target:any[]=entity.entityType==='profile'?next.family.profiles:entity.entityType==='card'?next.family.cards:entity.entityType==='scene'?next.family.scenes:entity.entityType==='room'?next.rooms:(next.reports??=[])
    const index=target.findIndex(item=>item.id===entity.entityId)
    if(index>=0){if(stableSyncJSON(target[index])===stableSyncJSON(payload))continue;const decision=decisions[syncKey(entity)];if(!decision)throw new Error('同编号资料存在差异，请逐项选择保留本机或采用在线版本。');if(decision==='local')continue;target[index]=payload}else target.push(payload)
  }
  const sceneIds=new Set([...SCENES.map(s=>s.id),...next.family.scenes.map(s=>s.id)]),cardIds=new Set([...CARDS,...CORE_CARDS,...next.family.cards].map(c=>c.id))
  for(const card of next.family.cards)if(card.sceneIds.some(id=>!sceneIds.has(id)))throw new Error('缺少关联场景，请同时选择对应场景后再导入。')
  for(const scene of next.family.scenes)if(scene.recommendedCardIds.some(id=>!cardIds.has(id)))throw new Error('缺少推荐卡片，请同时选择对应卡片后再导入。')
  // Ended rooms carry immutable profile/scene/card snapshots; shared catalogs need not be disclosed to helpers.
  for(const entity of entities.filter(e=>e.entityType==='report')){const report=next.reports!.find(r=>r.id===entity.entityId)!;if(report.evidenceIds.some(e=>!next.rooms.some(r=>r.id===e.roomId&&r.turns.some(t=>t.id===e.turnId))))throw new Error('报告缺少来源历史，请同时选择对应历史。')}
  const checked=validateState(next);if(!checked.valid)throw new Error('合并后的资料不完整、存在同名冲突或图片不符合本机限制，未更改本机数据。')
  return ensureStateCapacity(checked.state)
}
