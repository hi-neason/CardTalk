import { ensureStateCapacity } from './capacity'
import { updateFamilyProfile } from './family'
import type { CommunicationPreferences, Context, State } from './types'

export const DEFAULT_COMMUNICATION_PREFERENCES: Readonly<CommunicationPreferences> = Object.freeze({
  displayMode: 'both', density: 'comfortable', fontSize: 'normal', rate: 0.9,
  volume: 1, remoteVoiceKey: '', deviceVoiceURI: '', touchGuardMs: 0,
})
export function isCommunicationPreferences(value: unknown): value is CommunicationPreferences {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const p = value as Record<string, unknown>
  return ['both', 'images', 'text'].includes(p.displayMode as string)
    && ['comfortable', 'compact'].includes(p.density as string)
    && ['normal', 'large'].includes(p.fontSize as string)
    && typeof p.rate === 'number' && Number.isFinite(p.rate) && p.rate >= 0.5 && p.rate <= 1.5
    && typeof p.volume === 'number' && Number.isFinite(p.volume) && p.volume >= 0 && p.volume <= 1
    && (p.remoteVoiceKey === undefined || typeof p.remoteVoiceKey === 'string' && (p.remoteVoiceKey === '' || /^[A-Za-z0-9_-]{1,100}$/.test(p.remoteVoiceKey)))
    && typeof p.deviceVoiceURI === 'string' && p.deviceVoiceURI.length <= 200
    && typeof p.touchGuardMs === 'number' && Number.isInteger(p.touchGuardMs) && p.touchGuardMs >= 0 && p.touchGuardMs <= 1500
}
export function getCommunicationPreferences(state: State, context?: Context): CommunicationPreferences {
  return { ...(context?.profileSnapshot?.preferences ?? state.settings.preferences ?? DEFAULT_COMMUNICATION_PREFERENCES) }
}
export function updateGlobalPreferences(state: State, patch: Partial<CommunicationPreferences>): State {
  const preferences = { ...getCommunicationPreferences(state), ...patch }
  if (!isCommunicationPreferences(preferences)) throw new Error('沟通偏好格式或范围不正确')
  return ensureStateCapacity({ ...state, settings: { ...state.settings, preferences, fontSize: preferences.fontSize, rate: preferences.rate } })
}
export function updateFamilyPreferences(state: State, profileId: string, patch: Partial<CommunicationPreferences>): State {
  const profile = state.family.profiles.find(item => item.id === profileId)
  if (!profile) throw new Error('使用人不存在')
  const preferences = { ...(profile.preferences ?? getCommunicationPreferences(state)), ...patch }
  if (!isCommunicationPreferences(preferences)) throw new Error('沟通偏好格式或范围不正确')
  return updateFamilyProfile(state, profileId, { preferences })
}
