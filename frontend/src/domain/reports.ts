import { analyzeCommunication, selectAnalyticsEntries, type AnalyticsFilters, type CommunicationAnalytics } from './analytics'
import { ensureStateCapacity, MAX_LOCAL_STATE_CHARACTERS } from './capacity'
import type { SavedReport, ReportNarrative, State } from './types'
const obj = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const text = (v: unknown, max = 100) => typeof v === 'string' && v.length > 0 && v.length <= max
const count = (v: unknown) => typeof v === 'number' && Number.isSafeInteger(v) && v >= 0
const positive = (v: unknown) => typeof v === 'number' && Number.isFinite(v) && v >= 0
const rate = (v: unknown) => v === null || positive(v) && (v as number) <= 1
const counts = (v: unknown, keys: string[]) => obj(v) && keys.every(key => count(v[key]))
const date = (v: unknown) => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(v) && Number.isFinite(Date.parse(`${v}T00:00:00Z`)) && new Date(`${v}T00:00:00Z`).toISOString().slice(0,10) === v
export function isAnalyticsFilters(value: unknown): value is AnalyticsFilters {
  return obj(value) && ['profileId','sceneId','category'].every(key => value[key] === undefined || text(value[key]))
    && (value.source === undefined || ['all','independent','assisted','unknown'].includes(value.source as string))
    && (value.status === undefined || ['all','active','ended'].includes(value.status as string))
    && (value.from === undefined || date(value.from)) && (value.to === undefined || date(value.to))
    && (!value.from || !value.to || (value.from as string) <= (value.to as string))
}
export function isCommunicationAnalytics(v: unknown): v is CommunicationAnalytics {
  if (!obj(v) || !counts(v, ['confirmedCount','roomCount','activeDays']) || typeof v.sampleWarning !== 'boolean' || !rate(v.feedbackCoverage) || !rate(v.understoodRate)) return false
  if (!counts(v.feedback, ['understood','clarify','notUnderstood','unrecorded']) || !counts(v.kind, ['raw','ai','edited','saved']) || !counts(v.source, ['independent','assisted','unknown']) || !counts(v.playbackCounts, ['completed','failed','interrupted','unavailable','startedOnly','neverPlayed'])) return false
  if (!obj(v.inputCards) || !counts(v.inputCards, ['total','max']) || !positive(v.inputCards.average)) return false
  for (const key of ['topCards','sceneCounts','categoryCounts']) {
    const rows = v[key]
    if (!Array.isArray(rows) || rows.length > 10000 || !rows.every(row => obj(row) && text(row.id) && text(row.label, 100) && count(row.count)) || new Set(rows.map(row => row.id)).size !== rows.length) return false
  }
  if (!Array.isArray(v.dailyCounts) || !v.dailyCounts.every(row => obj(row) && date(row.date) && count(row.count)) || new Set(v.dailyCounts.map(row => row.date)).size !== v.dailyCounts.length) return false
  if (!obj(v.process) || !counts(v.process, ['collectedCount','missingCount','usableTimingCount','generationStarted','generationSucceeded','generationFailed','generationCancelled','candidatesRejected','candidateEditStarted']) || !(v.process.averageSeconds === null || positive(v.process.averageSeconds)) || !rate(v.process.rejectionRate)) return false
  const total = v.confirmedCount as number
  for (const key of ['feedback','kind','source','playbackCounts']) if (Object.values(v[key] as Record<string,number>).reduce((a,b) => a+b,0) !== total) return false
  return (v.process.collectedCount as number) + (v.process.missingCount as number) === total && (v.process.usableTimingCount as number) <= (v.process.collectedCount as number) && (v.roomCount as number) <= total && v.activeDays === v.dailyCounts.length && v.sampleWarning === (total < 5)
}
export function isReportNarrative(v:unknown):v is ReportNarrative { return obj(v) && text(v.text,1500) && v.provider==='deepseek' && text(v.promptVersion,100) && text(v.createdAt,40) && Number.isFinite(Date.parse(v.createdAt as string)); }
export function isSavedReport(v: unknown): v is SavedReport {
  return obj(v) && text(v.id) && typeof v.createdAt === 'string' && v.createdAt.length <= 40 && Number.isFinite(Date.parse(v.createdAt)) && v.metricVersion === 1 && (v.narrative===undefined || isReportNarrative(v.narrative)) && isAnalyticsFilters(v.filters) && isCommunicationAnalytics(v.metrics)
    && Array.isArray(v.evidenceIds) && v.evidenceIds.length <= 500000 && v.evidenceIds.every(item => obj(item) && text(item.roomId) && text(item.turnId)) && new Set(v.evidenceIds.map(item => JSON.stringify([item.roomId,item.turnId]))).size === v.evidenceIds.length && v.evidenceIds.length === v.metrics.confirmedCount
    && typeof v.sourceSignature === 'string' && v.sourceSignature.length <= MAX_LOCAL_STATE_CHARACTERS
}
function signature(state: State, filters: AnalyticsFilters): string {
  const statuses = new Map(state.rooms.map(room => [room.id,room.status]))
  return JSON.stringify(selectAnalyticsEntries(state.rooms,filters).map(({roomId,turn}) => ({ roomId, status: statuses.get(roomId), id: turn.id, createdAt: turn.createdAt, profileId: turn.contextSnapshot.profileId, sceneId: turn.contextSnapshot.sceneId, sceneName: turn.contextSnapshot.sceneSnapshot?.name, source: turn.source, kind: turn.kind, wasEdited: turn.wasEdited, cards: turn.cardSnapshot.map(({id,label,category})=>({id,label,category})), feedback: turn.feedback ? {value:turn.feedback.value,at:turn.feedback.at} : null, playback: turn.playbackEvents.map(({id,status,at})=>({id,status,at})), process: turn.processSnapshot })))
}
export function saveCommunicationReport(state: State, filters: AnalyticsFilters = {}, narrative?:ReportNarrative): State {
  if (!isAnalyticsFilters(filters)) throw new Error('报告筛选条件不正确')
  if ((state.reports?.length ?? 0) >= 50) throw new Error('本机最多保存50份报告，请先导出并整理')
  if(narrative && !isReportNarrative(narrative)) throw new Error('报告说明格式不正确')
  const report: SavedReport = { ...(narrative?{narrative:{...narrative}}:{}), id: `report-${Date.now().toString(36)}-${Math.random().toString(36).slice(2,12)}`, createdAt: new Date().toISOString(), metricVersion: 1, filters: {...filters}, metrics: analyzeCommunication(state.rooms,filters), evidenceIds: selectAnalyticsEntries(state.rooms,filters).map(({roomId,turn})=>({roomId,turnId:turn.id})), sourceSignature: signature(state,filters) }
  if (!isSavedReport(report)) throw new Error('报告数据格式不正确')
  return ensureStateCapacity({ ...state, reports: [...(state.reports ?? []), report] })
}
export function removeCommunicationReport(state: State, reportId: string): State {
  return { ...state, reports: (state.reports ?? []).filter(report=>report.id !== reportId) }
}
export function isCommunicationReportStale(state: State, report: SavedReport): boolean { return signature(state,report.filters) !== report.sourceSignature }
