import { appendExpressionEvent } from './process'
import { DEFAULT_COMMUNICATION_PREFERENCES } from './preferences'
import { ensureStateCapacity } from './capacity'
import { buildRoomContext } from './family'
import type { Card, Context, Expansion, FeedbackValue, PlaybackStatus, Room, State, Turn } from './types'
import { isCard, isContext, isExpansion, matchesCardSources } from './validation'
export { validateState } from './validation'
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T
const now = () => new Date().toISOString()
const id = () => `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`
const draft = (version = 0): Room['draft'] => ({ cards: [], version, expansion: null, confirmedText: null, selectedCandidateId: null, confirmedTurnId: null })
export function createState(): State { return { version: 1, reports: [], library: { favoriteCardIds: [], phrases: [] }, family: { profiles: [], cards: [], scenes: [] }, rooms: [], activeRoomId: null, settings: { preferences: { ...DEFAULT_COMMUNICATION_PREFERENCES }, fontSize: 'normal', rate: 0.9, preset: 'children', aiExpansionEnabled: true, speechSynthesisEnabled: false, emotionRecognitionEnabled: false } } }
export function getActiveRoom(state: State): Room | null { return state.rooms.find(room => room.id === state.activeRoomId && room.status === 'active') ?? null }
function active(state: State): Room { const room = getActiveRoom(state); if (!room) throw new Error('请先创建或恢复沟通会话'); return room }
function change(state: State, action: (room: Room) => void): State { active(state); const next = copy(state); action(active(next)); return ensureStateCapacity(next) }
function invalidate(room: Room) { delete room.draft.savedPhraseId; room.draft.version++; room.draft.expansion = null; room.draft.confirmedText = null; room.draft.selectedCandidateId = null; room.draft.confirmedTurnId = null }
function editable(room: Room) { if (room.draft.confirmedTurnId) throw new Error('请先开始下一句'); }
export function createRoom(state: State, context: Context, currentState = context.currentState ?? context.profileSnapshot?.currentState ?? ''): State {
  if (getActiveRoom(state)) throw new Error('请先结束或继续当前会话')
  context = buildRoomContext(state, context, currentState)
  if (!isContext(context)) throw new Error('会话资料格式不正确')
  if (state.rooms.length >= 500) throw new Error('本机记录已达上限，请先导出并整理记录')
  const next = copy(state), room: Room = { id: id(), context: copy(context), startedAt: now(), endedAt: null, status: 'active', turns: [], draft: draft() }
  next.rooms.push(room); next.activeRoomId = room.id; return ensureStateCapacity(next)
}
export function snapshotCard(card:Card):Card { return copy({id:card.id,label:card.label,category:card.category,icon:card.icon,kind:card.kind,...(card.description===undefined?{}:{description:card.description}),...(card.image===undefined?{}:{image:card.image}),...(card.provenance===undefined?{}:{provenance:card.provenance})}) }
export function addCard(state: State, card: Card): State { return change(state, room => { editable(room); if (!isCard(card)) throw new Error('卡片格式不正确'); if (card.id.startsWith('custom-') && !state.family.cards.some(item => item.id === card.id && !item.archived)) throw new Error('此卡片已归档或不存在'); if (room.draft.cards.length >= 20) throw new Error('每次表达最多选择20张卡片'); room.draft.process ??= { startedAt: now(), events: [] }; room.draft.cards.push(snapshotCard(card)); invalidate(room) }) }
export function removeCard(state: State, index: number): State { return change(state, room => { editable(room); if (!Number.isInteger(index) || index < 0 || index >= room.draft.cards.length) throw new Error('卡片位置无效'); room.draft.cards.splice(index, 1); invalidate(room) }) }
export function clearDraft(state: State): State { return change(state, room => { editable(room); room.draft = draft(room.draft.version + 1) }) }
export function rejectExpansion(state: State, reason: 'user' | 'invalidation' = 'user'): State { return change(state, room => { editable(room); if (reason === 'user' && room.draft.expansion?.candidates.length) appendExpressionEvent(room, 'candidates-rejected', room.draft.expansion.requestId); invalidate(room) }) }
export function applyExpansion(state: State, expansion: Expansion, expectedRoomId?: string): State {
  const room = getActiveRoom(state)
  if (!room || (expectedRoomId !== undefined && expectedRoomId !== room.id) || room.draft.confirmedTurnId || room.draft.savedPhraseId || !room.draft.cards.length || expansion.inputVersion !== room.draft.version) return state
  if (!isExpansion(expansion) || !matchesCardSources(expansion, room.draft.cards)) throw new Error('扩写结果格式不正确')
  return change(state, current => { if (!current.draft.process?.events.some(event => event.type === 'generation-succeeded' && event.requestId === expansion.requestId && event.inputVersion === expansion.inputVersion)) appendExpressionEvent(current, 'generation-succeeded', expansion.requestId); if (expansion.needsClarification && !current.draft.process?.events.some(event => event.type === 'clarification-requested' && event.requestId === expansion.requestId && event.inputVersion === expansion.inputVersion)) appendExpressionEvent(current, 'clarification-requested', expansion.requestId); current.draft.expansion = copy(expansion) })
}
function save(room: Room, text: string, candidateId?: string) {
  if (room.turns.length >= 1000) throw new Error('本次会话记录已达上限，请结束并创建新会话')
  const candidate = room.draft.expansion?.candidates.find(item => item.id === candidateId)
  const turn: Turn = { id: id(), createdAt: now(), cardSnapshot: copy(room.draft.cards), contextSnapshot: copy(room.context), finalText: text, source: room.context.source, wasEdited: candidate ? text !== candidate.text : false, kind: candidate ? 'generated' : 'raw', playbackEvents: [], feedback: null }
  if (room.draft.process) turn.processSnapshot = { ...copy(room.draft.process), confirmedAt: turn.createdAt }
  if (candidate) { turn.expansion = copy(room.draft.expansion!); turn.selectedCandidateId = candidate.id }
  delete room.draft.savedPhraseId
  room.turns.push(turn); room.draft.confirmedText = text; room.draft.confirmedTurnId = turn.id; room.draft.selectedCandidateId = candidateId ?? null
}
export function confirmCandidate(state: State, candidateId: string, editedText?: string): State {
  const room = active(state); if (room.draft.confirmedTurnId) return state
  const candidate = room.draft.expansion?.candidates.find(item => item.id === candidateId)
  if (!candidate) throw new Error('请选择当前表达的候选')
  const text = (editedText ?? candidate.text).trim(); if (!text || text.length > 500) throw new Error('表达应为1至500个字符')
  return change(state, current => save(current, text, candidateId))
}
export function recordRawExpression(state: State): State { const room = active(state); if (room.draft.confirmedTurnId) return state; if (!room.draft.cards.length) throw new Error('请先选择卡片'); return change(state, current => save(current, current.draft.cards.map(card => card.label).join(' '))) }
export function nextTurn(state: State): State { return change(state, room => { if (!room.draft.confirmedTurnId) throw new Error('请先确认或展示原始表达'); room.draft = draft(room.draft.version + 1) }) }
export function endRoom(state: State): State { const next = change(state, room => { room.status = 'ended'; room.endedAt = now(); room.draft.expansion = null; delete room.draft.savedPhraseId; room.draft.version++ }); next.activeRoomId = null; return next }
function updateTurn(state: State, roomId: string, turnId: string, action: (turn: Turn) => void): State { const next = copy(state); const turn = next.rooms.find(room => room.id === roomId)?.turns.find(item => item.id === turnId); if (!turn) throw new Error('找不到这条表达记录'); action(turn); return ensureStateCapacity(next) }
export function addPlaybackEvent(state: State, roomId: string, turnId: string, status: PlaybackStatus): State { return updateTurn(state, roomId, turnId, turn => { if (turn.playbackEvents.length >= 100) throw new Error('本条表达的播放记录已达上限'); turn.playbackEvents.push({ id: id(), status, at: now() }) }) }
export function setFeedback(state: State, roomId: string, turnId: string, value: FeedbackValue, by: string, note?: string): State { if (!by.trim() || by.length > 100 || (note?.length ?? 0) > 500) throw new Error('反馈信息过长或填写者为空'); return updateTurn(state, roomId, turnId, turn => { turn.feedback = { value, by: by.trim(), at: now(), ...(note ? { note } : {}) } }) }
export function removeRoom(state: State, roomId: string): State { if (state.activeRoomId === roomId) throw new Error('请先结束会话再删除'); const next = copy(state); next.rooms = next.rooms.filter(room => room.id !== roomId); return next }

export function prepareSavedPhrase(state: State, phraseId: string): State {
  const room = active(state); editable(room)
  const phrase = state.library?.phrases.find(item => item.id === phraseId)
  if (!phrase || phrase.profileId !== room.context.profileId) throw new Error('常用短句不存在或不属于当前使用人')
  return change(state, current => { current.draft = { ...draft(current.draft.version + 1), cards: copy(phrase.cardSnapshot), process: { startedAt: now(), events: [] }, savedPhraseId: phraseId } })
}
export function confirmSavedPhrase(state: State, phraseId: string): State {
  const room = active(state)
  if (room.draft.confirmedTurnId) return state
  const phrase = state.library?.phrases.find(item => item.id === phraseId)
  if (!phrase || phrase.profileId !== room.context.profileId || room.draft.savedPhraseId !== phraseId || JSON.stringify(room.draft.cards) !== JSON.stringify(phrase.cardSnapshot)) throw new Error('常用短句已变更，请重新选择并确认')
  return change(state, current => {
    save(current, phrase.text)
    const turn = current.turns.at(-1)!
    turn.kind = 'saved'; turn.sourceSavedPhraseId = phraseId
  })
}
