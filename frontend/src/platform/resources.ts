import { validatePublicPackage, type PublicPackage } from '../domain/resources'
export interface PublicRelease {revision:number;package:PublicPackage|null}
export function fetchPublicResources():Promise<PublicRelease>{
  const base=import.meta.env.VITE_API_BASE_URL?.replace(/\/$/,'')||''
  return new Promise((resolve,reject)=>uni.request({url:`${base}/api/v1/resources/current`,method:'GET',timeout:15000,success(r){const data=r.data as PublicRelease;if(r.statusCode===200&&Number.isSafeInteger(data?.revision)&&data.revision>=0&&(data.package===null||validatePublicPackage(data.package)))resolve(data);else reject(new Error(r.statusCode===503?'公共资源服务尚未启用，请继续使用内置卡片。':'公共资源无法读取，未修改本机资料。'))},fail(){reject(new Error('无法连接公共资源服务，本机沟通仍可使用。'))}}))
}

import {familyHeaders} from './familyTransport'
export interface ContentVersion {id:string;createdAt:string}
export interface ContentAudit {revision:number;versionId:string;actorId:string;createdAt:string}
export class ContentApiError extends Error {constructor(public status:number,message:string){super(message)}}
async function operation<T>(path:string,method:'GET'|'POST'='GET',data?:unknown):Promise<T>{const base=import.meta.env.VITE_API_BASE_URL?.replace(/\/$/,'')||'';return new Promise((resolve,reject)=>uni.request({url:`${base}/api/v1/operations/content/${path}`,method,data:data as UniApp.RequestOptions['data'],header:{...familyHeaders(),'Content-Type':'application/json'},withCredentials:true,timeout:15000,success(r){if(r.statusCode>=200&&r.statusCode<300)resolve(r.data as T);else reject(new ContentApiError(r.statusCode,(r.data as {message?:string})?.message||'公共内容运营服务不可用'))},fail(){reject(new Error('无法连接公共内容运营服务'))}}))}
export const contentOperations={versions:()=>operation<ContentVersion[]>('versions'),audit:()=>operation<ContentAudit[]>('audit'),publish:(pack:PublicPackage)=>operation<{id:string}>('versions','POST',pack),release:(versionId:string,expectedRevision:number)=>operation<PublicRelease>('release','POST',{versionId,expectedRevision})}
