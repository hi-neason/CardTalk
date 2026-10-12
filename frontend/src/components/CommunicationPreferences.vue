<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import {trial,remoteVoices,type RemoteVoice} from '../platform/api'
import type { CommunicationPreferences, Preset } from '../domain'
// #ifdef APP
import {getNativeVoices} from '../platform/nativeSpeech';
// #endif
import { speakText, stopSpeech } from '../platform/speech'
const props = defineProps<{ modelValue: CommunicationPreferences; disabled?: boolean; preset?:Preset }>()
const emit = defineEmits<{ 'update:modelValue': [CommunicationPreferences] }>()
const voices = ref<{ uri: string; name: string }[]>([])
const remoteRows=ref<RemoteVoice[]>([])
const remoteStatus=ref('')
const remoteLoading=ref(false)
let voiceEpoch=0
async function loadRemote(){if(props.disabled||remoteLoading.value)return;const epoch=++voiceEpoch;remoteLoading.value=true;try{const rows=await remoteVoices();if(!disposed&&epoch===voiceEpoch){remoteRows.value=rows;remoteStatus.value='仅显示服务端配置的授权音色';}}catch(e){if(!disposed&&epoch===voiceEpoch)remoteStatus.value=e instanceof Error?e.message:'获取声音失败';}finally{if(!disposed&&epoch===voiceEpoch)remoteLoading.value=false;}}
const status = ref('')
const previewing = ref(false)
const set = (patch: Partial<CommunicationPreferences>) => { if (!props.disabled) emit('update:modelValue', { ...props.modelValue, ...patch }) }
let disposed=false;
let previewEpoch=0;
function refreshVoices() {
  // #ifdef APP
  void getNativeVoices().then(items=>{if(!disposed)voices.value=items.filter(v=>/^zh|^cmn|^yue/i.test(v.language)).map(v=>({uri:v.id,name:`${v.name} (${v.language})`}));}).catch(()=>{if(!disposed)voices.value=[];});
  // #endif
  // #ifdef H5
  voices.value = typeof speechSynthesis === 'undefined' ? [] : speechSynthesis.getVoices().filter(v => /^zh|^cmn|^yue/i.test(v.lang)).map(v => ({ uri: v.voiceURI, name: `${v.name} (${v.lang})` }))
  // #endif
}
function stopPreview() { previewEpoch++; if (previewing.value) stopSpeech(); previewing.value = false }
async function preview(remote=false) {
  if(props.disabled)return;
  stopPreview()
  previewing.value = true; status.value = remote?'正在准备远端声音':'正在准备设备声音'
  const epoch=++previewEpoch
  const p = {...props.modelValue}
  const audience=props.preset
  if(remote){try{await trial()}catch(e){if(!disposed&&epoch===previewEpoch){previewing.value=false;status.value=e instanceof Error?e.message:'无法准备远端试听';}return;}}
  if(disposed||epoch!==previewEpoch||props.disabled)return
  void speakText('每一种表达，都值得被认真听见。', p.rate, (state, message) => { previewing.value = state === 'started'; status.value = message || ({ started: '正在试听', completed: '试听完成', interrupted: '试听已停止', failed: '声音不可用', unavailable: '声音不可用' })[state] }, remote, undefined, undefined, { volume: p.volume, deviceVoiceURI: p.deviceVoiceURI,remoteVoiceKey:p.remoteVoiceKey,audience })
}
watch(()=>props.disabled,value=>{if(value){voiceEpoch++;remoteLoading.value=false;stopPreview();}})
onMounted(() => {
  refreshVoices()
  // #ifdef H5
  if (typeof speechSynthesis !== 'undefined') speechSynthesis.addEventListener?.('voiceschanged', refreshVoices)
  // #endif
})
onUnmounted(() => {disposed=true;voiceEpoch++;
  stopPreview()
  // #ifdef H5
  if (typeof speechSynthesis !== 'undefined') speechSynthesis.removeEventListener?.('voiceschanged', refreshVoices)
  // #endif
})
</script>
<template>
  <view class="communication-preferences">
    <view class="preference-field"><text>卡片显示</text><view class="scene-options"><button role="button" tabindex="0" v-for="item in [{id:'both',name:'图片与文字'},{id:'images',name:'仅图片'},{id:'text',name:'仅文字'}] as const" :key="item.id" :disabled="disabled" :aria-pressed="modelValue.displayMode === item.id" :class="{active:modelValue.displayMode === item.id}" @click="set({displayMode:item.id})">{{ item.name }}</button></view></view>
    <view class="preference-field"><text>卡片间距</text><view class="scene-options"><button role="button" tabindex="0" :disabled="disabled" :class="{active:modelValue.density==='comfortable'}" @click="set({density:'comfortable'})">宽松</button><button role="button" tabindex="0" :disabled="disabled" :class="{active:modelValue.density==='compact'}" @click="set({density:'compact'})">紧凑</button></view></view>
    <view class="preference-field"><text>文字大小</text><view class="scene-options"><button role="button" tabindex="0" :disabled="disabled" :class="{active:modelValue.fontSize==='normal'}" @click="set({fontSize:'normal'})">标准文字</button><button role="button" tabindex="0" :disabled="disabled" :class="{active:modelValue.fontSize==='large'}" @click="set({fontSize:'large'})">大字</button></view></view>
    <view class="preference-field"><text>朗读速度 · {{ modelValue.rate }} 倍</text><view class="scene-options"><button role="button" tabindex="0" v-for="rate in [0.5,0.7,0.9,1,1.2]" :key="rate" :disabled="disabled" :aria-label="`朗读速度 ${rate} 倍`" :class="{active:modelValue.rate===rate}" @click="set({rate})">{{ rate }} 倍</button></view></view>
    <view class="preference-field"><text>播放音量 · {{ Math.round(modelValue.volume*100) }}%</text><view class="scene-options"><button role="button" tabindex="0" v-for="volume in [0,0.25,0.5,0.75,1]" :key="volume" :disabled="disabled" :aria-label="`播放音量 ${volume*100}%`" :class="{active:modelValue.volume===volume}" @click="set({volume})">{{ volume*100 }}%</button></view><text v-if="modelValue.volume===0" class="preference-note">当前为静音，播放时不会发出声音。</text></view>
    <view class="preference-field"><text>设备声音</text><view class="preference-voices scene-options"><button role="button" tabindex="0" :disabled="disabled" :class="{active:!modelValue.deviceVoiceURI}" @click="set({deviceVoiceURI:''})">设备默认中文声音</button><button role="button" tabindex="0" v-for="voice in voices" :key="voice.uri" :disabled="disabled" :class="{active:modelValue.deviceVoiceURI===voice.uri}" @click="set({deviceVoiceURI:voice.uri})">{{ voice.name }}</button></view><text class="preference-note">只列出此设备实际可用的中文声音；不影响讯飞音色。</text><text v-if="modelValue.deviceVoiceURI && !voices.some(v=>v.uri===modelValue.deviceVoiceURI)" class="preference-note">之前选择的声音在此设备不可用，将使用设备默认中文声音。</text><text v-else-if="!voices.length" class="preference-note">此设备暂未提供中文声音列表，朗读将尝试系统默认声音。</text><view class="preference-preview"><button role="button" tabindex="0" class="quiet-button" @click="preview(false)">试听设备声音</button><button role="button" tabindex="0" v-if="previewing" class="quiet-button" @click="stopPreview">停止试听</button><text v-if="status" role="status">{{ status }}</text></view></view>
    <view class="preference-field"><text>讯飞声音</text><view class="scene-options"><button role="button" tabindex="0" :disabled="disabled" :class="{active:!modelValue.remoteVoiceKey}" @click="set({remoteVoiceKey:''})">按使用人类型自动选择</button><button role="button" tabindex="0" :disabled="disabled || remoteLoading" @click="loadRemote">获取已配置远端声音</button><button role="button" tabindex="0" v-for="voice in remoteRows" :key="voice.key" :disabled="disabled || !voice.configured" :class="{active:modelValue.remoteVoiceKey===voice.key}" @click="set({remoteVoiceKey:voice.key})">{{voice.label}}{{voice.emotionSupported?' · 支持情绪':''}}{{!voice.configured?' · 未配置':''}}</button></view><text class="preference-note">联网且开启语音合成时生效；试听会请求远端合成服务。</text><text v-if="modelValue.remoteVoiceKey" class="preference-note">当前选择：{{remoteRows.find(v=>v.key===modelValue.remoteVoiceKey)?.label || modelValue.remoteVoiceKey}}</text><text v-if="remoteStatus" role="status">{{remoteStatus}}</text><button role="button" tabindex="0" :disabled="disabled || !remoteRows.some(v=>v.configured && (!modelValue.remoteVoiceKey || v.key===modelValue.remoteVoiceKey))" @click="preview(true)">试听远端声音</button></view>
    <view class="preference-field"><text>防止快速重复选卡</text><view class="scene-options"><button role="button" tabindex="0" v-for="ms in [0,300,600,1000]" :key="ms" :disabled="disabled" :class="{active:modelValue.touchGuardMs===ms}" @click="set({touchGuardMs:ms})">{{ ms===0?'不限制连点':`${ms} 毫秒` }}</button></view><text class="preference-note">开启后忽略同一张卡片的快速连点；等待后仍可重复选择。拒绝、停止与求助卡片随时可选。</text></view>
  </view>
</template>
