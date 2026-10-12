import { ensureStateCapacity, MAX_LOCAL_STATE_CHARACTERS } from './capacity'
import { validateState } from './validation'
import type { State } from './types'

export function parseBackup(text: string): State {
  if (!text.trim()) throw new Error('备份文件为空，请重新选择。')
  if (text.length > MAX_LOCAL_STATE_CHARACTERS * 3) throw new Error('备份超过本机 5 MiB 字符容量，无法导入。')
  let value: unknown
  try { value = JSON.parse(text) } catch { throw new Error('备份不是有效的 JSON 文件，当前数据未更改。') }
  const result = validateState(value)
  if (!result.valid) throw new Error('备份格式不正确或记录无效，当前数据未更改。')
  return ensureStateCapacity(result.state)
}
