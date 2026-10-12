import { afterEach, describe, expect, it, vi } from 'vitest';
const bridge = vi.hoisted(() => ({ decodeImage: vi.fn() }));
vi.mock('@/uni_modules/cardtalk-image', () => bridge);
import { validateNativeImage } from '../src/platform/nativeImageValidation';
import { readNativeImage } from '../src/platform/nativeImage';
import { cardImageInfo } from '../src/domain/family-validation';
const png = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=';
const truncated = 'data:image/png;base64,' + Buffer.from(png.split(',')[1]!, 'base64').subarray(0, 33).toString('base64');
afterEach(() => { vi.resetAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers(); });
const respond = (valid: boolean, width = 1, height = 1) => bridge.decodeImage.mock.calls.at(-1)![0].onResult({ valid, width, height });
describe('native image pixel boundary', () => {
  it('requires real decoder success, including for a plausible 33-byte PNG header', async () => {
    expect(cardImageInfo(truncated)).toMatchObject({ width: 1, height: 1 });
    const result = validateNativeImage(truncated); respond(false);
    await expect(result).rejects.toThrow('完整解码');
  });
  it('returns original local image only after decoder succeeds with matching dimensions', async () => {
    const result = validateNativeImage(png); respond(true);
    expect(await result).toBe(png);
    const mismatch = validateNativeImage(png); respond(true, 2, 1);
    await expect(mismatch).rejects.toThrow('完整解码');
  });
  it('rejects remote URLs and unsupported or invalid data before native execution', async () => {
    for (const value of ['https://example.com/photo.png', 'data:image/svg+xml;base64,PHN2Zy8+', 'data:image/png;base64,invalid']) await expect(validateNativeImage(value)).rejects.toThrow('PNG');
    expect(bridge.decodeImage).not.toHaveBeenCalled();
  });
  it('never revives an image after timeout or cancellation', async () => {
    vi.useFakeTimers();
    const result = validateNativeImage(png); const assertion = expect(result).rejects.toThrow('超时');
    await vi.advanceTimersByTimeAsync(15000); respond(true); await assertion;
    const controller = new AbortController(); const cancelled = validateNativeImage(png, controller.signal);
    const cancellation = expect(cancelled).rejects.toThrow('取消'); controller.abort(); respond(true); await cancellation;
  });
  it('does not start a decoder when cancelled before reading', async () => {
    const controller = new AbortController(); controller.abort();
    await expect(validateNativeImage(png, controller.signal)).rejects.toThrow('取消'); expect(bridge.decodeImage).not.toHaveBeenCalled();
  });
});

describe('native file reader lifecycle', () => {
  it('does not invoke a decoder or restore an image after a late file read', async () => {
    vi.useFakeTimers();
    let reader: { result: string; onloadend: () => void };
    vi.stubGlobal('plus', { io: {
      resolveLocalFileSystemURL: (_path: string, success: (entry: unknown) => void) => success({ isFile: true, file: (success: (file: object) => void) => success({}) }),
      FileReader: class { result = png; onloadend = () => {}; onerror = () => {}; constructor() { reader = this; } readAsDataURL() {} },
    } });
    const pending = readNativeImage('_doc/photo.png'); const failure = expect(pending).rejects.toThrow('重新选择');
    await vi.advanceTimersByTimeAsync(15000); reader!.onloadend(); await failure;
    expect(bridge.decodeImage).not.toHaveBeenCalled();
  });
});
