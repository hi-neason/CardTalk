<script setup lang="ts">
// #ifdef APP
import {chooseJSON,cancelNativeDocument} from '../platform/nativeDocument';
// #endif
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { parseBackup } from '../domain/backup'
import { MAX_LOCAL_STATE_CHARACTERS } from '../domain/capacity'
import type { State } from '../domain/types'
import '../family.css'

const props = defineProps<{ disabled: boolean }>()
const emit = defineEmits<{ import: [state: State] }>()
const candidate = ref<State | null>(null)
const filename = ref('')
const error = ref('')
const reading = ref(false)
const confirming = ref(false)
const turns = computed(() => candidate.value?.rooms.reduce((total, room) => total + room.turns.length, 0) ?? 0)
let generation = 0
function cancel() {
 // #ifdef APP
 cancelNativeDocument();
 // #endif
 generation++; candidate.value = null; filename.value = ''; error.value = ''; reading.value = false; confirming.value = false }
onBeforeUnmount(cancel)
watch(() => props.disabled, value => { if (value) cancel() })
function choose() {
  if (props.disabled || confirming.value) return
  cancel()
  // #ifdef H5
  const current = generation
  const input = document.createElement('input')
  input.type = 'file'; input.accept = '.json,application/json'
  input.onchange = async () => {
    const file = input.files?.[0]
    if (!file || current !== generation || props.disabled) return
    reading.value = true
    try {
      // UTF-8 can use up to three bytes per UTF-16 code unit. Exact limit is checked after decoding.
      if (file.size > MAX_LOCAL_STATE_CHARACTERS * 3) throw new Error('备份超过本机容量，无法导入。')
      const parsed = parseBackup(await file.text())
      if (current !== generation || props.disabled) return
      candidate.value = parsed; filename.value = file.name
    } catch (reason) { if (current === generation) error.value = reason instanceof Error ? reason.message : '无法读取备份，请重新选择。' }
    finally { if (current === generation) reading.value = false }
  }
  input.click()
  // #endif
  // #ifdef APP
  const nativeCurrent=generation;reading.value=true;
  void chooseJSON().then(text=>{if(text===null||nativeCurrent!==generation||props.disabled)return;candidate.value=parseBackup(text);filename.value='所选设备文档';}).catch(reason=>{if(nativeCurrent===generation)error.value=reason instanceof Error?reason.message:'无法读取备份';}).finally(()=>{if(nativeCurrent===generation)reading.value=false;});
  // #endif
}
async function confirm() {
  if (props.disabled || !candidate.value || reading.value || confirming.value) return
  const current = generation, selected = candidate.value
  confirming.value = true
  try {
    const result = await uni.showModal({ title: '替换全部本机数据？', content: '备份中的家庭资料、沟通记录和设置将替换当前数据，不会合并。请先导出现有备份。', confirmText: '确认替换', cancelText: '取消' })
    if (result.confirm && current === generation && !props.disabled) emit('import', selected)
  } catch { if (current === generation) error.value = '无法确认导入，请重试。' }
  finally { if (current === generation) confirming.value = false }
}
</script>

<template>
  <view class="backup-import">
    <text class="modal-copy">导入 JSON 备份将替换本机家庭资料、沟通记录和设置，不会合并。文件只在本机读取，不上传。</text>
    <text v-if="disabled" class="modal-copy">请先结束当前沟通，再导入备份，避免丢失草稿。</text>
    <button role="button" tabindex="0" class="outline-button" :disabled="disabled || confirming" @click="choose">{{ reading ? '重新选择备份' : '选择备份文件' }}</button>
    <view v-if="error" class="family-message family-error" role="alert">{{ error }}</view>
    <text v-if="reading" class="modal-copy" role="status">正在读取备份…</text>
    <view v-if="candidate" class="family-builtins" aria-label="备份预览">
      <text class="family-field-title">备份预览：{{ filename }}</text>
      <text>使用人 {{ candidate.family.profiles.length }} · 自定义卡片 {{ candidate.family.cards.length }} · 场景 {{ candidate.family.scenes.length }}</text>
      <text>沟通 {{ candidate.rooms.length }} 次 · 已记录表达 {{ turns }} 条</text>
      <text v-if="candidate.activeRoomId">备份包含未结束的沟通，导入后可以继续。</text>
      <view class="family-form-actions"><button role="button" tabindex="0" class="quiet-button" :disabled="confirming" @click="cancel">取消导入</button><button role="button" tabindex="0" class="primary-button" :disabled="disabled || confirming" @click="confirm">确认替换本机数据</button></view>
    </view>
  </view>
</template>

<style scoped>
.backup-import{display:flex;flex-direction:column;gap:12px;min-width:0}.backup-import .family-builtins{margin-top:0;overflow-wrap:anywhere}.backup-import .family-form-actions{flex-wrap:wrap;margin-top:4px}
</style>
