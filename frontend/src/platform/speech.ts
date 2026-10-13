import type { Card, Emotion, PlaybackStatus } from '../domain';
import { CARDS, CORE_CARDS } from '../domain';
// #ifdef APP
import {startNativeSpeech} from './nativeSpeech';
import {prepareNativeAudio} from './nativeAudio';
// #endif
import { remoteSpeech } from './api';

export interface DeviceSpeechOptions { volume?: number; deviceVoiceURI?: string; remoteVoiceKey?:string; audience?:'children'|'adult' }

type Update = (status: PlaybackStatus, message?: string) => void;
let stopCurrent: (() => void) | null = null;
let sequence = 0;
// Host cleanup may throw after a player has already been released. Always report
// the terminal status and attempt the remaining cleanup operations.
function cleanupPlayback(...actions: (() => void)[]) {
  for (const action of actions) { try { action(); } catch { /* Best-effort host cleanup. */ } }
}
export function stopSpeech() { sequence++; const stop = stopCurrent; stopCurrent = null; stop?.(); }

export function playCardAudio(cards: Card[], update: Update, rate = 0.9, options: DeviceSpeechOptions = {}) {
  if (cards.some(card => ![...CARDS, ...CORE_CARDS].some(builtin => builtin.id === card.id))) {
    // #ifdef H5
    void speakText(cards.map(card => card.label).join(' '), rate, update, false, undefined, undefined, options);
    return;
    // #endif
    // #ifndef H5
    stopSpeech();
    update('unavailable', '自定义卡片没有预置声音，可展示原始文字，或启用讯飞合成后播放。');
    return;
    // #endif
  }
  stopSpeech();
  const current = ++sequence;
  const audio = uni.createInnerAudioContext();
  audio.volume = options.volume ?? 1; audio.playbackRate = rate;
  let index = 0, started = false, settled = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  const dispose = () => { clearTimeout(timer); if (current === sequence) stopCurrent = null; cleanupPlayback(() => audio.destroy()); };
  stopCurrent = () => { if (!settled) { settled = true; cleanupPlayback(() => audio.stop()); dispose(); update('interrupted'); } };
  audio.onPlay(() => {
    if (settled || current !== sequence) return;
    clearTimeout(timer);
    timer = setTimeout(() => fail('固定词语音频未返回播放完成状态，请重试或展示文字。'), 120000);
    if (!started) { started = true; update('started'); }
  });
  audio.onEnded(() => {
    if (settled || current !== sequence) return;
    index++;
    if (index === cards.length) { settled = true; dispose(); update('completed'); }
    else play();
  });
  function fail(message: string) { if (!settled && current === sequence) { settled = true; dispose(); update('failed', message); } }
  audio.onError(() => fail('固定词语音频暂时无法播放，原始卡片和文字仍可展示。'));
  function play() {
    clearTimeout(timer);
    timer = setTimeout(() => fail('固定词语音频没有开始播放，请重试或展示文字。'), 10000);
    try { audio.src = `/static/audio/${cards[index]!.id}.m4a`; audio.play(); }
    catch { fail('固定词语音频暂时无法播放，原始卡片和文字仍可展示。'); }
  }
  if (!cards.length) { settled = true; dispose(); update('unavailable'); return; }
  play();
}

export async function speakText(text: string, rate: number, update: Update, useRemote: boolean, emotion?: Emotion, rawCards?: Card[], options: DeviceSpeechOptions = {}) {
  stopSpeech();
  const current = ++sequence;
  // #ifdef H5
  if (!useRemote && typeof window !== 'undefined' && 'speechSynthesis' in window) {
    const synth = window.speechSynthesis;
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = 'zh-CN'; utterance.rate = rate; utterance.volume = options.volume ?? 1;
    const voice = synth.getVoices().find(v => v.voiceURI === options.deviceVoiceURI && /^zh|^cmn|^yue/i.test(v.lang)) || synth.getVoices().find(v => /zh[-_]CN/i.test(v.lang) && v.localService) || synth.getVoices().find(v => /^zh/i.test(v.lang));
    if (voice) utterance.voice = voice;
    let settled = false, started = false;
    let timer: ReturnType<typeof setTimeout>;
    const finish = (status: PlaybackStatus, message?: string, cancel = false) => {
      if (settled || current !== sequence) return;
      settled = true; clearTimeout(timer); stopCurrent = null;
      if (cancel) cleanupPlayback(() => synth.cancel());
      update(status, message);
    };
    timer = setTimeout(() => finish('failed', '设备没有开始朗读，请检查中文语音与音量，或使用文字展示。', true), 10000);
    utterance.onstart = () => {
      if (settled || started || current !== sequence) return;
      started = true; clearTimeout(timer);
      timer = setTimeout(() => finish('failed', '设备未返回朗读完成状态，请重试或展示文字。', true), Math.max(120000, text.length * 2000));
      update('started');
    };
    utterance.onend = () => finish('completed');
    utterance.onerror = () => finish('failed', '设备朗读失败，已确认的表达仍可展示。');
    stopCurrent = () => { if (!settled) { settled = true; clearTimeout(timer); cleanupPlayback(() => synth.cancel()); update('interrupted'); } };
    try { synth.cancel(); synth.speak(utterance); }
    catch { finish('failed', '设备朗读失败，已确认的表达仍可展示。', true); }
    return;
  }
  // #endif
  // #ifdef APP
  if(!useRemote){stopCurrent=startNativeSpeech(text,rate,(status,message)=>{if(current!==sequence)return;update(status,message);if(status!=='started')stopCurrent=null;},options);return;}
  // #endif
  if (!useRemote) {
    if (rawCards?.length) { playCardAudio(rawCards, update, rate, options); return; }
    update('unavailable', '此端不能直接朗读新句子；可启用讯飞语音、展示文字，或播放原始卡片声音。'); return;
  }
  const call = remoteSpeech(text, rate, emotion, options);
  let audio: UniApp.InnerAudioContext | null = null;
  let cleanup = () => {};
  let settled = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  const finish = (status: PlaybackStatus, message?: string) => { if (settled || current !== sequence) return; settled = true; clearTimeout(timer); stopCurrent = null; cleanupPlayback(() => audio?.destroy(), cleanup); update(status, message); };
  stopCurrent = () => { if (!settled) { settled = true; clearTimeout(timer); cleanupPlayback(() => call.cancel(), () => audio?.stop(), () => audio?.destroy(), cleanup); update('interrupted'); } };
  try {
    const bytes = await call.promise;
    if (current !== sequence || settled) return;
    let src = '';
    // #ifdef H5
    src = URL.createObjectURL(new Blob([bytes], { type: 'audio/mpeg' }));
    cleanup = () => URL.revokeObjectURL(src);
    // #endif
    // #ifdef MP-WEIXIN
    const fs = uni.getFileSystemManager();
    src = `${wx.env.USER_DATA_PATH}/cardtalk-speech-${current}.mp3`;
    fs.writeFileSync(src, bytes);
    cleanup = () => { try { fs.unlinkSync(src); } catch { /* Already removed by host. */ } };
    // #endif
    // #ifdef APP
    const nativeFile=await prepareNativeAudio(bytes);
    if(current!==sequence||settled){nativeFile.dispose();return;}
    src=nativeFile.src;cleanup=nativeFile.dispose;
    // #endif
    if (!src) { finish('unavailable', '当前端尚未接入新句子语音播放。'); return; }
    audio = uni.createInnerAudioContext(); audio.volume = options.volume ?? 1; audio.src = src;
    audio.onPlay(() => { if (current === sequence && !settled) {
      clearTimeout(timer);
      timer = setTimeout(() => finish('failed', '语音未返回播放完成状态，请重试或展示文字。'), Math.max(120000, text.length * 2000));
      update('started');
    } });
    audio.onEnded(() => finish('completed'));
    audio.onError(() => finish('failed', '语音播放失败，请检查声音设置。'));
    timer = setTimeout(() => finish('failed', '语音没有开始播放，请重试或展示文字。'), 10000);
    audio.play();
  } catch (error) { finish('failed', error instanceof Error ? error.message : '语音合成失败'); }
}
