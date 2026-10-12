import { expect, test, type Page } from '@playwright/test';
import { createState, createRoom, addCard, recordRawExpression, CARDS } from '../../frontend/src/domain';
const button = (page: Page, name: string) => page.getByRole('button', { name, exact: true });
const field = (page: Page, name: string) => page.locator(`input[aria-label="${name}"]`);
async function setup(page: Page) {
  const state = recordRawExpression(addCard(createRoom(createState(), { preset: 'adult', communicatorName: '本机使用人', source: 'independent', sceneId: null, participants: [] }), CARDS[0]!));
  await page.addInitScript(state => localStorage.setItem('cardtalk.communication.v1', JSON.stringify(state)), state);
  const control = { loggedIn: false, families: [] as { id: string; name: string; role: string }[], members: [{ accountId: 'owner-1', username: 'fixture_user', role: 'owner', profileAccess: [] }, { accountId: 'member-2', username: '家人甲', role: 'member', profileAccess: [] }], calls: [] as { path: string; method: string; data: unknown }[] };
  await page.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname, method = route.request().method(), data = route.request().postData() ? route.request().postDataJSON() : null;
    control.calls.push({ path, method, data });
    if (path.endsWith('/health')) return route.fulfill({ json: { status: 'ok', llmConfigured: false, ttsConfigured: false } });
    if (path.endsWith('/trial-session')) return route.fulfill({ json: { token: 'account-fixture', remaining: 10, limit: 10, resetAt: '2099-01-01', capabilities: {} } });
    if (path.endsWith('/accounts/me')) return route.fulfill(control.loggedIn ? { json: { account: { id: 'owner-1', username: 'fixture_user' } } } : { status: 401, json: { message: '请先登录' } });
    if (/\/accounts\/(register|login)$/.test(path)) { if (data.password === 'wrong-password') return route.fulfill({ status: 401, json: { message: '用户名或密码不正确' } }); control.loggedIn = true; return route.fulfill({ json: { account: { id: 'owner-1', username: data.username } } }); }
    if (path.endsWith('/accounts/logout')) { control.loggedIn = false; return route.fulfill({ status: 204 }); }
    if (path.endsWith('/families/invitations/accept')) { control.families.push({ id: 'joined-family', name: '受邀家庭', role: 'member' }); return route.fulfill({ json: control.families.at(-1) }); }
    if (path === '/api/v1/families') { if (method === 'POST') control.families.push({ id: 'family-1', name: data.name, role: 'owner' }); return route.fulfill({ json: method === 'POST' ? control.families.at(-1) : control.families }); }
    if (path.endsWith('/invitations')) return route.fulfill({ json: { code: 'fixture-once-code', expiresAt: 4070908800 } });
    if (path.endsWith('/members/member-2') && method === 'DELETE') { control.members = control.members.filter(m => m.accountId !== 'member-2'); return route.fulfill({ status: 204 }); }
    if (path.endsWith('/members')) return route.fulfill({ json: control.members });
    return route.fulfill({ status: 503, json: { message: 'Unexpected fixture API; real providers disabled' } });
  });
  await page.goto('/'); await button(page, '设置').click(); await button(page, '账户与家庭成员').click(); await expect(button(page, '建立账户')).toBeEnabled(); return control;
}
async function signIn(page: Page, register = false) { if (register) await button(page, '建立账户').click(); await field(page, '账户用户名').fill('fixture_user'); await field(page, '账户密码').fill('test-only-password-12'); await button(page, register ? '确认建立账户' : '确认登录').click(); await expect(button(page, '退出账户')).toBeEnabled(); }

test('建立账户和家庭、一次性邀请码与成员移除，退出保留本机记录且无上传', async ({ page }) => {
  const fixture = await setup(page); const before = await page.evaluate(() => localStorage.getItem('cardtalk.communication.v1'));
  await expect(page.locator('.account-manager')).toContainText('不会自动上传本机资料'); await expect(field(page, '账户密码')).toHaveAttribute('type', 'password'); await signIn(page, true);
  await field(page, '新家庭名称').fill('测试家庭'); await button(page, '建立家庭').click(); await expect(page.locator('.account-member')).toHaveCount(2);
  await button(page, '协助者').click(); await field(page, '协助者使用人范围').fill('profile-a, profile-b'); await button(page, '生成一次性邀请码').click(); await expect(page.locator('.account-invitation')).toContainText('fixture-once-code');
  expect(fixture.calls.find(c => c.path.endsWith('/invitations'))?.data).toEqual({ role: 'helper', profileAccess: ['profile-a', 'profile-b'] });
  await button(page, '移除成员 家人甲').click(); await page.locator('.uni-modal__btn_primary').click(); await expect(page.locator('.account-member')).toHaveCount(1); await expect(button(page, '移除成员 fixture_user')).toHaveCount(0);
  await button(page, '退出账户').click(); await expect(button(page, '确认建立账户')).toBeVisible(); await expect(page.locator('.account-manager')).toContainText('本机记录仍保留'); expect(await page.evaluate(() => localStorage.getItem('cardtalk.communication.v1'))).toBe(before);
  expect(fixture.calls.filter(c => c.method !== 'GET').every(c => /\/accounts\/|\/families/.test(c.path) || c.path.endsWith('/trial-session'))).toBe(true);
  await button(page, '关闭').click(); await button(page, '本机历史').click(); await expect(page.locator('.history-row')).toHaveCount(1);
});

test('错误密码明确提示，可重试登录并以普通成员加入家庭但不显示管理操作', async ({ page }) => {
  const fixture = await setup(page); await field(page, '账户用户名').fill('fixture_user'); await field(page, '账户密码').fill('wrong-password'); await button(page, '确认登录').click();
  await expect(page.locator('.account-manager [role=alert]')).toHaveText('用户名或密码不正确'); await expect(button(page, '退出账户')).toHaveCount(0); await signIn(page);
  await field(page, '加入家庭邀请码').fill('fixture-once-code'); await button(page, '加入家庭').click(); await expect(button(page, '受邀家庭 · 家人')).toBeVisible(); await expect(button(page, '生成一次性邀请码')).toHaveCount(0); await expect(page.locator('.account-member')).toHaveCount(0);
  expect(fixture.calls.find(c => c.path.endsWith('/invitations/accept'))?.data).toEqual({ code: 'fixture-once-code' }); await expect(field(page, '加入家庭邀请码')).toHaveValue('');
});

test('390px账户登录和家庭管理表单无横向溢出', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 }); await setup(page);
  await expect.poll(() => page.locator('.modal-card').evaluate(el => el.scrollWidth > el.clientWidth + 1)).toBe(false); await signIn(page);
  await field(page, '新家庭名称').fill('手机测试家庭'); await button(page, '建立家庭').click(); await expect(page.locator('.account-member')).toHaveCount(2);
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1)).toBe(false); await expect.poll(() => page.locator('.modal-card').evaluate(el => el.scrollWidth > el.clientWidth + 1)).toBe(false);
});
