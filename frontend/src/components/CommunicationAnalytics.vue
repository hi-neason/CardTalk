<script setup lang="ts">
// #ifdef APP
import {exportText,cancelNativeDocument} from '../platform/nativeDocument';
// #endif
import { computed, ref, watch, onUnmounted } from 'vue';
import { analyzeCommunication, selectAnalyticsEntries, SCENES, CATEGORIES, compareCommunication, saveCommunicationReport, removeCommunicationReport, isCommunicationReportStale, type ReportNarrative, type AnalyticsFilters, type Source, type SavedReport, type State } from '../domain';
import OptionSelect from './OptionSelect.vue';
import {suspendFamilyAccess} from '../platform/familyAccess';
import { narrateReport, ApiError, type ReportFacts } from '../platform/api';
const props = defineProps<{ state: State; enabled: boolean; familyId?:string; persist?: (state:State)=>boolean }>();
const emit = defineEmits<{ inspect: [roomId: string] }>();
const profileId = ref('all'), sceneId = ref('all'), from = ref(''), to = ref(''), message = ref('');
const operationSource = ref<Source|'all'>('all'), category = ref('all'), status = ref<'all'|'active'|'ended'>('all');
const previousFrom=ref(''), previousTo=ref(''), inspectedReportId=ref('');
const inspectedReport=computed(()=>props.state.reports?.find(r=>r.id===inspectedReportId.value));
const validDate = (value:string) => { if (!value) return true; if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false; const date=new Date(`${value}T12:00:00`); return Number.isFinite(date.getTime()) && `${date.getFullYear()}-${String(date.getMonth()+1).padStart(2,'0')}-${String(date.getDate()).padStart(2,'0')}`===value; };
const invalidDate = computed(()=>!validDate(from.value) || !validDate(to.value));
const invalidRange = computed(() => !!from.value && !!to.value && from.value > to.value);
const filters = computed(() => ({ profileId: profileId.value, sceneId: sceneId.value, from: from.value || undefined, to: to.value || undefined, source:operationSource.value, category:category.value, status:status.value }));
const entries = computed(() => props.enabled && !invalidDate.value && !invalidRange.value ? selectAnalyticsEntries(props.state.rooms, filters.value) : []);
const report = computed(() => analyzeCommunication(props.enabled && !invalidDate.value && !invalidRange.value ? props.state.rooms : [], filters.value));
const narrative=ref<ReportNarrative|null>(null), narrativeBusy=ref(false), narrativeError=ref(''), narrativeRemaining=ref<number|null>(null);
let narrativeGeneration=0,cancelNarrative:(()=>void)|null=null;
const facts=computed<ReportFacts>(()=>{const r=report.value;return {confirmedCount:r.confirmedCount,roomCount:r.roomCount,activeDays:r.activeDays,understood:r.feedback.understood,clarify:r.feedback.clarify,notUnderstood:r.feedback.notUnderstood,unrecorded:r.feedback.unrecorded,raw:r.kind.raw,ai:r.kind.ai,edited:r.kind.edited,saved:r.kind.saved,processCollected:r.process.collectedCount,processMissing:r.process.missingCount};});
function stopNarrative(){narrativeGeneration++;cancelNarrative?.();cancelNarrative=null;narrativeBusy.value=false;}
watch(()=>JSON.stringify({filters:filters.value,facts:facts.value,enabled:props.enabled,familyId:props.familyId}),()=>{stopNarrative();narrative.value=null;narrativeError.value='';});
onUnmounted(()=>{stopNarrative();
// #ifdef APP
cancelNativeDocument();
// #endif
});
async function generateNarrative(){if(!props.enabled || narrativeBusy.value || invalidDate.value || invalidRange.value || !report.value.confirmedCount)return;
  const token=++narrativeGeneration, captured=facts.value;
  const choice=await uni.showModal({title:'生成报告 AI 说明？',content:'仅发送表达数、反馈数等匿名统计数字。不发送姓名、选卡文字、照片、观察或表达正文。说明需要结合下方统计核对。',confirmText:'发送统计并生成'});
  if(!choice.confirm || token!==narrativeGeneration || !props.enabled)return;
  narrativeBusy.value=true;narrative.value=null;narrativeError.value='';const request=narrateReport(captured,undefined,props.familyId);cancelNarrative=request.cancel;
  try{const response=await request.promise;if(token!==narrativeGeneration)return;if(!response.text?.trim() || response.text.length>1500 || response.provider!=='deepseek')throw new Error('报告说明格式不正确');narrative.value={text:response.text,createdAt:new Date().toISOString(),provider:response.provider,promptVersion:response.promptVersion};narrativeRemaining.value=response.remaining;}
  catch(e){if(token===narrativeGeneration&&props.familyId&&e instanceof ApiError&&[401,403].includes(e.status))suspendFamilyAccess(undefined,props.familyId);if(token===narrativeGeneration)narrativeError.value=e instanceof Error?e.message:'说明生成失败，统计仍可查看';}
  finally{if(token===narrativeGeneration){narrativeBusy.value=false;cancelNarrative=null;}}
}
function adoptNarrative(){if(!narrative.value || !props.enabled || invalidDate.value || invalidRange.value)return;try{if(props.persist?.(saveCommunicationReport(props.state,filters.value,narrative.value)))message.value='统计与 AI 说明已保存，说明与事实分开保留。';else message.value='报告未保存，请检查本机空间后重试。';}catch(e){message.value=e instanceof Error?e.message:'报告未保存';}}
const comparisonValid=computed(()=>!!from.value && !!to.value && !!previousFrom.value && !!previousTo.value && validDate(previousFrom.value) && validDate(previousTo.value) && previousFrom.value<=previousTo.value);
const comparison=computed(()=>comparisonValid.value ? compareCommunication(props.state.rooms,filters.value,{...filters.value,from:previousFrom.value,to:previousTo.value}):null);
const playbackLabels={completed:'播放完成',failed:'播放失败',interrupted:'已停止',unavailable:'声音不可用',startedOnly:'仅记录开始',neverPlayed:'尚未播放'};
function saveReport() { if(!props.enabled || invalidDate.value || invalidRange.value)return; try{ if(props.persist?.(saveCommunicationReport(props.state,filters.value)))message.value='统计报告已保存，可在下方回顾。';else message.value='报告未保存，请检查本机空间后重试。';}catch(e){message.value=e instanceof Error?e.message:'报告未保存';} }
async function removeReport(id:string) { const choice=await uni.showModal({title:'移除这份报告？',content:'只移除保存的统计报告，沟通历史仍保留。',confirmText:'移除'});if(!choice.confirm)return;try{if(props.persist?.(removeCommunicationReport(props.state,id))){inspectedReportId.value='';message.value='报告已移除。';}}catch(e){message.value=e instanceof Error?e.message:'无法移除报告';} }
function restoreReport(saved:SavedReport) { const f=saved.filters;profileId.value=f.profileId||'all';sceneId.value=f.sceneId||'all';from.value=f.from||'';to.value=f.to||'';operationSource.value=f.source||'all';category.value=f.category||'all';status.value=f.status||'all';message.value='已恢复报告筛选，上方显示最新统计。可重新保存，旧报告仍保留。'; }
function filterSummary(f:AnalyticsFilters) { return [f.profileId && f.profileId!=='all' ? f.profileId==='trial'?'未关联档案':profiles.value.find(p=>p.id===f.profileId)?.name||'原使用人档案':'全部使用人',f.sceneId && f.sceneId!=='all'?f.sceneId==='none'?'不限场景':scenes.value.find(s=>s.id===f.sceneId)?.name||'原场景':'全部场景',`${f.from||'最早记录'} 至 ${f.to||'最新记录'}`,f.source && f.source!=='all'?sources[f.source]:'全部来源',f.category && f.category!=='all'?CATEGORIES.find(c=>c.id===f.category)?.name||'原卡片分类':'全部分类',f.status==='active'?'进行中':f.status==='ended'?'已结束':'全部状态'].join(' · '); }
function exportSaved(saved:SavedReport) {downloadReport({version:1,generatedAt:saved.createdAt,filters:saved.filters,metrics:saved.metrics,...(saved.narrative?{narrative:saved.narrative}:{})}); }
async function downloadReport(data:unknown) {
 // #ifdef H5
 const url=URL.createObjectURL(new Blob([JSON.stringify(data,null,2)],{type:'application/json'})); const link=document.createElement('a');link.href=url;link.download=`cardtalk-report-${new Date().toISOString().slice(0,10)}.json`;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);message.value='报告已导出，仅含筛选条件与统计，不包含照片或表达正文。';
 // #endif
 // #ifdef APP
 try{const result=await exportText(`cardtalk-report-${new Date().toISOString().slice(0,10)}.json`,JSON.stringify(data,null,2));message.value=result.status==='saved'?'报告已导出到所选位置。':'已取消导出。';}catch(e){message.value=e instanceof Error?e.message:'导出失败';}
 // #endif
 // #ifdef MP-WEIXIN
 uni.setClipboardData({data:JSON.stringify(data,null,2),success:()=>{message.value='报告已复制，请保存到自己的安全位置。';}});
 // #endif
 }
const profiles = computed(() => { const values = new Map<string,string>(); props.state.family.profiles.forEach(p => values.set(p.id,p.name + (p.archived ? '（已归档）' : ''))); props.state.rooms.forEach(r => r.turns.forEach(t => { const c=t.contextSnapshot; if(c.profileId && !values.has(c.profileId)) values.set(c.profileId,c.communicatorName); })); return [...values].map(([id,name])=>({id,name})); });
const scenes = computed(() => { const values = new Map<string,string>(); SCENES.filter(s=>s.id!=='none').forEach(s=>values.set(s.id,s.name)); props.state.family.scenes.forEach(s=>values.set(s.id,s.name)); props.state.rooms.forEach(r=>r.turns.forEach(t=>{const c=t.contextSnapshot;if(c.sceneId)values.set(c.sceneId,c.sceneSnapshot?.name || values.get(c.sceneId) || c.sceneId)})); return [...values].map(([id,name])=>({id,name})); });
const percent = (value:number|null) => value === null ? '暂无反馈' : `${Math.round(value*100)}%`;
const sources = { independent:'本人操作', assisted:'家人协助', unknown:'来源未确认' };
const kinds = { raw:'原始表达', ai:'AI候选确认', edited:'候选修改后确认', saved:'常用表达确认' };
const feedbackLabels = { understood:'理解了', clarify:'需要再说明', notUnderstood:'没理解', unrecorded:'未记录反馈' };
const maxDaily = computed(()=>Math.max(1,...report.value.dailyCounts.map(d=>d.count)));
function exportReport() {
  if(!props.enabled || invalidDate.value || invalidRange.value) return;
  const data = { version:1, generatedAt:new Date().toISOString(), filters:filters.value, metrics:report.value, scope:'本机已确认表达；常用选卡不等于本人喜好；理解率只统计有反馈的表达。' };
  downloadReport(data);
}
</script>
<template>
  <view class="analytics-content">
    <view v-if="!enabled" class="empty-state">分析功能未开启。已有沟通历史和基础表达仍可使用。</view>
    <template v-else>
      <text class="modal-copy">从本机已确认表达回顾沟通。常用选卡反映使用频次，不代表本人喜好或能力评估。</text>
      <view class="analytics-filters">
        <view class="family-field"><text>使用人</text><OptionSelect :model-value="profileId" :options="[{id:'all',name:'全部使用人'},{id:'trial',name:'未关联档案'},...profiles]" label="分析使用人" @update:model-value="profileId=$event"/></view>
        <view class="family-field"><text>场景</text><OptionSelect :model-value="sceneId" :options="[{id:'all',name:'全部场景'},{id:'none',name:'不限场景'},...scenes]" label="分析场景" @update:model-value="sceneId=$event"/></view>
        <view class="family-field"><text>开始日期</text><input v-model="from" placeholder="YYYY-MM-DD" aria-label="分析开始日期"/></view>
        <view class="family-field"><text>结束日期</text><input v-model="to" placeholder="YYYY-MM-DD" aria-label="分析结束日期"/></view>
        <view class="family-field"><text>操作来源</text><OptionSelect :model-value="operationSource" :options="[{id:'all',name:'全部来源'},...Object.entries(sources).map(([id,name])=>({id,name}))]" label="分析操作来源" @update:model-value="operationSource=$event as Source|'all'"/></view>
        <view class="family-field"><text>卡片分类</text><OptionSelect :model-value="category" :options="[{id:'all',name:'全部分类'},...CATEGORIES.map(c=>({id:c.id,name:c.name}))]" label="分析卡片分类" @update:model-value="category=$event"/></view>
        <view class="family-field"><text>聊天室状态</text><OptionSelect :model-value="status" :options="[{id:'all',name:'全部状态'},{id:'active',name:'进行中'},{id:'ended',name:'已结束'}]" label="分析聊天室状态" @update:model-value="status=$event as 'all'|'active'|'ended'"/></view>
      </view>
      <text v-if="invalidDate" class="error-copy" role="alert">请输入有效日期，格式为 YYYY-MM-DD。</text>
      <text v-else-if="invalidRange" class="error-copy" role="alert">结束日期不能早于开始日期。</text>
      <template v-else>
        <view class="analytics-summary"><view><text class="analytics-number">{{report.confirmedCount}}</text><text>已确认表达</text></view><view><text class="analytics-number">{{report.roomCount}}</text><text>有表达的聊天室</text></view><view><text class="analytics-number">{{percent(report.feedbackCoverage)}}</text><text>反馈覆盖率</text></view><view><text class="analytics-number">{{percent(report.understoodRate)}}</text><text>有反馈表达的理解率</text></view></view>
        <text class="modal-copy">未记录反馈 {{report.feedback.unrecorded}} 条；播放完成不表示对方理解。草稿不计入分析。</text>
        <text v-if="report.sampleWarning" class="modal-copy">当前样本少于5条，只展示记录事实，不据此评价能力或趋势。</text>
        <view v-if="!report.confirmedCount" class="empty-state">这个范围还没有已确认表达。可以调整筛选，或完成一次沟通后再来看。</view>
        <template v-else>
          <view class="analytics-section"><text class="section-title">表达与理解</text><view class="analytics-breakdowns"><view><text class="analytics-caption">表达方式</text><view v-for="(label,key) in kinds" :key="key" class="analytics-row"><text>{{label}}</text><text>{{report.kind[key]}} 条</text></view></view><view><text class="analytics-caption">操作来源</text><view v-for="(label,key) in sources" :key="key" class="analytics-row"><text>{{label}}</text><text>{{report.source[key]}} 条</text></view></view><view><text class="analytics-caption">理解反馈</text><view v-for="(label,key) in feedbackLabels" :key="key" class="analytics-row"><text>{{label}}</text><text>{{report.feedback[key]}} 条</text></view></view></view></view>
          <view class="analytics-section"><text class="section-title">输入与表达过程</text><view class="analytics-row"><text>活跃天数</text><text>{{report.activeDays}} 天</text></view><view class="analytics-row"><text>每句平均选卡</text><text>{{report.inputCards.average.toFixed(1)}} 张 · 最多 {{report.inputCards.max}} 张</text></view><view class="analytics-row"><text>首卡至确认平均时间</text><text>{{report.process.averageSeconds===null?'未采集':`${report.process.averageSeconds.toFixed(1)} 秒`}}</text></view><text class="modal-copy">过程已采集 {{report.process.collectedCount}} 条，未采集 {{report.process.missingCount}} 条；有效计时 {{report.process.usableTimingCount}} 条。时间包含停顿和等待，不代表能力。</text><view class="analytics-row"><text>扩写成功 / 失败 / 取消</text><text>{{report.process.generationSucceeded}} / {{report.process.generationFailed}} / {{report.process.generationCancelled}}</text></view><view class="analytics-row"><text>候选拒绝 / 开始修改</text><text>{{report.process.candidatesRejected}} / {{report.process.candidateEditStarted}}</text></view><view class="analytics-row"><text>已采集扩写的候选拒绝率</text><text>{{report.process.rejectionRate===null?'无法计算':`${Math.round(report.process.rejectionRate*100)}%`}}</text></view></view>
          <view class="analytics-section"><text class="section-title">播放结果 · 独立于理解反馈</text><view v-for="(label,key) in playbackLabels" :key="key" class="analytics-row"><text>{{label}}</text><text>{{report.playbackCounts[key]}} 条</text></view></view>
          <view class="analytics-section"><text class="section-title">分类话题频次</text><view v-for="c in report.categoryCounts" :key="c.id" class="analytics-row"><text>{{c.label}}</text><text>{{c.count}} 条表达</text></view></view>
          <view class="analytics-section"><text class="section-title">时间段对比</text><text class="modal-copy">先指定当前起止日期，再输入对比范围；两个范围沿用相同使用人、场景和来源筛选。</text><view class="analytics-filters"><view class="family-field"><text>对比开始日期</text><input v-model="previousFrom" placeholder="YYYY-MM-DD" aria-label="对比开始日期"/></view><view class="family-field"><text>对比结束日期</text><input v-model="previousTo" placeholder="YYYY-MM-DD" aria-label="对比结束日期"/></view></view><text v-if="(previousFrom || previousTo) && !comparisonValid" class="modal-copy">请填写两组有效且顺序正确的起止日期。</text><view v-if="comparison" class="analytics-row"><text>当前 {{comparison.current.confirmedCount}} 条 · 对比 {{comparison.previous.confirmedCount}} 条</text><text>相差 {{comparison.change.confirmedCount}} 条</text></view><text v-if="comparison" class="modal-copy">有反馈表达的理解率差：{{comparison.change.understoodRate===null?'缺少明确反馈，无法对比':`${comparison.change.understoodRate.toFixed(1)} 个百分点`}}。范围时长不同也会影响数量。</text></view>
          <view class="analytics-section"><text class="section-title">每日表达趋势</text><view v-for="d in report.dailyCounts" :key="d.date" class="analytics-trend"><text>{{d.date}}</text><view class="analytics-track"><view :style="{width:`${d.count/maxDaily*100}%`}"/></view><text>{{d.count}} 条</text></view></view>
          <view class="analytics-breakdowns"><view class="analytics-section"><text class="section-title">常用选卡</text><text class="modal-copy">同一条表达中重复选卡只计一次。</text><view v-for="c in report.topCards.slice(0,10)" :key="c.id" class="analytics-row"><text>{{c.label}}</text><text>{{c.count}} 条表达</text></view></view><view class="analytics-section"><text class="section-title">沟通场景</text><view v-for="s in report.sceneCounts" :key="s.id" class="analytics-row"><text>{{s.label}}</text><text>{{s.count}} 条表达</text></view></view></view>
          <view class="analytics-section"><text class="section-title">统计依据 · {{entries.length}} 条表达</text><button v-for="e in [...entries].reverse()" :key="e.turn.id" role="button" tabindex="0" class="analytics-evidence" @click="emit('inspect',e.roomId)"><view><text>{{e.turn.contextSnapshot.communicatorName}} · {{new Date(e.turn.createdAt).toLocaleString('zh-CN')}}</text><text>{{e.turn.finalText}}</text></view><text>查看记录 →</text></button></view>
        </template>
        <view class="analytics-section"><text class="section-title">报告说明 · 与统计事实分开</text><text class="modal-copy">可联网生成匿名统计的解释，供家人核对；不会替本人推断意图或评定能力。说明额度由服务端独立计算，未配置服务时仍可查看统计。</text><button role="button" tabindex="0" class="outline-button" :disabled="narrativeBusy || !report.confirmedCount" @click="generateNarrative">{{narrativeBusy?'正在生成说明…':'生成 AI 报告说明'}}</button><button v-if="narrativeBusy" role="button" tabindex="0" class="text-button" @click="stopNarrative">取消说明生成</button><text v-if="narrativeError" class="error-copy" role="alert">{{narrativeError}}</text><view v-if="narrative" class="saved-report-detail"><text class="analytics-caption">AI 说明 · 请与统计核对</text><text>{{narrative.text}}</text><view class="scene-options"><button role="button" tabindex="0" class="quiet-button" :disabled="!persist" @click="adoptNarrative">采用 AI 说明并保存报告</button><button role="button" tabindex="0" class="text-button" @click="narrative=null">清除 AI 说明</button></view></view><text v-if="narrativeRemaining!==null" class="modal-copy">{{familyId?'当前家庭账户':'本机体验凭证'}}今日说明剩余 {{narrativeRemaining}} 次；卡片扩写额度单独计算。</text></view>
        <button role="button" tabindex="0" class="outline-button" :disabled="!persist" @click="saveReport">保存统计报告</button>
        <button role="button" tabindex="0" class="outline-button" @click="exportReport">导出统计报告</button><text v-if="message" role="status" class="modal-copy">{{message}}</text>
      </template>
    </template>
      <view class="analytics-section"><text class="section-title">已保存报告 · {{state.reports?.length || 0}} 份</text><view v-for="saved in [...(state.reports || [])].reverse()" :key="saved.id" class="saved-report"><button role="button" tabindex="0" class="analytics-evidence" @click="inspectedReportId=saved.id"><text>{{new Date(saved.createdAt).toLocaleString('zh-CN')}} · {{saved.metrics.confirmedCount}} 条表达</text><text>{{isCommunicationReportStale(state,saved)?'依据已变更':'依据未变更'}} →</text></button></view><view v-if="inspectedReport" class="saved-report-detail"><text class="analytics-caption">保存时的统计快照，不随反馈修改自动改写</text><text>已确认 {{inspectedReport.metrics.confirmedCount}} 条 · 反馈覆盖 {{percent(inspectedReport.metrics.feedbackCoverage)}} · 理解率 {{percent(inspectedReport.metrics.understoodRate)}}</text><text>筛选：{{filterSummary(inspectedReport.filters)}}</text><view v-if="inspectedReport.narrative" class="analytics-section"><text class="analytics-caption">保存的 AI 说明 · 与统计事实分开</text><text>{{inspectedReport.narrative.text}}</text></view><text>活跃 {{inspectedReport.metrics.activeDays}} 天 · 平均选卡 {{inspectedReport.metrics.inputCards.average.toFixed(1)}} 张 · 平均确认时间 {{inspectedReport.metrics.process.averageSeconds===null?'未采集':`${inspectedReport.metrics.process.averageSeconds.toFixed(1)} 秒`}}</text><view v-for="(label,key) in kinds" :key="key" class="analytics-row"><text>{{label}}</text><text>{{inspectedReport.metrics.kind[key]}} 条</text></view><view v-for="c in inspectedReport.metrics.topCards.slice(0,10)" :key="c.id" class="analytics-row"><text>{{c.label}}</text><text>{{c.count}} 条表达</text></view><text>过程已采集 {{inspectedReport.metrics.process.collectedCount}} 条 · 缺失 {{inspectedReport.metrics.process.missingCount}} 条</text><text v-if="isCommunicationReportStale(state,inspectedReport)" class="modal-copy">统计依据已变更。请恢复筛选并重新保存，旧快照仍保留。</text><view class="scene-options"><button role="button" tabindex="0" class="quiet-button" @click="restoreReport(inspectedReport)">恢复筛选并重新统计</button><button role="button" tabindex="0" class="quiet-button" @click="exportSaved(inspectedReport)">导出已保存报告</button><button role="button" tabindex="0" class="text-button" @click="removeReport(inspectedReport.id)">移除报告</button></view></view></view>

  </view>
</template>
