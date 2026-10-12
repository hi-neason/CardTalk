import type { Card, Emotion, SelectedEmotion } from './types'

type ExplicitEmotion = Exclude<Emotion, 'neutral'>
const cardEmotions: Readonly<Record<string, ExplicitEmotion>> = {
  happy: 'happy',
  sad: 'sad',
  angry: 'angry',
  afraid: 'fearful',
}

const negationCardIds = new Set(['not', 'no', 'core-no', 'core-refuse'])

/** Read explicit selected emotion cards only. This does not infer a person's mood. */
export function deriveSelectedEmotion(cards: readonly Card[], enabled: boolean): SelectedEmotion | null {
  if (!enabled) return null
  const selectedEmotions: ExplicitEmotion[] = []
  for (const card of cards) {
    if (card.kind !== 'word' || card.category !== 'feelings' || !Object.hasOwn(cardEmotions, card.id)) continue
    const emotion = cardEmotions[card.id]!
    if (!selectedEmotions.includes(emotion)) selectedEmotions.push(emotion)
  }
  const negated = cards.some(card => negationCardIds.has(card.id))
  return {
    emotion: !negated && selectedEmotions.length === 1 ? selectedEmotions[0]! : 'neutral',
    conflict: selectedEmotions.length > 1,
    selectedEmotions,
    ...(negated ? { suppressionReason: 'negation' as const } : {}),
  }
}
