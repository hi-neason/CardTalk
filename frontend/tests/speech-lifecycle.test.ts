import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
vi.mock('../src/platform/nativeSpeech', () => ({ startNativeSpeech: vi.fn() }));
vi.mock('../src/platform/nativeAudio', () => ({ prepareNativeAudio: vi.fn(async () => ({ src: '/cache/speech.mp3', dispose: vi.fn() })) }));
vi.mock('../src/platform/api', () => ({ remoteSpeech: vi.fn(() => ({ promise: Promise.resolve(new ArrayBuffer(1)), cancel: vi.fn() })) }));
import { playCardAudio, speakText, stopSpeech } from '../src/platform/speech';
import { CARDS } from '../src/domain';

function audioDevice() {
  const events: Record<string, () => void> = {};
  const audio = { destroy: vi.fn(), stop: vi.fn(), play: vi.fn(), src: '',
    onPlay: (fn: () => void) => { events.play = fn; },
    onEnded: (fn: () => void) => { events.end = fn; },
    onError: (fn: () => void) => { events.error = fn; } };
  vi.stubGlobal('uni', { createInnerAudioContext: () => audio, getFileSystemManager: () => ({ writeFileSync: vi.fn(), unlinkSync: vi.fn() }) });
  vi.stubGlobal('wx', { env: { USER_DATA_PATH: '/tmp' } });
  return { audio, events };
}
function browserSpeech() {
  let utterance: { onstart(): void; onend(): void; onerror(): void };
  const synth = { cancel: vi.fn(), getVoices: () => [], speak: vi.fn(u => { utterance = u; }) };
  vi.stubGlobal('window', { speechSynthesis: synth });
  vi.stubGlobal('SpeechSynthesisUtterance', class {});
  return { synth, events: () => utterance! };
}
beforeEach(() => vi.useFakeTimers());
afterEach(() => { stopSpeech(); vi.clearAllTimers(); vi.useRealTimers(); vi.unstubAllGlobals(); vi.clearAllMocks(); });

describe('speech lifecycle', () => {
  it('times out device start once and ignores a late start callback', async () => {
    const device = browserSpeech(), update = vi.fn();
    await speakText('我想喝水', .9, update, false);
    vi.advanceTimersByTime(10000);
    device.events().onstart(); device.events().onend();
    expect(update.mock.calls.map(c => c[0])).toEqual(['failed']);
    expect(vi.getTimerCount()).toBe(0);
  });
  it('recovers when device speech starts but never reports completion', async () => {
    const device = browserSpeech(), update = vi.fn();
    await speakText('我想喝水', .9, update, false);
    device.events().onstart();
    vi.advanceTimersByTime(120000);
    device.events().onend();
    expect(update.mock.calls.map(c => c[0])).toEqual(['started', 'failed']);
  });
  it('turns a synchronous browser speech failure into a terminal status', async () => {
    const { synth } = browserSpeech(), update = vi.fn();
    synth.speak.mockImplementation(() => { throw new Error('unavailable'); });
    await speakText('我想喝水', .9, update, false);
    expect(update.mock.calls.map(c => c[0])).toEqual(['failed']);
    expect(vi.getTimerCount()).toBe(0);
  });
  it('still reports timeout when browser cancellation throws', async () => {
    const { synth } = browserSpeech(), update = vi.fn();
    await speakText('我想喝水', .9, update, false);
    synth.cancel.mockImplementation(() => { throw new Error('host already released'); });
    expect(() => vi.advanceTimersByTime(10000)).not.toThrow();
    expect(update.mock.calls.map(c => c[0])).toEqual(['failed']);
    expect(vi.getTimerCount()).toBe(0);
  });
  it('still disposes and reports interrupted when audio stop throws', () => {
    const { audio, events } = audioDevice(), update = vi.fn();
    playCardAudio(CARDS.slice(0, 1), update); events.play();
    audio.stop.mockImplementation(() => { throw new Error('host already stopped'); });
    audio.destroy.mockImplementation(() => { throw new Error('host already released'); });
    expect(() => stopSpeech()).not.toThrow();
    expect(audio.destroy).toHaveBeenCalledOnce();
    expect(update.mock.calls.map(c => c[0])).toEqual(['started', 'interrupted']);
    expect(vi.getTimerCount()).toBe(0);
  });
  it('arms a fresh start timeout for each card and destroys stuck playback', () => {
    const { audio, events } = audioDevice(), update = vi.fn();
    playCardAudio(CARDS.slice(0, 2), update);
    events.play(); events.end();
    vi.advanceTimersByTime(10000);
    events.play(); events.end();
    expect(update.mock.calls.map(c => c[0])).toEqual(['started', 'failed']);
    expect(audio.destroy).toHaveBeenCalledOnce();
    expect(vi.getTimerCount()).toBe(0);
  });
  it('clears playback watchdogs on deliberate stop without a later failure', () => {
    const { events } = audioDevice(), update = vi.fn();
    playCardAudio(CARDS.slice(0, 1), update); events.play(); stopSpeech();
    vi.advanceTimersByTime(120000); events.end();
    expect(update.mock.calls.map(c => c[0])).toEqual(['started', 'interrupted']);
    expect(vi.getTimerCount()).toBe(0);
  });
  it('clears remote audio timers after successful completion', async () => {
    const { audio, events } = audioDevice(), update = vi.fn();
    await speakText('我想喝水', .9, update, true);
    events.play(); events.end();
    vi.advanceTimersByTime(120000);
    expect(update.mock.calls.map(c => c[0])).toEqual(['started', 'completed']);
    expect(audio.destroy).toHaveBeenCalledOnce();
    expect(vi.getTimerCount()).toBe(0);
  });
  it.each([false, true])('recovers stalled remote audio (started=%s)', async started => {
    const { audio, events } = audioDevice(), update = vi.fn();
    await speakText('我想喝水', .9, update, true);
    if (started) events.play();
    vi.advanceTimersByTime(started ? 120000 : 10000);
    events.play(); events.end();
    expect(update.mock.calls.map(c => c[0])).toEqual(started ? ['started', 'failed'] : ['failed']);
    expect(audio.destroy).toHaveBeenCalledOnce();
    expect(vi.getTimerCount()).toBe(0);
  });
});
