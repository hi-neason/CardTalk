import type { PlaybackStatus } from '../domain';
// #ifdef APP
// @ts-expect-error Native UTS modules are resolved by the uni-app compiler, not TypeScript.
import { speak, stop, listVoices } from '@/uni_modules/cardtalk-tts';
// #endif

export interface NativeVoice { id: string; name: string; language: string }
export interface NativeSpeechOptions { volume?: number; deviceVoiceURI?: string }
let requestSequence = 0;
/** Caller owns the returned cancellation handle. Queue acceptance is never reported as started. */
export function startNativeSpeech(text: string, rate: number, update: (status: PlaybackStatus, message?: string) => void, options: NativeSpeechOptions = {}): () => void {
  const id = `cardtalk-${Date.now()}-${++requestSequence}`;
  let settled = false;
  let started = false;
  const finish = (status: PlaybackStatus, message?: string, cancelNative = false) => {
    if (settled) return;
    settled = true; clearTimeout(startTimer); clearTimeout(completionTimer); if (cancelNative) stop(); update(status, message);
  };
  const startTimer = setTimeout(() => { if (!started && !settled) { finish('failed', '设备没有开始朗读，请检查已安装的中文语音', true); } }, 15000);
  // A lost native end callback must not leave the communication controls permanently playing.
  const completionTimer = setTimeout(() => { if (!settled) { finish('failed', '设备未返回朗读完成状态', true); } }, Math.max(120000, text.length * 2000));
  try {
    // #ifdef APP
    speak({ id, text, rate, volume: options.volume ?? 1, voiceId: options.deviceVoiceURI ?? '', onEvent: (event: { id: string; status: string; message: string }) => {
      if (settled || event.id !== id) return;
      if (event.status === 'started') { if (!started) { started = true; clearTimeout(startTimer); update('started'); } }
      else if (['completed', 'interrupted', 'failed', 'unavailable'].includes(event.status)) finish(event.status as PlaybackStatus, event.message || undefined);
    } });
    // #endif
    // #ifndef APP
    finish('unavailable', '当前平台不使用原生设备朗读');
    // #endif
  } catch { finish('unavailable', '设备语音服务不可用'); }
  return () => { if (!settled) { finish('interrupted', undefined, true); } };
}
export function getNativeVoices(): Promise<NativeVoice[]> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('设备语音列表读取超时')), 15000);
    try {
      // #ifdef APP
      listVoices({ success: (voices: NativeVoice[]) => { clearTimeout(timer); resolve(voices); }, fail: (message: string) => { clearTimeout(timer); reject(new Error(message)); } });
      // #endif
      // #ifndef APP
      clearTimeout(timer); resolve([]);
      // #endif
    } catch { clearTimeout(timer); reject(new Error('设备语音服务不可用')); }
  });
}
