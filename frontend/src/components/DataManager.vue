<script setup lang="ts">
// #ifdef APP
import {exportText,cancelNativeDocument} from '../platform/nativeDocument';
// #endif
import { computed, ref, onUnmounted } from 'vue';
import type { State } from '../domain';
import { getActiveRoom } from '../domain';
import { exportSelectedData, deleteLocalProfileData, clearLocalProductData } from '../domain/data';
const props=defineProps<{state:State;persist:(state:State)=>boolean;protectedOriginal:boolean}>();
const profileId=ref('all'),from=ref(''),to=ref(''),photos=ref(false),observations=ref(false),error=ref(''),message=ref('');
const selection=computed(()=>({profileId:profileId.value,from:from.value||undefined,to:to.value||undefined,includePhotos:photos.value,includeObservations:observations.value}));
let disposed=false;onUnmounted(()=>{disposed=true;
// #ifdef APP
cancelNativeDocument();
// #endif
});
const active=computed(()=>getActiveRoom(props.state));
const selectedProfile=computed(()=>props.state.family.profiles.find(p=>p.id===profileId.value));
async function download(){error.value='';message.value='';try{const data=exportSelectedData(props.state,selection.value);
// #ifdef H5
const url=URL.createObjectURL(new Blob([JSON.stringify(data,null,2)],{type:'application/json'}));const link=document.createElement('a');link.href=url;link.download=`cardtalk-selected-${new Date().toISOString().slice(0,10)}.json`;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
// #endif
// #ifdef APP
const result=await exportText(`cardtalk-selected-${new Date().toISOString().slice(0,10)}.json`,JSON.stringify(data,null,2));if(result.status!=='saved'||disposed)return;
// #endif
message.value=`已导出 ${data.profiles.length} 位使用人资料、${data.rooms.reduce((n,r)=>n+r.turns.length,0)} 条表达和 ${data.reports.length} 份报告。`;}catch(e){error.value=e instanceof Error?e.message:'导出失败';}}
async function removeProfile(){const target=selectedProfile.value;if(!target || props.protectedOriginal)return;error.value='';try{const next=deleteLocalProfileData(props.state,target.id);const result=await uni.showModal({title:`永久删除 ${target.name} 的本机资料？`,content:`将删除此档案、${props.state.rooms.length-next.rooms.length} 次归属沟通及相关常用表达、报告。同步待处理队列将清除。在线资料不会自动删除，请先保存完整备份。`,confirmText:'永久删除'});if(result.confirm&&!disposed&&!props.protectedOriginal&&props.persist(deleteLocalProfileData(props.state,target.id))){profileId.value='all';message.value='归属的本机资料已删除。';}}catch(e){error.value=e instanceof Error?e.message:'资料未删除';}}
async function clearAll(){if(active.value || props.protectedOriginal)return;const result=await uni.showModal({title:'清空全部本机产品数据？',content:'家庭档案、卡片、场景、沟通、常用表达、报告与设置将清空，同步待处理队列也将清除。在线资料、账户和体验额度不随此操作删除。请先导出完整备份。',confirmText:'清空本机数据'});if(result.confirm&&!disposed&&!getActiveRoom(props.state)&&!props.protectedOriginal){if(props.persist(clearLocalProductData())){profileId.value='all';message.value='本机产品数据已清空。';}else error.value='本机资料未清空，请检查存储空间后重试。';}}
</script>
<template>
  <view class="local-data-manager"><text class="section-title">按范围导出与删除</text><text class="modal-copy">范围导出是可阅读的资料文件，不包含草稿；恢复整台设备请使用上方完整备份。</text><view class="form-field"><text>使用人</text><picker :range="['全部使用人','未关联档案',...state.family.profiles.map(p=>p.name)]" @change="profileId=Number($event.detail.value)===0?'all':Number($event.detail.value)===1?'trial':state.family.profiles[Number($event.detail.value)-2]?.id || 'all'"><view class="filter-picker">{{profileId==='all'?'全部使用人':profileId==='trial'?'未关联档案':selectedProfile?.name || '请选择'}} ▾</view></picker></view><view class="form-field"><text>开始日期</text><input v-model="from" aria-label="数据导出开始日期" placeholder="YYYY-MM-DD，可留空"/></view><view class="form-field"><text>结束日期</text><input v-model="to" aria-label="数据导出结束日期" placeholder="YYYY-MM-DD，可留空"/></view><view class="scene-options"><button role="button" tabindex="0" :aria-pressed="photos" :class="{active:photos}" @click="photos=!photos">{{photos?'包含照片':'不含照片'}}</button><button role="button" tabindex="0" :aria-pressed="observations" :class="{active:observations}" @click="observations=!observations">{{observations?'包含家人观察':'不含家人观察'}}</button></view><button role="button" tabindex="0" class="outline-button" @click="download">导出所选范围资料</button><text v-if="protectedOriginal" class="modal-copy">原始记录受保护，请先导出原始备份并处理损坏记录，再执行删除。</text><button role="button" tabindex="0" class="delete-button" :disabled="!selectedProfile || protectedOriginal" @click="removeProfile">永久删除所选使用人的本机资料</button><button role="button" tabindex="0" class="delete-button" :disabled="!!active || protectedOriginal" @click="clearAll">清空全部本机产品数据</button><text v-if="active" class="modal-copy">请先结束当前沟通，再清空全部本机数据。</text><text v-if="error" role="alert" class="error-copy">{{error}}</text><text v-if="message" role="status" class="modal-copy">{{message}}</text></view>
</template>
<style scoped>.local-data-manager{display:flex;flex-direction:column;gap:16px;padding-top:24px;border-top:1px solid #e3e8f0;margin-top:24px;min-width:0}.local-data-manager .scene-options{flex-wrap:wrap}</style>
