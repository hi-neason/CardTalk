import { expect, test, type Page } from '@playwright/test';
import type { BillingOrder, Product } from '../../frontend/src/platform/billing';
const button = (page: Page, name: string) => page.getByRole('button', { name, exact: true });
const product: Product = { id: 'analytics-month', feature: 'analytics', title: '分析月包', amountMinor: 1200, currency: 'CNY', durationDays: 30 };
function order(familyId = 'family-a'): BillingOrder { return { id: 'order-fixture', familyId, actorId: 'account-a', productId: product.id, feature: product.feature, title: product.title, amountMinor: product.amountMinor, currency: 'CNY', durationDays: 30, provider: 'wechat', status: 'PENDING', checkoutUrl: 'https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?prepay_id=fixture', createdAt: '2026-10-12T12:00:00Z', updatedAt: '2026-10-12T12:00:00Z' }; }
async function setup(page: Page, catalog = true) {
  const controls = { products: catalog ? [product] : [], orders: [] as BillingOrder[], createInputs: [] as { productId: string; requestId: string }[], failCreate: false, authStatus: 200, accountId: 'account-a', paid: false, holdRights: false, rightsRequested: false, releaseRights: () => {}, rightsDone: Promise.resolve(), calls: [] as string[] };
  await page.addInitScript(() => { Object.defineProperty(window, '__openedPayment', { value: [] }); window.open = ((url: string) => { (window as unknown as { __openedPayment: string[] }).__openedPayment.push(url); return null; }) as typeof window.open; });
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname, method = route.request().method(); controls.calls.push(path);
    if (path.endsWith('/health')) return route.fulfill({ json: { status: 'ok', llmConfigured: false, ttsConfigured: false } });
    if (path.endsWith('/trial-session')) return route.fulfill({ json: { token: 'billing-fixture', remaining: 10, limit: 10, resetAt: '2099-01-01', capabilities: {} } });
    if (path.endsWith('/accounts/me')) return route.fulfill(controls.authStatus === 200 ? { json: { account: { id: controls.accountId, username: '账单用户' } } } : { status: controls.authStatus, json: { message: '登录已失效' } });
    if (path.endsWith('/accounts/logout')) return route.fulfill({ status: 204 });
    if (path === '/api/v1/families') return route.fulfill({ json: [{ id: 'family-a', name: '甲家庭', role: 'owner' }, { id: 'family-b', name: '乙家庭', role: 'owner' }] });
    if (path.endsWith('/members')) return route.fulfill({ json: [] });
    if (path.endsWith('/entitlements')) {
      let done = () => {}; if (controls.holdRights && path.includes('family-a')) { controls.rightsRequested = true; controls.rightsDone = new Promise<void>(resolve => done = resolve); await new Promise<void>(resolve => controls.releaseRights = resolve); }
      await route.fulfill({ json: { configured: true, role: 'owner', features: [{ feature: 'analytics', active: controls.paid, reason: controls.paid ? 'purchase' : 'none', expiresAt: null, sources: controls.paid ? ['purchase'] : [], actions: { readExisting: true, writeAdvanced: controls.paid, purchase: true } }] } }); done(); return;
    }
    if (path.endsWith('/billing/products')) return route.fulfill({ json: { products: controls.products, provider: 'wechat', providerConfigured: catalog, canPurchase: catalog } });
    if (path.endsWith('/billing/orders')) {
      if (method === 'POST') { controls.createInputs.push(route.request().postDataJSON()); if (controls.failCreate) { controls.failCreate = false; await route.abort('connectionreset'); return; } controls.orders = [order()]; return route.fulfill({ json: { order: controls.orders[0], providerConfigured: true } }); }
      return route.fulfill({ json: path.includes('family-a') ? controls.orders : [] });
    }
    if (path.endsWith('/query')) { controls.orders[0] = { ...controls.orders[0]!, status: controls.paid ? 'PAID' : 'PENDING' }; return route.fulfill({ json: controls.orders[0] }); }
    return route.fulfill({ status: 503, json: { message: 'Unexpected fixture call; real payment disabled' } });
  });
  await page.goto('/'); await button(page, '设置').click(); await button(page, '账户与家庭成员').click(); await expect(page.locator('.billing-manager')).toBeVisible(); await expect(button(page, '刷新权益与订单')).toBeEnabled(); return controls;
}
async function buy(page: Page) { await button(page, '购买 分析月包').click(); await page.locator('.uni-modal__btn_primary').click(); }
async function flush(page: Page) { await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))); }

test('未配置商品时不能下单，保留已有资料的权益状态明确展示', async ({ page }) => {
  const controls = await setup(page, false); await expect(page.locator('.billing-manager')).toContainText('当前未配置商品和价格，不能购买'); await expect(page.locator('.billing-manager')).toContainText('已有资料保留'); await expect(button(page, '购买 分析月包')).toHaveCount(0); expect(controls.createInputs).toHaveLength(0);
});

test('未知下单结果重试复用nonce，支付回跳不算付款，服务端查询后才展示购买权益', async ({ page }) => {
  const controls = await setup(page); controls.failCreate = true; await buy(page); await expect(page.locator('.billing-manager [role=alert]')).toContainText('连接失败');
  await buy(page); await expect(page.locator('.billing-order')).toContainText('等待支付'); expect(controls.createInputs).toHaveLength(2); expect(controls.createInputs[1]).toEqual(controls.createInputs[0]);
  await button(page, '前往支付').click(); await page.locator('.uni-modal__btn_primary').click(); await expect.poll(() => page.evaluate(() => (window as unknown as { __openedPayment: string[] }).__openedPayment.length)).toBe(1);
  await page.evaluate(() => { history.replaceState(null, '', '/?payment=success'); window.dispatchEvent(new Event('focus')); }); await expect(page.locator('.billing-order')).toContainText('等待支付'); await expect(page.locator('.billing-manager')).not.toContainText('包含已核验购买权益');
  await button(page, '查询支付 / 退款结果').click(); await expect(button(page, '刷新权益与订单')).toBeEnabled(); await expect(page.locator('.billing-order')).toContainText('等待支付');
  controls.paid = true; await button(page, '查询支付 / 退款结果').click(); await expect(page.locator('.billing-order')).toContainText('付款已核验'); await expect(page.locator('.billing-manager')).toContainText('包含已核验购买权益');
});

for (const changed of ['expired', 'different-account'] as const) {
  test(`${changed} 验权失败后清除权益商品订单并禁止继续购买`, async ({ page }) => {
    const controls = await setup(page); await buy(page); await expect(page.locator('.billing-order')).toHaveCount(1);
    if (changed === 'expired') controls.authStatus = 401; else controls.accountId = 'different-account';
    await button(page, '刷新权益与订单').click(); await expect(page.locator('.billing-manager [role=alert]')).toBeVisible(); await expect(page.locator('.billing-product')).toHaveCount(0); await expect(page.locator('.billing-order')).toHaveCount(0); await expect(page.locator('.billing-manager .feature-row')).toHaveCount(0); await expect(button(page, '刷新权益与订单')).toBeDisabled();
  });
}

test('切换家庭使旧组件失效，迟到权益不覆盖新家庭或继续读取旧订单', async ({ page }) => {
  const controls = await setup(page); controls.holdRights = true; await button(page, '刷新权益与订单').click(); await expect.poll(() => controls.rightsRequested).toBe(true);
  await button(page, '乙家庭 · 家庭创建者').click(); await expect(page.locator('.billing-manager > .section-title').first()).toContainText('乙家庭'); await expect(button(page, '刷新权益与订单')).toBeEnabled(); const callCount = controls.calls.filter(p => p === '/api/v1/families/family-a/billing/orders').length;
  controls.releaseRights(); await controls.rightsDone; await flush(page); await expect(page.locator('.billing-manager > .section-title').first()).toContainText('乙家庭'); expect(controls.calls.filter(p => p === '/api/v1/families/family-a/billing/orders')).toHaveLength(callCount);
});

test('支付确认弹窗期间账户面板已关闭，不得迟到打开付款窗口', async ({ page }) => {
  await setup(page); await buy(page); await expect(page.locator('.billing-order')).toHaveCount(1); await button(page, '前往支付').click(); await expect(page.locator('.uni-modal')).toBeVisible();
  // Trigger the underlying panel close while its payment confirmation promise is pending.
  await button(page, '关闭').dispatchEvent('click'); await expect(page.locator('.billing-manager')).toHaveCount(0); await page.locator('.uni-modal__btn_primary').click(); await flush(page);
  expect(await page.evaluate(() => (window as unknown as { __openedPayment: string[] }).__openedPayment)).toEqual([]);
});

test('查询同商品历史已付款订单不清除新待支付订单的重试编号', async ({ page }) => {
  const controls = await setup(page); await buy(page); await expect(page.locator('.billing-order')).toHaveCount(1);
  const pendingNonce = controls.createInputs[0]!.requestId;
  const historical = { ...order(), id: 'old-paid-order', title: '历史分析月包', status: 'PAID', checkoutUrl: null };
  controls.orders.push(historical); await button(page, '刷新权益与订单').click(); await expect(page.locator('.billing-order')).toHaveCount(2);
  await page.route('**/billing/orders/old-paid-order/query', route => route.fulfill({ json: historical }));
  await page.locator('.billing-order').filter({ hasText: '历史分析月包' }).getByRole('button', { name: '查询支付 / 退款结果', exact: true }).click(); await expect(button(page, '刷新权益与订单')).toBeEnabled();
  await buy(page); await expect.poll(() => controls.createInputs.length).toBe(2); expect(controls.createInputs[1]!.requestId).toBe(pendingNonce);
});
