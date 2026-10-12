import { MAX_LOCAL_STATE_CHARACTERS } from '../domain/capacity';
export const MAX_DOCUMENT_BYTES = MAX_LOCAL_STATE_CHARACTERS * 3;
export function encodeDocumentText(text: string): ArrayBuffer {
  if (text.length > MAX_DOCUMENT_BYTES) throw new Error('文件超过传输字节容量');
  let size = 0;
  for (const character of text) {
    const point = character.codePointAt(0)!;
    size += point < 0x80 ? 1 : point < 0x800 ? 2 : point < 0x10000 ? 3 : 4;
    if (size > MAX_DOCUMENT_BYTES) throw new Error('文件超过传输字节容量');
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const character of text) {
    let point = character.codePointAt(0)!;
    if (point >= 0xd800 && point <= 0xdfff) point = 0xfffd;
    if (point < 0x80) bytes[offset++] = point;
    else if (point < 0x800) { bytes[offset++] = 0xc0 | (point >> 6); bytes[offset++] = 0x80 | (point & 63); }
    else if (point < 0x10000) { bytes[offset++] = 0xe0 | (point >> 12); bytes[offset++] = 0x80 | ((point >> 6) & 63); bytes[offset++] = 0x80 | (point & 63); }
    else { bytes[offset++] = 0xf0 | (point >> 18); bytes[offset++] = 0x80 | ((point >> 12) & 63); bytes[offset++] = 0x80 | ((point >> 6) & 63); bytes[offset++] = 0x80 | (point & 63); }
  }
  return bytes.buffer.slice(0, offset);
}
/** Strict UTF-8 decoding without relying on a WebView TextDecoder implementation. */
export function decodeDocumentBytes(buffer: ArrayBuffer): string {
  if (buffer.byteLength > MAX_DOCUMENT_BYTES) throw new Error('文件超过本机备份容量');
  const bytes = new Uint8Array(buffer), pieces: string[] = [];
  let chunk = '';
  for (let i = 0; i < bytes.length;) {
    const first = bytes[i++]!;
    let point = first, remaining = 0, minimum = 0;
    if (first >= 0xc2 && first <= 0xdf) { point = first & 31; remaining = 1; minimum = 0x80; }
    else if (first >= 0xe0 && first <= 0xef) { point = first & 15; remaining = 2; minimum = 0x800; }
    else if (first >= 0xf0 && first <= 0xf4) { point = first & 7; remaining = 3; minimum = 0x10000; }
    else if (first >= 0x80) throw new Error('备份文件不是有效 UTF-8 文本');
    for (let j = 0; j < remaining; j++) {
      const next = bytes[i++];
      if (next === undefined || (next & 0xc0) !== 0x80) throw new Error('备份文件不是有效 UTF-8 文本');
      point = (point << 6) | (next & 63);
    }
    if (point < minimum || point > 0x10ffff || (point >= 0xd800 && point <= 0xdfff)) throw new Error('备份文件不是有效 UTF-8 文本');
    chunk += String.fromCodePoint(point);
    if (chunk.length >= 8192) { pieces.push(chunk); chunk = ''; }
  }
  pieces.push(chunk);
  return pieces.join('').replace(/^\uFEFF/, '');
}
export function documentFilename(name: string): string {
  const value = name.replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_').slice(0, 120).trim() || 'cardtalk-backup';
  return /\.json$/i.test(value) ? value : `${value}.json`;
}
