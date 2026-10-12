import { afterEach, describe, expect, it, vi } from 'vitest';
vi.mock('@/uni_modules/cardtalk-tts', () => ({ getTemporaryDirectory: () => '/cache' }));
import { prepareNativeAudio } from '../src/platform/nativeAudio';

afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });
function device(mode: 'ok' | 'text' | 'error' | 'corrupt') {
  const remove = vi.fn((done: () => void) => done());
  const encoded = Buffer.from([1, 2, 255]).toString('base64');
  const writer: { onwrite?: () => void; onerror?: () => void; writeAsBinary(value: string): void } = {
    writeAsBinary(value) { expect(value).toBe(encoded); mode === 'error' ? this.onerror?.() : this.onwrite?.(); },
  };
  const entry = { remove, toLocalURL: () => '_doc/audio.mp3', createWriter: (done: (w: typeof writer) => void) => done(writer), file: (done: (f: { size: number }) => void) => done({ size: mode === 'text' ? encoded.length : 3 }) };
  vi.stubGlobal('uni', { arrayBufferToBase64: () => encoded });
  vi.stubGlobal('plus', { io: { PRIVATE_DOC: 2, requestFileSystem: (_: number, done: (fs: unknown) => void) => done({ root: { getFile: (_name: string, _options: unknown, done: (e: typeof entry) => void) => done(entry) } }), FileReader: class {
    result = `data:audio/mpeg;base64,${mode === 'corrupt' ? 'wrong' : encoded}`;
    onloadend?: () => void;
    readAsDataURL() { this.onloadend?.(); }
  } } });
  return remove;
}
describe('private native audio preparation', () => {
  it('only exposes verified bytes and provides disposal', async () => {
    const remove = device('ok');
    const file = await prepareNativeAudio(new Uint8Array([1, 2, 255]).buffer);
    expect(file.src).toBe('_doc/audio.mp3'); expect(remove).not.toHaveBeenCalled();
    file.dispose(); expect(remove).toHaveBeenCalledOnce();
  });
  it('times out and removes a file created after cancellation', async () => {
    vi.useFakeTimers();
    let created: ((entry: unknown) => void) | undefined;
    const remove = vi.fn();
    vi.stubGlobal('uni', { arrayBufferToBase64: () => 'AQI=' });
    vi.stubGlobal('plus', { io: { PRIVATE_DOC: 2, requestFileSystem: (_: number, done: (fs: unknown) => void) => done({ root: { getFile: (_: string, _options: unknown, callback: typeof created) => { created = callback; } } }) } });
    const promise = prepareNativeAudio(new Uint8Array([1, 2]).buffer);
    const rejected = expect(promise).rejects.toThrow('无法安全保存');
    await vi.advanceTimersByTimeAsync(15000); await rejected;
    created?.({ remove }); expect(remove).toHaveBeenCalledOnce();
  });
  it.each(['text', 'error', 'corrupt'] as const)('deletes and rejects %s writes before playback', async mode => {
    const remove = device(mode);
    await expect(prepareNativeAudio(new Uint8Array([1, 2, 255]).buffer)).rejects.toThrow('无法安全保存');
    expect(remove).toHaveBeenCalled();
  });
});
