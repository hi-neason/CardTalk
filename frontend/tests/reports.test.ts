import { describe, expect, it } from 'vitest'
import { addCard, CARDS, createRoom, createState, isCommunicationReportStale, recordRawExpression, removeCommunicationReport, saveCommunicationReport, setFeedback, validateState } from '../src/domain'
function recorded() { return recordRawExpression(addCard(createRoom(createState(), { preset: 'children', sceneId: null, communicatorName: '体验者', source: 'unknown', participants: [] }), CARDS[0]!)) }
describe('保存分析报告', () => {
  it('保存固定统计，反馈更正标记过期，删除报告不改历史', () => {
    let state = saveCommunicationReport(recorded()), report = state.reports![0]!
    expect(report.metrics.feedback.unrecorded).toBe(1)
    expect(isCommunicationReportStale(state, report)).toBe(false)
    state = setFeedback(state, state.rooms[0]!.id, state.rooms[0]!.turns[0]!.id, 'understood', '家人', '私密反馈备注')
    expect(isCommunicationReportStale(state, report)).toBe(true)
    expect(report.metrics.feedback.understood).toBe(0)
    expect(removeCommunicationReport(state, report.id).rooms).toEqual(state.rooms)
    expect(validateState(state).valid).toBe(true)
    expect(report.sourceSignature).not.toContain('私密反馈备注')
    expect(report.sourceSignature).not.toContain('finalText')
    expect(report.sourceSignature).not.toContain('image')
  })
  it('缺失报告迁移为空，拒绝损坏指标、版本和重复证据', () => {
    const old = recorded(), migrated = validateState(old)
    expect(migrated.valid).toBe(true)
    if (migrated.valid) expect(migrated.state.reports).toEqual([])
    const state = saveCommunicationReport(old)
    for (const field of ['metricVersion','metrics','filters','evidenceIds']) {
      const bad = structuredClone(state)
      Object.assign(bad.reports![0]!, { [field]: null })
      expect(validateState(bad).valid).toBe(false)
    }
    const bad = structuredClone(state); bad.reports![0]!.metrics.confirmedCount = Infinity
    expect(validateState(bad).valid).toBe(false)
    expect(() => saveCommunicationReport(old, {from:'2026-10-15',to:'2026-10-12'})).toThrow()
  })
  it('播放和证据删除触发失效，未匹配其他使用人的变化不影响报告', () => {
    const state = saveCommunicationReport(recorded()), report = state.reports![0]!
    const changed = structuredClone(state)
    changed.rooms[0]!.turns[0]!.playbackEvents.push({id:'p',status:'completed',at:new Date().toISOString()})
    expect(isCommunicationReportStale(changed,report)).toBe(true)
    changed.rooms = []
    expect(isCommunicationReportStale(changed,report)).toBe(true)
    const photoOnly = structuredClone(state); photoOnly.rooms[0]!.turns[0]!.cardSnapshot[0]!.image = 'changed'
    expect(isCommunicationReportStale(photoOnly,report)).toBe(false)
  })
})
