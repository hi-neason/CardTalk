import { CATEGORIES, SCENES } from './content'
import type { Room, Source, Turn } from './types'

export interface AnalyticsFilters { profileId?: string; sceneId?: string; from?: string; to?: string; source?: Source | 'all'; category?: string; status?: 'active' | 'ended' | 'all' }
export interface AnalyticsCount { id: string; label: string; count: number }
export interface CommunicationAnalytics {
  activeDays: number
  sampleWarning: boolean
  inputCards: { total: number; average: number; max: number }
  categoryCounts: AnalyticsCount[]
  playbackCounts: { completed: number; failed: number; interrupted: number; unavailable: number; startedOnly: number; neverPlayed: number }
  process: { collectedCount: number; missingCount: number; usableTimingCount: number; averageSeconds: number | null; generationStarted: number; generationSucceeded: number; generationFailed: number; generationCancelled: number; candidatesRejected: number; candidateEditStarted: number; rejectionRate: number | null }
  confirmedCount: number
  roomCount: number
  feedback: { understood: number; clarify: number; notUnderstood: number; unrecorded: number }
  /** Ratios are 0..1; null means there is no denominator. */
  feedbackCoverage: number | null
  understoodRate: number | null
  kind: { raw: number; ai: number; edited: number; saved: number }
  source: Record<Source, number>
  topCards: AnalyticsCount[]
  sceneCounts: AnalyticsCount[]
  dailyCounts: { date: string; count: number }[]
}

function localDay(timestamp: string): string | null {
  const date = new Date(timestamp)
  if (!Number.isFinite(date.getTime())) return null
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}
function increment(counts: Map<string, AnalyticsCount>, id: string, label: string) {
  const entry = counts.get(id)
  if (entry) entry.count++
  else counts.set(id, { id, label, count: 1 })
}
const ranked = (counts: Map<string, AnalyticsCount>) => [...counts.values()].sort((a, b) => b.count - a.count || a.id.localeCompare(b.id))

/** Counts confirmed history only, without inferring understanding from speech playback. */
export function analyzeCommunication(rooms: Room[], filters: AnalyticsFilters = {}): CommunicationAnalytics {
  const result: CommunicationAnalytics = {
    activeDays: 0, sampleWarning: true, inputCards: { total: 0, average: 0, max: 0 }, categoryCounts: [],
    playbackCounts: { completed: 0, failed: 0, interrupted: 0, unavailable: 0, startedOnly: 0, neverPlayed: 0 },
    process: { collectedCount: 0, missingCount: 0, usableTimingCount: 0, averageSeconds: null, generationStarted: 0, generationSucceeded: 0, generationFailed: 0, generationCancelled: 0, candidatesRejected: 0, candidateEditStarted: 0, rejectionRate: null },
    confirmedCount: 0, roomCount: 0,
    feedback: { understood: 0, clarify: 0, notUnderstood: 0, unrecorded: 0 },
    feedbackCoverage: null, understoodRate: null,
    kind: { raw: 0, ai: 0, edited: 0, saved: 0 }, source: { independent: 0, assisted: 0, unknown: 0 },
    topCards: [], sceneCounts: [], dailyCounts: [],
  }
  let totalSeconds = 0
  const categories = new Map<string, AnalyticsCount>()
  const cards = new Map<string, AnalyticsCount>(), scenes = new Map<string, AnalyticsCount>(), days = new Map<string, number>()
  const entries = selectAnalyticsEntries(rooms, filters)
  result.roomCount = new Set(entries.map(entry => entry.roomId)).size
  for (const { turn } of entries) {
    const context = turn.contextSnapshot, date = localDay(turn.createdAt)!
    const sceneId = context.sceneId ?? 'none'
    result.confirmedCount++
    result.inputCards.total += turn.cardSnapshot.length
    result.inputCards.max = Math.max(result.inputCards.max, turn.cardSnapshot.length)
    for (const category of new Set(turn.cardSnapshot.map(card => card.category))) increment(categories, category, CATEGORIES.find(item => item.id === category)?.name ?? (category === 'core' ? '核心表达' : category))
    const playback = turn.playbackEvents.at(-1)?.status
    result.playbackCounts[!playback ? 'neverPlayed' : playback === 'started' ? 'startedOnly' : playback]++
    if (turn.processSnapshot) {
      result.process.collectedCount++
      const seconds = (Date.parse(turn.processSnapshot.confirmedAt) - Date.parse(turn.processSnapshot.startedAt)) / 1000
      if (Number.isFinite(seconds) && seconds >= 0) { result.process.usableTimingCount++; totalSeconds += seconds }
      const keys = { 'generation-started': 'generationStarted', 'generation-succeeded': 'generationSucceeded', 'generation-failed': 'generationFailed', 'generation-cancelled': 'generationCancelled', 'candidates-rejected': 'candidatesRejected', 'candidate-edit-started': 'candidateEditStarted' } as const
      for (const event of turn.processSnapshot.events) { const key = keys[event.type as keyof typeof keys]; if (key) result.process[key]++ }
    } else result.process.missingCount++
    result.source[turn.source]++
    result.kind[turn.kind === 'saved' ? 'saved' : turn.wasEdited ? 'edited' : turn.kind === 'raw' ? 'raw' : 'ai']++
    const feedback = turn.feedback?.value ?? 'unrecorded'
    result.feedback[feedback === 'not-understood' ? 'notUnderstood' : feedback]++
    const selected = new Set<string>()
    for (const card of turn.cardSnapshot) {
      if (!selected.has(card.id)) increment(cards, card.id, card.label)
      selected.add(card.id)
    }
    const sceneLabel = context.sceneSnapshot?.name ?? SCENES.find(scene => scene.id === sceneId)?.name ?? sceneId
    increment(scenes, sceneId, sceneLabel)
    days.set(date, (days.get(date) ?? 0) + 1)
  }
  const recorded = result.confirmedCount - result.feedback.unrecorded
  result.feedbackCoverage = result.confirmedCount ? recorded / result.confirmedCount : null
  result.understoodRate = recorded ? result.feedback.understood / recorded : null
  result.inputCards.average = result.confirmedCount ? result.inputCards.total / result.confirmedCount : 0
  result.activeDays = days.size
  result.sampleWarning = result.confirmedCount < 5
  result.categoryCounts = ranked(categories)
  result.process.averageSeconds = result.process.usableTimingCount ? totalSeconds / result.process.usableTimingCount : null
  result.process.rejectionRate = result.process.generationSucceeded && result.process.candidatesRejected <= result.process.generationSucceeded ? result.process.candidatesRejected / result.process.generationSucceeded : null
  result.topCards = ranked(cards)
  result.sceneCounts = ranked(scenes)
  result.dailyCounts = [...days].sort(([a], [b]) => a.localeCompare(b)).map(([date, count]) => ({ date, count }))
  return result
}

export interface AnalyticsEntry { roomId: string; turn: Turn }
/** Returns references to history for read-only drill-down using the same filters as totals. */
export function selectAnalyticsEntries(rooms: Room[], filters: AnalyticsFilters = {}): AnalyticsEntry[] {
  return rooms.flatMap(room => room.turns.filter(turn => {
    const context = turn.contextSnapshot, date = localDay(turn.createdAt)
    if (!date || (filters.from && date < filters.from) || (filters.to && date > filters.to)) return false
    if (filters.profileId && filters.profileId !== 'all' && (context.profileId ?? 'trial') !== filters.profileId) return false
    if (filters.sceneId && filters.sceneId !== 'all' && (context.sceneId ?? 'none') !== filters.sceneId) return false
    if (filters.source && filters.source !== 'all' && turn.source !== filters.source) return false
    if (filters.category && filters.category !== 'all' && !turn.cardSnapshot.some(card => card.category === filters.category)) return false
    if (filters.status && filters.status !== 'all' && room.status !== filters.status) return false
    return true
  }).map(turn => ({ roomId: room.id, turn })))
}

export function compareCommunication(rooms: Room[], currentFilters: AnalyticsFilters, previousFilters: AnalyticsFilters) {
  const current = analyzeCommunication(rooms, currentFilters), previous = analyzeCommunication(rooms, previousFilters)
  return { current, previous, change: { confirmedCount: current.confirmedCount - previous.confirmedCount, understoodRate: current.understoodRate === null || previous.understoodRate === null ? null : (current.understoodRate - previous.understoodRate) * 100 } }
}
