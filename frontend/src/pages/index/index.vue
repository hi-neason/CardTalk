<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch, nextTick } from 'vue';
import { onHide } from '@dcloudio/uni-app';
import { CARDS, CORE_CARDS, CATEGORIES, SCENES, PRESETS, getActiveRoom, createRoom, addCard, removeCard, clearDraft, rejectExpansion, applyExpansion, confirmCandidate, recordRawExpression, nextTurn, endRoom, addPlaybackEvent, setFeedback, removeRoom, deriveSelectedEmotion, type State, type Source, type Card, type Room, type Turn, type FeedbackValue, type PlaybackStatus, type Emotion } from '../../domain';
import { loadState, saveState, originalBackup, STATE_KEY, newId } from '../../platform/storage';
import { health, trial, expand, type Trial, type Health } from '../../platform/api';
import { enableKeyboardAccess } from '../../platform/accessibility';
import { playCardAudio, speakText, stopSpeech } from '../../platform/speech';

const initial = loadState();
const state = ref<State>(initial.state);
const storageError = ref(initial.error);
let protectOriginal = initial.protectedOriginal;
const room = computed(() => getActiveRoom(state.value));
const scene = computed(() => SCENES.find(s => s.id === room.value?.context.sceneId));
const currentTurn = computed(() => room.value?.turns.find(t => t.id === room.value?.draft.confirmedTurnId));
const category = ref('all');
const query = ref('');
const visibleCards = computed(() => CARDS.filter(c => (category.value === 'all' || c.category === category.value) && c.label.includes(query.value.trim())));
const error = ref('');
const notice = ref('');
const busy = ref(false);
const speaking = ref(false);
const speechStatus = ref('');
const service = ref<Health | null>(null);
const serviceError = ref('');
const quota = ref<Trial | null>(null);
const modal = ref<'new' | 'history' | 'settings' | 'features' | null>(null);
const inspectedRoomId = ref<string | null>(null);
const historyPreset = ref('all');
const historyScene = ref('all');
const historyQuery = ref('');
const history = computed(() => [...state.value.rooms].reverse().filter(r => (historyPreset.value === 'all' || r.context.preset === historyPreset.value) && (historyScene.value === 'all' || (historyScene.value === 'none' ? r.context.sceneId === null : r.context.sceneId === historyScene.value)) && (r.context.communicatorName + r.turns.map(t => t.finalText).join(' ')).includes(historyQuery.value)));
const inspected = computed(() => state.value.rooms.find(r => r.id === inspectedRoomId.value));
const newPreset = ref<'children' | 'adult'>('children');
const newName = ref('');
const newScene = ref('kitchen');
const participants = ref('家人');
const source = ref<Source>('unknown');
const sources: { id: Source; label: string }[] = [{ id: 'unknown', label: '尚未确认' }, { id: 'independent', label: '本人操作' }, { id: 'assisted', label: '家人协助' }];
const editCandidate = ref<string | null>(null);
const editedText = ref('');
const selectedEmotion = computed(() => deriveSelectedEmotion(room.value?.draft.cards || [], state.value.settings.emotionRecognitionEnabled));
const emotionLabels: Record<Emotion, string> = { neutral: '自然语气', happy: '开心', sad: '难过', angry: '生气', fearful: '害怕' };
type AiSettingKey = 'aiExpansionEnabled' | 'speechSynthesisEnabled' | 'emotionRecognitionEnabled';
const feedbackBy = ref('');
const feedbackValues: { id: FeedbackValue; label: string }[] = [{ id: 'understood', label: '理解了' }, { id: 'clarify', label: '需要再说明' }, { id: 'not-understood', label: '没理解' }];
let cancelRequest: (() => void) | null = null;
let requestGeneration = 0;
let playbackGeneration = 0;
let expansionAttempt: { roomId: string; version: number; requestId: string } | null = null;
const sourceLabel = (value: Source) => sources.find(s => s.id === value)?.label || '尚未确认';
const presetLabel = (value: string) => PRESETS.find(p => p.id === value)?.name || value;
const sceneLabel = (id: string | null) => SCENES.find(s => s.id === id)?.name || '不限场景';
const date = (value: string) => new Date(value).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' });
const playbackLabel = (value: PlaybackStatus) => ({ started: '开始播放', completed: '播放完成', interrupted: '已停止', failed: '播放失败', unavailable: '声音不可用' }[value]);

function commit(next: State) {
  if (next === state.value) return;
  state.value = next;
  if (protectOriginal) return;
  try { saveState(next); storageError.value = ''; }
  catch { storageError.value = '本次更改暂未保存到本机。请保持页面打开，并在设置中导出备份。'; }
}
function mutate(action: () => State) { try { commit(action()); error.value = ''; } catch (e) { error.value = e instanceof Error ? e.message : '暂时无法完成此操作'; } }
function invalidateAsync() { requestGeneration++; playbackGeneration++; expansionAttempt = null; cancelRequest?.(); cancelRequest = null; busy.value = false; stopSpeech(); speaking.value = false; editCandidate.value = null; }
function pick(card: Card) { if (!room.value) { openNew(); return; } if (room.value.draft.confirmedTurnId) { error.value = '这句话已经记录，请先开始下一句。'; return; } if (room.value.draft.cards.length >= 20) { error.value = '每次最多选择20张卡片，已选内容仍保留。'; return; } invalidateAsync(); mutate(() => addCard(state.value, card)); }
function remove(index: number) { invalidateAsync(); mutate(() => removeCard(state.value, index)); }
function clear() { invalidateAsync(); mutate(() => clearDraft(state.value)); }
function reject() { invalidateAsync(); mutate(() => rejectExpansion(state.value)); notice.value = "候选已清除，原始选卡保留。你可以调整卡片或重新扩写。"; }
function toggleAiSetting(key: AiSettingKey) {
  invalidateAsync();
  const next = { ...state.value, settings: { ...state.value.settings, [key]: !state.value.settings[key] } };
  const active = getActiveRoom(next);
  commit((key === 'aiExpansionEnabled' || key === 'emotionRecognitionEnabled') && active && !active.draft.confirmedTurnId ? rejectExpansion(next) : next);
  notice.value = '设置已保存到本机。';
  speechStatus.value = '';
}
function openNew() { newPreset.value = state.value.settings.preset; newName.value = ''; source.value = 'unknown'; modal.value = 'new'; }
function begin() {
  const names = participants.value.split(/[，,、]/).map(s => s.trim()).filter(Boolean);
  if (names.length > 8 || names.some(name => name.length > 30)) { error.value = '最多填写8位参与者，每个称呼不超过30字。'; return; }
  mutate(() => createRoom(state.value, { preset: newPreset.value, communicatorName: newName.value.trim() || (newPreset.value === 'children' ? '儿童体验' : '成人体验'), sceneId: newScene.value === 'none' ? null : newScene.value, participants: [...new Set(names)].map((name, i) => ({ id: `participant-${i + 1}`, name })), source: source.value }));
  if (room.value) { commit({ ...state.value, settings: { ...state.value.settings, preset: newPreset.value } }); modal.value = null; category.value = 'all'; feedbackBy.value = ''; notice.value = '聊天室已开始，记录仅保存在这台设备。'; }
}
async function refreshService() {
  serviceError.value = '';
  try { service.value = await health(); quota.value = await trial(); }
  catch (e) { service.value = null; serviceError.value = e instanceof Error ? e.message : '服务连接失败'; }
}
async function generate() {
  if (!state.value.settings.aiExpansionEnabled || busy.value || !room.value?.draft.cards.length || room.value.draft.confirmedTurnId) return;
  const captured = room.value;
  const generation = ++requestGeneration;
  busy.value = true; error.value = ''; editCandidate.value = null;
  try {
    quota.value = await trial();
    if (generation !== requestGeneration) return;
    if (!expansionAttempt || expansionAttempt.roomId !== captured.id || expansionAttempt.version !== captured.draft.version) expansionAttempt = { roomId: captured.id, version: captured.draft.version, requestId: newId() };
    const request = expand(captured, expansionAttempt.requestId, deriveSelectedEmotion(captured.draft.cards, state.value.settings.emotionRecognitionEnabled)?.emotion); cancelRequest = request.cancel;
    const result = await request.promise;
    if (generation !== requestGeneration || room.value?.id !== captured.id) return;
    commit(applyExpansion(state.value, result, captured.id));
    expansionAttempt = null;
    if (quota.value) quota.value = { ...quota.value, remaining: result.remaining };
  } catch (e) { if (generation === requestGeneration) error.value = e instanceof Error ? e.message : '扩写失败，原始表达仍可使用'; }
  finally { if (generation === requestGeneration) { busy.value = false; cancelRequest = null; } }
}
function cancelGeneration() { requestGeneration++; cancelRequest?.(); cancelRequest = null; busy.value = false; notice.value = '已取消扩写，原始选卡保留。'; }
function confirm(id: string, edit = false) { mutate(() => confirmCandidate(state.value, id, edit ? editedText.value : undefined)); editCandidate.value = null; notice.value = currentTurn.value ? '这句话已确认并记录。点击播放，或展示给家人看。' : ''; }
function showRaw() { if (!room.value?.draft.cards.length) return; invalidateAsync(); mutate(() => recordRawExpression(state.value)); notice.value = '已记录原始表达，可展示文字或播放卡片声音。'; }
function next() { invalidateAsync(); mutate(() => nextTurn(state.value)); notice.value = ''; speechStatus.value = ''; }
async function finishRoom() {
  const result = await uni.showModal({ title: '结束这次沟通？', content: '已记录的表达会保存在本机。未确认的选卡保留在会话详情，不会当作本人已表达的内容。', confirmText: '结束沟通' });
  if (!result.confirm) return;
  invalidateAsync(); mutate(() => endRoom(state.value)); notice.value = '聊天室已结束。可以在本机历史中回顾。'; speechStatus.value = '';
}
function playbackUpdate(roomId: string, turnId: string, generation: number) {
  return (status: PlaybackStatus, message?: string) => {
    mutate(() => addPlaybackEvent(state.value, roomId, turnId, status));
    if (generation !== playbackGeneration) return;
    speaking.value = status === 'started'; speechStatus.value = playbackLabel(status);
    if (message) error.value = message;
  };
}
async function playTurn(targetRoom: Room, turn: Turn, raw = false) {
  const generation = ++playbackGeneration;
  stopSpeech();
  error.value = ''; speaking.value = true; speechStatus.value = '正在准备声音';
  const update = playbackUpdate(targetRoom.id, turn.id, generation);
  if (raw) playCardAudio(turn.cardSnapshot, update);
  else {
    if (state.value.settings.speechSynthesisEnabled) {
      try { await trial(); } catch (e) { if (generation !== playbackGeneration) return; speaking.value = false; error.value = e instanceof Error ? e.message : '语音连接失败'; return; }
    }
    if (generation !== playbackGeneration) return;
    await speakText(turn.finalText, state.value.settings.rate, update, state.value.settings.speechSynthesisEnabled, state.value.settings.emotionRecognitionEnabled ? (turn.wasEdited ? 'neutral' : deriveSelectedEmotion(turn.cardSnapshot, true)?.emotion) : undefined, turn.kind === 'raw' ? turn.cardSnapshot : undefined);
  }
}
function playCurrent(raw = false) { if (room.value && currentTurn.value) void playTurn(room.value, currentTurn.value, raw); }
function stop() { playbackGeneration++; stopSpeech(); speaking.value = false; speechStatus.value = '已停止'; }
function feedback(targetRoom: Room, turn: Turn, value: FeedbackValue) {
  mutate(() => setFeedback(state.value, targetRoom.id, turn.id, value, feedbackBy.value.trim() || '现场参与者（未注明）'));
}
async function deleteHistory(target: Room) {
  const result = await uni.showModal({ title: '删除这条本机记录？', content: '删除后无法恢复。此操作只影响本机，不会更改服务端体验额度。', confirmText: '删除' });
  if (result.confirm) { stop(); mutate(() => removeRoom(state.value, target.id)); inspectedRoomId.value = null; }
}
function exportBackup() {
  const text = protectOriginal ? originalBackup() : JSON.stringify(state.value, null, 2);
  // #ifdef H5
  const url = URL.createObjectURL(new Blob([text], { type: 'application/json' }));
  const link = document.createElement('a'); link.href = url; link.download = `cardtalk-backup-${new Date().toISOString().slice(0, 10)}.json`; link.click(); URL.revokeObjectURL(url); notice.value = '备份已导出，文件包含本机沟通内容，请自行保存。'; return;
  // #endif
  // #ifndef H5
  uni.setClipboardData({ data: text, success: () => { notice.value = '记录已复制，包含沟通内容，请保存在自己的安全位置。'; } });
  // #endif
}
async function resetCorrupt() {
  const result = await uni.showModal({ title: '重置损坏的本机记录？', content: '请先导出原始备份。重置会替换原本无法读取的数据，并保存本次打开期间的记录。', confirmText: '重置并保存' });
  if (!result.confirm) return;
  try { uni.removeStorageSync(STATE_KEY); saveState(state.value); protectOriginal = false; storageError.value = ''; notice.value = '本机记录已重新保存。'; } catch { storageError.value = '本机仍无法保存，请先导出记录。'; }
}
function scrollToExpression() { uni.pageScrollTo({ selector: '#expression-panel', duration: 200 }); }
function closeModal() { if (modal.value === 'history') stop(); modal.value = null; inspectedRoomId.value = null; }
let disableKeyboardAccess = () => {};
// #ifdef H5
let previousFocus: HTMLElement | null = null;
watch(modal, async (value, old) => {
  if (value && !old) previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  await nextTick();
  if (value) document.querySelector<HTMLElement>('[role=dialog] [tabindex="0"]')?.focus();
  else previousFocus?.focus();
});
function keyboard(event: KeyboardEvent) { if (event.key === 'Escape') closeModal(); }
// #endif
onMounted(() => {
  void refreshService();
  disableKeyboardAccess = enableKeyboardAccess();
  // #ifdef H5
  window.addEventListener('keydown', keyboard);
  // #endif
});
onHide(() => { if (busy.value) cancelGeneration(); stop(); });
onUnmounted(() => {
  disableKeyboardAccess();
  invalidateAsync();
  // #ifdef H5
  window.removeEventListener('keydown', keyboard);
  // #endif
});
</script>

<template>
  <view class="app-shell" :class="{ 'large-text': state.settings.fontSize === 'large' }">
    <view class="topbar">
      <view class="brand"><view class="brand-mark"><text>ct</text></view><view><text class="brand-name">CardTalk</text><text class="brand-caption">家庭辅助沟通</text></view></view>
      <view class="top-actions"><text class="local-indicator">● 本机保存</text><button role="button" tabindex="0" class="quiet-button" @click="modal = 'history'">本机历史</button><button role="button" tabindex="0" class="quiet-button" @click="modal = 'settings'">设置</button></view>
    </view>
    <view v-if="storageError" class="persistent-warning" role="alert">{{ storageError }}</view>
    <view class="workspace">
      <view class="intro">
        <view><text class="eyebrow">让每一个想法，被听见</text><text class="page-title">{{ room ? '今天，你想说什么？' : '把想法说给你听。' }}</text><text class="intro-copy">{{ room ? '选几张卡片，按自己的节奏表达。' : '从生活里熟悉的卡片开始，家人一起听、一起理解。' }}</text></view>
        <button role="button" tabindex="0" v-if="!room" class="primary-button start-button" @click="openNew">开始沟通 <text>↗</text></button>
        <button role="button" tabindex="0" v-else class="end-button" @click="finishRoom">结束聊天室</button>
      </view>
      <view v-if="room" class="room-context"><view class="context-leading"><text class="context-dot"></text><text>{{ room.context.communicatorName }}</text><text class="context-separator">/</text><text>{{ scene?.name || '不限场景' }}</text></view><text>和 {{ room.context.participants.map(p => p.name).join('、') || '现场参与者' }} 一起 · {{ sourceLabel(room.context.source) }}</text></view>
      <view v-else class="welcome-strip"><text class="welcome-number">01</text><text>开始一次沟通后，选择卡片、确认候选，再把表达说出来。</text><text class="welcome-note">儿童与成人都可以体验</text></view>

      <view class="board-layout">
        <view class="card-board" id="card-board">
          <view class="section-header"><text class="section-title">随时可以说</text><text class="section-caption">这些卡片一直在这里</text></view>
          <scroll-view class="core-scroll" scroll-x><view class="core-row"><button role="button" tabindex="0" v-for="card in CORE_CARDS" :key="card.id" class="core-card" :class="{ 'boundary-card': ['not', 'stop', 'pain'].includes(card.icon) }" :aria-label="`选择${card.label}`" @click="pick(card)"><image class="core-art" :src="`/static/cards/${card.icon}.svg`" mode="aspectFit"/><text>{{ card.label }}</text></button></view></scroll-view>
          <view class="category-search"><scroll-view class="category-scroll" scroll-x><view class="category-row"><button role="button" tabindex="0" class="category-button" :class="{ active: category === 'all' }" :aria-pressed="category === 'all'" @click="category = 'all'">全部</button><button role="button" tabindex="0" v-for="item in CATEGORIES" :key="item.id" class="category-button" :class="{ active: category === item.id }" :aria-pressed="category === item.id" @click="category = item.id">{{ item.name }}</button></view></scroll-view><view class="search-box"><text>⌕</text><input v-model="query" placeholder="找一张卡片" aria-label="搜索卡片" /></view></view>
          <view class="card-grid"><button role="button" tabindex="0" v-for="card in visibleCards" :key="card.id" class="word-card" :class="`group-${card.category}`" :disabled="!!room?.draft.confirmedTurnId" :aria-label="`选择${card.label}`" @click="pick(card)"><view class="card-art-wrap"><image class="card-art" :src="`/static/cards/${card.icon}.svg`" mode="aspectFit"/></view><text class="word-label">{{ card.label }}</text><text v-if="room?.draft.cards.some(c => c.id === card.id)" class="selection-count">{{ room.draft.cards.filter(c => c.id === card.id).length }}</text></button></view>
          <view v-if="!visibleCards.length" class="empty-state">没有找到这张卡片，试试其他词语或分类。</view>
        </view>

        <view class="expression-panel" id="expression-panel">
          <view class="section-header"><text class="section-title">我的表达</text><text class="count-label">{{ room?.draft.cards.length || 0 }} / 20</text></view>
          <view v-if="!room?.draft.cards.length" class="expression-empty"><view class="empty-dialog-art"><text>…</text></view><text class="empty-title">想法，可以慢慢拼出来。</text><text>点选左侧卡片，顺序不用完美。<br/>你始终可以说“不”。</text></view>
          <view v-else class="selected-tray"><button role="button" tabindex="0" v-for="(card, index) in room.draft.cards" :key="`${index}-${card.id}`" class="selected-word" :disabled="!!room.draft.confirmedTurnId" :aria-label="`移除第${index + 1}张${card.label}`" @click="remove(index)"><image :src="`/static/cards/${card.icon}.svg`" mode="aspectFit"/><text>{{ card.label }}</text><text v-if="!room.draft.confirmedTurnId" class="remove-mark">×</text></button></view>
          <view v-if="room?.draft.cards.length && !currentTurn" class="draft-actions"><button role="button" tabindex="0" class="text-button" @click="remove(room.draft.cards.length - 1)">撤销一张</button><button role="button" tabindex="0" class="text-button" @click="clear">清空</button><button role="button" tabindex="0" class="text-button" @click="showRaw">使用原始表达</button></view>
          <view v-if="error" class="message error-message" role="alert">{{ error }}</view><view v-if="notice" class="message notice-message" role="status">{{ notice }}</view>
          <text v-if="selectedEmotion && room?.draft.cards.length" class="emotion-hint">{{ selectedEmotion.suppressionReason === 'negation' ? '选卡包含否定，将使用自然语气' : selectedEmotion.conflict ? '选中了多种情绪，将使用自然语气' : `表达语气：${emotionLabels[selectedEmotion.emotion]}` }}</text><text v-if="!state.settings.aiExpansionEnabled && !currentTurn" class="generation-note">AI 扩写已关闭，可直接使用原始表达。</text><view v-if="!currentTurn && state.settings.aiExpansionEnabled" class="generation-controls"><button role="button" tabindex="0" class="primary-button generate-button" :disabled="!room?.draft.cards.length || busy" @click="generate">{{ busy ? '正在整理表达…' : '帮我整理成一句话' }}<text v-if="!busy">→</text></button><button role="button" tabindex="0" v-if="busy" class="text-button cancel-button" @click="cancelGeneration">取消，保留选卡</button><text class="generation-note">联网 AI 扩写 · 由你确认后才发声</text></view>
          <view v-if="room?.draft.expansion && !currentTurn && state.settings.aiExpansionEnabled" class="candidate-area"><text class="candidate-heading">哪一句是你的意思？</text><view v-for="(candidate,index) in room.draft.expansion.candidates" :key="candidate.id" class="candidate-item"><button role="button" tabindex="0" class="candidate-button" @click="confirm(candidate.id)"><text class="candidate-index">{{ index + 1 }}</text><text>{{ candidate.text }}</text><text class="candidate-arrow">↗</text></button><button role="button" tabindex="0" class="candidate-edit text-button" @click="editCandidate = candidate.id; editedText = candidate.text">修改后确认</button><view v-if="editCandidate === candidate.id" class="candidate-editor"><input v-model="editedText" maxlength="250" aria-label="修改候选表达"/><button role="button" tabindex="0" class="small-primary" @click="confirm(candidate.id,true)">确认修改</button></view></view><text v-if="room.draft.expansion.needsClarification" class="clarification">{{ room.draft.expansion.clarification }}</text><text v-if="!room.draft.expansion.candidates.length" class="clarification">暂时没有合适的候选。你可以修改卡片，或使用原始表达。</text><button role="button" tabindex="0" class="outline-button" @click="reject">都不合适，保留选卡</button></view>
          <view v-if="currentTurn" class="confirmed-area"><text class="confirmed-label">已确认 · {{ currentTurn.kind === 'raw' ? '原始表达' : '由你选择' }}</text><text class="confirmed-text">{{ currentTurn.finalText }}</text><text v-if="currentTurn.wasEdited && state.settings.emotionRecognitionEnabled" class="emotion-hint">表达经过修改，将以自然语气朗读。</text><button role="button" tabindex="0" class="primary-button speak-button" @click="playCurrent()">{{ speaking ? '重新播放' : '说出这句话' }} <text>◖))</text></button><view class="playback-controls"><button role="button" tabindex="0" v-if="speaking" class="text-button" @click="stop">停止声音</button><button role="button" tabindex="0" v-if="currentTurn.kind === 'generated'" class="text-button" @click="playCurrent(true)">播放原始卡片</button><text v-if="speechStatus" role="status">{{ speechStatus }}</text></view><view class="feedback-area"><text>家人理解了吗？<text class="optional-tag">可跳过</text></text><view class="feedback-options"><button role="button" tabindex="0" v-for="item in feedbackValues" :key="item.id" :class="{ active: currentTurn.feedback?.value === item.id }" @click="feedback(room!,currentTurn,item.id)">{{ item.label }}</button></view><input class="feedback-name" v-model="feedbackBy" maxlength="100" placeholder="反馈者称呼，可留空" aria-label="反馈者称呼"/><text class="feedback-source">{{ feedbackBy ? `由 ${feedbackBy} 反馈` : '未注明反馈者；不会推断为某位家人' }}</text></view><button role="button" tabindex="0" class="outline-button next-button" @click="next">开始下一句 →</button></view>
          <view class="panel-footer"><text>记录保存在这台设备</text><text>{{ quota ? `今日扩写剩余 ${quota.remaining} 次` : '联网后查看体验额度' }}</text></view>
        </view>
      </view>
      <view class="workspace-footer"><text>每一种表达，都值得被认真听见。</text><button role="button" tabindex="0" class="text-button" @click="modal = 'features'">了解家庭功能 ↗</button></view>
    </view>

    <button v-if="room && !modal" role="button" tabindex="0" class="mobile-expression-link" @click="scrollToExpression"><text>{{ currentTurn ? '已确认表达' : `已选 ${room.draft.cards.length} / 20 张` }}</text><text>查看表达 ↓</text></button>
    <view v-if="modal" class="modal-backdrop" @click.self="closeModal"><view class="modal-card" :class="{ 'history-modal': modal === 'history' }" role="dialog" aria-modal="true" :aria-label="modal === 'new' ? '创建聊天室' : modal === 'history' ? '本机历史' : modal === 'settings' ? '设置' : '家庭功能'"><view class="modal-header"><text>{{ modal === 'new' ? '开始一次新的沟通' : modal === 'history' ? '本机沟通历史' : modal === 'settings' ? 'AI 与本机设置' : '按需要开启家庭功能' }}</text><button role="button" tabindex="0" class="close-button" aria-label="关闭" @click="closeModal">×</button></view>
      <view v-if="modal === 'new'" class="form-content"><text class="modal-copy">选择合适的体验预设。使用人资料管理会在后续家庭版本开放。</text><view class="preset-options"><button role="button" tabindex="0" v-for="preset in PRESETS" :key="preset.id" :class="{ active: newPreset === preset.id }" @click="newPreset = preset.id"><text>{{ preset.name }}</text><text>{{ preset.description }}</text></button></view><view class="form-field"><text>这次是谁在表达？</text><input v-model="newName" maxlength="40" placeholder="称呼，可留空使用体验预设" aria-label="表达者称呼"/></view><view class="form-field"><text>在哪里？</text><view class="scene-options"><button role="button" tabindex="0" v-for="s in SCENES" :key="s.id" :class="{ active: newScene === s.id }" @click="newScene = s.id">{{ s.name }}</button></view></view><view class="form-field"><text>和谁一起？</text><input v-model="participants" maxlength="240" placeholder="如妈妈、爸爸，用逗号分隔" aria-label="沟通参与者"/></view><view class="form-field"><text>这次由谁操作？</text><view class="scene-options"><button role="button" tabindex="0" v-for="s in sources" :key="s.id" :class="{ active: source === s.id }" @click="source = s.id">{{ s.label }}</button></view></view><view v-if="error" class="message error-message">{{ error }}</view><button role="button" tabindex="0" class="primary-button full-button" @click="begin">创建聊天室，开始选卡 →</button></view>

      <view v-if="modal === 'history'" class="history-content"><view v-if="!inspected"><text class="modal-copy">本机记录不会自动上传。清理浏览器或微信数据可能移除这些记录。</text><view class="history-filters"><view class="scene-options"><button role="button" tabindex="0" v-for="value in ['all','children','adult']" :key="value" :class="{ active: historyPreset === value }" @click="historyPreset = value">{{ value === 'all' ? '全部使用人' : presetLabel(value) }}</button></view><picker :range="['全部场景',...SCENES.map(s=>s.name)]" @change="historyScene = Number($event.detail.value) === 0 ? 'all' : (SCENES[Number($event.detail.value)-1]?.id || 'all')"><view class="filter-picker">{{ historyScene === 'all' ? '全部场景' : sceneLabel(historyScene) }} ▾</view></picker><input v-model="historyQuery" placeholder="搜索称呼或表达" aria-label="搜索本机历史"/></view><view v-if="!history.length" class="empty-state">还没有符合条件的记录。完成一次沟通后，会在这里看到。</view><button role="button" tabindex="0" v-for="r in history" :key="r.id" class="history-row" @click="inspectedRoomId = r.id"><view><text class="history-name">{{ r.context.communicatorName }} · {{ sceneLabel(r.context.sceneId) }}</text><text class="history-meta">{{ date(r.startedAt) }} · {{ r.turns.length }} 条表达 · {{ r.status === 'active' ? '进行中' : '已结束' }}</text></view><text>→</text></button></view><view v-else><button role="button" tabindex="0" class="text-button" @click="stop(); inspectedRoomId = null">← 返回历史列表</button><view class="history-playback" role="status"><button role="button" tabindex="0" v-if="speaking" class="outline-button" @click="stop">停止声音</button><text v-if="speechStatus">{{ speechStatus }}</text><text v-if="error" class="error-copy" role="alert">{{ error }}</text></view><view class="history-detail-title"><text>{{ inspected.context.communicatorName }} · {{ sceneLabel(inspected.context.sceneId) }}</text><text>{{ date(inspected.startedAt) }} · {{ sourceLabel(inspected.context.source) }}</text></view><view v-for="turn in inspected.turns" :key="turn.id" class="history-turn"><text class="history-turn-time">{{ date(turn.createdAt) }} · {{ sourceLabel(turn.source) }}</text><text class="history-turn-final">{{ turn.finalText }}</text><text class="history-turn-raw">原始选卡：{{ turn.cardSnapshot.map(c => c.label).join('、') }}</text><text v-if="turn.expansion" class="history-turn-raw">AI候选：{{ turn.expansion.candidates.map(c => c.text).join(' / ') }}</text><text class="history-turn-raw">{{ turn.kind === 'raw' ? '原始表达' : turn.wasEdited ? '候选修改后确认' : '候选确认' }} · {{ turn.playbackEvents.length ? playbackLabel(turn.playbackEvents[turn.playbackEvents.length-1]!.status) : '尚未播放' }} · {{ turn.feedback ? feedbackValues.find(f=>f.id===turn.feedback?.value)?.label || '未记录反馈' : '未记录反馈' }}{{ turn.feedback ? `（${turn.feedback.by}）` : '' }}</text><view class="history-turn-actions"><button role="button" tabindex="0" class="text-button" @click="playTurn(inspected!,turn)">重新播放</button><button role="button" tabindex="0" v-for="item in feedbackValues" :key="item.id" class="text-button" :class="{ selected: turn.feedback?.value === item.id }" @click="feedback(inspected!,turn,item.id)">{{ item.label }}</button></view></view><view v-if="inspected.draft.cards.length && !inspected.draft.confirmedTurnId" class="history-turn"><text>未确认选卡：{{ inspected.draft.cards.map(c=>c.label).join('、') }}</text><text class="history-meta">这部分没有作为已确认表达计入记录。</text></view><view v-if="inspected.status === 'active'" class="message notice-message">这是当前未结束聊天室。关闭历史即可继续。</view><button role="button" tabindex="0" v-else class="delete-button" @click="deleteHistory(inspected)">删除这条本机记录</button></view></view>

      <view v-if="modal === 'settings'" class="form-content"><view class="form-field"><text>文字大小</text><view class="scene-options"><button role="button" tabindex="0" v-for="size in ['normal','large'] as const" :key="size" :class="{ active: state.settings.fontSize === size }" @click="commit({ ...state, settings: { ...state.settings, fontSize: size } })">{{ size === 'normal' ? '标准' : '大字' }}</button></view></view><view class="form-field"><text>朗读速度</text><slider :value="state.settings.rate * 100" :min="50" :max="120" :step="10" activeColor="#163e73" @change="commit({ ...state, settings: { ...state.settings, rate: Number($event.detail.value)/100 } })"/><text class="modal-copy">{{ state.settings.rate }} 倍</text></view><view class="ai-settings"><text class="section-title">AI 功能</text>
        <view class="ai-setting-row"><view><text class="setting-title">AI 扩写</text><text class="setting-description">用大模型将选卡整理成更自然的表达，由你确认后发声。</text></view><button role="switch" tabindex="0" class="setting-switch" :class="{ enabled: state.settings.aiExpansionEnabled }" :aria-checked="state.settings.aiExpansionEnabled" aria-label="AI扩写" @click="toggleAiSetting('aiExpansionEnabled')"><text>{{ state.settings.aiExpansionEnabled ? '开启' : '关闭' }}</text><view class="switch-knob"/></button></view>
        <view class="ai-setting-row"><view><text class="setting-title">语音合成</text><text class="setting-description">开启使用科大讯飞拟人合成；关闭使用设备发声。</text></view><button role="switch" tabindex="0" class="setting-switch" :class="{ enabled: state.settings.speechSynthesisEnabled }" :aria-checked="state.settings.speechSynthesisEnabled" aria-label="语音合成" @click="toggleAiSetting('speechSynthesisEnabled')"><text>{{ state.settings.speechSynthesisEnabled ? '开启' : '关闭' }}</text><view class="switch-knob"/></button></view>
        <view class="ai-setting-row"><view><text class="setting-title">情绪识别</text><text class="setting-description">依据选中的情绪卡，让扩写语气与讯飞合成带上相应情绪。</text></view><button role="switch" tabindex="0" class="setting-switch" :class="{ enabled: state.settings.emotionRecognitionEnabled }" :aria-checked="state.settings.emotionRecognitionEnabled" aria-label="情绪识别" @click="toggleAiSetting('emotionRecognitionEnabled')"><text>{{ state.settings.emotionRecognitionEnabled ? '开启' : '关闭' }}</text><view class="switch-knob"/></button></view>
        <text class="modal-copy">情绪识别只依据“开心、难过、生气、害怕”卡片，不猜测本人情绪。选中多种情绪时使用自然语气。关闭后仍保留卡片原意，只停止附加情绪语气。</text>
        <text class="modal-copy">讯飞需要联网及服务端配置，情绪合成还取决于已开通的音色。Web 的设备声音由系统提供；微信小程序不能直接调用系统朗读新句子，关闭合成时可播放预置卡片声音或展示文字。</text>
      </view><view class="service-status"><text>扩写服务：{{ service ? service.llmConfigured ? 'DeepSeek 已配置' : '尚未配置 API 密钥' : '连接未就绪' }}</text><text>讯飞拟人合成：{{ service?.ttsConfigured ? '已配置' : '尚未配置' }}</text><text>情绪声音：{{ service?.ttsEmotionSupported ? '已配置支持情绪的音色' : '尚未配置支持情绪的音色' }}</text><text v-if="state.settings.speechSynthesisEnabled && state.settings.emotionRecognitionEnabled && !service?.ttsEmotionSupported" class="error-copy">当前音色尚未启用情绪合成。带情绪的扩写仍可使用，语音需配置相应音色或关闭情绪识别。</text><text v-if="quota">今日扩写：剩余 {{ quota.remaining }} / {{ quota.limit }} 次</text><text v-if="serviceError" class="error-copy">{{ serviceError }}</text><button role="button" tabindex="0" class="text-button" @click="refreshService">重新检查服务</button></view><view class="data-section"><text class="section-title">本机数据</text><text class="modal-copy">记录只保存在这台设备。导出的文件包含沟通内容，请自行妥善保存。</text><button role="button" tabindex="0" class="outline-button" @click="exportBackup">{{ protectOriginal ? '导出原始备份' : '导出本机备份' }}</button><button role="button" tabindex="0" v-if="protectOriginal" class="delete-button" @click="resetCorrupt">备份后重置损坏记录</button></view></view>

      <view v-if="modal === 'features'" class="form-content"><text class="modal-copy">MVP 已提供基础沟通和本机历史。以下家庭模块尚未开放，未来按需要独立授权，不捆绑基础表达。</text><view v-for="feature in [{name:'使用人管理',text:'管理多位家庭成员及个性化沟通资料'},{name:'场景管理',text:'自定义生活场景、描述和关联卡片'},{name:'分析与报告',text:'回顾表达喜好、方式和有反馈的沟通效果'}]" :key="feature.name" class="feature-row"><view><text>{{ feature.name }}</text><text>{{ feature.text }}</text></view><text class="feature-status">尚未开放</text></view><text class="modal-copy">体验数据不会因为查看这些功能而上传。额度用尽仍可使用选卡、文字展示和已准备的声音。</text></view>
    </view></view>
  </view>
</template>
