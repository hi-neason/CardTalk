import { describe, expect, it } from 'vitest'
import { CARDS, CORE_CARDS, CATEGORIES, createState, createRoom, getActiveRoom, addCard, removeCard, clearDraft, rejectExpansion, applyExpansion, confirmCandidate, recordRawExpression, nextTurn, endRoom, addPlaybackEvent, setFeedback, removeRoom, validateState } from '../src/domain'
import type { Context, Expansion, State } from '../src/domain'
const context: Context = { preset: 'children', sceneId: 'kitchen', participants: [{ id: 'mom', name: '妈妈' }], communicatorName: '使用者', source: 'unknown' }
const apple = CARDS.find(c => c.id === 'apple')!
const selected = () => addCard(createRoom(createState(), context), apple)
const expansion = (s: State): Expansion => ({ requestId: 'r1', inputVersion: getActiveRoom(s)!.draft.version, candidates: [{ id: 'a', text: '我想吃苹果', sourceCardIds: ['apple'] }], needsClarification: false, clarification: null, promptVersion: 'v1', remaining: 9 })
describe('沟通业务', () => {
  it('词库覆盖分类与固定核心表达，标识唯一', () => {
    expect(CARDS.length).toBeGreaterThanOrEqual(50); expect(CARDS.length).toBeLessThanOrEqual(70)
    expect(new Set([...CARDS, ...CORE_CARDS].map(c => c.id)).size).toBe(CARDS.length + CORE_CARDS.length)
    expect(CARDS.every(c => CATEGORIES.some(category => category.id === c.category))).toBe(true)
    expect(CORE_CARDS.map(c => c.label)).toEqual(['是','不是','不要','帮帮我','停一下','我疼','再说一遍'])
  })
  it('最多20张，保留顺序与重复，不修改调用者数据', () => {
    const initial = selected(); let state = initial
    for (let n = 1; n < 20; n++) state = addCard(state, CARDS[n % 3]!)
    expect(getActiveRoom(initial)!.draft.cards).toEqual([apple])
    expect(getActiveRoom(state)!.draft.cards.map(c => c.id)).toEqual(['apple', ...Array.from({length:19},(_,n) => CARDS[(n+1)%3]!.id)])
    expect(() => addCard(state, apple)).toThrow('20')
    const removed = removeCard(state, 2)
    expect(getActiveRoom(removed)!.draft.cards.length).toBe(19)
    expect(() => removeCard(state, -1)).toThrow()
  })
  it('选卡修改清空候选，过期结果与结束后结果均忽略', () => {
    const s = selected(), result = expansion(s), expanded = applyExpansion(s, result)
    expect(getActiveRoom(expanded)!.draft.expansion).toEqual(result)
    const changed = addCard(expanded, apple)
    expect(getActiveRoom(changed)!.draft.expansion).toBeNull()
    expect(applyExpansion(changed, result)).toBe(changed)
    const ended = endRoom(s)
    expect(applyExpansion(ended, result)).toBe(ended)
    const newer = addCard(createRoom(ended, context), apple)
    expect(applyExpansion(newer, result, s.activeRoomId!)).toBe(newer)
    expect(getActiveRoom(clearDraft(expanded))!.draft.expansion).toBeNull()
  })
  it('拒绝超过3个选项或引用未选卡片的结果', () => {
    const s = selected(), e = expansion(s)
    expect(() => applyExpansion(s, {...e,candidates: Array.from({length:4},(_,i)=>({...e.candidates[0]!,id:String(i)}))})).toThrow()
    expect(() => applyExpansion(s, {...e,candidates:[{...e.candidates[0]!, sourceCardIds:['fake']}]})).toThrow()
  })
  it('全部拒绝保留原始选卡并使已返回及迟到候选失效', () => {
    const s = selected(), result = expansion(s), expanded = applyExpansion(s, result)
    const rejected = rejectExpansion(expanded), room = getActiveRoom(rejected)!
    expect(room.draft.cards).toEqual([apple])
    expect(room.draft.version).toBe(getActiveRoom(expanded)!.draft.version + 1)
    expect(room.draft.expansion).toBeNull()
    expect(room.draft.confirmedText).toBeNull()
    expect(room.draft.selectedCandidateId).toBeNull()
    expect(room.draft.confirmedTurnId).toBeNull()
    expect(room.turns).toHaveLength(0)
    expect(getActiveRoom(expanded)!.draft.expansion).toEqual(result)
    expect(applyExpansion(rejected, result)).toBe(rejected)
    expect(() => confirmCandidate(rejected, 'a')).toThrow()
    const regenerated = applyExpansion(rejected, expansion(rejected))
    const confirmed = confirmCandidate(regenerated, 'a')
    expect(() => rejectExpansion(confirmed)).toThrow('下一句')
    expect(validateState(confirmed).valid).toBe(true)
  })
  it('候选来源必须逐项匹配原始选卡的完整顺序和重复次数', () => {
    const self = CARDS.find(card => card.id === 'i')!
    const s = addCard(addCard(selected(), self), apple)
    const e = expansion(s), ids = ['apple', 'i', 'apple']
    const result = { ...e, candidates: [{ ...e.candidates[0]!, sourceCardIds: ids }] }
    expect(getActiveRoom(applyExpansion(s, result))!.draft.expansion).toEqual(result)
    for (const sourceCardIds of [[], ['apple'], ['apple', 'i'], ['i', 'apple', 'apple'], ['apple', 'i', 'apple', 'apple']]) {
      const corrupt = { ...result, candidates: [{ ...result.candidates[0]!, sourceCardIds }] }
      expect(() => applyExpansion(s, corrupt)).toThrow('格式不正确')
      const corruptedDraft = { ...s, rooms: s.rooms.map(room => ({ ...room, draft: { ...room.draft, expansion: corrupt } })) }
      expect(validateState(corruptedDraft).valid).toBe(false)
      const recorded = confirmCandidate(applyExpansion(s, result), 'a')
      recorded.rooms[0]!.turns[0]!.expansion = corrupt
      expect(validateState(recorded).valid).toBe(false)
    }
  })
  it('确认生成唯一历史快照并保留修改，不重复保存', () => {
    let s = selected(); s = applyExpansion(s, expansion(s)); s = confirmCandidate(s,'a','我想要苹果')
    const room = getActiveRoom(s)!, turn = room.turns[0]!
    expect(turn.wasEdited).toBe(true); expect(turn.finalText).toBe('我想要苹果'); expect(turn.source).toBe('unknown')
    expect(confirmCandidate(s,'a')).toBe(s)
    expect(() => addCard(s,apple)).toThrow('下一句')
    const next = addCard(nextTurn(s), CARDS[0]!)
    expect(getActiveRoom(next)!.turns[0]!.cardSnapshot).toEqual([apple])
    expect(room.turns[0]!.contextSnapshot).not.toBe(context)
    expect(validateState(next).valid).toBe(true)
  })
  it('原始表达必须非空，保存后才可下一句，禁止静默替换活跃会话', () => {
    const empty = createRoom(createState(),context)
    expect(() => recordRawExpression(empty)).toThrow(); expect(() => nextTurn(empty)).toThrow()
    expect(() => createRoom(empty,context)).toThrow()
    const s = recordRawExpression(addCard(selected(),apple))
    expect(getActiveRoom(s)!.turns[0]!.finalText).toBe('苹果 苹果')
    expect(recordRawExpression(s)).toBe(s)
    expect(validateState(endRoom(s)).valid).toBe(true)
  })
  it('播放结果和理解反馈分别记录，已结束历史仍可反馈', () => {
    const original = recordRawExpression(selected()), room = getActiveRoom(original)!, turn = room.turns[0]!
    let s = addPlaybackEvent(original,room.id,turn.id,'started')
    s = addPlaybackEvent(s,room.id,turn.id,'completed')
    expect(s.rooms[0]!.turns[0]!.feedback).toBeNull()
    s = setFeedback(endRoom(s),room.id,turn.id,'clarify','妈妈','再说一次')
    expect(s.rooms[0]!.turns[0]!.playbackEvents).toHaveLength(2)
    expect(s.rooms[0]!.turns[0]!.feedback?.value).toBe('clarify')
    expect(turn.playbackEvents).toHaveLength(0)
    expect(validateState(s).valid).toBe(true)
    expect(removeRoom(s,room.id).rooms).toHaveLength(0)
    expect(() => removeRoom(original,room.id)).toThrow()
  })
  it('拒绝损坏、超限、未支持版本及错误关联存储，保留输入', () => {
    const s = selected(), snapshot = JSON.stringify(s)
    for (const corrupt of [null,{}, {...s,version:2},{...s,activeRoomId:'missing'},{...s,settings:{...s.settings,rate:NaN}},{...s,rooms:[{...s.rooms[0],draft:{...s.rooms[0]!.draft,cards:Array(21).fill(apple)}}]}, {...s,rooms:[{...s.rooms[0],context:{...context,source:'invented'}}]}]) expect(validateState(corrupt).valid).toBe(false)
    expect(JSON.stringify(s)).toBe(snapshot)
    expect(validateState(s).valid).toBe(true)
  })
})
