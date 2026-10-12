<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch, nextTick } from 'vue';
import { onHide } from '@dcloudio/uni-app';
import { CARDS, CORE_CARDS, CATEGORIES, SCENES, PRESETS, getActiveRoom, createRoom, addCard, removeCard, clearDraft, rejectExpansion, applyExpansion, confirmCandidate, recordRawExpression, nextTurn, endRoom, addPlaybackEvent, setFeedback, removeRoom, deriveSelectedEmotion, getAvailableCards, getAvailableProfiles, getAvailableScenes, getRecommendedCards, toggleFavoriteCard, getFavoriteCards, getRecentCards, savePhraseFromTurn, removeSavedPhrase, getSavedPhrases, prepareSavedPhrase, confirmSavedPhrase, recordExpressionEvent, type ExpressionEvent, type SceneSnapshot, type State, type Source, type Card, type Room, type Turn, type FeedbackValue, type PlaybackStatus, type Emotion, type CommunicationPreferences, getCommunicationPreferences, updateGlobalPreferences } from '../../domain';
// #ifndef MP-WEIXIN
import FamilyManager from '../../components/FamilyManager.vue';
import CommunicationAnalytics from '../../components/CommunicationAnalytics.vue';
// #endif
// #ifndef MP-WEIXIN
import BackupImport from '../../components/BackupImport.vue';
// #endif
// #ifndef MP-WEIXIN
import AccountManager from '../../components/AccountManager.vue';
import PublicResources from '../../components/PublicResources.vue';
// #endif
// #ifndef MP-WEIXIN
import DataManager from '../../components/DataManager.vue';
// #endif
// #ifdef APP
import {exportText,cancelNativeDocument} from '../../platform/nativeDocument';
// #endif
import CommunicationHelp from '../../components/CommunicationHelp.vue';
import CommunicationPreferencesEditor from '../../components/CommunicationPreferences.vue';
import {familyAccess,chooseFamilyAccess,updateFamilyAccessClock,suspendFamilyAccess} from '../../platform/familyAccess';
import { loadState, saveState, originalBackup, STATE_KEY, newId } from '../../platform/storage';
import { health, trial, expand, ApiError, type Trial, type Health } from '../../platform/api';
import { enableKeyboardAccess } from '../../platform/accessibility';
import { playCardAudio, speakText, stopSpeech } from '../../platform/speech';

const initial = loadState();
const state = ref<State>(initial.state);
const storageError = ref(initial.error);
let protectOriginal = initial.protectedOriginal;
const room = computed(() => getActiveRoom(state.value));
const scene = computed(() => room.value?.context.sceneSnapshot || getAvailableScenes(state.value).find(s => s.id === room.value?.context.sceneId));
const communicationPreferences = computed(() => getCommunicationPreferences(state.value, room.value?.context));
const globalPreferences = computed(() => getCommunicationPreferences(state.value));
let lastPick: { id: string; time: number } | null = null;
const currentTurn = computed(() => room.value?.turns.find(t => t.id === room.value?.draft.confirmedTurnId));
const category = ref('all');
const query = ref('');
const cardCollection = ref<'all'|'favorites'|'recent'>('all');
const collectionCards = computed(() => cardCollection.value === 'favorites' ? getFavoriteCards(state.value) : cardCollection.value === 'recent' ? getRecentCards(state.value,room.value?.context) : getAvailableCards(state.value));
const savedPhrases = computed(() => getSavedPhrases(state.value,room.value?.context));
const preparedPhrase = computed(() => savedPhrases.value.find(p=>p.id === room.value?.draft.savedPhraseId));
const visibleCards = computed(() => collectionCards.value.filter(c => (category.value === 'all' || c.category === category.value) && c.label.includes(query.value.trim())));
const error = ref('');
const notice = ref('');
const busy = ref(false);
const speaking = ref(false);
const speechStatus = ref('');
const service = ref<Health | null>(null);
const serviceError = ref('');
const quota = ref<Trial | null>(null);
const modal = ref<'new' | 'history' | 'settings' | 'features' | 'family' | 'analytics' | 'library' | 'help' | 'account' | 'resources' | null>(null);
const familyTab = ref<'profiles' | 'cards' | 'scenes'>('profiles');
let familyPlatform = false;
// #ifndef MP-WEIXIN
familyPlatform = true;
// #endif
const familyCapabilities = computed(() => ({ profileManagement: familyPlatform && (familyAccess.selected.value ? familyAccess.capabilities.value.profileManagement : quota.value?.capabilities.profileManagement === true), scenarioManagement: familyPlatform && (familyAccess.selected.value ? familyAccess.capabilities.value.scenarioManagement : quota.value?.capabilities.scenarioManagement === true), customCards: familyPlatform && (familyAccess.selected.value ? familyAccess.capabilities.value.customCards : quota.value?.capabilities.customCards === true) }));
const availableProfiles = computed(() => getAvailableProfiles(state.value));
const availableScenes = computed(() => getAvailableScenes(state.value));
const recommendedCards = computed(() => getRecommendedCards(state.value, room.value?.context.sceneId || null));
const newProfileId = ref('');
const newCurrentState = ref('');
const historyProfile = ref('all');
const historyProfiles = computed(() => [{ id: 'all', name: '全部使用人档案' }, { id: 'trial', name: '未关联档案' }, ...state.value.family.profiles.map(p => ({ id: p.id, name: p.name + (p.archived ? '（已归档）' : '') }))]);
const historyScenes = computed(() => { const entries = new Map<string, {id:string;name:string}>(); SCENES.forEach(s => entries.set(s.id, s)); state.value.family.scenes.forEach(s => entries.set(s.id, s)); state.value.rooms.forEach(r => { if (r.context.sceneSnapshot) entries.set(r.context.sceneSnapshot.id, r.context.sceneSnapshot); }); return [...entries.values()]; });
const inspectedRoomId = ref<string | null>(null);
const historyPreset = ref('all');
const historyScene = ref('all');
const historyQuery = ref('');
const history = computed(() => [...state.value.rooms].reverse().filter(r => (historyProfile.value === 'all' || (historyProfile.value === 'trial' ? !r.context.profileId : r.context.profileId === historyProfile.value)) && (historyPreset.value === 'all' || r.context.preset === historyPreset.value) && (historyScene.value === 'all' || (historyScene.value === 'none' ? r.context.sceneId === null : r.context.sceneId === historyScene.value)) && (r.context.communicatorName + r.turns.map(t => t.finalText).join(' ')).includes(historyQuery.value)));
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
const feedbackNote = ref('');
const historyNotes = ref<Record<string,string>>({});
watch(() => currentTurn.value?.id, () => { feedbackNote.value = currentTurn.value?.feedback?.note || ''; }, { immediate: true });
const feedbackValues: { id: FeedbackValue; label: string }[] = [{ id: 'understood', label: '理解了' }, { id: 'clarify', label: '需要再说明' }, { id: 'not-understood', label: '没理解' }];
let cancelRequest: (() => void) | null = null;
let requestGeneration = 0;
let playbackGeneration = 0;
let activeExpressionAttempt: { roomId: string; version: number; requestId?: string } | null = null;
let expansionAttempt: { roomId: string; version: number; requestId: string } | null = null;
const sourceLabel = (value: Source) => sources.find(s => s.id === value)?.label || '尚未确认';
const presetLabel = (value: string) => PRESETS.find(p => p.id === value)?.name || value;
const sceneLabel = (id: string | null, snapshot?: SceneSnapshot) => snapshot?.name || historyScenes.value.find(s => s.id === id)?.name || '不限场景';
const art = (card: Card) => card.image || `/static/cards/${card.icon}.svg`;
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
function recordProcess(type: ExpressionEvent['type'], attempt: {roomId:string;version:number;requestId?:string}, errorCode?: string) {
  try { commit(recordExpressionEvent(state.value,attempt.roomId,type,attempt.version,attempt.requestId,errorCode)); } catch { notice.value='本次过程记录未能保存，当前选卡仍保留。'; }
}
function cancelProcess() { if(busy.value && activeExpressionAttempt) recordProcess('generation-cancelled',activeExpressionAttempt); activeExpressionAttempt=null; }
const processLabels: Record<ExpressionEvent['type'], string> = {'generation-started':'发起扩写','generation-succeeded':'得到候选','generation-failed':'扩写失败','generation-cancelled':'取消扩写','candidates-rejected':'拒绝候选','candidate-edit-started':'开始修改候选','clarification-requested':'需要澄清'};
function processDuration(turn:Turn) { const process=turn.processSnapshot; if(!process)return ''; const seconds=Math.max(0,Math.round((Date.parse(process.confirmedAt)-Date.parse(process.startedAt))/1000)); return seconds<60?`${seconds} 秒`:`${Math.floor(seconds/60)} 分 ${seconds%60} 秒`; }
function editChoice(candidateId:string,text:string) { if(room.value) recordProcess('candidate-edit-started',{roomId:room.value.id,version:room.value.draft.version,requestId:room.value.draft.expansion?.requestId}); editCandidate.value=candidateId;editedText.value=text; }
function invalidateAsync() { cancelProcess(); requestGeneration++; playbackGeneration++; expansionAttempt = null; cancelRequest?.(); cancelRequest = null; busy.value = false; stopSpeech(); speaking.value = false; editCandidate.value = null; }
function pick(card: Card) {
  const now = Date.now();
  const exempt = ['not','no','stop','pain','help','core-refuse','core-no','core-stop','core-pain','core-help'].includes(card.id) || /不要|停止|停一下|拒绝|求助|救命|疼|帮忙|帮帮|不想|不愿意/.test(card.label);
  if (!exempt && lastPick?.id === card.id && now - lastPick.time < communicationPreferences.value.touchGuardMs) { notice.value = '已忽略快速重复点击，稍等后可再次选择这张卡片。'; return; }
 if (!room.value) { openNew(); return; } if (room.value.draft.confirmedTurnId) { error.value = '这句话已经记录，请先开始下一句。'; return; } if (!preparedPhrase.value && room.value.draft.cards.length >= 20) { error.value = '每次最多选择20张卡片，已选内容仍保留。'; return; } lastPick = { id: card.id, time: now }; invalidateAsync(); mutate(() => addCard(preparedPhrase.value ? clearDraft(state.value) : state.value, card)); }
function remove(index: number) { lastPick = null; invalidateAsync(); mutate(() => removeCard(state.value, index)); }
function clear() { lastPick = null; invalidateAsync(); mutate(() => clearDraft(state.value)); }
function reject() { invalidateAsync(); mutate(() => rejectExpansion(state.value)); notice.value = "候选已清除，原始选卡保留。你可以调整卡片或重新扩写。"; }
function toggleAiSetting(key: AiSettingKey) {
  invalidateAsync();
  const next = { ...state.value, settings: { ...state.value.settings, [key]: !state.value.settings[key] } };
  const active = getActiveRoom(next);
  commit((key === 'aiExpansionEnabled' || key === 'emotionRecognitionEnabled') && active && !active.draft.confirmedTurnId ? rejectExpansion(next, 'invalidation') : next);
  notice.value = '设置已保存到本机。';
  speechStatus.value = '';
}
function openNew() { newPreset.value = state.value.settings.preset; newName.value = ''; newProfileId.value = ''; newCurrentState.value = ''; source.value = 'unknown'; if (!availableScenes.value.some(s => s.id === newScene.value)) newScene.value = 'kitchen'; modal.value = 'new'; }
function selectProfile(id: string) { newProfileId.value = id; const profile = availableProfiles.value.find(p => p.id === id); if (profile) { newName.value = profile.name; newPreset.value = profile.preset; } else { newName.value = ''; } }
function openFamily(tab: 'profiles' | 'cards' | 'scenes' = 'profiles') { stop(); speechStatus.value = ''; error.value = ''; familyTab.value = tab; modal.value = 'family'; void refreshService();void refreshFamilyAccess(); }
async function refreshFamilyAccess(){const selected=familyAccess.selected.value;if(!selected)return;try{await chooseFamilyAccess(selected.accountId,selected.family);}catch{serviceError.value='家庭权限暂时无法确认，新增高级操作暂停，已有资料保留。';}}
const analyticsReady = ref(false);
const analyticsEnabled=computed(()=>familyAccess.selected.value?familyAccess.capabilities.value.analytics:quota.value?.capabilities.analytics===true);
let accessTimer:ReturnType<typeof setInterval>|null=null;
async function openAnalytics() { stop(); error.value = ''; analyticsReady.value = false; modal.value = 'analytics'; await refreshService();await refreshFamilyAccess(); analyticsReady.value = !serviceError.value && analyticsEnabled.value; }
function inspectAnalytics(roomId: string) { inspectedRoomId.value = roomId; modal.value = 'history'; }
function persistPublicResources(next:State):boolean {if(!familyCapabilities.value.customCards&&JSON.stringify(next.family.cards)!==JSON.stringify(state.value.family.cards))return false;if(!familyCapabilities.value.scenarioManagement&&JSON.stringify(next.family.scenes)!==JSON.stringify(state.value.family.scenes))return false;return saveFamily(next);}
function saveFamily(next: State): boolean {
  if (protectOriginal) { storageError.value = '原始记录受保护，请先导出备份并在设置中重置后再保存家庭资料。'; return false; }
  try { saveState(next); state.value = next; storageError.value = ''; return true; }
  catch { storageError.value = '家庭资料未保存，请检查本机空间，并导出备份。原来的资料仍保留。'; return false; }
}
async function previewCard(card: Card) {
  const generation = ++playbackGeneration; stopSpeech(); error.value = ''; speaking.value = true; speechStatus.value = '正在准备试听';
  await speakText(card.label, globalPreferences.value.rate, (status, message) => { if (generation !== playbackGeneration) return; speaking.value = status === 'started'; speechStatus.value = playbackLabel(status); if (message) error.value = message; }, false, undefined, undefined, globalPreferences.value);
}
function savePreferences(preferences: CommunicationPreferences) {
  try { const next = updateGlobalPreferences(state.value, preferences); if (!saveFamily(next)) return; notice.value = '沟通偏好已保存，已建档沟通继续使用创建时的个人偏好。'; } catch (e) { error.value = e instanceof Error ? e.message : '偏好未保存'; }
}
function begin() {
  lastPick = null;
  const names = participants.value.split(/[，,、]/).map(s => s.trim()).filter(Boolean);
  if (names.length > 8 || names.some(name => name.length > 30)) { error.value = '最多填写8位参与者，每个称呼不超过30字。'; return; }
  mutate(() => createRoom(state.value, { preset: newPreset.value, communicatorName: newName.value.trim() || (newPreset.value === 'children' ? '儿童体验' : '成人体验'), sceneId: newScene.value === 'none' ? null : newScene.value, ...(newProfileId.value ? { profileId: newProfileId.value } : {}), participants: [...new Set(names)].map((name, i) => ({ id: `participant-${i + 1}`, name })), source: source.value }, newCurrentState.value.trim()));
  if (room.value) { commit({ ...state.value, settings: { ...state.value.settings, preset: newPreset.value } }); modal.value = null; category.value = 'all'; feedbackBy.value = ''; notice.value = '聊天室已开始，记录仅保存在这台设备。'; }
}
async function refreshService() {
  serviceError.value = '';
  try { service.value = await health(); quota.value = await trial(); }
  catch (e) { service.value = null; serviceError.value = e instanceof Error ? e.message : '服务连接失败'; }
}
async function generate() {
  if (room.value?.draft.savedPhraseId || !state.value.settings.aiExpansionEnabled || busy.value || !room.value?.draft.cards.length || room.value.draft.confirmedTurnId) return;
  const captured = room.value;
  const generation = ++requestGeneration;
  busy.value = true; error.value = ''; editCandidate.value = null;
  activeExpressionAttempt = {roomId:captured.id,version:captured.draft.version};
  try {
    quota.value = await trial();
    if (generation !== requestGeneration) return;
    if (captured.draft.cards.some(c => c.id.startsWith('custom-')) && !familyCapabilities.value.customCards) throw new Error('自定义卡片扩写尚未开启，原始表达仍保留。');
    if (!expansionAttempt || expansionAttempt.roomId !== captured.id || expansionAttempt.version !== captured.draft.version) expansionAttempt = { roomId: captured.id, version: captured.draft.version, requestId: newId() };
    activeExpressionAttempt = {...expansionAttempt};
    recordProcess('generation-started',activeExpressionAttempt);
    const request = expand(captured, expansionAttempt.requestId, deriveSelectedEmotion(captured.draft.cards, state.value.settings.emotionRecognitionEnabled)?.emotion, familyCapabilities.value, familyAccess.selected.value?.family.id); cancelRequest = request.cancel;
    const result = await request.promise;
    if (generation !== requestGeneration || room.value?.id !== captured.id) return;
    commit(applyExpansion(state.value, result, captured.id));
    expansionAttempt = null;
    if (quota.value) quota.value = { ...quota.value, remaining: result.remaining };
  } catch (e) { if (generation === requestGeneration) { if(familyAccess.selected.value&&e instanceof ApiError&&[401,403].includes(e.status))suspendFamilyAccess(); if(activeExpressionAttempt) recordProcess('generation-failed',activeExpressionAttempt,e instanceof ApiError && /^[A-Za-z0-9_.:-]{1,80}$/.test(e.code)?e.code:'REQUEST_FAILED'); error.value = e instanceof Error ? e.message : '扩写失败，原始表达仍可使用'; } }
  finally { if (generation === requestGeneration) { busy.value = false; cancelRequest = null; activeExpressionAttempt = null; } }
}
watch(()=>familyAccess.selected.value?`${familyAccess.selected.value.accountId}:${familyAccess.selected.value.family.id}`:'trial',()=>{cancelGeneration();expansionAttempt=null;});
function cancelGeneration() { cancelProcess(); requestGeneration++; cancelRequest?.(); cancelRequest = null; busy.value = false; notice.value = '已取消扩写，原始选卡保留。'; }
function confirm(id: string, edit = false) { mutate(() => confirmCandidate(state.value, id, edit ? editedText.value : undefined)); editCandidate.value = null; notice.value = currentTurn.value ? '这句话已确认并记录。点击播放，或展示给家人看。' : ''; }
function showRaw() { if (!room.value?.draft.cards.length) return; invalidateAsync(); mutate(() => recordRawExpression(state.value)); notice.value = '已记录原始表达，可展示文字或播放卡片声音。'; }
function next() { lastPick = null; invalidateAsync(); mutate(() => nextTurn(state.value)); notice.value = ''; speechStatus.value = ''; }
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
  const preferences = {...getCommunicationPreferences(state.value, turn.contextSnapshot),audience:turn.contextSnapshot.preset};
  if (raw) playCardAudio(turn.cardSnapshot, update, preferences.rate, preferences);
  else {
    if (state.value.settings.speechSynthesisEnabled) {
      try { await trial(); } catch (e) { if (generation !== playbackGeneration) return; speaking.value = false; error.value = e instanceof Error ? e.message : '语音连接失败'; return; }
    }
    if (generation !== playbackGeneration) return;
    await speakText(turn.finalText, preferences.rate, update, state.value.settings.speechSynthesisEnabled, state.value.settings.emotionRecognitionEnabled ? (turn.wasEdited || turn.kind === 'saved' ? 'neutral' : deriveSelectedEmotion(turn.cardSnapshot, true)?.emotion) : undefined, turn.kind === 'raw' ? turn.cardSnapshot : undefined, preferences);
  }
}
function favorite(card: Card) { try { if (saveFamily(toggleFavoriteCard(state.value,card.id))) notice.value = state.value.library?.favoriteCardIds.includes(card.id) ? '已收藏卡片。' : '已取消收藏。'; } catch(e) { error.value = e instanceof Error ? e.message : '收藏未保存'; } }
function savePhrase(targetRoom: Room, turn: Turn) { try { if (saveFamily(savePhraseFromTurn(state.value,targetRoom.id,turn.id))) notice.value = '已保存为常用表达。再次使用时仍需本人确认。'; } catch(e) { error.value = e instanceof Error ? e.message : '常用表达未保存'; } }
function openLibrary() { stop(); error.value = ''; modal.value = 'library'; }
function phraseSource(id: string) { const phrase = savedPhrases.value.find(p=>p.id===id); const original = state.value.rooms.find(r=>r.id===phrase?.sourceRoomId); if(original) { inspectedRoomId.value=original.id; modal.value='history'; } else error.value='来源历史已删除，保存的文字仍然保留。'; }
async function choosePhrase(id: string) {
  if(!room.value) { error.value='请先创建聊天室，再选择对应使用人的常用表达。'; return; }
  if(currentTurn.value) { error.value='请先开始下一句，再使用常用表达。'; return; }
  if(room.value.draft.cards.length) { const result = await uni.showModal({title:'替换当前未确认选卡？',content:'选择常用表达会替换当前选卡。已确认的历史不受影响。',confirmText:'替换选卡'}); if(!result.confirm) return; }
  invalidateAsync();
  try { if(saveFamily(prepareSavedPhrase(state.value,id))) { modal.value=null; notice.value='请确认这句常用表达是否仍符合现在的意思。'; } } catch(e) { error.value=e instanceof Error?e.message:'无法使用常用表达'; }
}
function confirmPhrase() { const id=room.value?.draft.savedPhraseId; if(!id)return; try { if(saveFamily(confirmSavedPhrase(state.value,id))) notice.value='常用表达已确认并记录，点击播放或展示文字。'; } catch(e) { error.value=e instanceof Error?e.message:'无法确认常用表达'; } }
async function removePhrase(id: string) { const result=await uni.showModal({title:'移除这条常用表达？',content:'只移除常用表达，原来的沟通历史继续保留。',confirmText:'移除'}); if(!result.confirm)return; try { if(saveFamily(removeSavedPhrase(state.value,id))) notice.value='已移除常用表达，历史保留。'; } catch(e) {error.value=e instanceof Error?e.message:'无法移除';} }
function playCurrent(raw = false) { if (room.value && currentTurn.value) void playTurn(room.value, currentTurn.value, raw); }
function openAccount() { stop(); error.value = ''; modal.value = 'account'; }
function openSettings() { stop(); modal.value = 'settings'; }
function stop() { playbackGeneration++; stopSpeech(); speaking.value = false; speechStatus.value = '已停止'; }
function editHistoryNote(id:string,event:unknown) { const value=(event as {detail?:{value?:unknown}})?.detail?.value;if(typeof value==='string')historyNotes.value[id]=value; }
function saveFeedbackNote(targetRoom: Room, turn: Turn, note: string) { if(!turn.feedback)return; mutate(()=>setFeedback(state.value,targetRoom.id,turn.id,turn.feedback!.value,turn.feedback!.by,note.trim() || undefined)); }
function feedback(targetRoom: Room, turn: Turn, value: FeedbackValue) {
  mutate(() => setFeedback(state.value, targetRoom.id, turn.id, value, feedbackBy.value.trim() || turn.feedback?.by || '现场参与者（未注明）', (modal.value!=='history' && turn.id===currentTurn.value?.id && targetRoom.id===room.value?.id ? feedbackNote.value : historyNotes.value[turn.id] ?? turn.feedback?.note ?? '').trim() || undefined));
}
async function deleteHistory(target: Room) {
  const result = await uni.showModal({ title: '删除这条本机记录？', content: '删除后无法恢复。此操作只影响本机，不会更改服务端体验额度。', confirmText: '删除' });
  if (result.confirm) { stop(); mutate(() => removeRoom(state.value, target.id)); inspectedRoomId.value = null; }
}
async function exportBackup() {
  const text = protectOriginal ? originalBackup() : JSON.stringify(state.value);
  // #ifdef H5
  const url = URL.createObjectURL(new Blob([text], { type: 'application/json' }));
  const link = document.createElement('a'); link.href = url; link.download = `cardtalk-backup-${new Date().toISOString().slice(0, 10)}.json`; link.click(); URL.revokeObjectURL(url); notice.value = '备份已导出，文件包含本机沟通内容，请自行保存。'; return;
  // #endif
  // #ifdef APP
  try{const result=await exportText(`cardtalk-backup-${new Date().toISOString().slice(0,10)}.json`,text);notice.value=result.status==='saved'?'备份已导出到所选位置。':'已取消导出。';}catch(e){storageError.value=e instanceof Error?(e as Error).message:'导出失败';}
  return;
  // #endif
  // #ifdef MP-WEIXIN
  uni.setClipboardData({ data: text, success: () => { notice.value = '记录已复制，包含沟通内容，请保存在自己的安全位置。'; } });
  // #endif
}
function persistDataDeletion(next:State):boolean {
  if(protectOriginal)return false;
  const originals=new Map<string,unknown>();
  const rollback=()=>{let restored=true;for(const [key,value] of originals){try{uni.setStorageSync(key,value);}catch{restored=false;}}return restored;};
  try{for(const key of uni.getStorageInfoSync().keys){if(!key.startsWith('cardtalk.cloud-sync.v1.'))continue;const raw=uni.getStorageSync(key);originals.set(key,raw);let metadata;try{metadata=typeof raw==='string'?JSON.parse(raw):raw;}catch{uni.removeStorageSync(key);continue;}if(metadata&&typeof metadata==='object'&&Object.hasOwn(metadata,'pending')){delete metadata.pending;uni.setStorageSync(key,JSON.stringify(metadata));}}}
  catch{const restored=rollback();storageError.value=restored?'同步队列无法更新，本机资料与待处理队列已保留。':'本机资料未删除；部分同步队列恢复失败，请保留本机备份后重新核对在线资料。';return false;}
  const saved=saveFamily(next);if(!saved){if(!rollback())storageError.value='本机资料未删除；部分同步队列恢复失败，请保留本机备份后重新核对在线资料。';return false;}
  invalidateAsync();inspectedRoomId.value=null;feedbackNote.value='';historyNotes.value={};return true;
}
function importBackup(next: State) {
  if (room.value) { storageError.value = '请先结束当前沟通，再导入备份。'; return; }
  try {
    saveState(next); invalidateAsync(); state.value = next; protectOriginal = false; storageError.value = ''; error.value = ''; inspectedRoomId.value = null; modal.value = null; notice.value = '备份已导入，本机数据已替换。';
  } catch { storageError.value = '备份未导入，本机数据未更改。请检查存储空间后重试。'; }
}
async function resetCorrupt() {
  const result = await uni.showModal({ title: '重置损坏的本机记录？', content: '请先导出原始备份。重置会替换原本无法读取的数据，并保存本次打开期间的记录。', confirmText: '重置并保存' });
  if (!result.confirm) return;
  try { uni.removeStorageSync(STATE_KEY); saveState(state.value); protectOriginal = false; storageError.value = ''; notice.value = '本机记录已重新保存。'; } catch { storageError.value = '本机仍无法保存，请先导出记录。'; }
}
function scrollToExpression() { uni.pageScrollTo({ selector: '#expression-panel', duration: 200 }); }
function closeModal() { if (modal.value === 'history' || modal.value === 'family') stop(); modal.value = null; inspectedRoomId.value = null; }
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
  accessTimer=setInterval(updateFamilyAccessClock,1000);
  void refreshService();
  disableKeyboardAccess = enableKeyboardAccess();
  // #ifdef H5
  window.addEventListener('keydown', keyboard);
  // #endif
});
onHide(() => { if (busy.value) cancelGeneration(); stop(); });
onUnmounted(() => {
// #ifdef APP
cancelNativeDocument();
// #endif
  if(accessTimer)clearInterval(accessTimer);
  disableKeyboardAccess();
  invalidateAsync();
  // #ifdef H5
  window.removeEventListener('keydown', keyboard);
  // #endif
});
</script>

<template>
  <view class="app-shell" :class="{ 'large-text': communicationPreferences.fontSize === 'large', 'cards-images-only': communicationPreferences.displayMode === 'images', 'cards-text-only': communicationPreferences.displayMode === 'text', 'cards-compact': communicationPreferences.density === 'compact' }">
    <view v-if="familyAccess.selected.value" class="message" role="status">当前功能权限：{{familyAccess.selected.value.family.name}} · {{familyAccess.ready.value?'已核验':'等待联网核验，已有资料保留'}}</view><view class="topbar">
      <view class="brand"><view class="brand-mark"><text>ct</text></view><view><text class="brand-name">CardTalk</text><text class="brand-caption">家庭辅助沟通</text></view></view>
      <view class="top-actions"><text class="local-indicator">● 本机保存</text><button role="button" tabindex="0" class="quiet-button" @click="modal = 'history'">本机历史</button><!-- #ifndef MP-WEIXIN --><button role="button" tabindex="0" class="quiet-button" @click="openFamily()">家庭管理</button><button role="button" tabindex="0" class="quiet-button" @click="openAnalytics">分析报告</button><!-- #endif --><button role="button" tabindex="0" class="quiet-button" @click="openSettings">设置</button></view>
    </view>
    <view v-if="storageError" class="persistent-warning" role="alert">{{ storageError }}</view>
    <view class="workspace">
      <view class="intro">
        <view><text class="eyebrow">让每一个想法，被听见</text><text class="page-title">{{ room ? '今天，你想说什么？' : '把想法说给你听。' }}</text><text class="intro-copy">{{ room ? '选几张卡片，按自己的节奏表达。' : '从生活里熟悉的卡片开始，家人一起听、一起理解。' }}</text></view>
        <button role="button" tabindex="0" v-if="!room" class="primary-button start-button" @click="openNew">开始沟通 <text>↗</text></button>
        <button role="button" tabindex="0" v-else class="end-button" @click="finishRoom">结束聊天室</button>
      </view>
      <view v-if="room" class="room-context"><view class="context-leading"><text class="context-dot"></text><image v-if="room.context.profileSnapshot?.image" class="context-image" :src="room.context.profileSnapshot.image" mode="aspectFit" :aria-label="`${room.context.communicatorName}图片`"/><text>{{ room.context.communicatorName }}</text><text class="context-separator">/</text><image v-if="room.context.sceneSnapshot?.image" class="context-image" :src="room.context.sceneSnapshot.image" mode="aspectFit" :aria-label="`${scene?.name}图片`"/><text>{{ scene?.name || '不限场景' }}</text></view><text>和 {{ room.context.participants.map(p => p.name).join('、') || '现场参与者' }} 一起 · {{ sourceLabel(room.context.source) }}</text></view>
      <view v-else class="welcome-strip"><text class="welcome-number">01</text><text>开始一次沟通后，选择卡片、确认候选，再把表达说出来。</text><text class="welcome-note">儿童与成人都可以体验</text></view>

      <view class="board-layout">
        <view class="card-board" id="card-board">
          <view class="section-header"><text class="section-title">随时可以说</text><text class="section-caption">这些卡片一直在这里</text></view>
          <scroll-view class="core-scroll" scroll-x><view class="core-row"><button role="button" tabindex="0" v-for="card in CORE_CARDS" :key="card.id" class="core-card" :class="{ 'boundary-card': ['not', 'stop', 'pain'].includes(card.icon) }" :aria-label="`选择${card.label}`" @click="pick(card)"><image class="core-art" :src="art(card)" mode="aspectFit"/><text>{{ card.label }}</text></button></view></scroll-view>
          <view class="library-toolbar"><view class="scene-options"><button role="button" tabindex="0" :class="{active:cardCollection==='all'}" @click="cardCollection='all'">全部卡片</button><button role="button" tabindex="0" :class="{active:cardCollection==='favorites'}" @click="cardCollection='favorites'">收藏卡片</button><button role="button" tabindex="0" :class="{active:cardCollection==='recent'}" @click="cardCollection='recent'">最近使用</button></view><button role="button" tabindex="0" class="quiet-button" @click="openLibrary">常用表达</button></view><view class="category-search"><scroll-view class="category-scroll" scroll-x><view class="category-row"><button role="button" tabindex="0" class="category-button" :class="{ active: category === 'all' }" :aria-pressed="category === 'all'" @click="category = 'all'">全部</button><button role="button" tabindex="0" v-for="item in CATEGORIES" :key="item.id" class="category-button" :class="{ active: category === item.id }" :aria-pressed="category === item.id" @click="category = item.id">{{ item.name }}</button></view></scroll-view><view class="search-box"><text>⌕</text><input v-model="query" placeholder="找一张卡片" aria-label="搜索卡片" /></view></view>
          <view v-if="recommendedCards.length" class="scene-recommendations"><text class="section-caption">场景推荐 · 通用卡片始终可选</text><view class="recommendation-row"><button role="button" tabindex="0" v-for="card in recommendedCards" :key="card.id" class="recommendation-card" :disabled="!!room?.draft.confirmedTurnId" :aria-label="`推荐${card.label}`" @click="pick(card)"><image :src="art(card)" mode="aspectFit"/><text>{{ card.label }}</text></button></view></view><view class="card-grid"><view v-for="card in visibleCards" :key="card.id" class="card-grid-item"><button role="button" tabindex="0" class="word-card" :class="`group-${card.category}`" :disabled="!!room?.draft.confirmedTurnId" :aria-label="`选择${card.label}`" @click="pick(card)"><view class="card-art-wrap"><image class="card-art" :src="art(card)" mode="aspectFit"/></view><text class="word-label">{{ card.label }}</text><text v-if="room?.draft.cards.some(c => c.id === card.id)" class="selection-count">{{ room.draft.cards.filter(c => c.id === card.id).length }}</text></button><button role="button" tabindex="0" class="favorite-card" :class="{active:state.library?.favoriteCardIds.includes(card.id)}" :aria-label="`${state.library?.favoriteCardIds.includes(card.id)?'取消收藏':'收藏'}${card.label}`" :aria-pressed="!!state.library?.favoriteCardIds.includes(card.id)" @click="favorite(card)">{{ state.library?.favoriteCardIds.includes(card.id)?'★ 已收藏':'☆ 收藏' }}</button></view></view>
          <view v-if="!visibleCards.length" class="empty-state">没有找到这张卡片，试试其他词语或分类。</view>
        </view>

        <view class="expression-panel" id="expression-panel">
          <view class="section-header"><text class="section-title">我的表达</text><text class="count-label">{{ room?.draft.cards.length || 0 }} / 20</text></view>
          <view v-if="!room?.draft.cards.length" class="expression-empty"><view class="empty-dialog-art"><text>…</text></view><text class="empty-title">想法，可以慢慢拼出来。</text><text>点选左侧卡片，顺序不用完美。<br/>你始终可以说“不”。</text></view>
          <view v-else class="selected-tray"><button role="button" tabindex="0" v-for="(card, index) in room.draft.cards" :key="`${index}-${card.id}`" class="selected-word" :disabled="!!room.draft.confirmedTurnId || !!preparedPhrase" :aria-label="`移除第${index + 1}张${card.label}`" @click="remove(index)"><image :src="art(card)" mode="aspectFit"/><text>{{ card.label }}</text><text v-if="!room.draft.confirmedTurnId" class="remove-mark">×</text></button></view>
          <view v-if="room?.draft.cards.length && !currentTurn && !preparedPhrase" class="draft-actions"><button role="button" tabindex="0" class="text-button" @click="remove(room.draft.cards.length - 1)">撤销一张</button><button role="button" tabindex="0" class="text-button" @click="clear">清空</button><button role="button" tabindex="0" class="text-button" @click="showRaw">使用原始表达</button></view>
          <view v-if="preparedPhrase && !currentTurn" class="saved-phrase-preview"><text class="section-caption">常用表达 · 请重新确认</text><text class="confirmed-text">{{ preparedPhrase.text }}</text><button role="button" tabindex="0" class="primary-button full-button" @click="confirmPhrase">确认使用这句表达</button><button role="button" tabindex="0" class="text-button" @click="clear">返回重新选卡</button></view><view v-if="error" class="message error-message" role="alert">{{ error }}</view><view v-if="notice" class="message notice-message" role="status">{{ notice }}</view>
          <text v-if="selectedEmotion && room?.draft.cards.length && !preparedPhrase && currentTurn?.kind !== 'saved'" class="emotion-hint">{{ selectedEmotion.suppressionReason === 'negation' ? '选卡包含否定，将使用自然语气' : selectedEmotion.conflict ? '选中了多种情绪，将使用自然语气' : `表达语气：${emotionLabels[selectedEmotion.emotion]}` }}</text><text v-if="!state.settings.aiExpansionEnabled && !currentTurn" class="generation-note">AI 扩写已关闭，可直接使用原始表达。</text><view v-if="!currentTurn && !preparedPhrase && state.settings.aiExpansionEnabled" class="generation-controls"><button role="button" tabindex="0" class="primary-button generate-button" :disabled="!room?.draft.cards.length || busy" @click="generate">{{ busy ? '正在整理表达…' : '帮我整理成一句话' }}<text v-if="!busy">→</text></button><button role="button" tabindex="0" v-if="busy" class="text-button cancel-button" @click="cancelGeneration">取消，保留选卡</button><text class="generation-note">联网 AI 扩写 · 由你确认后才发声</text></view>
          <view v-if="room?.draft.expansion && !currentTurn && !preparedPhrase && state.settings.aiExpansionEnabled" class="candidate-area"><text class="candidate-heading">哪一句是你的意思？</text><view v-for="(candidate,index) in room.draft.expansion.candidates" :key="candidate.id" class="candidate-item"><button role="button" tabindex="0" class="candidate-button" @click="confirm(candidate.id)"><text class="candidate-index">{{ index + 1 }}</text><text>{{ candidate.text }}</text><text class="candidate-arrow">↗</text></button><button role="button" tabindex="0" class="candidate-edit text-button" @click="editChoice(candidate.id,candidate.text)">修改后确认</button><view v-if="editCandidate === candidate.id" class="candidate-editor"><input v-model="editedText" maxlength="250" aria-label="修改候选表达"/><button role="button" tabindex="0" class="small-primary" @click="confirm(candidate.id,true)">确认修改</button></view></view><text v-if="room.draft.expansion.needsClarification" class="clarification">{{ room.draft.expansion.clarification }}</text><text v-if="!room.draft.expansion.candidates.length" class="clarification">暂时没有合适的候选。你可以修改卡片，或使用原始表达。</text><button role="button" tabindex="0" class="outline-button" @click="reject">都不合适，保留选卡</button></view>
          <view v-if="currentTurn" class="confirmed-area"><text class="confirmed-label">已确认 · {{ currentTurn.kind === 'raw' ? '原始表达' : currentTurn.kind === 'saved' ? '常用表达' : '由你选择' }}</text><text class="confirmed-text">{{ currentTurn.finalText }}</text><text v-if="currentTurn.wasEdited && state.settings.emotionRecognitionEnabled" class="emotion-hint">表达经过修改，将以自然语气朗读。</text><button role="button" tabindex="0" class="primary-button speak-button" @click="playCurrent()">{{ speaking ? '重新播放' : '说出这句话' }} <text>◖))</text></button><view class="playback-controls"><button role="button" tabindex="0" v-if="speaking" class="text-button" @click="stop">停止声音</button><button role="button" tabindex="0" v-if="currentTurn.kind === 'generated'" class="text-button" @click="playCurrent(true)">播放原始卡片</button><text v-if="speechStatus" role="status">{{ speechStatus }}</text></view><button role="button" tabindex="0" class="text-button save-phrase-button" @click="savePhrase(room!,currentTurn)">保存为常用表达</button><view class="feedback-area"><text>家人理解了吗？<text class="optional-tag">可跳过</text></text><view class="feedback-options"><button role="button" tabindex="0" v-for="item in feedbackValues" :key="item.id" :class="{ active: currentTurn.feedback?.value === item.id }" @click="feedback(room!,currentTurn,item.id)">{{ item.label }}</button></view><input class="feedback-name" v-model="feedbackBy" maxlength="100" placeholder="反馈者称呼，可留空" aria-label="反馈者称呼"/><input v-model="feedbackNote" class="feedback-name" maxlength="500" aria-label="理解反馈备注" placeholder="反馈备注，可留空"/><button role="button" tabindex="0" class="text-button" :disabled="!currentTurn.feedback" @click="saveFeedbackNote(room!,currentTurn,feedbackNote)">保存反馈备注</button><text class="feedback-source">{{ feedbackBy ? `由 ${feedbackBy} 反馈` : '未注明反馈者；不会推断为某位家人' }}</text></view><button role="button" tabindex="0" class="outline-button next-button" @click="next">开始下一句 →</button></view>
          <view class="panel-footer"><text>记录保存在这台设备</text><text>{{ quota ? `今日扩写剩余 ${quota.remaining} 次` : '联网后查看体验额度' }}</text></view>
        </view>
      </view>
      <view class="workspace-footer"><text>每一种表达，都值得被认真听见。</text><button role="button" tabindex="0" class="text-button" @click="modal = 'features'">了解家庭功能 ↗</button></view>
    </view>

    <button v-if="room && !modal" role="button" tabindex="0" class="mobile-expression-link" @click="scrollToExpression"><text>{{ currentTurn ? '已确认表达' : `已选 ${room.draft.cards.length} / 20 张` }}</text><text>查看表达 ↓</text></button>
    <view v-if="modal" class="modal-backdrop" @click.self="closeModal"><view class="modal-card" :class="{ 'history-modal': modal === 'history' || modal === 'analytics', 'family-modal': modal === 'family' }" role="dialog" aria-modal="true" :aria-label="modal === 'new' ? '创建聊天室' : modal === 'history' ? '本机历史' : modal === 'settings' ? '设置' : modal === 'family' ? '家庭管理' : modal === 'analytics' ? '分析报告' : modal === 'library' ? '常用表达' : modal === 'help' ? '使用指南' : modal === 'account' ? '账户与家庭' : modal === 'resources' ? '公共资源' : '家庭功能'"><view class="modal-header"><text>{{ modal === 'new' ? '开始一次新的沟通' : modal === 'history' ? '本机沟通历史' : modal === 'settings' ? 'AI 与本机设置' : modal === 'family' ? '家庭管理' : modal === 'analytics' ? '本机沟通分析' : modal === 'library' ? '常用表达' : modal === 'help' ? '使用指南' : modal === 'account' ? '账户与家庭' : modal === 'resources' ? '公共资源' : '按需要开启家庭功能' }}</text><button role="button" tabindex="0" class="close-button" aria-label="关闭" @click="closeModal">×</button></view>
      <view v-if="modal === 'new'" class="form-content"><text class="modal-copy">选择体验预设，或使用已有档案。当前状态由你填写，不从家人观察自动推断。</text><view v-if="familyCapabilities.profileManagement && availableProfiles.length" class="form-field"><text>选择使用人档案</text><view class="scene-options"><button role="button" tabindex="0" :class="{ active: !newProfileId }" @click="selectProfile('')">使用体验预设</button><button role="button" tabindex="0" v-for="p in availableProfiles" :key="p.id" :class="{ active: newProfileId === p.id }" @click="selectProfile(p.id)"><image v-if="p.image" class="context-choice-image" :src="p.image" mode="aspectFit"/><text>{{ p.name }}</text></button></view></view><view class="preset-options"><button role="button" tabindex="0" v-for="preset in PRESETS" :key="preset.id" :class="{ active: newPreset === preset.id }" @click="newPreset = preset.id; newProfileId = ''"><text>{{ preset.name }}</text><text>{{ preset.description }}</text></button></view><view class="form-field"><text>这次是谁在表达？</text><input v-model="newName" maxlength="40" placeholder="称呼，可留空使用体验预设" aria-label="表达者称呼"/></view><view class="form-field"><text>在哪里？</text><view class="scene-options"><button role="button" tabindex="0" v-for="s in availableScenes" :key="s.id" :class="{ active: newScene === s.id }" @click="newScene = s.id"><image v-if="s.image" class="context-choice-image" :src="s.image" mode="aspectFit"/><text>{{ s.name }}</text></button></view></view><view v-if="newProfileId" class="form-field"><text>当前状态（选填，仅用于本次沟通）</text><input v-model="newCurrentState" maxlength="300" aria-label="当前状态" placeholder="由本人确认或家人明确提供的当次背景"/><text class="modal-copy">兴趣、性格与表达习惯将作为参考；长期观察和照片不发送给模型。</text></view><view class="form-field"><text>和谁一起？</text><input v-model="participants" maxlength="240" placeholder="如妈妈、爸爸，用逗号分隔" aria-label="沟通参与者"/></view><view class="form-field"><text>这次由谁操作？</text><view class="scene-options"><button role="button" tabindex="0" v-for="s in sources" :key="s.id" :class="{ active: source === s.id }" @click="source = s.id">{{ s.label }}</button></view></view><view v-if="error" class="message error-message">{{ error }}</view><button role="button" tabindex="0" class="primary-button full-button" @click="begin">创建聊天室，开始选卡 →</button></view>

      <view v-if="modal === 'history'" class="history-content"><text v-if="notice.includes('常用表达')" class="message notice-message" role="status">{{notice}}</text><text v-if="storageError" class="message error-message" role="alert">{{storageError}}</text><view v-if="!inspected"><text class="modal-copy">本机记录不会自动上传。清理浏览器或微信数据可能移除这些记录。</text><view class="history-filters"><view class="scene-options"><button role="button" tabindex="0" v-for="value in ['all','children','adult']" :key="value" :class="{ active: historyPreset === value }" @click="historyPreset = value">{{ value === 'all' ? '全部使用人' : presetLabel(value) }}</button></view><picker :range="['全部场景',...historyScenes.map(s=>s.name)]" @change="historyScene = Number($event.detail.value) === 0 ? 'all' : (historyScenes[Number($event.detail.value)-1]?.id || 'all')"><view class="filter-picker">{{ historyScene === 'all' ? '全部场景' : sceneLabel(historyScene) }} ▾</view></picker><picker :range="historyProfiles.map(p=>p.name)" @change="historyProfile = historyProfiles[Number($event.detail.value)]?.id || 'all'"><view class="profile-filter-picker">{{ historyProfiles.find(p=>p.id===historyProfile)?.name || '全部使用人档案' }} ▾</view></picker><input v-model="historyQuery" placeholder="搜索称呼或表达" aria-label="搜索本机历史"/></view><view v-if="!history.length" class="empty-state">还没有符合条件的记录。完成一次沟通后，会在这里看到。</view><button role="button" tabindex="0" v-for="r in history" :key="r.id" class="history-row" @click="inspectedRoomId = r.id"><view><text class="history-name">{{ r.context.communicatorName }} · {{ sceneLabel(r.context.sceneId,r.context.sceneSnapshot) }}</text><text class="history-meta">{{ date(r.startedAt) }} · {{ r.turns.length }} 条表达 · {{ r.status === 'active' ? '进行中' : '已结束' }}</text></view><text>→</text></button></view><view v-else><button role="button" tabindex="0" class="text-button" @click="stop(); inspectedRoomId = null">← 返回历史列表</button><view class="history-playback" role="status"><button role="button" tabindex="0" v-if="speaking" class="outline-button" @click="stop">停止声音</button><text v-if="speechStatus">{{ speechStatus }}</text><text v-if="error" class="error-copy" role="alert">{{ error }}</text></view><view class="history-detail-title"><view v-if="inspected.context.profileSnapshot?.image || inspected.context.sceneSnapshot?.image" class="history-context-images"><image v-if="inspected.context.profileSnapshot?.image" class="history-context-image" :src="inspected.context.profileSnapshot.image" mode="aspectFit" :aria-label="`${inspected.context.communicatorName}图片`"/><image v-if="inspected.context.sceneSnapshot?.image" class="history-context-image" :src="inspected.context.sceneSnapshot.image" mode="aspectFit" :aria-label="`${inspected.context.sceneSnapshot.name}图片`"/></view><text>{{ inspected.context.communicatorName }} · {{ sceneLabel(inspected.context.sceneId,inspected.context.sceneSnapshot) }}</text><text>{{ date(inspected.startedAt) }} · {{ sourceLabel(inspected.context.source) }}</text></view><view v-for="turn in inspected.turns" :key="turn.id" class="history-turn"><text class="history-turn-time">{{ date(turn.createdAt) }} · {{ sourceLabel(turn.source) }}</text><text class="history-turn-final">{{ turn.finalText }}</text><view class="history-card-images"><image v-for="(card,i) in turn.cardSnapshot" :key="i" :src="art(card)" mode="aspectFit" :aria-label="card.label"/></view><text class="history-turn-raw">原始选卡：{{ turn.cardSnapshot.map(c => c.label).join('、') }}</text><text v-if="turn.expansion" class="history-turn-raw">AI候选：{{ turn.expansion.candidates.map(c => c.text).join(' / ') }}</text><text class="history-turn-raw">{{ turn.kind === 'raw' ? '原始表达' : turn.kind === 'saved' ? '常用表达确认' : turn.wasEdited ? '候选修改后确认' : '候选确认' }} · {{ turn.playbackEvents.length ? playbackLabel(turn.playbackEvents[turn.playbackEvents.length-1]!.status) : '尚未播放' }} · {{ turn.feedback ? feedbackValues.find(f=>f.id===turn.feedback?.value)?.label || '未记录反馈' : '未记录反馈' }}{{ turn.feedback ? `（${turn.feedback.by}）` : '' }}</text><view class="history-process"><text v-if="!turn.processSnapshot">过程未采集</text><template v-else><text>从首次选卡到确认：{{processDuration(turn)}}（包含等待时间）</text><text v-if="!turn.processSnapshot.events.length">直接确认表达，未使用候选扩写。</text><view v-for="event in turn.processSnapshot.events" :key="event.id" class="process-event"><text>{{processLabels[event.type]}}</text><text>{{date(event.at)}}</text></view></template></view><text v-if="turn.feedback?.note" class="history-turn-raw">反馈备注：{{turn.feedback.note}}</text><view v-if="turn.feedback" class="history-note-editor"><input :value="historyNotes[turn.id] ?? turn.feedback.note ?? ''" @input="editHistoryNote(turn.id,$event)" maxlength="500" :aria-label="`反馈备注 ${turn.finalText}`" placeholder="更正反馈备注"/><button role="button" tabindex="0" class="text-button" @click="saveFeedbackNote(inspected!,turn,historyNotes[turn.id] ?? turn.feedback.note ?? '')">保存这条备注</button></view><view class="history-turn-actions"><button role="button" tabindex="0" class="text-button" @click="savePhrase(inspected!,turn)">保存为常用表达</button><button role="button" tabindex="0" class="text-button" @click="playTurn(inspected!,turn)">重新播放</button><button role="button" tabindex="0" v-for="item in feedbackValues" :key="item.id" class="text-button" :class="{ selected: turn.feedback?.value === item.id }" @click="feedback(inspected!,turn,item.id)">{{ item.label }}</button></view></view><view v-if="inspected.draft.cards.length && !inspected.draft.confirmedTurnId" class="history-turn"><text>未确认选卡：{{ inspected.draft.cards.map(c=>c.label).join('、') }}</text><text class="history-meta">这部分没有作为已确认表达计入记录。</text></view><view v-if="inspected.status === 'active'" class="message notice-message">这是当前未结束聊天室。关闭历史即可继续。</view><button role="button" tabindex="0" v-else class="delete-button" @click="deleteHistory(inspected)">删除这条本机记录</button></view></view>

      <!-- #ifndef MP-WEIXIN --><view v-if="modal === 'family'" class="family-content"><text v-if="storageError" class="message error-message" role="alert">{{ storageError }}</text><view v-if="speaking || speechStatus || error" class="history-playback"><button role="button" tabindex="0" v-if="speaking" class="outline-button" @click="stop">停止试听</button><text role="status">{{ speechStatus }}</text><text v-if="error" class="error-copy" role="alert">{{ error }}</text></view><FamilyManager :state="state" :capabilities="familyCapabilities" :tab="familyTab" :persist="saveFamily" @preview="previewCard" /></view><!-- #endif --><view v-if="modal === 'settings'" class="form-content"><button role="button" tabindex="0" class="outline-button help-entry" @click="modal='help'">使用指南</button><text class="section-title">全局沟通偏好</text><text class="modal-copy">未关联使用人档案时直接使用这些设置。个人偏好在家庭管理中设置。</text><CommunicationPreferencesEditor :model-value="globalPreferences" @update:model-value="savePreferences" /><text v-if="storageError || error" role="alert" class="error-copy">{{ storageError || error }}</text><view class="ai-settings"><text class="section-title">AI 功能</text>
        <view class="ai-setting-row"><view><text class="setting-title">AI 扩写</text><text class="setting-description">用大模型将选卡整理成更自然的表达，由你确认后发声。</text></view><button role="switch" tabindex="0" class="setting-switch" :class="{ enabled: state.settings.aiExpansionEnabled }" :aria-checked="state.settings.aiExpansionEnabled" aria-label="AI扩写" @click="toggleAiSetting('aiExpansionEnabled')"><text>{{ state.settings.aiExpansionEnabled ? '开启' : '关闭' }}</text><view class="switch-knob"/></button></view>
        <view class="ai-setting-row"><view><text class="setting-title">语音合成</text><text class="setting-description">开启使用科大讯飞拟人合成；关闭使用设备发声。</text></view><button role="switch" tabindex="0" class="setting-switch" :class="{ enabled: state.settings.speechSynthesisEnabled }" :aria-checked="state.settings.speechSynthesisEnabled" aria-label="语音合成" @click="toggleAiSetting('speechSynthesisEnabled')"><text>{{ state.settings.speechSynthesisEnabled ? '开启' : '关闭' }}</text><view class="switch-knob"/></button></view>
        <view class="ai-setting-row"><view><text class="setting-title">情绪识别</text><text class="setting-description">依据选中的情绪卡，让扩写语气与讯飞合成带上相应情绪。</text></view><button role="switch" tabindex="0" class="setting-switch" :class="{ enabled: state.settings.emotionRecognitionEnabled }" :aria-checked="state.settings.emotionRecognitionEnabled" aria-label="情绪识别" @click="toggleAiSetting('emotionRecognitionEnabled')"><text>{{ state.settings.emotionRecognitionEnabled ? '开启' : '关闭' }}</text><view class="switch-knob"/></button></view>
        <text class="modal-copy">情绪识别只依据“开心、难过、生气、害怕”卡片，不猜测本人情绪。选中多种情绪时使用自然语气。关闭后仍保留卡片原意，只停止附加情绪语气。</text>
        <text class="modal-copy">讯飞需要联网及服务端配置，情绪合成还取决于已开通的音色。Web 的设备声音由系统提供；微信小程序不能直接调用系统朗读新句子，关闭合成时可播放预置卡片声音或展示文字。</text>
      </view><view class="service-status"><text>扩写服务：{{ service ? service.llmConfigured ? 'DeepSeek 已配置' : '尚未配置 API 密钥' : '连接未就绪' }}</text><text>讯飞拟人合成：{{ service?.ttsConfigured ? '已配置' : '尚未配置' }}</text><text>情绪声音：{{ service?.ttsEmotionSupported ? '已配置支持情绪的音色' : '尚未配置支持情绪的音色' }}</text><text v-if="state.settings.speechSynthesisEnabled && state.settings.emotionRecognitionEnabled && !service?.ttsEmotionSupported" class="error-copy">当前音色尚未启用情绪合成。带情绪的扩写仍可使用，语音需配置相应音色或关闭情绪识别。</text><text v-if="quota">今日扩写：剩余 {{ quota.remaining }} / {{ quota.limit }} 次</text><text v-if="serviceError" class="error-copy">{{ serviceError }}</text><button role="button" tabindex="0" class="text-button" @click="refreshService">重新检查服务</button></view><!-- #ifndef MP-WEIXIN --><button role="button" tabindex="0" class="outline-button" @click="openAccount">账户与家庭成员</button><button role="button" tabindex="0" class="outline-button" @click="modal='resources'">公共资源</button><!-- #endif --><view class="data-section"><text class="section-title">本机数据</text><text class="modal-copy">记录只保存在这台设备。导出的文件包含沟通内容，请自行妥善保存。</text><button role="button" tabindex="0" class="outline-button" @click="exportBackup">{{ protectOriginal ? '导出原始备份' : '导出本机备份' }}</button><button role="button" tabindex="0" v-if="protectOriginal" class="delete-button" @click="resetCorrupt">备份后重置损坏记录</button><!-- #ifndef MP-WEIXIN --><BackupImport :disabled="!!room" @import="importBackup"/><DataManager :state="state" :persist="persistDataDeletion" :protected-original="protectOriginal"/><!-- #endif --></view></view>

      <!-- #ifndef MP-WEIXIN -->
      <PublicResources v-if="modal === 'resources'" :state="state" :persist="persistPublicResources" :can-install-cards="familyCapabilities.customCards" :can-install-scenes="familyCapabilities.scenarioManagement"/><AccountManager v-if="modal === 'account'" :state="state" :persist="saveFamily" :profile-options="availableProfiles.map(p=>({id:p.id,name:p.name}))"/>
      <CommunicationAnalytics v-if="modal === 'analytics'" :state="state" :enabled="analyticsReady && analyticsEnabled" :family-id="familyAccess.selected.value?.family.id" :persist="saveFamily" @inspect="inspectAnalytics"/>
      <!-- #endif -->
      <view v-if="modal === 'help'" class="form-content"><CommunicationHelp/></view>
      <view v-if="modal === 'library'" class="form-content"><text class="modal-copy">{{ room?.context.profileId ? room.context.communicatorName + '的常用表达' : '未关联使用人档案的常用表达' }}。选择后先确认，再主动播放，不会自动发声。</text><text v-if="!room" class="modal-copy">请先开始沟通，才能使用对应的常用表达。</text><text v-if="error || storageError" class="message error-message" role="alert">{{error || storageError}}</text><text v-if="notice" class="message notice-message" role="status">{{notice}}</text><view v-if="!savedPhrases.length" class="empty-state">还没有常用表达。从已确认表达或本机历史中保存一句。</view><view v-for="phrase in savedPhrases" :key="phrase.id" class="saved-phrase-row"><text class="saved-phrase-text">{{phrase.text}}</text><text class="history-meta">保存于 {{date(phrase.createdAt)}}</text><view class="saved-phrase-actions"><button role="button" tabindex="0" class="small-primary" :disabled="!room || !!currentTurn" :aria-label="`使用常用表达 ${phrase.text}`" @click="choosePhrase(phrase.id)">选择这句</button><button role="button" tabindex="0" class="text-button" @click="phraseSource(phrase.id)">查看来源</button><button role="button" tabindex="0" class="text-button" :aria-label="`移除常用表达 ${phrase.text}`" @click="removePhrase(phrase.id)">移除</button></view></view><text v-if="currentTurn" class="modal-copy">当前表达已确认，请先开始下一句。</text></view>
      <view v-if="modal === 'features'" class="form-content"><text class="modal-copy">家庭功能分别开启，已有沟通与本机历史保留。{{familyAccess.selected.value?'当前使用所选家庭的独立权益。':'当前为本机开发试用，不代表已完成购买授权。'}}</text><view v-for="feature in [{key:'profiles',name:'使用人管理',text:'管理多位家庭成员及个性化沟通资料',enabled:familyCapabilities.profileManagement},{key:'cards',name:'自定义卡片',text:'添加文字、图片、分类和场景关联',enabled:familyCapabilities.customCards},{key:'scenes',name:'场景管理',text:'描述常见活动与物品，配置推荐卡片',enabled:familyCapabilities.scenarioManagement}]" :key="feature.key" class="feature-row"><view><text>{{ feature.name }}</text><text>{{ feature.text }}</text></view><button v-if="familyPlatform" role="button" tabindex="0" class="text-button" @click="openFamily(feature.key as 'profiles'|'cards'|'scenes')">{{ feature.enabled ? '已开启 →' : '查看 →' }}</button><text v-else class="feature-status">体验版暂未开放</text></view><view class="feature-row"><view><text>分析与报告</text><text>回顾表达喜好、方式和有反馈的沟通效果</text></view><button v-if="familyPlatform" role="button" tabindex="0" class="text-button" @click="openAnalytics">{{ analyticsEnabled ? '已开启 →' : '查看 →' }}</button><text v-else class="feature-status">体验版暂未开放</text></view><text class="modal-copy">家庭资料保存在本机；联网扩写只发送所选卡片及相关资料，照片与观察记录不上传。额度用尽仍可使用基础表达。</text></view>
    </view></view>
  </view>
</template>
