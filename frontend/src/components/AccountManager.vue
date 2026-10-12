<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { accountApi, AccountApiError, type Account, type FamilyWorkspace, type FamilyMember } from '../platform/account';
import CloudManager from './CloudManager.vue';
import BillingManager from './BillingManager.vue';
import {chooseFamilyAccess,clearFamilyAccess,familyAccess} from '../platform/familyAccess';
import type { State } from '../domain';
const props=defineProps<{profileOptions?:{id:string;name:string}[];state?:State;persist?:(next:State)=>boolean}>();
const cloudOpen=ref(false),cloudSessionKey=ref(`session-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`);
const account=ref<Account|null>(null), families=ref<FamilyWorkspace[]>([]), members=ref<FamilyMember[]>([]);
const username=ref(''),password=ref(''),register=ref(false),familyName=ref(''),familyId=ref(''),invitationCode=ref(''),role=ref<'admin'|'member'|'helper'>('member'),profiles=ref('');
const editingMember=ref<FamilyMember|null>(null),editRole=ref<'admin'|'member'|'helper'>('member'),editProfiles=ref('');
const allowedRoles=computed(()=>currentFamily.value?.role==='owner'?['admin','member','helper'] as const:['member','helper'] as const);
const issuedInvite=ref<{code:string;expiresAt:number}|null>(null),error=ref(''),message=ref(''),busy=ref(false);
const currentFamily=computed(()=>families.value.find(f=>f.id===familyId.value));
const canManage=computed(()=>currentFamily.value?.role==='owner'||currentFamily.value?.role==='admin');
const roles={owner:'家庭创建者',admin:'管理者',member:'家人',helper:'协助者'};
let generation=0;
onUnmounted(()=>{generation++;accountApi.cancelAuthentication();password.value='';});
async function run(action:()=>Promise<void>){if(busy.value)return;const token=++generation;busy.value=true;error.value='';message.value='';try{await action();}catch(e){if(token===generation)error.value=e instanceof Error?e.message:'操作失败';}finally{if(token===generation)busy.value=false;}}
async function refreshFamilies(){families.value=await accountApi.families();if(!families.value.some(f=>f.id===familyId.value))familyId.value=families.value[0]?.id||'';await loadMembers();}
async function loadMembers(){members.value=[];issuedInvite.value=null;if(familyId.value&&canManage.value)members.value=await accountApi.members(familyId.value);}
onMounted(()=>{void run(async()=>{try{const result=await accountApi.me();account.value=result.account;await refreshFamilies();}catch(e){account.value=null;if(!(e instanceof AccountApiError) || e.status!==401)throw e;}});});
function signIn(){clearFamilyAccess();void run(async()=>{const result=await(register.value?accountApi.register:accountApi.login)(username.value.trim(),password.value);password.value='';cloudSessionKey.value=`session-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;account.value=result.account;await refreshFamilies();message.value=register.value?'账户已建立。本机资料没有上传。':'已登录。本机资料没有上传。';});}
function createFamily(){void run(async()=>{await accountApi.createFamily(familyName.value.trim());familyName.value='';await refreshFamilies();message.value='家庭已建立。本机资料仍保存在本机。';});}
function selectFamily(id:string){cloudOpen.value=false;familyId.value=id;void run(loadMembers);}
function invite(){void run(async()=>{issuedInvite.value=await accountApi.invite(familyId.value,role.value,role.value==='helper'?profiles.value.split(/[,，\s]+/).map(p=>p.trim()).filter(Boolean):[]);});}
function accept(){void run(async()=>{await accountApi.accept(invitationCode.value.trim());invitationCode.value='';await refreshFamilies();message.value='已加入家庭。';});}
async function remove(member:FamilyMember){const choice=await uni.showModal({title:`移除 ${member.username}？`,content:'移除后该成员不能继续访问这个家庭的在线资源。本机设备上的数据需单独管理。',confirmText:'移除'});if(choice.confirm)void run(async()=>{await accountApi.remove(familyId.value,member.accountId);await refreshFamilies();message.value='成员已移除。';});}
function editMember(member:FamilyMember){if(member.role==='owner')return;editingMember.value=member;editRole.value=member.role;editProfiles.value=member.profileAccess.join(',');}
function saveMember(){if(!editingMember.value)return;const member=editingMember.value;void run(async()=>{await accountApi.update(familyId.value,member.accountId,editRole.value,editRole.value==='helper'?editProfiles.value.split(/[,，\s]+/).filter(Boolean):[]);editingMember.value=null;await refreshFamilies();message.value='成员权限已更新。';});}
function useFamily(){const family=currentFamily.value;if(!family||!account.value)return;void run(async()=>{await chooseFamilyAccess(account.value!.id,family);message.value=`已选择 ${family.name} 的功能权限。本机资料未上传。`;});}
function useTrial(){clearFamilyAccess();message.value='已回到本机体验，资料未上传。';}
function signOut(){clearFamilyAccess();cloudOpen.value=false;cloudSessionKey.value=`session-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;void run(async()=>{await accountApi.logout();account.value=null;families.value=[];members.value=[];issuedInvite.value=null;editingMember.value=null;password.value='';message.value='已退出账户。本机记录仍保留。';});}
function copyInvite(){if(issuedInvite.value)uni.setClipboardData({data:issuedInvite.value.code,success:()=>message.value='邀请码已复制，请只交给需要加入的家人。'});}
</script>
<template>
  <view class="account-manager"><CloudManager v-if="cloudOpen && account && currentFamily && state && persist" :key="`${account.id}:${currentFamily.id}:${cloudSessionKey}`" :state="state" :persist="persist" :account-id="account.id" :family="currentFamily" :session-key="cloudSessionKey" @close="cloudOpen=false"/><template v-else>
    <text class="modal-copy">账户与家庭成员用于在线协作。登录或加入家庭不会自动上传本机资料、照片和沟通记录。</text>
    <text v-if="error" role="alert" class="error-copy">{{error}}</text><text v-if="message" role="status" class="modal-copy">{{message}}</text>
    <template v-if="!account">
      <view class="scene-options"><button role="button" tabindex="0" :disabled="busy" :class="{active:!register}" @click="register=false">登录账户</button><button role="button" tabindex="0" :disabled="busy" :class="{active:register}" @click="register=true">建立账户</button></view>
      <view class="form-field"><text>用户名</text><input v-model="username" maxlength="40" autocomplete="username" aria-label="账户用户名" placeholder="3–40位字母、数字或下划线"/></view>
      <view class="form-field"><text>密码</text><input v-model="password" type="text" password maxlength="128" :autocomplete="register?'new-password':'current-password'" aria-label="账户密码" placeholder="至少12个字符"/></view>
      <button role="button" tabindex="0" class="small-primary" :disabled="busy || !username.trim() || !password" @click="signIn">{{busy?'正在处理…':register?'确认建立账户':'确认登录'}}</button>
    </template>
    <template v-else>
      <view v-if="currentFamily" class="scene-options"><button role="button" tabindex="0" class="outline-button" :disabled="busy" @click="useFamily">使用这个家庭的功能</button><button v-if="familyAccess.selected.value" role="button" tabindex="0" class="text-button" @click="useTrial">返回本机体验</button></view><BillingManager v-if="currentFamily" :key="`${account.id}:${currentFamily.id}:${cloudSessionKey}`" :account-id="account.id" :family="currentFamily"/><view class="account-heading"><text>已登录 · {{account.username}}</text><button role="button" tabindex="0" :disabled="busy" class="text-button" @click="signOut">退出账户</button></view>
      <view class="scene-options"><button v-for="family in families" :key="family.id" role="button" tabindex="0" :disabled="busy" :class="{active:family.id===familyId}" @click="selectFamily(family.id)">{{family.name}} · {{roles[family.role]}}</button></view>
      <button v-if="currentFamily && state && persist" role="button" tabindex="0" class="outline-button" :disabled="busy" @click="cloudOpen=true">同步与在线资料</button><view class="form-field"><text>建立一个家庭</text><input v-model="familyName" aria-label="新家庭名称" maxlength="80" placeholder="家庭名称"/><button role="button" tabindex="0" :disabled="busy || !familyName.trim()" class="quiet-button" @click="createFamily">建立家庭</button></view>
      <view class="form-field"><text>使用邀请码加入</text><input v-model="invitationCode" aria-label="加入家庭邀请码" maxlength="200" placeholder="由家庭管理者提供"/><button role="button" tabindex="0" :disabled="busy || !invitationCode.trim()" class="quiet-button" @click="accept">加入家庭</button></view>
      <template v-if="canManage">
        <text class="section-title">{{currentFamily?.name}} · 家庭成员</text>
        <view v-for="member in members" :key="member.accountId" class="account-member"><view><text>{{member.username}} · {{roles[member.role]}}</text><text v-if="member.role==='helper'" class="modal-copy">可查看的使用人：{{member.profileAccess.map(id=>profileOptions?.find(p=>p.id===id)?.name || '其他使用人').join('、') || '尚未分配'}}</text></view><button v-if="member.role!=='owner' && (currentFamily?.role==='owner' || member.role!=='admin' || member.accountId===account.id)" role="button" tabindex="0" class="text-button" :disabled="busy" :aria-label="`编辑成员权限 ${member.username}`" @click="editMember(member)">编辑权限</button><button v-if="member.role!=='owner'" role="button" tabindex="0" class="text-button" :disabled="busy" :aria-label="`移除成员 ${member.username}`" @click="remove(member)">移除</button></view>
        <view v-if="editingMember" class="account-invitation"><text>编辑 {{editingMember.username}} 的权限</text><view class="scene-options"><button v-for="value in allowedRoles" :key="value" role="button" tabindex="0" :class="{active:editRole===value}" @click="editRole=value">{{roles[value]}}</button></view><view v-if="editRole==='helper'" class="scene-options"><button v-for="p in profileOptions || []" :key="p.id" role="button" tabindex="0" :class="{active:editProfiles.split(',').includes(p.id)}" @click="editProfiles=editProfiles.split(',').includes(p.id)?editProfiles.split(',').filter(id=>id!==p.id).join(','):[...editProfiles.split(',').filter(Boolean),p.id].join(',')">{{p.name}}</button></view><button role="button" tabindex="0" class="quiet-button" :disabled="busy" @click="saveMember">保存成员权限</button><button role="button" tabindex="0" class="text-button" @click="editingMember=null">取消编辑权限</button></view>
        <text class="section-title">邀请家人</text><view class="scene-options"><button v-for="value in allowedRoles" :key="value" role="button" tabindex="0" :disabled="busy" :class="{active:role===value}" @click="role=value">{{roles[value]}}</button></view>
        <view v-if="role==='helper'" class="form-field"><text>允许查看的使用人</text><view class="scene-options"><button v-for="p in profileOptions || []" :key="p.id" role="button" tabindex="0" :class="{active:profiles.split(',').includes(p.id)}" @click="profiles=profiles.split(',').includes(p.id)?profiles.split(',').filter(id=>id!==p.id).join(','):[...profiles.split(',').filter(Boolean),p.id].join(',')">{{p.name}}</button></view><input v-model="profiles" aria-label="协助者使用人范围" placeholder="多个编号以逗号分隔"/><text class="modal-copy">协助者只能查看被指定的在线使用人资料，不具备编辑权限。</text></view>
        <button role="button" tabindex="0" :disabled="busy" class="outline-button" @click="invite">生成一次性邀请码</button>
        <view v-if="issuedInvite" class="account-invitation"><text>{{issuedInvite.code}}</text><text class="modal-copy">有效期至 {{new Date(issuedInvite.expiresAt*1000).toLocaleString('zh-CN')}}；使用一次后失效。</text><button role="button" tabindex="0" class="quiet-button" @click="copyInvite">复制邀请码</button></view>
      </template>
      <text v-else-if="currentFamily" class="modal-copy">当前角色可参与家庭，成员管理由家庭创建者或管理者操作。</text>
    </template>
  </template></view>
</template>
<style scoped>
.account-manager{display:flex;flex-direction:column;gap:20px;padding:0 24px 24px;min-width:0}.account-heading,.account-member{display:flex;justify-content:space-between;align-items:center;gap:12px}.account-member{border-bottom:1px solid #e3e8f0;padding:14px 0}.account-member>view{display:flex;flex-direction:column;gap:6px;min-width:0;overflow-wrap:anywhere}.account-invitation{padding:16px;background:#edf3f9;border-radius:10px;display:flex;flex-direction:column;gap:12px;overflow-wrap:anywhere}.account-manager .scene-options{flex-wrap:wrap}
</style>
