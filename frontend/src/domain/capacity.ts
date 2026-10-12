import type { State } from './types'
// Matches the local storage adapter's serialized-string bound.
export const MAX_LOCAL_STATE_CHARACTERS = 5 * 1024 * 1024
export function ensureStateCapacity(state: State): State {
  if (JSON.stringify(state).length > MAX_LOCAL_STATE_CHARACTERS) throw new Error('本机记录空间不足，请先导出并整理历史或缩小图片；本次更改未保存')
  return state
}
