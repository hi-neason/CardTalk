import { ensureStateCapacity } from './capacity'
import { CORE_CARDS } from './content'
import { getAvailableCards } from './family'
import type { Card, Context, State } from './types'
const available = (state: State) => [...getAvailableCards(state), ...CORE_CARDS]
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T
export function toggleFavoriteCard(state: State, cardId: string): State {
  const next = copy(state); next.library ??= { favoriteCardIds: [], phrases: [] }
  const ids = next.library.favoriteCardIds
  if (ids.includes(cardId)) next.library.favoriteCardIds = ids.filter(id => id !== cardId)
  else {
    if (!available(state).some(card => card.id === cardId)) throw new Error('卡片已归档或不存在')
    if (ids.length >= 500) throw new Error('收藏卡片最多500张')
    ids.push(cardId)
  }
  return ensureStateCapacity(next)
}
export function getFavoriteCards(state: State): Card[] {
  const cards = new Map(available(state).map(card => [card.id, card]))
  return (state.library?.favoriteCardIds ?? []).flatMap(id => cards.has(id) ? [cards.get(id)!] : [])
}
export function getRecentCards(state: State, context?: Context, limit = 12): Card[] {
  if (!Number.isInteger(limit) || limit <= 0) return []
  const cardsById = new Map(available(state).map(card => [card.id, card])), seen = new Set<string>(), cards: Card[] = []
  const turns = state.rooms.flatMap(room => room.turns).filter(turn => turn.contextSnapshot.profileId === context?.profileId).reverse().sort((a,b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))
  for (const turn of turns) for (const card of turn.cardSnapshot) {
    if (!cardsById.has(card.id) || seen.has(card.id)) continue
    cards.push(copy(cardsById.get(card.id)!)); seen.add(card.id)
    if (cards.length >= limit) return cards
  }
  return cards
}
export function savePhraseFromTurn(state: State, roomId: string, turnId: string): State {
  const turn = state.rooms.find(room => room.id === roomId)?.turns.find(turn => turn.id === turnId)
  if (!turn) throw new Error('找不到已确认的表达记录')
  if (state.library?.phrases.some(phrase => phrase.sourceRoomId === roomId && phrase.sourceTurnId === turnId)) return state
  const next = copy(state); next.library ??= { favoriteCardIds: [], phrases: [] }
  if (next.library.phrases.length >= 100) throw new Error('常用短句最多100条')
  next.library.phrases.push({ id: `phrase-${Date.now().toString(36)}-${Math.random().toString(36).slice(2,12)}`, text: turn.finalText, cardSnapshot: copy(turn.cardSnapshot), createdAt: new Date().toISOString(), sourceRoomId: roomId, sourceTurnId: turnId, ...(turn.contextSnapshot.profileId ? { profileId: turn.contextSnapshot.profileId } : {}) })
  return ensureStateCapacity(next)
}
export function removeSavedPhrase(state: State, phraseId: string): State {
  if (!state.library?.phrases.some(phrase => phrase.id === phraseId)) return state
  const next = copy(state)
  next.library!.phrases = next.library!.phrases.filter(phrase => phrase.id !== phraseId)
  for (const room of next.rooms) if (room.draft.savedPhraseId === phraseId) { delete room.draft.savedPhraseId; room.draft.version++ }
  return next
}
export function getSavedPhrases(state: State, context?: Context) {
  return (state.library?.phrases ?? []).filter(phrase => phrase.profileId === context?.profileId)
}
