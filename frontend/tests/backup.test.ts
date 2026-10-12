import { describe, expect, it } from 'vitest'
import { parseBackup } from '../src/domain/backup'
import { MAX_LOCAL_STATE_CHARACTERS } from '../src/domain/capacity'
import { createState, createFamilyProfile, createCustomScene, createRoom, addCard, recordRawExpression, endRoom, CARDS, updateFamilyProfile, updateCustomScene, saveCommunicationReport } from '../src/domain'

describe('本机备份解析', () => {
  it('迁移旧 v1 家庭库与 AI 设置', () => {
    const { family: _family, ...old } = createState()
    const backup = { ...old, settings: { fontSize: 'normal', rate: 1, preset: 'children' } }
    const restored = parseBackup(JSON.stringify(backup))
    expect(restored.family).toEqual({ profiles: [], cards: [], scenes: [] })
    expect(restored.settings).toMatchObject({ aiExpansionEnabled: true, speechSynthesisEnabled: false, emotionRecognitionEnabled: false })
    expect(backup).not.toHaveProperty('family')
  })
  it('拒绝无效 JSON、状态及超过容量的文件', () => {
    for (const text of ['', ' ', '<script>alert(1)</script>', '{', 'null', '[]', JSON.stringify({ ...createState(), version: 2 }), JSON.stringify({ ...createState(), family: null })]) expect(() => parseBackup(text)).toThrow()
    expect(() => parseBackup(' '.repeat(MAX_LOCAL_STATE_CHARACTERS * 3) + '{}')).toThrow('容量')
    const state = createState()
    const raw = JSON.stringify(state)
    expect(parseBackup(raw + ' '.repeat(MAX_LOCAL_STATE_CHARACTERS - raw.length + 10))).toEqual(state)
    const reportState=saveCommunicationReport(state),saved=reportState.reports![0]!
    reportState.reports=[{...saved,sourceSignature:'x'.repeat(MAX_LOCAL_STATE_CHARACTERS/2+100)},{...saved,id:'another-report',sourceSignature:'x'.repeat(MAX_LOCAL_STATE_CHARACTERS/2+100)}]
    expect(()=>parseBackup(JSON.stringify(reportState))).toThrow('空间不足')
  })
  it('保留历史快照、当前资料和未结束的草稿', () => {
    let state = createFamilyProfile(createState(), { name: '爷爷', preset: 'adult', interests: '苹果' })
    state = createCustomScene(state, { name: '阳台', description: '看花' })
    const profileId = state.family.profiles[0]!.id, sceneId = state.family.scenes[0]!.id
    const context = { preset: 'adult' as const, sceneId, participants: [], communicatorName: '爷爷', source: 'independent' as const, profileId }
    state = createRoom(state, context)
    state = recordRawExpression(addCard(state, CARDS[0]!))
    state = endRoom(state)
    state = updateFamilyProfile(state, profileId, { name: '外公' })
    state = updateCustomScene(state, sceneId, { name: '花园' })
    state = addCard(createRoom(state, context), CARDS[1]!)
    const restored = parseBackup(JSON.stringify(state))
    expect(restored).toEqual(state)
    expect(restored.rooms[0]!.turns[0]!.contextSnapshot.profileSnapshot!.name).toBe('爷爷')
    expect(restored.rooms[0]!.turns[0]!.contextSnapshot.sceneSnapshot!.name).toBe('阳台')
    expect(restored.rooms[1]!.draft.cards).toHaveLength(1)
    expect(restored.family.profiles[0]!.name).toBe('外公')
  })
})
