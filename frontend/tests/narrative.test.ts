import { describe, expect, it } from 'vitest';
import { createState, validateState, saveCommunicationReport, type ReportNarrative } from '../src/domain';
import { isReportNarrative } from '../src/domain/reports';
const narrative: ReportNarrative = { text: '没有确认表达，暂不能评价沟通趋势。', createdAt: '2026-10-12T12:00:00.000Z', provider: 'deepseek', promptVersion: 'report-test-v1' };
describe('saved report narrative', () => {
  it('preserves a separately stored narrative across serialization while accepting older reports without one', () => {
    const old = saveCommunicationReport(createState()); expect(validateState(JSON.parse(JSON.stringify(old))).valid).toBe(true);
    const input = { ...narrative }; const next = saveCommunicationReport(old, {}, input); input.text = '调用方后来修改的文本';
    const loaded = validateState(JSON.parse(JSON.stringify(next))); expect(loaded.valid).toBe(true);
    if (loaded.valid) { expect(loaded.state.reports![0]).not.toHaveProperty('narrative'); expect(loaded.state.reports![1]!.narrative!.text).toBe('没有确认表达，暂不能评价沟通趋势。'); expect(loaded.state.reports![1]!.metrics).toEqual(loaded.state.reports![0]!.metrics); }
  });
  it('rejects malformed metadata, unknown providers and oversized narrative text without adding a report', () => {
    const state = createState();
    for (const invalid of [{ ...narrative, provider: 'other' }, { ...narrative, text: '' }, { ...narrative, text: '长'.repeat(1501) }, { ...narrative, createdAt: 'not-a-date' }, { ...narrative, promptVersion: '' }]) {
      expect(isReportNarrative(invalid)).toBe(false); expect(() => saveCommunicationReport(state, {}, invalid as ReportNarrative)).toThrow();
    }
    expect(state.reports || []).toHaveLength(0);
  });
});
