import { describe, expect, it, vi } from 'vitest'
import { addCard, applyExpansion, CARDS, clearDraft, confirmCandidate, createRoom, createState, nextTurn, recordExpressionEvent, recordRawExpression, rejectExpansion, validateState } from '../src/domain'
import type { Expansion, State } from '../src/domain'
function selected() { return addCard(createRoom(createState(), { preset: 'children', sceneId: null, participants: [], communicatorName: '体验者', source: 'unknown' }), CARDS[0]!) }
function expansion(state: State): Expansion { const room = state.rooms[0]!; return { requestId: 'request-1', inputVersion: room.draft.version, candidates: [{ id: 'one', text: '我想说话', sourceCardIds: room.draft.cards.map(card => card.id) }], needsClarification: false, clarification: null, promptVersion: 'v1', remaining: 5 } }
describe('表达过程记录', () => {
  it('首张卡记录真实时间，改卡保留，清空及下一句重置', () => {
    vi.useFakeTimers()
    try {
      vi.setSystemTime(new Date('2026-10-12T10:00:00Z'))
      let state = selected()
      const startedAt = state.rooms[0]!.draft.process!.startedAt
      vi.setSystemTime(new Date('2026-10-12T10:00:10Z'))
      state = addCard(state, CARDS[1]!)
      expect(state.rooms[0]!.draft.process!.startedAt).toBe(startedAt)
      state = recordRawExpression(state)
      expect(state.rooms[0]!.turns[0]!.processSnapshot).toEqual({ startedAt, confirmedAt: '2026-10-12T10:00:10.000Z', events: [] })
      expect(nextTurn(state).rooms[0]!.draft.process).toBeUndefined()
      expect(clearDraft(selected()).rooms[0]!.draft.process).toBeUndefined()
    } finally { vi.useRealTimers() }
  })
  it('拒绝、取消、失败、编辑事件保留至确认，成功响应不重复计数', () => {
    let state = selected(); const roomId = state.rooms[0]!.id
    state = recordExpressionEvent(state, roomId, 'generation-started', 1, 'request-1')
    state = applyExpansion(state, expansion(state)); state = applyExpansion(state, expansion(state))
    state = rejectExpansion(state)
    state = recordExpressionEvent(state, roomId, 'generation-cancelled', 2, 'request-2')
    state = recordExpressionEvent(state, roomId, 'generation-failed', 2, 'request-3', 'NETWORK_ERROR')
    state = applyExpansion(state, expansion(state))
    state = recordExpressionEvent(state, roomId, 'candidate-edit-started', 2, 'request-1')
    state = confirmCandidate(state, 'one', '我现在想说话')
    const snapshot = state.rooms[0]!.turns[0]!.processSnapshot!
    expect(snapshot.events.map(event => event.type)).toEqual(['generation-started', 'generation-succeeded', 'candidates-rejected', 'generation-cancelled', 'generation-failed', 'generation-succeeded', 'candidate-edit-started'])
    expect(snapshot.events[4]!.errorCode).toBe('NETWORK_ERROR')
    expect(snapshot.events).not.toBe(state.rooms[0]!.draft.process!.events)
    expect(validateState(state).valid).toBe(true)
    expect(nextTurn(state).rooms[0]!.turns[0]!.processSnapshot).toEqual(snapshot)
  })
  it('迟到事件和错误房间不写入新草稿或已确认表达', () => {
    let state = selected(); const roomId = state.rooms[0]!.id
    state = addCard(state, CARDS[1]!)
    expect(recordExpressionEvent(state, roomId, 'generation-failed', 1, 'old', 'TIMEOUT')).toBe(state)
    expect(recordExpressionEvent(state, 'other', 'generation-started', 2)).toBe(state)
    state = recordRawExpression(state)
    expect(recordExpressionEvent(state, roomId, 'generation-cancelled', 2)).toBe(state)
    expect(rejectExpansion(selected()).rooms[0]!.draft.process!.events).toEqual([])
  })
  it('旧历史不推测过程时间，坏事件拒绝载入', () => {
    const state = recordRawExpression(selected())
    delete state.rooms[0]!.draft.process; delete state.rooms[0]!.turns[0]!.processSnapshot
    const old = validateState(state)
    expect(old.valid).toBe(true)
    if (old.valid) expect(old.state.rooms[0]!.turns[0]!.processSnapshot).toBeUndefined()
    for (const process of [null, {}, { startedAt: 'bad', events: [] }, { startedAt: new Date().toISOString(), events: [{}] }]) {
      const copy = structuredClone(state); copy.rooms[0]!.draft.process = process as never
      expect(validateState(copy).valid).toBe(false)
    }
    const active = selected()
    expect(() => recordExpressionEvent(active, active.rooms[0]!.id, 'generation-failed', 1, 'r', 'provider secret text')).toThrow()
  })
  it('达到200条后明确拒绝并保留原状态，重复事件ID拒绝', () => {
    let state = selected(); const roomId = state.rooms[0]!.id
    for (let i = 0; i < 200; i++) state = recordExpressionEvent(state, roomId, 'candidate-edit-started', 1)
    expect(() => recordExpressionEvent(state, roomId, 'generation-started', 1)).toThrow('200')
    expect(state.rooms[0]!.draft.process!.events).toHaveLength(200)
    expect(validateState(state).valid).toBe(true)
    const bad = structuredClone(state)
    bad.rooms[0]!.draft.process!.events[1]!.id = bad.rooms[0]!.draft.process!.events[0]!.id
    expect(validateState(bad).valid).toBe(false)
  })
})

it('设置引起的候选失效不记录本人拒绝，保留选卡与过程', () => {
  let state = selected()
  state = applyExpansion(state, expansion(state))
  const before = state.rooms[0]!.draft
  state = rejectExpansion(state, 'invalidation')
  expect(state.rooms[0]!.draft.expansion).toBeNull()
  expect(state.rooms[0]!.draft.cards).toEqual(before.cards)
  expect(state.rooms[0]!.draft.process).toEqual(before.process)
  expect(state.rooms[0]!.draft.version).toBe(before.version + 1)
})

it('成功扩写明确要求澄清时记录独立事实，重复响应不重复记录', () => {
  let state = selected()
  const response = { ...expansion(state), needsClarification: true, clarification: '你是想要还是想吃？' }
  state = applyExpansion(state, response)
  state = applyExpansion(state, response)
  expect(state.rooms[0]!.draft.process!.events.map(event => event.type)).toEqual(['generation-succeeded', 'clarification-requested'])
  state = confirmCandidate(state, 'one')
  expect(state.rooms[0]!.turns[0]!.processSnapshot!.events[1]!.type).toBe('clarification-requested')
  expect(validateState(state).valid).toBe(true)
})
