import { getCommunicationPreferences } from './preferences'
import { ensureStateCapacity } from './capacity'
import { CARDS, CORE_CARDS, SCENES } from './content'
import type { Card, Context, CustomCard, CustomCardInput, CustomScene, CustomSceneInput, FamilyProfile, FamilyProfileInput, State, PublicProvenance } from './types'
import { validateFamily, normalizedName } from './family-validation'
export { validateFamily, cardImageInfo, MAX_CARD_IMAGE_DIMENSION, MAX_FAMILY_IMAGE_BYTES } from './family-validation'
const clone = <T>(v: T): T => JSON.parse(JSON.stringify(v)) as T
const timestamp = () => new Date().toISOString()
const newId = (kind: string) => `custom-${kind}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`
function finish(state: State): State { if (!validateFamily(state.family)) throw new Error('资料格式、图片大小或关联不正确，请检查必填项、重名和容量限制'); return ensureStateCapacity(state) }
function unique(name: string, existing: { id: string; name?: string; label?: string; archived?: boolean }[], id?: string) { if (existing.some(item => item.id !== id && !item.archived && normalizedName(item.name ?? item.label!) === normalizedName(name))) throw new Error('已有同名的未归档资料，请使用不同名称') }
export function getCardLabelWarning(label: string): string | null { return [...CARDS, ...CORE_CARDS].some(card => normalizedName(card.label) === normalizedName(label)) ? '与内置卡片同名，可通过自定义图片区分；两张卡片会同时保留。' : null }
export function getAvailableProfiles(state: State): FamilyProfile[] { return state.family.profiles.filter(profile => !profile.archived) }
/** All general vocabulary remains available regardless of scene; recommendations are additive. */
export function getAvailableCards(state: State): Card[] { return [...CARDS, ...state.family.cards.filter(card => !card.archived)] }
export function getAvailableScenes(state: State): { id: string; name: string; description: string; image?: string; provenance?:PublicProvenance }[] { return [...SCENES, ...state.family.scenes.filter(scene => !scene.archived).map(scene => ({ ...scene, description: describeScene(scene) }))] }
function describeScene(scene: CustomScene) { return [scene.description, scene.activities ? `常见活动：${scene.activities}` : '', scene.objects ? `常见物品：${scene.objects}` : ''].filter(Boolean).join('\n') }
export function getRecommendedCards(state: State, sceneId: string | null): Card[] { if (!sceneId) return []; const scene = state.family.scenes.find(item => item.id === sceneId && !item.archived); return getAvailableCards(state).filter(card => scene?.recommendedCardIds.includes(card.id) || state.family.cards.some(custom => custom.id === card.id && custom.sceneIds.includes(sceneId))) }
export function createFamilyProfile(state: State, input: FamilyProfileInput): State {
  if (state.family.profiles.length >= 50) throw new Error('使用人最多50位（含归档）')
  unique(input.name, state.family.profiles)
  const next = clone(state), now = timestamp()
  next.family.profiles.push({ age: null, language: '普通话', interests: '', personality: '', communicationStyle: '', observations: '', observer: '', observedAt: input.observations?.trim() ? now : null, ...clone(input), name: input.name.trim(), id: newId('profile'), archived: false, updatedAt: now })
  if (next.family.profiles.at(-1)?.image === '') delete next.family.profiles.at(-1)!.image
  return finish(next)
}
export function updateFamilyProfile(state: State, id: string, patch: Partial<FamilyProfileInput>): State {
  const next = clone(state), item = next.family.profiles.find(profile => profile.id === id)
  if (!item) throw new Error('使用人不存在')
  if (!item.archived) unique(patch.name ?? item.name, next.family.profiles, id)
  Object.assign(item, clone(patch), { id, archived: item.archived, name: (patch.name ?? item.name).trim(), updatedAt: timestamp() })
  if (patch.image === '') delete item.image
  return finish(next)
}
export function archiveFamilyProfile(state: State, id: string, archived = true): State { const next = clone(state), item = next.family.profiles.find(profile => profile.id === id); if (!item) throw new Error('使用人不存在'); if (!archived) unique(item.name, next.family.profiles, id); item.archived = archived; item.updatedAt = timestamp(); return finish(next) }
export function createCustomCard(state: State, input: CustomCardInput): State {
  if (state.family.cards.length >= 200) throw new Error('自定义卡片最多200张（含归档）')
  unique(input.label, state.family.cards)
  const next = clone(state)
  next.family.cards.push({ icon: 'book', kind: 'word', sceneIds: [], ...clone(input), label: input.label.trim(), id: newId('card'), archived: false, updatedAt: timestamp() })
  if (next.family.cards.at(-1)?.image === '') delete next.family.cards.at(-1)!.image
  return finish(next)
}
export function updateCustomCard(state: State, id: string, patch: Partial<CustomCardInput>): State {
  const next = clone(state), item = next.family.cards.find(card => card.id === id)
  if (!item) throw new Error('自定义卡片不存在')
  if (!item.archived) unique(patch.label ?? item.label, next.family.cards, id)
  Object.assign(item, clone(patch), { id, archived: item.archived, label: (patch.label ?? item.label).trim(), updatedAt: timestamp() })
  // An empty image explicitly removes a prior photo without affecting existing snapshots.
  if (patch.image === '') delete item.image
  return finish(next)
}
export function archiveCustomCard(state: State, id: string, archived = true): State { const next = clone(state), item = next.family.cards.find(card => card.id === id); if (!item) throw new Error('自定义卡片不存在'); if (!archived) unique(item.label, next.family.cards, id); item.archived = archived; item.updatedAt = timestamp(); return finish(next) }
export function createCustomScene(state: State, input: CustomSceneInput): State {
  if (state.family.scenes.length >= 50) throw new Error('自定义场景最多50个（含归档）')
  unique(input.name, [...SCENES, ...state.family.scenes])
  const next = clone(state)
  next.family.scenes.push({ activities: '', objects: '', recommendedCardIds: [], ...clone(input), name: input.name.trim(), id: newId('scene'), archived: false, updatedAt: timestamp() })
  if (next.family.scenes.at(-1)?.image === '') delete next.family.scenes.at(-1)!.image
  return finish(next)
}
export function updateCustomScene(state: State, id: string, patch: Partial<CustomSceneInput>): State {
  const next = clone(state), item = next.family.scenes.find(scene => scene.id === id)
  if (!item) throw new Error('自定义场景不存在')
  if (!item.archived) unique(patch.name ?? item.name, [...SCENES, ...next.family.scenes], id)
  Object.assign(item, clone(patch), { id, archived: item.archived, name: (patch.name ?? item.name).trim(), updatedAt: timestamp() })
  if (patch.image === '') delete item.image
  return finish(next)
}
export function archiveCustomScene(state: State, id: string, archived = true): State { const next = clone(state), item = next.family.scenes.find(scene => scene.id === id); if (!item) throw new Error('自定义场景不存在'); if (!archived) unique(item.name, [...SCENES, ...next.family.scenes], id); item.archived = archived; item.updatedAt = timestamp(); return finish(next) }

/** Capture relevant profile fields; long-term observations never become the current intent. */
export function buildRoomContext(state: State, context: Context, currentState = context.currentState ?? context.profileSnapshot?.currentState ?? ''): Context {
  const result: Context = clone(context)
  delete result.profileSnapshot; delete result.sceneSnapshot
  result.currentState = currentState
  if (context.profileId) {
    const profile = getAvailableProfiles(state).find(item => item.id === context.profileId)
    if (!profile) throw new Error('使用人已归档或不存在')
    result.communicatorName = profile.name; result.preset = profile.preset
    result.profileSnapshot = { name: profile.name, age: profile.age, language: profile.language, interests: profile.interests, personality: profile.personality, communicationStyle: profile.communicationStyle, currentState, ...(profile.image ? { image: profile.image } : {}), preferences: clone(profile.preferences ?? getCommunicationPreferences(state)) }
  }
  if (context.sceneId && context.sceneId !== 'none') {
    const scene = getAvailableScenes(state).find(item => item.id === context.sceneId)
    if (!scene) throw new Error('场景已归档或不存在')
    result.sceneSnapshot = { id: scene.id, name: scene.name, description: scene.description, ...(scene.provenance ? {provenance:clone(scene.provenance)} : {}), ...(scene.image ? { image: scene.image } : {}) }
  }
  if (context.sceneId === 'none') result.sceneId = null
  return result
}
