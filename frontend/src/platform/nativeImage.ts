import { validateNativeImage } from './nativeImageValidation';
/** Reads a user-selected local image; validation of format and pixels stays in the shared image pipeline. */
export async function readNativeImage(path: string, signal?: AbortSignal): Promise<string> {
  if (signal?.aborted) throw new Error('图片读取已取消');
  if (/^(?:https?:|data:|javascript:)/i.test(path)) throw new Error('只支持读取本机选择的图片');
  // #ifdef APP-PLUS
  const data = await new Promise<string>((resolve, reject) => {
    let settled = false;
    const finish = (value?: string) => {
      if (settled) return;
      settled = true; clearTimeout(timer); signal?.removeEventListener('abort', abort);
      value ? resolve(value) : reject(new Error('无法读取所选图片，请重新选择'));
    };
    const abort = () => finish();
    const timer = setTimeout(() => finish(), 15000);
    signal?.addEventListener('abort', abort, { once: true });
    plus.io.resolveLocalFileSystemURL(path, entry => {
      if (settled || !entry.isFile) { finish(); return; }
      (entry as unknown as PlusIoFileEntry).file(file => {
        if (settled) return;
        const reader = new plus.io.FileReader();
        reader.onerror = () => finish();
        reader.onloadend = () => finish(typeof reader.result === 'string' && reader.result.startsWith('data:image/') ? reader.result : undefined);
        try { reader.readAsDataURL(file); } catch { finish(); }
      }, () => finish());
    }, () => finish());
  });
  return validateNativeImage(data, signal);
  // #endif
  // #ifdef APP-HARMONY
  const result = uni.getFileSystemManager().readFileSync(path);
  if (!(result instanceof ArrayBuffer)) throw new Error('无法读取所选图片');
  // Detect the container from bytes; metadata is not proof of successful decoding.
  const bytes = new Uint8Array(result as ArrayBuffer);
  const format = bytes[0] === 137 && bytes[1] === 80 ? 'png'
    : bytes[0] === 255 && bytes[1] === 216 ? 'jpeg'
    : bytes[0] === 82 && bytes[1] === 73 && bytes[2] === 70 && bytes[3] === 70 ? 'webp' : '';
  if (!format) throw new Error('请选择 PNG、JPEG 或 WebP 图片');
  return validateNativeImage(`data:image/${format};base64,${uni.arrayBufferToBase64(result as ArrayBuffer)}`, signal);
  // #endif
  throw new Error('当前平台不使用原生图片读取');
}
