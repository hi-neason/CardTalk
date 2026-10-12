import { describe, expect, it } from 'vitest'
import { addCard, archiveCustomCard, CARDS, clearDraft, confirmSavedPhrase, CORE_CARDS, createCustomCard, createFamilyProfile, createRoom, createState, endRoom, getFavoriteCards, getRecentCards, getSavedPhrases, nextTurn, prepareSavedPhrase, recordRawExpression, removeSavedPhrase, savePhraseFromTurn, toggleFavoriteCard, updateCustomCard, validateState } from '../src/domain'
import type { Context, State } from '../src/domain'
const context: Context = { preset: 'children', sceneId: null, participants: [], communicatorName: '体验者', source: 'independent' }
function recorded(state = createState(), ctx = context): State { return recordRawExpression(addCard(createRoom(state, ctx), CARDS[0]!)) }
function saved(state = recorded()) { const room = state.rooms.at(-1)!; return savePhraseFromTurn(state, room.id, room.turns[0]!.id) }
describe('收藏与常用短句', () => {
  it('旧状态迁移不改变输入，损坏库拒绝', () => {
    const old = recorded(); delete old.library
    const before = JSON.stringify(old), result = validateState(old)
    expect(result.valid).toBe(true)
    if (result.valid) expect(result.state.library).toEqual({ favoriteCardIds: [], phrases: [] })
    expect(JSON.stringify(old)).toBe(before)
    for (const library of [null, {}, { favoriteCardIds: ['a','a'], phrases: [] }, { favoriteCardIds: [], phrases: [{}] }]) expect(validateState({ ...old, library }).valid).toBe(false)
  })
  it('收藏可取消，归档卡隐藏而核心卡保留，最近卡按本人历史去重', () => {
    let state = createCustomCard(createState(), { label: '家里', category: 'daily' })
    const custom = state.family.cards[0]!
    state = toggleFavoriteCard(toggleFavoriteCard(state, custom.id), CORE_CARDS[0]!.id)
    state = recordRawExpression(addCard(addCard(createRoom(state, context), custom), CORE_CARDS[0]!))
    expect(getRecentCards(state).map(card => card.id)).toEqual([custom.id, CORE_CARDS[0]!.id])
    state = archiveCustomCard(state, custom.id)
    expect(getFavoriteCards(state).map(card => card.id)).toEqual([CORE_CARDS[0]!.id])
    expect(getRecentCards(state).map(card => card.id)).toEqual([CORE_CARDS[0]!.id])
    state = toggleFavoriteCard(state, custom.id)
    expect(state.library!.favoriteCardIds).not.toContain(custom.id)
    expect(() => toggleFavoriteCard(state, custom.id)).toThrow()
    expect(getRecentCards(state, { ...context, profileId: 'other' })).toEqual([])
  })
  it('短句保存来源去重，准备不自动记录，确认建立当前来源的新记录', () => {
    let state = saved(), original = state.rooms[0]!.turns[0]!
    const phrase = state.library!.phrases[0]!
    expect(savePhraseFromTurn(state, phrase.sourceRoomId, phrase.sourceTurnId)).toBe(state)
    state = createRoom(endRoom(state), { ...context, source: 'assisted', sceneId: 'park' })
    state = prepareSavedPhrase(state, phrase.id)
    expect(state.rooms[1]!.turns).toHaveLength(0)
    state = confirmSavedPhrase(state, phrase.id)
    const turn = state.rooms[1]!.turns[0]!
    expect(turn).toMatchObject({ finalText: original.finalText, kind: 'saved', sourceSavedPhraseId: phrase.id, wasEdited: false, source: 'assisted', contextSnapshot: { sceneId: 'park' }, playbackEvents: [] })
    expect(turn.expansion).toBeUndefined()
    expect(confirmSavedPhrase(state, phrase.id)).toBe(state)
    expect(validateState(state).valid).toBe(true)
    expect(nextTurn(state).rooms[1]!.draft.savedPhraseId).toBeUndefined()
  })
  it('改选卡、清除和删除短句均让旧确认失效', () => {
    const state = nextTurn(saved()), phrase = state.library!.phrases[0]!
    const prepared = prepareSavedPhrase(state, phrase.id)
    for (const changed of [addCard(prepared, CARDS[1]!), clearDraft(prepared), removeSavedPhrase(prepared, phrase.id)]) {
      expect(changed.rooms[0]!.draft.savedPhraseId).toBeUndefined()
      expect(() => confirmSavedPhrase(changed, phrase.id)).toThrow()
    }
    expect(endRoom(prepared).rooms[0]!.draft.savedPhraseId).toBeUndefined()
  })
  it('短句隔离使用人，归档原卡仍能重用完整已确认文本', () => {
    let state = createFamilyProfile(createCustomCard(createState(), { label: '旧卡', category: 'daily' }), { name: '乐乐', preset: 'children' })
    const profileId = state.family.profiles[0]!.id, custom = state.family.cards[0]!
    state = saved(recordRawExpression(addCard(createRoom(state, { ...context, profileId }), custom)))
    const phrase = state.library!.phrases[0]!
    expect(getSavedPhrases(state)).toEqual([])
    expect(getSavedPhrases(state, { ...context, profileId })).toHaveLength(1)
    state = createRoom(endRoom(archiveCustomCard(state, custom.id)), context)
    expect(() => prepareSavedPhrase(state, phrase.id)).toThrow()
    state = createRoom(endRoom(state), { ...context, profileId })
    state = confirmSavedPhrase(prepareSavedPhrase(state, phrase.id), phrase.id)
    expect(state.rooms.at(-1)!.turns[0]!.finalText).toBe('旧卡')
    expect(validateState(state).valid).toBe(true)
    const corrupted = structuredClone(state); corrupted.rooms.at(-1)!.turns[0]!.wasEdited = true
    expect(validateState(corrupted).valid).toBe(false)
  })
})

it('最近卡历史仅决定排序，新表达读取当前可选目录的文字与图片', () => {
  let state = createCustomCard(createState(), { label: '旧文字', category: 'daily' })
  const original = state.family.cards[0]!
  state = recordRawExpression(addCard(createRoom(state, context), original))
  state = updateCustomCard(state, original.id, { label: '新文字', icon: 'apple' })
  expect(getRecentCards(state)[0]).toMatchObject({id:original.id,label:'新文字',icon:'apple'})
  expect(state.rooms[0]!.turns[0]!.cardSnapshot[0]!.label).toBe('旧文字')
})
