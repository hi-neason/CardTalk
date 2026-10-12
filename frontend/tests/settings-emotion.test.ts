import { describe, expect, it } from 'vitest'
import { CARDS, CORE_CARDS, createState, createRoom, addCard, recordRawExpression, endRoom, validateState, deriveSelectedEmotion } from '../src/domain'
import type { Card } from '../src/domain'
const card = (id: string): Card => CARDS.find(item => item.id === id)!

describe('本机能力开关与旧记录迁移', () => {
  it('默认扩写开启、语音合成和情绪识别关闭', () => {
    expect(createState().settings).toMatchObject({ aiExpansionEnabled: true, speechSynthesisEnabled: false, emotionRecognitionEnabled: false })
  })
  it('旧version1安全补默认值，不改变历史、原输入或活跃状态', () => {
    let state = createRoom(createState(), { preset: 'adult', sceneId: null, participants: [], communicatorName: '体验者', source: 'independent' })
    state = endRoom(recordRawExpression(addCard(state, card('happy'))))
    const legacy = { ...state, settings: { fontSize: 'large', rate: 1.2, preset: 'adult' } }
    const original = JSON.stringify(legacy)
    const result = validateState(legacy)
    expect(result.valid).toBe(true)
    if (!result.valid) throw new Error(result.error)
    expect(result.state).not.toBe(legacy)
    expect(result.state.settings).toEqual({ ...legacy.settings, aiExpansionEnabled: true, speechSynthesisEnabled: false, emotionRecognitionEnabled: false })
    expect(result.state.rooms).toEqual(state.rooms)
    expect(result.state.activeRoomId).toBeNull()
    expect(JSON.stringify(legacy)).toBe(original)
    expect(validateState(JSON.parse(JSON.stringify(result.state)))).toEqual(result)
  })
  it('部分字段迁移保持已经做出的开关选择，不把false替换为默认true', () => {
    const state = createState()
    const result = validateState({ ...state, settings: { fontSize: 'normal', rate: 0.9, preset: 'children', aiExpansionEnabled: false, emotionRecognitionEnabled: true } })
    expect(result.valid).toBe(true)
    if (result.valid) expect(result.state.settings).toMatchObject({ aiExpansionEnabled: false, speechSynthesisEnabled: false, emotionRecognitionEnabled: true })
  })
  it('显式错误类型不能作为旧字段缺失被悄悄修复', () => {
    const state = createState()
    for (const key of ['aiExpansionEnabled', 'speechSynthesisEnabled', 'emotionRecognitionEnabled']) {
      for (const value of [null, undefined, 'false', 0, {}, []]) expect(validateState({ ...state, settings: { ...state.settings, [key]: value } }).valid).toBe(false)
    }
  })
})

describe('只读取本人所选情绪卡', () => {
  it('关闭时不产生情绪字段，无论是否选择情绪卡', () => {
    expect(deriveSelectedEmotion([card('happy')], false)).toBeNull()
    expect(deriveSelectedEmotion([card('sad'), card('angry')], false)).toBeNull()
  })
  it.each([['happy', 'happy'], ['sad', 'sad'], ['angry', 'angry'], ['afraid', 'fearful']])('识别明确选择的%s卡', (id, emotion) => {
    expect(deriveSelectedEmotion([card('i'), card(id)], true)).toEqual({ emotion, conflict: false, selectedEmotions: [emotion] })
  })
  it('重复同情绪不会变成冲突，且不修改原始卡片', () => {
    const cards = [card('happy'), card('happy'), card('apple')]
    const snapshot = JSON.stringify(cards)
    expect(deriveSelectedEmotion(cards, true)).toEqual({ emotion: 'happy', conflict: false, selectedEmotions: ['happy'] })
    expect(JSON.stringify(cards)).toBe(snapshot)
  })
  it('冲突的明确情绪保留来源并使用neutral，不按次数或先后替本人选择', () => {
    expect(deriveSelectedEmotion([card('happy'), card('sad'), card('happy')], true)).toEqual({ emotion: 'neutral', conflict: true, selectedEmotions: ['happy', 'sad'] })
    expect(deriveSelectedEmotion([card('angry'), card('afraid')], true)).toEqual({ emotion: 'neutral', conflict: true, selectedEmotions: ['angry', 'fearful'] })
  })
  it('疼痛、拒绝、饥渴、冷热与身体状态不推断成情绪', () => {
    const cards = ['pain', 'not', 'stop', 'hungry', 'thirsty', 'cold', 'hot', 'tired'].map(card)
    expect(deriveSelectedEmotion([...cards, ...CORE_CARDS], true)).toEqual({ emotion: 'neutral', conflict: false, selectedEmotions: [], suppressionReason: 'negation' })
    expect(deriveSelectedEmotion([], true)).toEqual({ emotion: 'neutral', conflict: false, selectedEmotions: [] })
  })
  it('相同文字和非情绪分类或短句不作为情绪卡', () => {
    const custom = { ...card('happy'), id: 'custom-happy' }
    expect(deriveSelectedEmotion([custom, { ...card('happy'), category: 'actions' }, { ...card('sad'), kind: 'phrase' }], true)?.selectedEmotions).toEqual([])
  })
})


describe('否定表达不带入肯定情绪', () => {
  const lookup = (id: string) => [...CARDS, ...CORE_CARDS].find(item => item.id === id)!
  it.each([
    ['不开心', ['i', 'not', 'happy'], ['happy']],
    ['不要生气', ['core-refuse', 'angry'], ['angry']],
    ['不是难过', ['no', 'sad'], ['sad']],
    ['核心不是难过', ['core-no', 'sad'], ['sad']],
  ])('%s保留情绪来源但使用neutral', (_text, ids, selectedEmotions) => {
    expect(deriveSelectedEmotion((ids as string[]).map(lookup), true)).toEqual({ emotion: 'neutral', conflict: false, selectedEmotions, suppressionReason: 'negation' })
  })
  it('否定不推断反向情绪，情绪冲突仍如实保留', () => {
    expect(deriveSelectedEmotion(['not', 'happy', 'sad'].map(lookup), true)).toEqual({ emotion: 'neutral', conflict: true, selectedEmotions: ['happy', 'sad'], suppressionReason: 'negation' })
    expect(deriveSelectedEmotion(['pain'].map(lookup), true)).toEqual({ emotion: 'neutral', conflict: false, selectedEmotions: [] })
  })
  it('肯定的开心表达照常保留happy', () => {
    expect(deriveSelectedEmotion(['i', 'happy'].map(lookup), true)).toEqual({ emotion: 'happy', conflict: false, selectedEmotions: ['happy'] })
  })
})
