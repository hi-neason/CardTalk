import { describe, expect, it } from 'vitest'
import { addCard, CARDS, createFamilyProfile, createRoom, createState, DEFAULT_COMMUNICATION_PREFERENCES, endRoom, getCommunicationPreferences, recordRawExpression, updateFamilyPreferences, updateGlobalPreferences, validateState } from '../src/domain'
import type { CommunicationPreferences, Context } from '../src/domain'
const context: Context = { preset: 'children', sceneId: null, communicatorName: '体验者', participants: [], source: 'unknown' }
describe('个人沟通偏好', () => {
  it('旧记录补充偏好且不改原记录、旧字号和语速保留', () => {
    const old = createState(); delete old.settings.preferences
    old.settings.fontSize = 'large'; old.settings.rate = 1.2
    const before = JSON.stringify(old), result = validateState(old)
    expect(result.valid).toBe(true)
    if (result.valid) expect(result.state.settings.preferences).toEqual({ ...DEFAULT_COMMUNICATION_PREFERENCES, fontSize: 'large', rate: 1.2 })
    expect(JSON.stringify(old)).toBe(before)
    expect(getCommunicationPreferences(old)).toEqual(DEFAULT_COMMUNICATION_PREFERENCES)
  })
  it('默认值与各状态隔离，修改全局偏好不污染旧对象', () => {
    const first = createState(), second = createState()
    const next = updateGlobalPreferences(first, { displayMode: 'text', density: 'compact', fontSize: 'large', volume: 0, rate: 1.5, touchGuardMs: 1500, deviceVoiceURI: 'system-voice' })
    const returned = getCommunicationPreferences(next); returned.volume = 1
    expect(next.settings.preferences?.volume).toBe(0)
    expect(first.settings.preferences).toEqual(DEFAULT_COMMUNICATION_PREFERENCES)
    expect(second.settings.preferences).toEqual(DEFAULT_COMMUNICATION_PREFERENCES)
    expect(next.settings).toMatchObject({ rate: 1.5, fontSize: 'large' })
    expect(validateState(next).valid).toBe(true)
  })
  it('个人偏好冻结到房间和表达，修改仅影响新房间', () => {
    let state = createFamilyProfile(createState(), { name: '乐乐', preset: 'children' })
    const id = state.family.profiles[0]!.id
    state = updateFamilyPreferences(state, id, { displayMode: 'images', rate: 0.6, deviceVoiceURI: 'voice-a' })
    state = recordRawExpression(addCard(createRoom(state, { ...context, profileId: id }), CARDS[0]!))
    const original = JSON.stringify(state.rooms[0])
    state = updateFamilyPreferences(state, id, { displayMode: 'text', rate: 1.3, deviceVoiceURI: 'voice-b' })
    state = updateGlobalPreferences(state, { volume: 0.2 })
    expect(JSON.stringify(state.rooms[0])).toBe(original)
    expect(getCommunicationPreferences(state, state.rooms[0]!.context)).toMatchObject({ displayMode: 'images', rate: 0.6, deviceVoiceURI: 'voice-a', volume: 1 })
    expect(state.rooms[0]!.turns[0]!.contextSnapshot.profileSnapshot?.preferences?.rate).toBe(0.6)
    state = createRoom(endRoom(state), { ...context, profileId: id })
    expect(getCommunicationPreferences(state, state.rooms[1]!.context)).toMatchObject({ displayMode: 'text', rate: 1.3, deviceVoiceURI: 'voice-b' })
    const loaded = validateState(JSON.parse(JSON.stringify(state)))
    expect(loaded.valid).toBe(true)
  })
  it('未自定义个人偏好时，新房间冻结全局偏好，体验者跟随全局', () => {
    let state = updateGlobalPreferences(createState(), { volume: 0.7 })
    state = createFamilyProfile(state, { name: '家人', preset: 'adult' })
    state = createRoom(state, { ...context, profileId: state.family.profiles[0]!.id })
    state = updateGlobalPreferences(state, { volume: 0.2 })
    expect(getCommunicationPreferences(state, state.rooms[0]!.context).volume).toBe(0.7)
    expect(getCommunicationPreferences(state, context).volume).toBe(0.2)
  })
  it('不完整、超范围或损坏偏好拒绝保存和载入，三个位置均检查', () => {
    const badValues = [{ displayMode: 'invalid' }, { density: 'invalid' }, { fontSize: 'invalid' }, { rate: 1.6 }, { rate: 0.4 }, { rate: NaN }, { volume: -0.1 }, { volume: 1.1 }, { touchGuardMs: 0.5 }, { touchGuardMs: 1501 }, { touchGuardMs: -1 }, { deviceVoiceURI: 'x'.repeat(201) }]
    let state = createFamilyProfile(createState(), { name: '乐乐', preset: 'children' })
    const id = state.family.profiles[0]!.id
    state = createRoom(state, { ...context, profileId: id })
    for (const patch of badValues) {
      expect(() => updateGlobalPreferences(state, patch as Partial<CommunicationPreferences>)).toThrow()
      expect(() => updateFamilyPreferences(state, id, patch as Partial<CommunicationPreferences>)).toThrow()
    }
    for (const bad of [null, {}, { ...DEFAULT_COMMUNICATION_PREFERENCES, rate: 2 }, { ...DEFAULT_COMMUNICATION_PREFERENCES, volume: Infinity }]) {
      for (const location of ['global', 'profile', 'snapshot']) {
        const copy = structuredClone(state)
        if (location === 'global') copy.settings.preferences = bad as CommunicationPreferences
        if (location === 'profile') copy.family.profiles[0]!.preferences = bad as CommunicationPreferences
        if (location === 'snapshot') copy.rooms[0]!.context.profileSnapshot!.preferences = bad as CommunicationPreferences
        expect(validateState(copy).valid, location).toBe(false)
      }
    }
    expect(() => updateFamilyPreferences(state, 'missing', {})).toThrow('不存在')
  })
})
