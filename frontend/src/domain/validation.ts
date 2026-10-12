import type { Card, Context, Expansion, State } from './types'
type Obj = Record<string, unknown>
const obj = (v: unknown): v is Obj => typeof v === 'object' && v !== null && !Array.isArray(v)
const str = (v: unknown, max = 500, empty = false): v is string => typeof v === 'string' && v.length <= max && (empty || v.trim().length > 0)
const integer = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v >= 0
const one = (v: unknown, values: string[]) => typeof v === 'string' && values.includes(v)
const date = (v: unknown) => str(v, 40) && !Number.isNaN(Date.parse(v))
const arr = (v: unknown, max: number, check: (x: unknown) => boolean): v is unknown[] => Array.isArray(v) && v.length <= max && v.every(check)
const nullable = (v: unknown, check: (x: unknown) => boolean) => v === null || check(v)
const uniqueIds = (items: unknown[]) => new Set(items.map(item => (item as Obj).id)).size === items.length
const source = (v: unknown) => one(v, ['independent', 'assisted', 'unknown'])
export function isCard(v: unknown): v is Card { return obj(v) && str(v.id, 100) && str(v.label, 50) && str(v.category, 100) && str(v.icon, 100) && one(v.kind, ['word', 'phrase']) && (v.description === undefined || str(v.description, 500)) }
export function isContext(v: unknown): v is Context { return obj(v) && one(v.preset, ['children', 'adult']) && nullable(v.sceneId, x => str(x, 100)) && str(v.communicatorName, 100) && source(v.source) && arr(v.participants, 20, x => obj(x) && str(x.id, 100) && str(x.name, 100)) && uniqueIds(v.participants) }
export function matchesCardSources(expansion: Expansion, cards: Card[]): boolean {
  return expansion.candidates.every(candidate => candidate.sourceCardIds.length === cards.length && candidate.sourceCardIds.every((id, index) => id === cards[index]!.id))
}
export function isExpansion(v: unknown): v is Expansion {
  return obj(v) && str(v.requestId, 100) && integer(v.inputVersion) && arr(v.candidates, 3, x => obj(x) && str(x.id, 100) && str(x.text) && arr(x.sourceCardIds, 20, y => str(y, 100))) && uniqueIds(v.candidates) && typeof v.needsClarification === 'boolean' && nullable(v.clarification, x => str(x, 500, true)) && str(v.promptVersion, 100) && integer(v.remaining)
}
function turn(v: unknown): boolean {
  if (!obj(v) || !str(v.id, 100) || !date(v.createdAt) || !arr(v.cardSnapshot, 20, isCard) || !v.cardSnapshot.length || !isContext(v.contextSnapshot) || !str(v.finalText, 1100) || !source(v.source) || v.source !== v.contextSnapshot.source || typeof v.wasEdited !== 'boolean' || !one(v.kind, ['generated', 'raw'])) return false
  if (!arr(v.playbackEvents, 100, x => obj(x) && str(x.id, 100) && date(x.at) && one(x.status, ['started', 'completed', 'interrupted', 'failed', 'unavailable'])) || !uniqueIds(v.playbackEvents)) return false
  if (!nullable(v.feedback, x => obj(x) && one(x.value, ['understood', 'clarify', 'not-understood', 'unrecorded']) && str(x.by, 100) && date(x.at) && (x.note === undefined || str(x.note)))) return false
  if (v.kind === 'generated') return isExpansion(v.expansion) && str(v.selectedCandidateId, 100) && v.expansion.candidates.some(c => c.id === v.selectedCandidateId) && matchesCardSources(v.expansion, v.cardSnapshot as Card[])
  return v.expansion === undefined && v.selectedCandidateId === undefined && v.wasEdited === false
}
function room(v: unknown): boolean {
  if (!obj(v) || !str(v.id, 100) || !isContext(v.context) || !date(v.startedAt) || !one(v.status, ['active', 'ended']) || !(v.status === 'active' ? v.endedAt === null : date(v.endedAt)) || !arr(v.turns, 1000, turn) || !uniqueIds(v.turns) || !obj(v.draft)) return false
  const d = v.draft
  if (!arr(d.cards, 20, isCard) || !integer(d.version) || !nullable(d.expansion, isExpansion) || !nullable(d.confirmedText, x => str(x, 1100)) || !nullable(d.selectedCandidateId, x => str(x, 100)) || !nullable(d.confirmedTurnId, x => str(x, 100))) return false
  if (d.expansion !== null && ((d.expansion as Expansion).inputVersion !== d.version || !d.cards.length || !matchesCardSources(d.expansion as Expansion, d.cards as Card[]))) return false
  if (d.confirmedTurnId === null) return d.confirmedText === null && d.selectedCandidateId === null
  return v.turns.some(t => obj(t) && t.id === d.confirmedTurnId && t.finalText === d.confirmedText && (t.selectedCandidateId ?? null) === d.selectedCandidateId)
}
export type ValidationResult = { valid: true; state: State } | { valid: false; error: string }
export function validateState(value: unknown): ValidationResult {
  try {
    if (!obj(value) || value.version !== 1 || !arr(value.rooms, 500, room) || !uniqueIds(value.rooms) || !nullable(value.activeRoomId, x => str(x, 100)) || !obj(value.settings) || !one(value.settings.fontSize, ['normal', 'large']) || !one(value.settings.preset, ['children', 'adult']) || typeof value.settings.rate !== 'number' || !Number.isFinite(value.settings.rate) || value.settings.rate < 0.5 || value.settings.rate > 2) throw new Error('格式或字段无效')
    for (const key of ['aiExpansionEnabled', 'speechSynthesisEnabled', 'emotionRecognitionEnabled']) {
      if (Object.hasOwn(value.settings, key) && typeof value.settings[key] !== 'boolean') throw new Error('开关格式无效')
    }
    const active = value.rooms.filter(r => (r as Obj).status === 'active') as Obj[]
    if (value.activeRoomId === null ? active.length !== 0 : active.length !== 1 || active[0]?.id !== value.activeRoomId) throw new Error('会话状态不一致')
    return { valid: true, state: { ...value, settings: {
      ...value.settings,
      aiExpansionEnabled: value.settings.aiExpansionEnabled ?? true,
      speechSynthesisEnabled: value.settings.speechSynthesisEnabled ?? false,
      emotionRecognitionEnabled: value.settings.emotionRecognitionEnabled ?? false,
    } } as unknown as State }
  } catch { return { valid: false, error: '本机记录格式不正确或超出容量限制。原始数据已保留，请先导出检查。' } }
}
