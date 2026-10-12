import {familyHeaders} from './familyTransport';
import { type CloudEntity, type SyncConsent, type SyncOperation, validateCloudEntity } from '../domain/sync'
export interface CloudStatus { configured:boolean;enabled:boolean;workspace:'cloud';familyId:string;assetReferencesSupported:boolean }
export interface SyncMetadata { accountId:string;familyId:string;cursor:string;revisions:Record<string,{revision:number;deleted:boolean}>;pending?:{sessionKey:string;consent:SyncConsent;operations:SyncOperation[]} }
export class CloudApiError extends Error { constructor(public status:number,public details:any){super(details?.message||`云端请求失败（${status}）`)} }
const BASE=import.meta.env.VITE_API_BASE_URL?.replace(/\/$/,'')||''
export function createCloudClient(familyId:string) {
  const requests=new Set<UniApp.RequestTask>();let stopped=false
  function call<T>(suffix:string,method:'GET'|'POST'='GET',data?:any,mediaType='application/json',binary=false):Promise<T>{
    if(stopped)return Promise.reject(new Error('在线会话已停止，请重新进入。'))
    return new Promise((resolve,reject)=>{let task:UniApp.RequestTask;task=uni.request({url:`${BASE}/api/v1/families/${encodeURIComponent(familyId)}${suffix}`,method,data,withCredentials:true,timeout:40000,responseType:binary?'arraybuffer':'text',header:{...familyHeaders(),'Content-Type':mediaType},success:r=>{if(stopped){reject(new Error('在线会话已停止'));return}if(r.statusCode<200||r.statusCode>=300){reject(new CloudApiError(r.statusCode,r.data));return}resolve((binary?{bytes:r.data,mediaType:Object.entries(r.header||{}).find(([key])=>key.toLowerCase()==='content-type')?.[1]}:r.data) as T)},fail:()=>reject(new CloudApiError(0,{message:'网络请求未完成，待重试操作和本机资料仍保留。'})),complete:()=>requests.delete(task)});requests.add(task)})
  }
  return {
    stop(){stopped=true;for(const task of requests)task.abort();requests.clear()},
    status:()=>call<CloudStatus>('/sync/status'),
    async pull(cursor='0'){const result=await call<{entities:CloudEntity[];cursor:string;hasMore:boolean}>(`/sync/pull?cursor=${encodeURIComponent(cursor)}&limit=50`);if(!Array.isArray(result.entities)||!result.entities.every(validateCloudEntity)||!/^\d+$/.test(result.cursor)||typeof result.hasMore!=='boolean')throw new Error('在线资料返回格式不正确，游标未推进。');return result},
    push:(operations:SyncOperation[],consent:SyncConsent)=>call<{results:{operationId:string;entityType:string;entityId:string;revision:number}[];cursor:string}>('/sync/push','POST',{consent,operations}),
    upload:(profileId:string,bytes:ArrayBuffer,mediaType:string)=>call<{id:string}>(`/assets?profileId=${encodeURIComponent(profileId)}`,'POST',bytes,mediaType),
    download:(assetId:string)=>call<{bytes:ArrayBuffer;mediaType:string}>(`/assets/${encodeURIComponent(assetId)}`,'GET',undefined,'application/json',true)
  }
}
const key=(accountId:string,familyId:string)=>`cardtalk.cloud-sync.v1.${encodeURIComponent(accountId)}.${encodeURIComponent(familyId)}`
export function loadSyncMetadata(accountId:string,familyId:string):SyncMetadata {
  const raw=uni.getStorageSync(key(accountId,familyId));if(!raw)return {accountId,familyId,cursor:'0',revisions:{}}
  try{const parsed=typeof raw==='string'?JSON.parse(raw):raw;if(parsed.accountId!==accountId||parsed.familyId!==familyId||!/^\d+$/.test(parsed.cursor)||!parsed.revisions||typeof parsed.revisions!=='object'||Array.isArray(parsed.revisions))throw new Error();for(const [name,revision] of Object.entries(parsed.revisions) as [string,any][]){if(!/^(profile|card|scene|room|report):[A-Za-z0-9_-]{1,100}$/.test(name)||!Number.isSafeInteger(revision?.revision)||revision.revision<0||typeof revision.deleted!=='boolean')throw new Error()}if(parsed.pending){const p=parsed.pending;if(typeof p.sessionKey!=='string'||!p.consent||typeof p.consent.photos!=='boolean'||typeof p.consent.observations!=='boolean'||!Array.isArray(p.operations)||p.operations.length>10000||p.operations.some((o:any)=>!o||!['profile','card','scene','room','report'].includes(o.entityType)||!/^[-A-Za-z0-9_]{1,100}$/.test(o.entityId)||!/^[-A-Za-z0-9_]{1,100}$/.test(o.operationId)||!Number.isSafeInteger(o.baseRevision)||o.baseRevision<0||typeof o.deleted!=='boolean'||(!o.deleted&&(!o.payload||o.payload.id!==o.entityId)))||new Set(p.operations.map((o:any)=>o.operationId)).size!==p.operations.length)throw new Error()}return parsed}catch{throw new Error('本机同步元数据损坏；原数据保留，请先导出本机备份。')}
}
export function saveSyncMetadata(metadata:SyncMetadata){const text=JSON.stringify(metadata);if(text.length>5*1024*1024)throw new Error('同步待处理队列超过本机容量，未发送。');uni.setStorageSync(key(metadata.accountId,metadata.familyId),text);if(uni.getStorageSync(key(metadata.accountId,metadata.familyId))!==text)throw new Error('同步状态未保存，请检查本机空间后重试。')}
export async function transformSyncImages(value:any,mode:'upload'|'download'|'omit',asset:(input:any)=>Promise<any>):Promise<any>{
  if(Array.isArray(value))return Promise.all(value.map(item=>transformSyncImages(item,mode,asset)))
  if(!value||typeof value!=='object')return value
  const result:Record<string,any>={}
  for(const [key,item] of Object.entries(value)){if(key==='image'){if(mode==='upload'&&item)result.assetRef={id:await asset(item)};else if(mode==='download')result.image=item;continue}if(key==='assetRef'){if(mode==='download')result.image=await asset(item);continue}result[key]=await transformSyncImages(item,mode,asset)}
  return result
}
export function dataImageBytes(image:string){const match=/^data:(image\/(?:png|jpeg|webp));base64,([A-Za-z0-9+/]+={0,2})$/.exec(image);if(!match)throw new Error('本机图片格式不支持上传。');const bytes=Uint8Array.from(atob(match[2]!),c=>c.charCodeAt(0));if(bytes.length>16*1024*1024)throw new Error('图片超过云端16 MiB限制。');return {bytes:bytes.buffer,mediaType:match[1]!}}
export function bytesDataImage(bytes:ArrayBuffer,mediaType:string){const mime=mediaType.split(';')[0]!.trim();if(!['image/png','image/jpeg','image/webp'].includes(mime))throw new Error('在线图片格式不支持，未导入。');let value='';for(const byte of new Uint8Array(bytes))value+=String.fromCharCode(byte);return `data:${mime};base64,${btoa(value)}`}
