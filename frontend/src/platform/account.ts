import {familyHeaders,setNativeFamilyToken,clearNativeFamilyToken,beginNativeFamilyAuthentication,invalidateNativeFamilyAuthentication} from './familyTransport';
export interface Account { id: string; username: string }
export interface FamilyWorkspace { id: string; name: string; role: 'owner'|'admin'|'member'|'helper'; profileAccess?: string[] }
export interface FamilyMember { accountId: string; username: string; role: FamilyWorkspace['role']; profileAccess: string[] }
export interface AccountSession { account: Account; expiresAt?: string }
export class AccountApiError extends Error { constructor(public status:number,message:string){super(message);} }
const BASE = import.meta.env.VITE_API_BASE_URL?.replace(/\/$/,'') || '';
// Family sessions are separate from anonymous trial tokens. Browsers use HttpOnly cookies.
async function call<T>(path:string, method:'GET'|'POST'|'PUT'|'DELETE'='GET', data?:unknown,headers?:Record<string,string>):Promise<T> {
  return new Promise((resolve,reject)=>{
    uni.request({url:`${BASE}/api/v1${path}`,method,data:data as UniApp.RequestOptions['data'],timeout:15000,header:{...(headers||familyHeaders()),'Content-Type':'application/json'},withCredentials:true,
      success(response){ const value=response.data as {message?:string};if(response.statusCode>=200&&response.statusCode<300)resolve(response.data as T);else reject(new AccountApiError(response.statusCode,value?.message || '家庭服务暂时不可用')); },
      fail(){reject(new Error('无法连接家庭服务，本机沟通仍可使用。'));},
    });
  });
}
async function signIn(username:string,password:string,register:boolean){
  // #ifdef APP
  const epoch=beginNativeFamilyAuthentication();
  const result=await call<AccountSession&{token:string}>(`/accounts/native/${register?'register':'login'}`,'POST',{username,password});if(!setNativeFamilyToken(result.token,epoch))throw new Error('登录操作已取消，请使用当前账户');return result;
  // #endif
  // #ifndef APP
  return call<AccountSession>(`/accounts/${register?'register':'login'}`,'POST',{username,password});
  // #endif
}
export const accountApi = {
  me:()=>call<AccountSession>('/accounts/me'),
  login:(username:string,password:string)=>signIn(username,password,false),
  register:(username:string,password:string)=>signIn(username,password,true),
  cancelAuthentication:()=>{
 // #ifdef APP
 invalidateNativeFamilyAuthentication();
 // #endif
 },
 logout:async()=>{const headers=familyHeaders();clearNativeFamilyToken();return call('/accounts/logout','POST',{},headers);},
  families:()=>call<FamilyWorkspace[]>('/families'),
  createFamily:(name:string)=>call<FamilyWorkspace>('/families','POST',{name}),
  members:(familyId:string)=>call<FamilyMember[]>(`/families/${encodeURIComponent(familyId)}/members`),
  invite:(familyId:string,role:Exclude<FamilyWorkspace['role'],'owner'>,profileAccess:string[])=>call<{code:string;expiresAt:number}>(`/families/${encodeURIComponent(familyId)}/invitations`,'POST',{role,profileAccess}),
  accept:(code:string)=>call<FamilyWorkspace>('/families/invitations/accept','POST',{code}),
  remove:(familyId:string,accountId:string)=>call(`/families/${encodeURIComponent(familyId)}/members/${encodeURIComponent(accountId)}`,'DELETE'),
  update:(familyId:string,accountId:string,role:Exclude<FamilyWorkspace['role'],'owner'>,profileAccess:string[])=>call(`/families/${encodeURIComponent(familyId)}/members/${encodeURIComponent(accountId)}`,'PUT',{role,profileAccess}),
};
