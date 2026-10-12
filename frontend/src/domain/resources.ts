import type { State } from './types'
import { CARDS, SCENES, CATEGORIES } from './content'
import { validateFamily, normalizedName } from './family-validation'
import { ensureStateCapacity } from './capacity'
export interface PublicCard { id:string;label:string;category:string;icon:string;kind:'word'|'phrase';description:string;sceneIds:string[] }
export interface PublicScene { id:string;name:string;description:string;recommendedCardIds:string[] }
export interface PublicPackage { id:string;title:string;source:string;license:string;cards:PublicCard[];scenes:PublicScene[] }
const icons=new Set(CARDS.map(c=>c.icon))
const object=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v)
const exact=(v:Record<string,unknown>,keys:string[])=>Object.keys(v).every(k=>keys.includes(k))
const text=(v:unknown,max:number):v is string=>typeof v==='string'&&!!v.trim()&&v.length<=max
const id=(v:unknown):v is string=>typeof v==='string'&&/^[a-z0-9][a-z0-9-]{0,39}$/.test(v)
const refs=(v:unknown,ids:string[],max:number)=>Array.isArray(v)&&v.length<=max&&v.every(i=>typeof i==='string'&&ids.includes(i))&&new Set(v).size===v.length
export function validatePublicPackage(v:unknown):v is PublicPackage {
  if(!object(v)||!exact(v,['id','title','source','license','cards','scenes'])||!id(v.id)||!text(v.title,100)||!text(v.source,500)||!text(v.license,500)||!Array.isArray(v.cards)||v.cards.length>70||!Array.isArray(v.scenes)||v.scenes.length>50)return false
  const cards=v.cards,scenes=v.scenes
  if(!cards.every(c=>object(c)&&exact(c,['id','label','category','icon','kind','description','sceneIds'])&&id(c.id)&&text(c.label,50)&&CATEGORIES.some(x=>x.id===c.category)&&typeof c.icon==='string'&&icons.has(c.icon)&&['word','phrase'].includes(String(c.kind))&&text(c.description,500))||!scenes.every(s=>object(s)&&exact(s,['id','name','description','recommendedCardIds'])&&id(s.id)&&text(s.name,100)&&text(s.description,1000)))return false
  return new Set(cards.map(c=>c.id)).size===cards.length&&new Set(scenes.map(s=>s.id)).size===scenes.length&&new Set(cards.map(c=>normalizedName(c.label))).size===cards.length&&new Set(scenes.map(s=>normalizedName(s.name))).size===scenes.length&&cards.every(c=>refs(c.sceneIds,scenes.map(s=>s.id),50))&&scenes.every(s=>refs(s.recommendedCardIds,cards.map(c=>c.id),70))
}
export const publicCardId=(id:string)=>`custom-card-public-${id}`
export const publicSceneId=(id:string)=>`custom-scene-public-${id}`
export interface ResourceSelection { cards:string[];scenes:string[] }
/** Explicit additions only: existing entries (including edited public resources) can never be overwritten. */
export function installPublicResources(state:State,p:PublicPackage,selection:ResourceSelection,revision?:number):State {
  if(revision!==undefined&&(!Number.isSafeInteger(revision)||revision<0))throw new Error('发布修订号不正确')
  if(!validatePublicPackage(p)||!refs(selection.cards,p.cards.map(c=>c.id),70)||!refs(selection.scenes,p.scenes.map(s=>s.id),50))throw new Error('公共资源格式或选择不正确')
  if(!selection.cards.length&&!selection.scenes.length)throw new Error('请逐项选择需要安装的资源')
  for(const c of p.cards.filter(c=>selection.cards.includes(c.id)))if(c.sceneIds.some(id=>!selection.scenes.includes(id)))throw new Error(`卡片“${c.label}”需要同时选择关联场景`)
  for(const scene of p.scenes.filter(s=>selection.scenes.includes(s.id)))if(scene.recommendedCardIds.some(id=>!selection.cards.includes(id)))throw new Error(`场景“${scene.name}”需要同时选择关联卡片`)
  const next:State=JSON.parse(JSON.stringify(state)),now=new Date().toISOString()
  const provenance={packageId:p.id,title:p.title,source:p.source,license:p.license,...(revision===undefined?{}:{revision})}
  const cardIds=new Set(selection.cards.map(publicCardId)),sceneIds=new Set(selection.scenes.map(publicSceneId))
  for(const c of p.cards.filter(c=>selection.cards.includes(c.id))){if(next.family.cards.some(x=>x.id===publicCardId(c.id)||!x.archived&&normalizedName(x.label)===normalizedName(c.label)))throw new Error(`卡片“${c.label}”已存在，请取消该项后安装其余资源`);next.family.cards.push({...c,provenance:{...provenance},id:publicCardId(c.id),sceneIds:c.sceneIds.map(publicSceneId).filter(id=>sceneIds.has(id)),archived:false,updatedAt:now})}
  for(const s of p.scenes.filter(s=>selection.scenes.includes(s.id))){if([...SCENES,...next.family.scenes].some(x=>x.id===publicSceneId(s.id)||normalizedName(x.name)===normalizedName(s.name)))throw new Error(`场景“${s.name}”已存在，请取消该项后安装其余资源`);next.family.scenes.push({...s,provenance:{...provenance},id:publicSceneId(s.id),recommendedCardIds:s.recommendedCardIds.map(publicCardId).filter(id=>cardIds.has(id)),activities:'',objects:'',archived:false,updatedAt:now})}
  if(!validateFamily(next.family))throw new Error('安装超过本机容量或存在名称冲突，请减少选择')
  return ensureStateCapacity(next)
}
