import { computed, ref } from 'vue';
import { accountApi, type FamilyWorkspace } from './account';
import { createBillingClient, type Entitlement } from './billing';

/** Explicit online workspace selection; never persisted as proof of purchased access. */
const selected = ref<{accountId:string;family:FamilyWorkspace}|null>(null);
const rights = ref<Entitlement[]>([]), ready=ref(false), clock=ref(Date.now());
let generation=0;
export function familyAccessGeneration(){return generation;}
export function clearFamilyAccess(){generation++;selected.value=null;rights.value=[];ready.value=false;}
export function suspendFamilyAccess(accountId?:string,familyId?:string){if(accountId&&selected.value?.accountId!==accountId||familyId&&selected.value?.family.id!==familyId)return;generation++;rights.value=[];ready.value=false;}
export function updateFamilyAccessClock(){clock.value=Date.now();}
export async function chooseFamilyAccess(accountId:string,family:FamilyWorkspace){
  const token=++generation;selected.value={accountId,family};rights.value=[];ready.value=false;
  try{const session=await accountApi.me();if(token!==generation)return;if(session.account.id!==accountId)throw new Error('账户已变化，请重新选择家庭');
    const result=await createBillingClient(family.id).entitlements();if(token!==generation)return;selected.value={accountId,family:{...family,role:validRole(result.role)}};rights.value=result.features;ready.value=true;clock.value=Date.now();
  }catch(e){if(token===generation){rights.value=[];ready.value=false;}throw e;}
}
function validRole(role?:string):FamilyWorkspace['role']{return ['owner','admin','member','helper'].includes(role||'')?role as FamilyWorkspace['role']:'helper';}
export function acceptFamilyEntitlements(accountId:string,familyId:string,features:Entitlement[],token:number,role?:string){if(token!==generation)return;if(selected.value?.accountId!==accountId||selected.value.family.id!==familyId)return;selected.value={...selected.value!,family:{...selected.value!.family,role:validRole(role)}};rights.value=features;ready.value=true;clock.value=Date.now();}
const allowed=(feature:string)=>ready.value&&selected.value?.family.role!=='helper'&&rights.value.some(r=>r.feature===feature&&r.active&&r.actions.writeAdvanced&&(!r.expiresAt||Date.parse(r.expiresAt)>clock.value));
export const familyAccess={selected:computed(()=>selected.value),ready:computed(()=>ready.value),capabilities:computed(()=>({profileManagement:allowed('profiles'),scenarioManagement:allowed('scenarios'),analytics:allowed('analytics'),customCards:ready.value&&selected.value?.family.role!=='helper'}))};
