import { describe, expect, it, vi } from 'vitest'
import { createState, createRoom, endRoom, addCard, recordRawExpression, getActiveRoom, validateState, CARDS, createFamilyProfile, updateFamilyProfile, archiveFamilyProfile, createCustomCard, updateCustomCard, archiveCustomCard, createCustomScene, updateCustomScene, archiveCustomScene, getAvailableCards, getAvailableProfiles, getAvailableScenes, getRecommendedCards, buildRoomContext, cardImageInfo, MAX_CARD_IMAGE_BYTES, deriveSelectedEmotion } from '../src/domain'
import type { Context } from '../src/domain'
const context: Context = { preset: 'children', sceneId: 'kitchen', participants: [], communicatorName: '体验者', source: 'independent' }
const png = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII='
function headerImage(bytes: number, width = 1, height = 1) {
  const data = Buffer.alloc(bytes); Buffer.from(png.split(',')[1]!, 'base64').copy(data)
  data.writeUInt32BE(width, 16); data.writeUInt32BE(height, 20)
  return `data:image/png;base64,${data.toString('base64')}`
}
describe('家庭管理及历史快照', () => {
  it('旧version1补空家庭库，损坏家庭数据保留错误而不重置', () => {
    const { family: _family, ...old } = createState()
    const snapshot = JSON.stringify(old), result = validateState(old)
    expect(result.valid).toBe(true)
    if (result.valid) expect(result.state.family).toEqual({ profiles: [], cards: [], scenes: [] })
    expect(JSON.stringify(old)).toBe(snapshot)
    for (const family of [null, {}, { profiles: [], cards: [], scenes: ['bad'] }]) expect(validateState({ ...old, family }).valid).toBe(false)
  })
  it('档案创建修改归档恢复，重名和人数限制明确', () => {
    let state = createFamilyProfile(createState(), { name: ' 乐乐 ', preset: 'children', age: 8 })
    const id = state.family.profiles[0]!.id, original = state
    state = updateFamilyProfile(state, id, { name: '乐乐同学', language: '普通话', interests: '拼图' })
    expect(original.family.profiles[0]!.name).toBe('乐乐')
    expect(() => createFamilyProfile(state, { name: ' 乐乐同学 ', preset: 'adult' })).toThrow('同名')
    state = archiveFamilyProfile(state, id); expect(getAvailableProfiles(state)).toHaveLength(0)
    state = archiveFamilyProfile(state, id, false); expect(getAvailableProfiles(state)).toHaveLength(1)
    expect(() => updateFamilyProfile(state, id, { age: -1 })).toThrow()
    expect(() => updateFamilyProfile(state, id, { observations: '今天疲倦', observer: '' })).toThrow()
    for (let i = 1; i < 50; i++) state = createFamilyProfile(state, { name: `成员${i}`, preset: 'adult' })
    expect(() => createFamilyProfile(state, { name: '第51位', preset: 'adult' })).toThrow('50')
  })
  it('图片限格式、字节和实际头部尺寸，图片总量不超2MiB', () => {
    expect(cardImageInfo(png)).toMatchObject({ width: 1, height: 1 })
    for (const image of ['https://example.com/photo.png', 'data:image/svg+xml;base64,PHN2Zy8+', 'data:image/png;base64,YmFk', headerImage(MAX_CARD_IMAGE_BYTES + 1), headerImage(100, 1025), headerImage(100, 1, 0)]) expect(cardImageInfo(image)).toBeNull()
    let state = createState()
    for (let i = 0; i < 10; i++) state = createCustomCard(state, { label: `照片${i}`, category: 'daily', image: headerImage(MAX_CARD_IMAGE_BYTES) })
    expect(() => createCustomCard(state, { label: '超出总图片额度', category: 'daily', image: headerImage(MAX_CARD_IMAGE_BYTES) })).toThrow()
    const id = state.family.cards[0]!.id
    state = updateCustomCard(state, id, { image: '' }); expect(state.family.cards[0]!.image).toBeUndefined()
    expect(() => createCustomCard(state, { label: '格式错误', category: 'daily', image: 'data:image/png;base64,YmFk' })).toThrow()
  })
  it('自定义卡与场景有效关联，场景推荐不隐藏通用卡，归档不删除快照', () => {
    let state = createCustomScene(createState(), { name: '阳台', description: '家里的阳台', activities: '浇花', objects: '水壶' })
    const sceneId = state.family.scenes[0]!.id
    state = createCustomCard(state, { label: '浇花', category: 'actions', kind: 'phrase', image: png, sceneIds: [sceneId] })
    const custom = state.family.cards[0]!
    state = updateCustomScene(state, sceneId, { recommendedCardIds: [custom.id, 'water'] })
    expect(getRecommendedCards(state, sceneId).map(c => c.id)).toEqual(['water', custom.id])
    expect(getAvailableCards(state)).toHaveLength(CARDS.length + 1)
    expect(() => updateCustomScene(state, sceneId, { recommendedCardIds: ['missing'] })).toThrow()
    expect(() => updateCustomCard(state, custom.id, { sceneIds: ['missing'] })).toThrow()
    state = createRoom(state, { ...context, sceneId }); state = recordRawExpression(addCard(state, custom))
    const snapshot = JSON.stringify(getActiveRoom(state))
    state = updateCustomCard(state, custom.id, { label: '给花浇水', image: '' })
    state = updateCustomScene(state, sceneId, { name: '阳台花园' })
    state = archiveCustomCard(state, custom.id); state = archiveCustomScene(state, sceneId)
    expect(JSON.stringify(getActiveRoom(state))).toBe(snapshot)
    expect(getAvailableCards(state)).toHaveLength(CARDS.length)
    expect(getAvailableScenes(state).some(s => s.id === sceneId)).toBe(false)
    state = endRoom(state)
    expect(() => createRoom(state, { ...context, sceneId })).toThrow('归档')
    state = createRoom(state, context)
    expect(() => addCard(state, custom)).toThrow('归档')
    expect(state.rooms[0]!.turns[0]!.cardSnapshot[0]!.image).toBe(png)
    expect(validateState(state).valid).toBe(true)
  })
  it('使用人快照剔除长期观察，当前状态单独传入，新会话才采用编辑内容', () => {
    let state = createFamilyProfile(createState(), { name: '爷爷', preset: 'adult', age: 72, observations: '家人观察：上周喜欢散步', observer: '女儿', interests: '花草' })
    const id = state.family.profiles[0]!.id
    const roomContext = { ...context, profileId: id }
    expect(buildRoomContext(state, roomContext, '刚吃完饭').profileSnapshot).not.toHaveProperty('observations')
    state = createRoom(state, roomContext, '刚吃完饭')
    const room = getActiveRoom(state)!
    expect(room.context.profileSnapshot).toMatchObject({ name: '爷爷', age: 72, interests: '花草', currentState: '刚吃完饭' })
    expect(room.context.currentState).toBe('刚吃完饭'); expect(room.context.preset).toBe('adult')
    state = updateFamilyProfile(state, id, { name: '外公', interests: '象棋' }); state = archiveFamilyProfile(state, id)
    expect(getActiveRoom(state)!.context).toEqual(room.context)
    state = endRoom(state); expect(() => createRoom(state, roomContext)).toThrow('归档')
    state = archiveFamilyProfile(state, id, false); state = createRoom(state, roomContext, '想休息')
    expect(getActiveRoom(state)!.context.profileSnapshot).toMatchObject({ name: '外公', interests: '象棋', currentState: '想休息' })
    expect(validateState(state).valid).toBe(true)
  })
  it('自定义否定卡保持中性，自定义开心图标和名称不用于推断情绪', () => {
    let state = createCustomCard(createState(), { label: '不', category: 'actions' })
    state = createCustomCard(state, { label: '开心', category: 'feelings', icon: 'happy' })
    const happy = CARDS.find(c => c.id === 'happy')!
    expect(deriveSelectedEmotion([state.family.cards[0]!, happy], true)).toMatchObject({ emotion: 'neutral', suppressionReason: 'negation' })
    expect(deriveSelectedEmotion([state.family.cards[1]!], true)?.selectedEmotions).toEqual([])
    expect(() => createCustomCard(state, { label: ' 不 ', category: 'words' })).toThrow('同名')
  })
  it('导入重复ID、内置ID冒用及无效关联会失败，归档后同名恢复仍受约束', () => {
    let state = createCustomCard(createState(), { label: '我的玩具', category: 'daily' })
    const original = state.family.cards[0]!
    expect(validateState({ ...state, family: { ...state.family, cards: [original, original] } }).valid).toBe(false)
    expect(validateState({ ...state, family: { ...state.family, cards: [{ ...original, id: 'apple' }] } }).valid).toBe(false)
    expect(validateState({ ...state, family: { ...state.family, cards: [{ ...original, sceneIds: ['gone'] }] } }).valid).toBe(false)
    state = archiveCustomCard(state, original.id); state = createCustomCard(state, { label: '我的玩具', category: 'daily' })
    expect(() => archiveCustomCard(state, original.id, false)).toThrow('同名')
  })
  it('没有atob的运行环境仍可解析图片，覆盖所有base64补齐长度', () => {
    const fixtures = [png, headerImage(69, 12, 20), headerImage(70, 20, 12), headerImage(71, 8, 8)]
    const expected = fixtures.map(cardImageInfo)
    vi.stubGlobal('atob', undefined)
    try {
      expect(fixtures.map(cardImageInfo)).toEqual(expected)
      expect(expected.every(info => info !== null)).toBe(true)
      expect(cardImageInfo('data:image/png;base64,invalid===')).toBeNull()
      const state = createCustomCard(createState(), { label: '无atob照片', category: 'daily', image: png })
      expect(validateState(state).valid).toBe(true)
    } finally { vi.unstubAllGlobals() }
  })
  it('卡片和场景数量上限包括归档项', () => {
    let state = createCustomCard(createState(), { label: '种子卡', category: 'daily', image: '' })
    expect(state.family.cards[0]!.image).toBeUndefined()
    state = createCustomScene(state, { name: '种子场景', description: '描述' })
    const card = state.family.cards[0]!, scene = state.family.scenes[0]!
    state = { ...state, family: { ...state.family,
      cards: Array.from({ length: 200 }, (_, i) => ({ ...card, id: `custom-card-${i}`, label: `卡${i}`, archived: true })),
      scenes: Array.from({ length: 50 }, (_, i) => ({ ...scene, id: `custom-scene-${i}`, name: `场景${i}`, archived: true })),
    } }
    expect(validateState(state).valid).toBe(true)
    expect(() => createCustomCard(state, { label: '超限卡', category: 'daily' })).toThrow('200')
    expect(() => createCustomScene(state, { name: '超限场景', description: '描述' })).toThrow('50')
  })
  it('图片快照累计超过本机容量时拒绝变更，保留先前可保存的状态', () => {
    let state = createCustomCard(createState(), { label: '大照片', category: 'daily', image: headerImage(MAX_CARD_IMAGE_BYTES) })
    const card = state.family.cards[0]!
    state = createRoom(state, context)
    let rejected = false
    for (let i = 0; i < 20; i++) {
      const before = JSON.stringify(state)
      try { state = addCard(state, card) } catch (error) {
        expect((error as Error).message).toContain('空间不足')
        expect(JSON.stringify(state)).toBe(before); rejected = true; break
      }
    }
    expect(rejected).toBe(true); expect(validateState(state).valid).toBe(true)
  })

  it('管理表单的空含义说明可进入沟通、保存原始表达并重新读取', () => {
    let state = createCustomCard(createState(), { label: '我的水杯', category: 'daily', description: '', image: png })
    const card = state.family.cards[0]!
    state = createRoom(state, context)
    state = recordRawExpression(addCard(state, card))
    expect(getActiveRoom(state)!.turns[0]!.finalText).toBe('我的水杯')
    const loaded = validateState(JSON.parse(JSON.stringify(state)))
    expect(loaded.valid).toBe(true)
    if (loaded.valid) expect(loaded.state.rooms[0]!.turns[0]!.cardSnapshot[0]!.image).toBe(png)
  })

})
