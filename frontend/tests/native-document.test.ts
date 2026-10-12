import { afterEach, describe, expect, it, vi } from 'vitest';
const bridge = vi.hoisted(() => ({ openDocument: vi.fn(), cancel: vi.fn() }));
vi.mock('@/uni_modules/cardtalk-document', () => bridge);
import { cancelNativeDocument, chooseJSON, exportText } from '../src/platform/nativeDocument';
import { decodeDocumentBytes, documentFilename, encodeDocumentText, MAX_DOCUMENT_BYTES } from '../src/platform/nativeDocumentCodec';

afterEach(() => { cancelNativeDocument(); if (bridge.openDocument.mock.calls.length) respond('cancelled'); vi.resetAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers(); });
function platform() {
  bridge.cancel.mockImplementation(() => respond('cancelled'));
  vi.stubGlobal('uni', { arrayBufferToBase64: (value: ArrayBuffer) => Buffer.from(value).toString('base64'), base64ToArrayBuffer: (value: string) => { const data = Uint8Array.from(Buffer.from(value, 'base64')); return data.buffer; } });
}
function respond(status: string, text = '') {
  const options = bridge.openDocument.mock.calls.at(-1)![0];
  options.onResult({ id: options.id, status, base64: Buffer.from(text).toString('base64'), message: status === 'failed' ? '设备写入失败' : '' });
}
describe('native document boundary', () => {
  it('roundtrips Chinese, emoji and a BOM with strict UTF-8', () => {
    const text = '{"表达":"不要 🧑🏽‍🦽"}';
    expect(decodeDocumentBytes(encodeDocumentText(text))).toBe(text);
    expect(decodeDocumentBytes(encodeDocumentText('\uFEFF' + text))).toBe(text);
    expect(documentFilename('../私密/备份')).toBe('.._私密_备份.json');
  });
  it.each([[0xc0, 0x80], [0xed, 0xa0, 0x80], [0xf4, 0x90, 0x80, 0x80], [0xe4, 0xb8]])('rejects malformed UTF-8 %j', (...bytes: number[]) => {
    expect(() => decodeDocumentBytes(new Uint8Array(bytes).buffer)).toThrow('UTF-8');
  });
  it('transports formatted or recovery text larger than the compact state limit', () => {
    const text = ' '.repeat(5 * 1024 * 1024) + '{\"recovery\":true}';
    expect(decodeDocumentBytes(encodeDocumentText(text))).toBe(text);
  });
  it('uses actual UTF-8 bytes for raw export capacity', () => {
    expect(() => encodeDocumentText('中'.repeat(Math.floor(MAX_DOCUMENT_BYTES / 3) + 1))).toThrow('字节容量');
  });
  it('rejects oversized native bytes before decoding', () => {
    expect(() => decodeDocumentBytes(new ArrayBuffer(MAX_DOCUMENT_BYTES + 1))).toThrow('容量');
  });
  it('returns selected text without importing or changing storage', async () => {
    platform(); const read = chooseJSON();
    respond('selected', '{"family":{}}'); expect(await read).toBe('{"family":{}}');
  });
  it('only reports export success after the native saved callback', async () => {
    platform(); const exported = exportText('备份', '{"a":1}');
    expect(bridge.openDocument.mock.calls[0]![0]).toMatchObject({ mode: 'export', filename: '备份.json', maxBytes: MAX_DOCUMENT_BYTES });
    respond('saved'); expect(await exported).toEqual({ status: 'saved' });
  });
  it('cancels and ignores a late read result', async () => {
    platform(); const read = chooseJSON(); cancelNativeDocument(); respond('selected', 'private');
    expect(await read).toBeNull(); expect(bridge.cancel).toHaveBeenCalledOnce();
  });
  it('waits for the real saved result when cancellation loses to a committed write', async () => {
    platform(); bridge.cancel.mockImplementation(() => {});
    let settled = false;
    const write = exportText('backup', '{}').then(result => { settled = true; return result; });
    cancelNativeDocument(); await Promise.resolve(); expect(settled).toBe(false);
    respond('saved'); expect(await write).toEqual({ status: 'saved' });
  });
  it('reports uncertain results when the host cannot acknowledge cancellation', async () => {
    platform(); vi.useFakeTimers(); bridge.cancel.mockImplementation(() => {});
    const write = exportText('backup', '{}'); const rejected = expect(write).rejects.toThrow('结果尚未确认');
    await vi.advanceTimersByTimeAsync(330000); await rejected;
    respond('saved');
  });
  it('rejects concurrent selectors while preserving the first', async () => {
    platform(); const read = chooseJSON(); await expect(chooseJSON()).rejects.toThrow('已有');
    respond('cancelled'); expect(await read).toBeNull();
  });
  it('times out instead of interpreting cancellation as success', async () => {
    platform(); vi.useFakeTimers(); const read = chooseJSON(); const rejected = expect(read).rejects.toThrow('超时');
    await vi.advanceTimersByTimeAsync(300000); await rejected;
    respond('selected', 'late'); expect(bridge.cancel).toHaveBeenCalledOnce();
  });
  it('surfaces native write failures without claiming saved', async () => {
    platform(); const write = exportText('backup.json', '{}'); respond('failed');
    await expect(write).rejects.toThrow('设备写入失败');
  });
});
