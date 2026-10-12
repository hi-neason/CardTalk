import { afterEach, describe, expect, it, vi } from 'vitest'
import { createState, createFamilyProfile, createRoom, updateFamilyPreferences, updateGlobalPreferences, validateState, getCommunicationPreferences, DEFAULT_COMMUNICATION_PREFERENCES } from '../src/domain'

afterEach(() => { vi.unstubAllGlobals(); vi.resetModules() })
describe('远端声音偏好', () => {
  it('旧备份缺少远端声音仍可加载，个人声音冻结至房间且独立于全局', () => {
    const old = createState(); delete old.settings.preferences!.remoteVoiceKey
    expect(validateState(old).valid).toBe(true)
    let state = createFamilyProfile(old, { name: '家人', preset: 'adult' })
    const id = state.family.profiles[0]!.id
    state = updateFamilyPreferences(state, id, { remoteVoiceKey: 'adult_voice-1' })
    state = createRoom(state, { profileId: id, preset: 'adult', communicatorName: '家人', sceneId: null, participants: [], source: 'unknown' })
    state = updateFamilyPreferences(state, id, { remoteVoiceKey: 'default' })
    state = updateGlobalPreferences(state, { remoteVoiceKey: 'other' })
    const restored = validateState(JSON.parse(JSON.stringify(state)))
    expect(restored.valid).toBe(true)
    if (restored.valid) expect(getCommunicationPreferences(restored.state, restored.state.rooms[0]!.context).remoteVoiceKey).toBe('adult_voice-1')
    expect(DEFAULT_COMMUNICATION_PREFERENCES.remoteVoiceKey).toBe('')
  })
  it('损坏音色标识拒绝保存和备份导入，空值保留自动选择', () => {
    for (const remoteVoiceKey of ['with space', '../voice', '中文', 'x'.repeat(101), null, 3]) {
      const state = createState()
      expect(() => updateGlobalPreferences(state, { remoteVoiceKey } as never)).toThrow()
      ;(state.settings.preferences as any).remoteVoiceKey = remoteVoiceKey
      expect(validateState(state).valid).toBe(false)
    }
    expect(updateGlobalPreferences(createState(), { remoteVoiceKey: '' }).settings.preferences?.remoteVoiceKey).toBe('')
  })
  it('请求只上传远端声音键与使用人类型，空键保持旧协议', async () => {
    const requests: any[] = []
    vi.stubGlobal('uni', { getStorageSync: () => '', request: (options: any) => { requests.push(options); options.success({ statusCode: 200, data: new ArrayBuffer(1) }); return { abort() {} } } })
    const { remoteSpeech } = await import('../src/platform/api')
    await remoteSpeech('不要', 0.9, 'sad', { remoteVoiceKey: 'adult_voice-1', audience: 'adult', deviceVoiceURI: 'private-device-voice' } as any).promise
    expect(requests[0].data).toEqual({ requestId: expect.any(String), text: '不要', rate: 0.9, emotion: 'sad', voiceKey: 'adult_voice-1', audience: 'adult' })
    await remoteSpeech('你好', 1, undefined, { remoteVoiceKey: '' }).promise
    expect(requests[1].data).toEqual({ requestId: expect.any(String), text: '你好', rate: 1 })
  })
  it('声音目录拒绝无效接口结构', async () => {
    let payload: unknown = { voices: [] }
    vi.stubGlobal('uni', { getStorageSync: () => '', request: (options: any) => { options.success({ statusCode: 200, data: payload }); return { abort() {} } } })
    const { remoteVoices } = await import('../src/platform/api')
    await expect(remoteVoices()).rejects.toThrow('声音目录格式不正确')
    payload = [{ key: 'default', label: '默认声音', audience: 'all', configured: true, emotionSupported: false }]
    await expect(remoteVoices()).resolves.toEqual(payload)
    payload = [{ key: '../voice', label: '错误', audience: 'all', configured: true, emotionSupported: false }]
    await expect(remoteVoices()).rejects.toThrow('声音目录格式不正确')
  })
})
