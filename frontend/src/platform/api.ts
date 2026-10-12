import { SCENES, type Room, type Expansion, type Emotion } from '../domain';
import { newId, readIdentity, saveIdentity } from './storage';

export interface Trial { token: string; remaining: number; limit: number; resetAt: string; capabilities: { profileManagement: boolean; scenarioManagement: boolean; analytics: boolean } }
export interface Health { status: string; llmConfigured: boolean; ttsConfigured: boolean; ttsEmotionSupported?: boolean; promptVersion: string }
export class ApiError extends Error {
  constructor(public code: string, message: string, public status: number) { super(message); }
}
const API_BASE = import.meta.env.VITE_API_BASE_URL?.replace(/\/$/, '') || '';
function request<T>(path: string, method: 'GET' | 'POST', data?: unknown, token?: string, binary = false): { promise: Promise<T>; cancel: () => void } {
  let task: UniApp.RequestTask | undefined;
  const promise = new Promise<T>((resolve, reject) => {
    task = uni.request({
      url: `${API_BASE}/api/v1${path}`,
      method,
      data: data as UniApp.RequestOptions['data'],
      timeout: 40000,
      responseType: binary ? 'arraybuffer' : 'text',
      header: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      success(response) {
        if (response.statusCode >= 200 && response.statusCode < 300) { resolve(response.data as T); return; }
        let error = response.data as { code?: string; message?: string };
        if (binary && response.data instanceof ArrayBuffer) {
          try {
            const encoded = Array.from(new Uint8Array(response.data), byte => `%${byte.toString(16).padStart(2, '0')}`).join('');
            error = JSON.parse(decodeURIComponent(encoded));
          } catch { /* Non-JSON upstream failures use the status fallback below. */ }
        }
        reject(new ApiError(error?.code || 'REQUEST_FAILED', error?.message || `服务暂时不可用（${response.statusCode}）`, response.statusCode));
      },
      fail(error) { reject(new ApiError('NETWORK_ERROR', /abort/.test(error.errMsg) ? '请求已取消' : '连接不到服务，请检查网络或稍后重试。原始表达仍然可用。', 0)); },
    });
  });
  return { promise, cancel: () => task?.abort() };
}

let identity = readIdentity();
let pendingIdentity: Promise<Trial> | null = null;
export function health() { return request<Health>('/health', 'GET').promise; }
export async function trial(): Promise<Trial> {
  if (pendingIdentity) return pendingIdentity;
  pendingIdentity = (async () => {
    let value: Trial;
    if (identity.token) {
      try { value = await request<Trial>('/trial-session', 'GET', undefined, identity.token).promise; }
      catch (error) {
        if (!(error instanceof ApiError) || error.status !== 401) throw error;
        value = await request<Trial>('/trial-session', 'POST', { clientId: identity.clientId }).promise;
      }
    } else value = await request<Trial>('/trial-session', 'POST', { clientId: identity.clientId }).promise;
    identity = { ...identity, token: value.token };
    // Avoid silently resetting quotas when persistence is denied; keep identity in memory.
    try { saveIdentity(identity); } catch { /* The UI separately reports history persistence. */ }
    return value;
  })();
  try { return await pendingIdentity; } finally { pendingIdentity = null; }
}
export function expand(room: Room, requestId = newId(), emotion?: Emotion) {
  const scene = SCENES.find(item => item.id === room.context.sceneId);
  return request<Expansion>('/expansions', 'POST', {
    requestId, inputVersion: room.draft.version, roomId: room.id,
    cards: room.draft.cards.map(({ id, label, description }) => ({ id, label, ...(description ? { description } : {}) })),
    context: { preset: room.context.preset, scene: scene && scene.id !== 'none' ? { id: scene.id, name: scene.name, description: scene.description } : null, participants: room.context.participants, ...(emotion ? { emotion } : {}) },
  }, identity.token);
}
export function remoteSpeech(text: string, rate: number, emotion?: Emotion) {
  const requestId = newId();
  const call = request<ArrayBuffer>('/speech', 'POST', { requestId, text, rate, ...(emotion ? { emotion } : {}) }, identity.token, true);
  let pending = true, canceled = false;
  return {
    promise: call.promise.finally(() => { pending = false; }),
    cancel: () => {
      if (!pending || canceled) return;
      canceled = true;
      call.cancel();
      // Transport abort alone cannot guarantee cancellation of an upstream socket.
      // This authenticated best-effort request asks the backend to abort synthesis.
      void request<unknown>(`/speech/${requestId}/cancel`, 'POST', undefined, identity.token).promise.catch(() => {});
    },
  };
}
