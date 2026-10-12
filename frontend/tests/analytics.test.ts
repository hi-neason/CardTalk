import { describe, expect, it } from 'vitest'
import { compareCommunication, analyzeCommunication, selectAnalyticsEntries } from '../src/domain'
import type { Context, Room, Turn } from '../src/domain'
const context: Context = { preset: 'children', sceneId: null, participants: [], communicatorName: '体验者', source: 'unknown' }
function turn(id: string, fields: Partial<Turn> = {}): Turn {
  return { id, createdAt: new Date(2026, 9, 12, 12).toISOString(), cardSnapshot: [], contextSnapshot: context, finalText: '我想吃苹果', source: 'unknown', wasEdited: false, kind: 'raw', playbackEvents: [], feedback: null, ...fields }
}
function room(id: string, turns: Turn[]): Room {
  return { id, turns, context, startedAt: new Date(2026, 9, 1).toISOString(), endedAt: null, status: 'active', draft: { cards: [], version: 0, expansion: null, confirmedText: null, selectedCandidateId: null, confirmedTurnId: null } }
}
describe('本机沟通分析', () => {
  it('空历史与未确认草稿不计为表达或会话', () => {
    const draftRoom = room('draft', [])
    draftRoom.draft.cards = [{ id: 'apple', label: '苹果', category: 'fruit', icon: 'apple', kind: 'word' }]
    expect(analyzeCommunication([draftRoom])).toMatchObject({ confirmedCount: 0, roomCount: 0, feedbackCoverage: null, understoodRate: null, topCards: [], dailyCounts: [] })
  })
  it('本地日期按表达时间包含起止整日，会话数量由匹配表达去重', () => {
    const at = (day: number, hour: number, minute = 0, second = 0, ms = 0) => new Date(2026, 9, day, hour, minute, second, ms).toISOString()
    const rooms = [room('one', [turn('before', { createdAt: at(11, 23, 59, 59, 999) }), turn('first', { createdAt: at(12, 0) }), turn('last', { createdAt: at(12, 23, 59, 59, 999) }), turn('after', { createdAt: at(13, 0) })]), room('other', [turn('outside', { createdAt: at(10, 0) })])]
    const filters = { from: '2026-10-12', to: '2026-10-12' }
    expect(selectAnalyticsEntries(rooms, filters).map(entry => entry.turn.id)).toEqual(['first', 'last'])
    expect(analyzeCommunication(rooms, filters)).toMatchObject({ confirmedCount: 2, roomCount: 1, dailyCounts: [{ date: '2026-10-12', count: 2 }] })
    expect(analyzeCommunication(rooms, { from: '2026-10-13', to: '2026-10-12' }).confirmedCount).toBe(0)
  })
  it('档案和场景筛选读取表达快照，支持体验者和未指定场景', () => {
    const rooms = [room('one', [turn('child', { contextSnapshot: { ...context, profileId: 'child', sceneId: 'custom-garden', sceneSnapshot: { id: 'custom-garden', name: '过去的花园', description: '' } } }), turn('adult', { contextSnapshot: { ...context, profileId: 'adult', sceneId: 'kitchen' } }), turn('trial')])]
    rooms[0]!.context = { ...context, profileId: 'new', sceneId: 'bedroom' }
    expect(analyzeCommunication(rooms, { profileId: 'child' }).sceneCounts).toEqual([{ id: 'custom-garden', label: '过去的花园', count: 1 }])
    expect(analyzeCommunication(rooms, { profileId: 'trial', sceneId: 'none' }).confirmedCount).toBe(1)
    expect(analyzeCommunication(rooms, { profileId: 'all', sceneId: 'kitchen' }).sceneCounts).toEqual([{ id: 'kitchen', label: '厨房', count: 1 }])
    expect(analyzeCommunication(rooms, { profileId: 'child', sceneId: 'kitchen' }).confirmedCount).toBe(0)
  })
  it('卡片按快照ID统计每次表达至多一次，不合并同名卡片且不修改历史', () => {
    const card = { id: 'custom-apple', label: '旧苹果', category: 'fruit', icon: 'apple', kind: 'word' as const }
    const rooms = [room('one', [turn('one', { cardSnapshot: [card, card, { ...card, id: 'different' }] }), turn('two', { cardSnapshot: [{ ...card, label: '新苹果' }] })])]
    const before = JSON.stringify(rooms)
    expect(analyzeCommunication(rooms).topCards).toEqual([{ id: 'custom-apple', label: '旧苹果', count: 2 }, { id: 'different', label: '旧苹果', count: 1 }])
    expect(JSON.stringify(rooms)).toBe(before)
  })
  it('播放完成不等于理解，理解率仅以明确反馈为分母', () => {
    const feedback = (value: NonNullable<Turn['feedback']>['value']) => ({ value, by: '家人', at: new Date().toISOString() })
    const rooms = [room('one', [turn('played', { playbackEvents: [{ id: 'p', status: 'completed', at: new Date().toISOString() }] }), turn('unrecorded', { feedback: feedback('unrecorded') }), turn('yes', { feedback: feedback('understood') }), turn('clarify', { feedback: feedback('clarify') }), turn('no', { feedback: feedback('not-understood') })])]
    expect(analyzeCommunication(rooms)).toMatchObject({ confirmedCount: 5, feedback: { understood: 1, clarify: 1, notUnderstood: 1, unrecorded: 2 }, feedbackCoverage: 3 / 5, understoodRate: 1 / 3 })
    expect(analyzeCommunication([room('none', [turn('no-feedback')])])).toMatchObject({ feedbackCoverage: 0, understoodRate: null })
  })
  it('原始、AI候选和编辑表达互斥，来源读取记录而不推断自主性', () => {
    const rooms = [room('one', [turn('raw', { source: 'independent' }), turn('ai', { kind: 'generated', source: 'assisted' }), turn('edited', { kind: 'generated', wasEdited: true })])]
    expect(analyzeCommunication(rooms)).toMatchObject({ kind: { raw: 1, ai: 1, edited: 1 }, source: { independent: 1, assisted: 1, unknown: 1 } })
  })
})

describe('常用表达统计', () => {
  it('常用表达确认单列，不计AI候选确认', () => {
    expect(analyzeCommunication([room('saved-room', [turn('saved-turn', { kind: 'saved', sourceSavedPhraseId: 'phrase-1' })])]).kind).toEqual({ raw: 0, ai: 0, edited: 0, saved: 1 })
  })
})

describe('扩展统计口径', () => {
  it('分类单表达去重，来源与房间状态过滤一致，播放只看最后事件', () => {
    const card = { id:'a',label:'卡片',category:'daily',icon:'book',kind:'word' as const }
    const rooms = [room('active', [turn('one',{cardSnapshot:[card,card],source:'assisted',playbackEvents:[{id:'1',status:'completed',at:new Date().toISOString()},{id:'2',status:'failed',at:new Date().toISOString()}]})]), {...room('ended',[turn('two')]), status:'ended' as const, endedAt:new Date().toISOString()}]
    const report = analyzeCommunication(rooms,{source:'assisted',category:'daily',status:'active'})
    expect(report).toMatchObject({confirmedCount:1,activeDays:1,sampleWarning:true,inputCards:{total:2,average:2,max:2},categoryCounts:[{id:'daily',label:'生活',count:1}],playbackCounts:{completed:0,failed:1},process:{collectedCount:0,missingCount:1,averageSeconds:null}})
  })
  it('计时仅使用真实且非负过程样本，旧缺失不视为零秒', () => {
    const start='2026-10-12T00:00:00Z', end='2026-10-12T00:00:10Z'
    const report=analyzeCommunication([room('one',[turn('old'),turn('timed',{processSnapshot:{startedAt:start,confirmedAt:end,events:[]}}),turn('clock',{processSnapshot:{startedAt:end,confirmedAt:start,events:[]}})])])
    expect(report.process).toMatchObject({collectedCount:2,missingCount:1,usableTimingCount:1,averageSeconds:10,rejectionRate:null})
  })
})

it('期间对比率差用百分点，无反馈期间不假定零理解', () => {
  const feedback = (value: 'understood' | 'clarify') => ({ value, by:'家人',at:new Date().toISOString() })
  const rooms = [room('r',[turn('a',{source:'assisted',feedback:feedback('understood')}),turn('b',{source:'independent',feedback:feedback('understood')}),turn('c',{source:'independent',feedback:feedback('clarify')})])]
  expect(compareCommunication(rooms,{source:'assisted'},{source:'independent'}).change).toEqual({confirmedCount:-1,understoodRate:50})
  expect(compareCommunication(rooms,{source:'unknown'},{source:'independent'}).change.understoodRate).toBeNull()
})

it('拒绝次数超过成功次数时保留原始计数但拒绝率不呈现超100%', () => {
  const at=new Date().toISOString()
  const report=analyzeCommunication([room('r',[turn('t',{processSnapshot:{startedAt:at,confirmedAt:at,events:[{id:'1',at,type:'generation-succeeded',inputVersion:1},{id:'2',at,type:'candidates-rejected',inputVersion:1},{id:'3',at,type:'candidates-rejected',inputVersion:1}]}})])])
  expect(report.process).toMatchObject({generationSucceeded:1,candidatesRejected:2,rejectionRate:null})
})
