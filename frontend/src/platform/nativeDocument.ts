// #ifdef APP
// @ts-expect-error UTS plugin resolution belongs to the native compiler.
import { openDocument, cancel } from '@/uni_modules/cardtalk-document';
// #endif
import { decodeDocumentBytes, documentFilename, encodeDocumentText, MAX_DOCUMENT_BYTES } from './nativeDocumentCodec';
export interface DocumentExportResult { status: 'saved' | 'cancelled' }
interface NativeResult { id: string; status: string; base64: string; message: string }
let sequence = 0;
let pending: { id: string; settle: (result: NativeResult) => void } | null = null;
/** Call when leaving the import/export surface. Late native results cannot trigger data replacement. */
export function cancelNativeDocument() {
  const current = pending;
  if (!current) return;
  try { cancel(current.id); }
  catch { current.settle({ id: current.id, status: 'failed', base64: '', message: '无法确认系统文件操作结果，请检查目标文件' }); }
  // The host acknowledges a successful cancellation. If writing already began,
  // it reports the actual saved/failed result instead of promising cancellation.
}
function select(mode: 'import' | 'export', filename = '', base64 = ''): Promise<NativeResult> {
  if (pending) return Promise.reject(new Error('已有文件选择窗口，请先完成或取消'));
  const id = `cardtalk-document-${Date.now()}-${++sequence}`;
  return new Promise((resolve, reject) => {
    let done = false;
    let timedOut = false;
    let confirmationTimer: ReturnType<typeof setTimeout> | undefined;
    const settle = (result: NativeResult) => {
      if (done || result.id !== id) return;
      done = true; clearTimeout(timer); clearTimeout(confirmationTimer);
      if (pending?.id === id) pending = null;
      if (timedOut && result.status === 'cancelled') reject(new Error('文件选择超时，请重新选择'));
      else result.status === 'failed' ? reject(new Error(result.message || '文件操作失败')) : resolve(result);
    };
    const timer = setTimeout(() => {
      timedOut = true;
      confirmationTimer = setTimeout(() => settle({ id, status: 'failed', base64: '', message: '系统文件操作结果尚未确认，请检查目标文件后重试' }), 30000);
      try { cancel(id); } catch { settle({ id, status: 'failed', base64: '', message: '无法确认系统文件操作结果，请检查目标文件' }); }
    }, 5 * 60 * 1000);
    pending = { id, settle };
    try { openDocument({ id, mode, filename, base64, maxBytes: MAX_DOCUMENT_BYTES, onResult: settle }); }
    catch { settle({ id, status: 'failed', base64: '', message: '设备文件选择服务不可用' }); }
  });
}
export async function chooseJSON(): Promise<string | null> {
  const result = await select('import');
  if (result.status === 'cancelled') return null;
  if (result.status !== 'selected') throw new Error('文件选择未完成');
  if (result.base64.length > Math.ceil(MAX_DOCUMENT_BYTES / 3) * 4) throw new Error('文件超过本机备份容量');
  return decodeDocumentBytes(uni.base64ToArrayBuffer(result.base64));
}
export async function exportText(filename: string, text: string): Promise<DocumentExportResult> {
  const bytes = encodeDocumentText(text);
  const result = await select('export', documentFilename(filename), uni.arrayBufferToBase64(bytes));
  if (result.status !== 'saved' && result.status !== 'cancelled') throw new Error('文件尚未保存完成');
  return { status: result.status };
}
