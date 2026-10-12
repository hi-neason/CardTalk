// #ifdef APP
// @ts-expect-error UTS plugin resolution belongs to the native compiler.
import { decodeImage } from '@/uni_modules/cardtalk-image';
// #endif
import { cardImageInfo, MAX_CARD_IMAGE_DIMENSION } from '../domain/family-validation';
interface DecodeResult { valid: boolean; width: number; height: number }
/** Metadata is only a bounded preflight. Native pixel decoding must also succeed. */
export function validateNativeImage(data: string, signal?: AbortSignal): Promise<string> {
  const info = cardImageInfo(data);
  if (!info) return Promise.reject(new Error('请选择不超过 1024×1024 的 PNG、JPEG 或 WebP 图片'));
  return new Promise((resolve, reject) => {
    let done = false;
    const finish = (result?: DecodeResult, message = '图片无法完整解码，请重新选择') => {
      if (done) return;
      done = true; clearTimeout(timer); signal?.removeEventListener('abort', abort);
      if (result?.valid && result.width === info.width && result.height === info.height) resolve(data);
      else reject(new Error(message));
    };
    const abort = () => finish(undefined, '图片读取已取消');
    const timer = setTimeout(() => finish(undefined, '图片解码超时，请重新选择'), 15000);
    if (signal?.aborted) { abort(); return; }
    signal?.addEventListener('abort', abort, { once: true });
    try { decodeImage({ base64: data.slice(data.indexOf(',') + 1), maxDimension: MAX_CARD_IMAGE_DIMENSION, onResult: finish }); }
    catch { finish(); }
  });
}
