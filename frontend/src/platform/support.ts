import {trial,ApiError} from './api';
import {familyHeaders} from './familyTransport';
export type SupportScope='trial'|'account';
export interface SupportTicket {id:string;kind:'support'|'privacy_deletion';message:string;contact?:string;familyId?:string;status:string;reply?:string;deletionEvidence?:string;createdAt:string;updatedAt:string;retainUntil?:string}
const BASE=import.meta.env.VITE_API_BASE_URL?.replace(/\/$/,'')||'';
export function createSupportClient(scope:SupportScope){
 let stopped=false;const requests=new Set<UniApp.RequestTask>();
 async function call<T>(path:string,method:'GET'|'POST'='GET',data?:unknown):Promise<T>{
  const session=scope==='trial'?await trial():null;if(stopped)throw new Error('操作已取消');
  return new Promise((resolve,reject)=>{let task:UniApp.RequestTask;task=uni.request({url:`${BASE}/api/v1${path}`,method,data:data as UniApp.RequestOptions['data'],withCredentials:true,timeout:20000,header:{...familyHeaders(),'Content-Type':'application/json','X-CardTalk-Support-Scope':scope,...(session?{'X-CardTalk-Trial-Token':session.token}:{})},success:r=>{if(stopped){reject(new Error('操作已取消'));return;}if(r.statusCode>=200&&r.statusCode<300)resolve(r.data as T);else{const error=r.data as {code?:string;message?:string};reject(new ApiError(error.code||'SUPPORT_FAILED',error.message||'支持服务暂时不可用',r.statusCode));}},fail:()=>reject(new Error('连接未完成，填写内容已保留，可使用相同编号重试。')),complete:()=>requests.delete(task)});requests.add(task);});
 }
 return {status:()=>call<{enabled:boolean;dailyLimit:number;retentionDays:number;actorScope:SupportScope;canOperate:boolean;privacyDeletionAutomatic:false}>('/support/status'),list:(cursor='0')=>call<{tickets:SupportTicket[];nextCursor:string;hasMore:boolean}>(`/support/tickets?cursor=${encodeURIComponent(cursor)}&limit=50`),create:(input:{requestId:string;kind:SupportTicket['kind'];message:string;contact?:string;familyId?:string})=>call<SupportTicket>('/support/tickets','POST',input),withdraw:(id:string)=>call<SupportTicket>(`/support/tickets/${encodeURIComponent(id)}/withdraw`,'POST',{}),operatorList:(cursor='0')=>call<{tickets:SupportTicket[];nextCursor:string;hasMore:boolean}>(`/operations/tickets?limit=50&cursor=${encodeURIComponent(cursor)}`),update:(id:string,input:unknown)=>call<SupportTicket>(`/operations/tickets/${encodeURIComponent(id)}/updates`,'POST',input),audit:(id:string)=>call<unknown>(`/operations/tickets/${encodeURIComponent(id)}/audit`),stop:()=>{stopped=true;for(const request of requests)request.abort();requests.clear();}};
}
