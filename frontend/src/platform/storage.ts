import { createState, validateState, type State } from '../domain';

export const STATE_KEY = 'cardtalk.communication.v1';
export const IDENTITY_KEY = 'cardtalk.trial.identity.v1';
export function loadState(): { state: State; error: string; protectedOriginal: boolean } {
  try {
    const raw = uni.getStorageSync(STATE_KEY);
    if (!raw) return { state: createState(), error: '', protectedOriginal: false };
    if (typeof raw !== 'string' || raw.length > 5 * 1024 * 1024) throw new Error('本机记录格式或大小不正确');
    const result = validateState(JSON.parse(raw));
    if (!result.valid) throw new Error(result.error);
    return { state: result.state, error: '', protectedOriginal: false };
  } catch {
    return { state: createState(), error: '本机记录无法读取，原始数据已保留。当前操作暂存于本次打开期间，可在设置中导出原始备份后重置。', protectedOriginal: true };
  }
}
export function saveState(state: State) {
  const result = validateState(state);
  if (!result.valid) throw new Error(result.error);
  const raw = JSON.stringify(result.state);
  if (raw.length > 5 * 1024 * 1024) throw new Error('本机记录空间不足，请先导出并整理历史');
  uni.setStorageSync(STATE_KEY, raw);
}
export function newId(): string {
  return `ct-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}-${Math.random().toString(36).slice(2, 12)}`;
}
export function readIdentity(): { clientId: string; token: string } {
  try {
    const value = uni.getStorageSync(IDENTITY_KEY);
    if (value && typeof value.clientId === 'string' && /^[a-zA-Z0-9-]{8,100}$/.test(value.clientId)) return { clientId: value.clientId, token: typeof value.token === 'string' ? value.token : '' };
  } catch { /* A new identity is still usable when storage is unavailable. */ }
  return { clientId: newId(), token: '' };
}
export function saveIdentity(identity: { clientId: string; token: string }) { uni.setStorageSync(IDENTITY_KEY, identity); }
export function originalBackup(): string { const value = uni.getStorageSync(STATE_KEY); return typeof value === 'string' ? value : JSON.stringify(value); }
