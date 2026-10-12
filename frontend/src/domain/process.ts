import { ensureStateCapacity } from './capacity'
import type { ExpressionEvent, ExpressionProcess, Room, State } from './types'
const EVENT_TYPES = ['clarification-requested', 'generation-started', 'generation-succeeded', 'generation-failed', 'generation-cancelled', 'candidates-rejected', 'candidate-edit-started']
const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
const timestamp = (value: unknown) => typeof value === 'string' && value.length <= 40 && Number.isFinite(Date.parse(value))
const identifier = (value: unknown) => typeof value === 'string' && value.trim().length > 0 && value.length <= 100
export function isExpressionProcess(value: unknown, snapshot = false): value is ExpressionProcess {
  if (!object(value) || !timestamp(value.startedAt) || (snapshot && !timestamp(value.confirmedAt)) || !Array.isArray(value.events) || value.events.length > 200) return false
  return value.events.every(event => object(event) && identifier(event.id) && timestamp(event.at) && EVENT_TYPES.includes(event.type as string) && Number.isSafeInteger(event.inputVersion) && (event.inputVersion as number) >= 0 && (event.requestId === undefined || identifier(event.requestId)) && (event.errorCode === undefined || typeof event.errorCode === 'string' && /^[A-Za-z0-9_.:-]{1,80}$/.test(event.errorCode))) && new Set(value.events.map(event => event.id)).size === value.events.length
}
/** Internal mutation helper: callers own a cloned room and capacity checking. */
export function appendExpressionEvent(room: Room, type: ExpressionEvent['type'], requestId?: string, errorCode?: string): void {
  const at = new Date().toISOString()
  const process = room.draft.process ?? { startedAt: at, events: [] }
  if (process.events.length >= 200) throw new Error('本次表达过程记录已达200条，请清空后重新开始')
  const event: ExpressionEvent = { id: `${Date.now().toString(36)}-${Math.random().toString(36).slice(2,12)}`, at, type, inputVersion: room.draft.version, ...(requestId !== undefined ? { requestId } : {}), ...(errorCode !== undefined ? { errorCode } : {}) }
  const next = { ...process, events: [...process.events, event] }
  if (!isExpressionProcess(next)) throw new Error('表达过程事件格式不正确')
  room.draft.process = next
}
export function recordExpressionEvent(state: State, roomId: string, type: ExpressionEvent['type'], inputVersion: number, requestId?: string, errorCode?: string): State {
  const room = state.rooms.find(room => room.id === roomId && room.id === state.activeRoomId && room.status === 'active')
  if (!room || room.draft.confirmedTurnId || room.draft.version !== inputVersion || !room.draft.cards.length) return state
  const next = JSON.parse(JSON.stringify(state)) as State
  appendExpressionEvent(next.rooms.find(room => room.id === roomId)!, type, requestId, errorCode)
  return ensureStateCapacity(next)
}
