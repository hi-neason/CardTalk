import { isCommunicationPreferences } from './preferences'
import { CARDS, CORE_CARDS, CATEGORIES, SCENES } from './content'
import type { Family, FamilyProfile, CustomCard, CustomScene, ProfileSnapshot, SceneSnapshot, PublicProvenance } from './types'
export const MAX_FAMILY_IMAGE_BYTES = 2 * 1024 * 1024
export const MAX_CARD_IMAGE_DIMENSION = 1024
const obj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)
const text = (v: unknown, max: number, required = false): v is string => typeof v === 'string' && v.length <= max && (!required || !!v.trim())
const timestamp = (v: unknown) => text(v, 40, true) && Number.isFinite(Date.parse(v))
const age = (v: unknown) => v === null || typeof v === 'number' && Number.isInteger(v) && v >= 0 && v <= 120
const ids = (v: unknown, max: number): v is string[] => Array.isArray(v) && v.length <= max && v.every(id => text(id, 100, true)) && new Set(v).size === v.length
export const normalizedName = (v: string) => v.trim().toLocaleLowerCase()

// Callers validate the base64 alphabet, padding and length before decoding.
function decodeBase64(value: string): Uint8Array {
  if (typeof atob === 'function') return Uint8Array.from(atob(value), char => char.charCodeAt(0))
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'
  const padding = value.endsWith('==') ? 2 : value.endsWith('=') ? 1 : 0
  const bytes = new Uint8Array(value.length / 4 * 3 - padding)
  let offset = 0
  for (let i = 0; i < value.length; i += 4) {
    const a = alphabet.indexOf(value[i]!), b = alphabet.indexOf(value[i + 1]!)
    const c = value[i + 2] === '=' ? 0 : alphabet.indexOf(value[i + 2]!)
    const d = value[i + 3] === '=' ? 0 : alphabet.indexOf(value[i + 3]!)
    const block = a << 18 | b << 12 | c << 6 | d
    bytes[offset++] = block >>> 16 & 255
    if (offset < bytes.length) bytes[offset++] = block >>> 8 & 255
    if (offset < bytes.length) bytes[offset++] = block & 255
  }
  return bytes
}

/** Read dimensions from supported raster headers. SVG and remote URLs are never accepted. */
export function cardImageInfo(image: unknown): { bytes: number; width: number; height: number } | null {
  if (typeof image !== 'string') return null
  const match = /^data:image\/(png|jpeg|webp);base64,([A-Za-z0-9+/]+={0,2})$/.exec(image)
  if (!match || match[2]!.length % 4 !== 0) return null
  try {
    const data = decodeBase64(match[2]!)
    const be16 = (i: number) => data[i]! * 256 + data[i + 1]!
    const be32 = (i: number) => data[i]! * 16777216 + data[i + 1]! * 65536 + data[i + 2]! * 256 + data[i + 3]!
    const le24 = (i: number) => data[i]! + data[i + 1]! * 256 + data[i + 2]! * 65536
    const ascii = (i: number, n: number) => String.fromCharCode(...data.slice(i, i + n))
    let width = 0, height = 0
    if (match[1] === 'png') {
      if (data.length < 33 || ascii(1, 3) !== 'PNG' || data[0] !== 137 || ascii(12, 4) !== 'IHDR' || be32(8) !== 13) return null
      width = be32(16); height = be32(20)
    } else if (match[1] === 'jpeg') {
      if (data[0] !== 255 || data[1] !== 216) return null
      let i = 2
      while (i + 8 < data.length) {
        if (data[i++] !== 255) return null
        while (data[i] === 255) i++
        const marker = data[i++]!
        if (marker === 217 || marker === 218) break
        if (marker === 1 || marker >= 208 && marker <= 215) continue
        const length = be16(i)
        if (length < 2 || i + length > data.length) return null
        if ([192, 193, 194, 195, 197, 198, 199, 201, 202, 203, 205, 206, 207].includes(marker)) { height = be16(i + 3); width = be16(i + 5); break }
        i += length
      }
    } else {
      if (data.length < 30 || ascii(0, 4) !== 'RIFF' || ascii(8, 4) !== 'WEBP') return null
      const format = ascii(12, 4)
      if (format === 'VP8X') { width = le24(24) + 1; height = le24(27) + 1 }
      else if (format === 'VP8L' && data[20] === 47) { width = 1 + (data[21]! | (data[22]! & 63) << 8); height = 1 + ((data[22]! >> 6) | data[23]! << 2 | (data[24]! & 15) << 10) }
      else if (format === 'VP8 ' && data[23] === 157 && data[24] === 1 && data[25] === 42) { width = (data[26]! | data[27]! << 8) & 16383; height = (data[28]! | data[29]! << 8) & 16383 }
    }
    return Number.isInteger(width) && Number.isInteger(height) && width > 0 && height > 0 && width <= MAX_CARD_IMAGE_DIMENSION && height <= MAX_CARD_IMAGE_DIMENSION ? { bytes: data.length, width, height } : null
  } catch { return null }
}
export const isCardImage = (image: unknown) => cardImageInfo(image) !== null
export function isProfileSnapshot(v: unknown): v is ProfileSnapshot {
  return obj(v) && !Object.hasOwn(v, 'observations') && !Object.hasOwn(v, 'observer') && text(v.name, 100, true) && (v.preferences === undefined || isCommunicationPreferences(v.preferences)) && (v.image === undefined || isCardImage(v.image)) && age(v.age) && text(v.language, 80) && text(v.interests, 500) && text(v.personality, 500) && text(v.communicationStyle, 500) && text(v.currentState, 500)
}
export function isPublicProvenance(v:unknown):v is PublicProvenance { return obj(v) && Object.keys(v).every(k=>['packageId','title','source','license','revision'].includes(k)) && text(v.packageId,40,true) && /^[a-z0-9][a-z0-9-]{0,39}$/.test(v.packageId) && text(v.title,100,true) && text(v.source,500,true) && text(v.license,500,true) && (v.revision===undefined || typeof v.revision==='number' && Number.isSafeInteger(v.revision) && v.revision>=0) }
export function isSceneSnapshot(v: unknown): v is SceneSnapshot { return obj(v) && (v.provenance === undefined || isPublicProvenance(v.provenance)) && text(v.id, 100, true) && text(v.name, 100, true) && (v.image === undefined || isCardImage(v.image)) && text(v.description, 2200) }
export function isFamilyProfile(v: unknown): v is FamilyProfile {
  return obj(v) && text(v.id, 100, true) && v.id.startsWith('custom-profile-') && text(v.name, 100, true) && (v.preferences === undefined || isCommunicationPreferences(v.preferences)) && (v.image === undefined || isCardImage(v.image)) && ['children', 'adult'].includes(v.preset as string) && age(v.age) && text(v.language, 80) && text(v.interests, 500) && text(v.personality, 500) && text(v.communicationStyle, 500) && text(v.observations, 1000) && text(v.observer, 100) && (v.observedAt === null || timestamp(v.observedAt)) && (!v.observations.trim() || !!v.observer.trim() && v.observedAt !== null) && typeof v.archived === 'boolean' && timestamp(v.updatedAt)
}
export function isCustomCard(v: unknown): v is CustomCard {
  return obj(v) && (v.provenance === undefined || isPublicProvenance(v.provenance)) && text(v.id, 100, true) && v.id.startsWith('custom-card-') && text(v.label, 50, true) && CATEGORIES.some(category => category.id === v.category) && text(v.icon, 100, true) && /^[a-z0-9-]+$/.test(v.icon) && ['word', 'phrase'].includes(v.kind as string) && (v.description === undefined || text(v.description, 500)) && (v.image === undefined || isCardImage(v.image)) && ids(v.sceneIds, 50) && typeof v.archived === 'boolean' && timestamp(v.updatedAt)
}
export function isCustomScene(v: unknown): v is CustomScene {
  return obj(v) && (v.provenance === undefined || isPublicProvenance(v.provenance)) && text(v.id, 100, true) && v.id.startsWith('custom-scene-') && text(v.name, 100, true) && (v.image === undefined || isCardImage(v.image)) && text(v.description, 1000, true) && text(v.activities, 500) && text(v.objects, 500) && ids(v.recommendedCardIds, 70) && typeof v.archived === 'boolean' && timestamp(v.updatedAt)
}
export function validateFamily(value: unknown): value is Family {
  if (!obj(value) || !Array.isArray(value.profiles) || value.profiles.length > 50 || !value.profiles.every(isFamilyProfile) || !Array.isArray(value.cards) || value.cards.length > 200 || !value.cards.every(isCustomCard) || !Array.isArray(value.scenes) || value.scenes.length > 50 || !value.scenes.every(isCustomScene)) return false
  for (const items of [value.profiles, value.cards, value.scenes]) if (new Set(items.map(item => item.id)).size !== items.length) return false
  const activeNamesUnique = (items: { archived: boolean; name?: string; label?: string }[]) => { const names = items.filter(item => !item.archived).map(item => normalizedName(item.name ?? item.label!)); return new Set(names).size === names.length }
  if (!activeNamesUnique(value.profiles) || !activeNamesUnique(value.cards) || !activeNamesUnique(value.scenes)) return false
  if (value.scenes.some(scene => !scene.archived && SCENES.some(builtin => normalizedName(builtin.name) === normalizedName(scene.name)))) return false
  const sceneIds = new Set([...SCENES.map(scene => scene.id), ...value.scenes.map(scene => scene.id)])
  const cardIds = new Set([...CARDS, ...CORE_CARDS, ...value.cards].map(card => card.id))
  return value.cards.every(card => card.sceneIds.every(id => sceneIds.has(id))) && value.scenes.every(scene => scene.recommendedCardIds.every(id => cardIds.has(id))) && [...value.profiles, ...value.cards, ...value.scenes].reduce((sum, item) => sum + (item.image ? cardImageInfo(item.image)!.bytes : 0), 0) <= MAX_FAMILY_IMAGE_BYTES
}
