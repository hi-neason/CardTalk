import { test, expect, type Page } from '@playwright/test'
const button = (page: Page, name: string | RegExp) => page.getByRole('button', { name, exact: typeof name === 'string' })
async function setup(page: Page) {
  const calls = { catalogs: 0, speech: [] as Record<string, unknown>[] }
  await page.addInitScript(() => {
    Object.defineProperty(window, 'speechSynthesis', { configurable: true, value: { getVoices: () => [], cancel: () => {}, speak: () => { throw new Error('Must not switch to device speech') } } })
  })
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/voices')) { calls.catalogs++; await route.fulfill({ json: [
      { key: 'adult-voice', label: '成人测试声音', audience: 'adult', configured: true, emotionSupported: true },
      { key: 'unavailable', label: '未开通声音', audience: 'all', configured: false, emotionSupported: false },
    ] }); return }
    if (path.endsWith('/speech')) { calls.speech.push(route.request().postDataJSON()); await route.fulfill({ status: 422, json: { code: 'TTS_VOICE_UNKNOWN', message: '选择的声音已不可用，请重新选择' } }); return }
    if (path.endsWith('/health')) { await route.fulfill({ json: { status: 'ok', llmConfigured: false, ttsConfigured: true } }); return }
    await route.fulfill({ json: { token: 'voice-fixture', remaining: 10, limit: 10, resetAt: '2099-01-01T00:00:00Z', capabilities: { profileManagement: true, customCards: true, scenarioManagement: true, analytics: true } } })
  })
  await page.goto('/')
  return calls
}
test('声音目录仅主动获取，选择不自动播放，失效音色保留已确认表达', async ({ page }) => {
  const calls = await setup(page)
  await button(page, '设置').click()
  expect(calls.catalogs).toBe(0); expect(calls.speech).toHaveLength(0)
  await expect(button(page, '试听远端声音')).toBeDisabled()
  await button(page, '获取已配置远端声音').click()
  await expect(button(page, '未开通声音 · 未配置')).toBeDisabled()
  await button(page, '成人测试声音 · 支持情绪').click()
  expect(calls.catalogs).toBe(1); expect(calls.speech).toHaveLength(0)
  await page.getByRole('switch', { name: '语音合成', exact: true }).click()
  await button(page, '关闭').click()
  await page.reload()
  await button(page, /开始沟通/).click()
  await button(page, /创建聊天室，开始选卡/).click()
  await button(page, '选择我').click()
  await button(page, '使用原始表达').click()
  await expect(page.locator('.confirmed-text')).toHaveText('我')
  expect(calls.speech).toHaveLength(0)
  await button(page, /说出这句话/).click()
  await expect.poll(() => calls.speech.length).toBe(1)
  expect(calls.speech[0]).toMatchObject({ text: '我', voiceKey: 'adult-voice', audience: 'children' })
  expect(calls.speech[0]).not.toHaveProperty('deviceVoiceURI')
  await expect(page.locator('.playback-controls')).toContainText('播放失败')
  await expect(page.locator('.error-message')).toContainText('选择的声音已不可用')
  await expect(page.locator('.confirmed-text')).toHaveText('我')
})
for (const exit of ['停止试听', '关闭'] as const) {
  test(`远端试听等待身份期间${exit}，迟到身份不会发声`, async ({ page }) => {
    const calls = await setup(page)
    await button(page, '设置').click()
    await button(page, '获取已配置远端声音').click()
    await button(page, '成人测试声音 · 支持情绪').click()
    await expect(page.getByRole('dialog')).toContainText('今日扩写：剩余 10 / 10 次')
    let requested = false
    let release!: () => void
    await page.route('**/api/v1/trial-session', async route => {
      requested = true
      await new Promise<void>(resolve => { release = resolve })
      await route.fulfill({ json: { token: 'preview-fixture', remaining: 10, limit: 10, resetAt: '2099-01-01T00:00:00Z', capabilities: { profileManagement: true, scenarioManagement: true, analytics: true } } })
    })
    await button(page, '试听远端声音').click()
    await expect.poll(() => requested).toBe(true)
    expect(calls.speech).toHaveLength(0)
    await button(page, exit).click()
    const response = page.waitForResponse('**/api/v1/trial-session')
    release(); await response
    await page.waitForTimeout(150)
    expect(calls.speech).toHaveLength(0)
  })
}
test('主动试听刷新身份并携带选定声音，不上传设备偏好', async ({ page }) => {
  const calls = await setup(page)
  await button(page, '设置').click()
  await button(page, '获取已配置远端声音').click()
  await button(page, '成人测试声音 · 支持情绪').click()
  const speech = page.waitForRequest('**/api/v1/speech')
  await button(page, '试听远端声音').click()
  expect((await speech).headers().authorization).toBe('Bearer voice-fixture')
  await expect.poll(() => calls.speech.length).toBe(1)
  expect(calls.speech[0]).toMatchObject({ text: '每一种表达，都值得被认真听见。', voiceKey: 'adult-voice' })
  expect(calls.speech[0]).not.toHaveProperty('deviceVoiceURI')
})
