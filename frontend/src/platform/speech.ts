import type { Card, Emotion, PlaybackStatus } from '../domain';
import { CARDS, CORE_CARDS } from '../domain';
import { remoteSpeech } from './api';

type Update = (status: PlaybackStatus, message?: string) => void;
let stopCurrent: (() => void) | null = null;
let sequence = 0;
export function stopSpeech() { sequence++; const stop = stopCurrent; stopCurrent = null; stop?.(); }

export function playCardAudio(cards: Card[], update: Update, rate = 0.9) {
  if (cards.some(card => ![...CARDS, ...CORE_CARDS].some(builtin => builtin.id === card.id))) {
    // #ifdef H5
    void speakText(cards.map(card => card.label).join(' '), rate, update, false);
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
  let index = 0, started = false, settled = false;
  const dispose = () => { audio.destroy(); if (current === sequence) stopCurrent = null; };
  stopCurrent = () => { if (!settled) { settled = true; audio.stop(); update('interrupted'); dispose(); } };
  audio.onPlay(() => { if (!started && current === sequence) { started = true; update('started'); } });
  audio.onEnded(() => {
    if (settled || current !== sequence) return;
    index++;
    if (index === cards.length) { settled = true; update('completed'); dispose(); }
    else play();
  });
  audio.onError(() => { if (!settled && current === sequence) { settled = true; update('failed', '固定词语音频暂时无法播放，原始卡片和文字仍可展示。'); dispose(); } });
  function play() { audio.src = `/static/audio/${cards[index]!.id}.m4a`; audio.play(); }
  if (!cards.length) { settled = true; dispose(); update('unavailable'); return; }
  play();
}

export async function speakText(text: string, rate: number, update: Update, useRemote: boolean, emotion?: Emotion, rawCards?: Card[]) {
  stopSpeech();
  const current = ++sequence;
  // #ifdef H5
  if (!useRemote && typeof window !== 'undefined' && 'speechSynthesis' in window) {
    const synth = window.speechSynthesis;
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = 'zh-CN'; utterance.rate = rate;
    const voice = synth.getVoices().find(v => /zh[-_]CN/i.test(v.lang) && v.localService) || synth.getVoices().find(v => /^zh/i.test(v.lang));
    if (voice) utterance.voice = voice;
    let settled = false;
    const timer = setTimeout(() => { if (!settled && current === sequence) { settled = true; synth.cancel(); update('failed', '设备没有开始朗读，请检查中文语音与音量，或使用文字展示。'); stopCurrent = null; } }, 10000);
    const finish = (status: PlaybackStatus, message?: string) => { if (settled || current !== sequence) return; settled = true; clearTimeout(timer); stopCurrent = null; update(status, message); };
    utterance.onstart = () => { clearTimeout(timer); if (current === sequence) update('started'); };
    utterance.onend = () => finish('completed');
    utterance.onerror = () => finish('failed', '设备朗读失败，已确认的表达仍可展示。');
    stopCurrent = () => { if (!settled) { settled = true; clearTimeout(timer); synth.cancel(); update('interrupted'); } };
    synth.cancel(); synth.speak(utterance); return;
  }
  // #endif
  if (!useRemote) {
    if (rawCards?.length) { playCardAudio(rawCards, update); return; }
    update('unavailable', '此端不能直接朗读新句子；可启用讯飞语音、展示文字，或播放原始卡片声音。'); return;
  }
  const call = remoteSpeech(text, rate, emotion);
  let audio: UniApp.InnerAudioContext | null = null;
  let cleanup = () => {};
  let settled = false;
  const finish = (status: PlaybackStatus, message?: string) => { if (settled || current !== sequence) return; settled = true; stopCurrent = null; audio?.destroy(); cleanup(); update(status, message); };
  stopCurrent = () => { call.cancel(); if (!settled) { settled = true; audio?.stop(); audio?.destroy(); cleanup(); update('interrupted'); } };
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
    if (!src) { finish('unavailable', '当前端尚未接入新句子语音播放。'); return; }
    audio = uni.createInnerAudioContext(); audio.src = src;
    audio.onPlay(() => { if (current === sequence && !settled) update('started'); });
    audio.onEnded(() => finish('completed'));
    audio.onError(() => finish('failed', '语音播放失败，请检查声音设置。'));
    audio.play();
  } catch (error) { finish('failed', error instanceof Error ? error.message : '语音合成失败'); }
}
