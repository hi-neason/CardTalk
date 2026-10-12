import { createState, getActiveRoom } from './state'
import { validateState } from './validation'
import type { State } from './types'
export interface DataSelection { profileId:string; from?:string; to?:string; includePhotos:boolean; includeObservations:boolean }
const copy=<T>(value:T):T=>JSON.parse(JSON.stringify(value))
function day(value:string){const date=new Date(value);return `${date.getFullYear()}-${String(date.getMonth()+1).padStart(2,'0')}-${String(date.getDate()).padStart(2,'0')}`}
function validDate(value?:string){if(!value)return true;const d=new Date(`${value}T12:00:00`);return /^\d{4}-\d{2}-\d{2}$/.test(value)&&Number.isFinite(d.getTime())&&day(d.toISOString())===value}
function redact(value:unknown,selection:DataSelection):unknown{
  if(Array.isArray(value))return value.map(item=>redact(item,selection))
  if(value&&typeof value==='object')return Object.fromEntries(Object.entries(value).filter(([key])=>(selection.includePhotos||!['image','assetRef'].includes(key))&&(selection.includeObservations||!['observations','observer','observedAt'].includes(key))).map(([key,item])=>[key,redact(item,selection)]))
  return value
}
/** Selected data export is a readable archive, not a complete restorable backup. */
export function exportSelectedData(state:State,selection:DataSelection){
  if(!validDate(selection.from)||!validDate(selection.to)||(selection.from&&selection.to&&selection.from>selection.to))throw new Error('请选择有效且顺序正确的日期范围')
  const matches=(profileId?:string)=>selection.profileId==='all'||(profileId??'trial')===selection.profileId
  const rooms=state.rooms.flatMap(room=>{
    const turns=room.turns.filter(turn=>matches(turn.contextSnapshot.profileId)&&(!selection.from||day(turn.createdAt)>=selection.from)&&(!selection.to||day(turn.createdAt)<=selection.to))
    return turns.length?[{id:room.id,context:room.context,startedAt:room.startedAt,endedAt:room.endedAt,status:room.status,turns}]:[]
  })
  const evidence=new Set(rooms.flatMap(room=>room.turns.map(turn=>`${room.id}:${turn.id}`)))
  const reports=(state.reports??[]).filter(report=>report.evidenceIds.every(item=>evidence.has(`${item.roomId}:${item.turnId}`))&&matches(report.filters.profileId==='all'?undefined:report.filters.profileId))
  return redact({version:1,type:'cardtalk-selected-data',generatedAt:new Date().toISOString(),scope:selection,profiles:state.family.profiles.filter(profile=>matches(profile.id)),rooms,reports},selection) as {version:number;type:string;generatedAt:string;scope:DataSelection;profiles:unknown[];rooms:{turns:unknown[]}[];reports:unknown[]}
}
export function deleteLocalProfileData(state:State,profileId:string):State{
  if(!state.family.profiles.some(profile=>profile.id===profileId))throw new Error('使用人档案不存在')
  const active=getActiveRoom(state)
  if(active&&(active.context.profileId===profileId||active.turns.some(turn=>turn.contextSnapshot.profileId===profileId)))throw new Error('请先结束涉及这个使用人的当前沟通，再删除资料')
  const next=copy(state),removedRooms=new Set<string>(),removedEvidence=new Set<string>()
  next.rooms=next.rooms.filter(room=>{if(room.context.profileId!==profileId)return true;removedRooms.add(room.id);room.turns.forEach(turn=>removedEvidence.add(`${room.id}:${turn.id}`));return false}).map(room=>{
    room.turns=room.turns.filter(turn=>{if(turn.contextSnapshot.profileId!==profileId)return true;removedEvidence.add(`${room.id}:${turn.id}`);return false})
    if(room.draft.confirmedTurnId&&!room.turns.some(turn=>turn.id===room.draft.confirmedTurnId))room.draft={cards:[],version:room.draft.version+1,expansion:null,confirmedText:null,selectedCandidateId:null,confirmedTurnId:null}
    return room
  })
  next.family.profiles=next.family.profiles.filter(profile=>profile.id!==profileId)
  if(next.library)next.library.phrases=next.library.phrases.filter(phrase=>phrase.profileId!==profileId&&!removedRooms.has(phrase.sourceRoomId)&&!removedEvidence.has(`${phrase.sourceRoomId}:${phrase.sourceTurnId}`))
  next.reports=(next.reports??[]).filter(report=>report.filters.profileId!==profileId&&!report.sourceSignature.includes(JSON.stringify(profileId))&&!report.evidenceIds.some(item=>removedRooms.has(item.roomId)||removedEvidence.has(`${item.roomId}:${item.turnId}`)))
  const result=validateState(next);if(!result.valid)throw new Error('删除后的资料关联不一致，原数据未更改')
  return result.state
}
export function clearLocalProductData():State{return createState()}
