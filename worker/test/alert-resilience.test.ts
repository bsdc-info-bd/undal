import assert from 'node:assert/strict';
import { test } from 'node:test';
import { pushBody, sendToTopic } from '../src/fcmv1';
import { raiseKitchenAlert } from '../src/alerting';
import { runRealertSweep } from '../src/crons/realert';
import { handleOrderCreated, handleTestAlert } from '../src/routes/notify';
import { FakeFirebase } from './helpers/fake-firebase';

test('the transport enforces order priority and no collapse key even for a mistaken caller', () => {
  const { message } = JSON.parse(pushBody({ type: 'order_created' }, {
    data: {}, highPriority: false, collapseKey: 'all-orders',
    display: { title: 't', body: 'b', channelId: 'wrong', tag: 'all' },
  }));
  assert.equal(message.android.priority, 'HIGH');
  assert.equal(message.android.collapse_key, undefined);
  assert.equal(message.notification, undefined);
  assert.equal(message.android.notification, undefined);
});

test('extra metadata cannot replace the order identity or routing contract', async () => {
  const backend = new FakeFirebase(), restore = backend.install();
  try {
    await raiseKitchenAlert(backend.env, { orderId: 'real-order', shortId: 'UND1', title: 'New', body: 'Review',
      extra: { orderId: 'wrong-order', type: 'promo', channelId: 'wrong', repeated: 'true' } });
    const data = backend.fcmSends[0].message.data;
    assert.equal(data.orderId, 'real-order'); assert.equal(data.type, 'order_created');
    assert.equal(data.channelId, 'vendor_new_order'); assert.equal(data.repeated, 'true');
    assert.ok(Number(data.sentAt) > 0);
  } finally { restore(); }
});

test('a transient FCM refusal is retried without deleting the device token', async () => {
  const backend = new FakeFirebase(), restore = backend.install();
  try {
    backend.fcmFailFirst = 1; backend.fcmStatus = 503; backend.fcmErrorCode = 'UNAVAILABLE';
    const outcome = await sendToTopic(backend.env, 'undal_kitchen', { data: { type: 'order_created' } });
    assert.equal(outcome.ok, true); assert.equal(backend.fcmSends.length, 2);
  } finally { restore(); }
});

test('quota refusals do not hot-loop and are not classified as dead tokens', async () => {
  const backend = new FakeFirebase(), restore = backend.install();
  try {
    backend.fcmRefuseAll = true; backend.fcmStatus = 429; backend.fcmErrorCode = 'QUOTA_EXCEEDED';
    const outcome = await sendToTopic(backend.env, 'undal_kitchen', { data: { type: 'order_created' } });
    assert.equal(outcome.ok, false);
    if (!outcome.ok) assert.equal(outcome.unregistered, false);
    assert.equal(backend.fcmSends.length, 1);
  } finally { restore(); }
});

test('complete delivery failure is surfaced by both the alert ladder and the cron', async () => {
  const backend = new FakeFirebase(), restore = backend.install();
  try {
    backend.fcmRefuseAll = true; backend.fcmStatus = 403; backend.fcmErrorCode = 'PERMISSION_DENIED';
    backend.setDocument('orders/new', { status: 'placed', createdAt: new Date(Date.now() - 120_000).toISOString(), shortId: 'UND1' });
    const outcome = await raiseKitchenAlert(backend.env, { orderId: 'new', shortId: 'UND1', title: 'New', body: 'Review' });
    assert.equal(outcome.delivered, 0); assert.match(outcome.warning!, /No alert route/);
    const sweep = await runRealertSweep(backend.env);
    assert.equal(sweep.failures.length, 1);
  } finally { restore(); }
});

test('abandoned orders cannot permanently starve newer orders in later pages', async () => {
  const backend = new FakeFirebase(), restore = backend.install();
  try {
    for (let i = 0; i < 51; i++) backend.setDocument(`orders/old-${String(i).padStart(3, '0')}`, {
      status: 'placed', createdAt: new Date(Date.now() - 86_400_000).toISOString(), shortId: 'OLD',
    });
    backend.setDocument('orders/z-new', { status: 'placed', createdAt: new Date(Date.now() - 120_000).toISOString(), shortId: 'NEW' });
    const first = await runRealertSweep(backend.env);
    assert.equal(first.considered, 50); assert.equal(first.realerted, 0);
    const second = await runRealertSweep(backend.env);
    assert.equal(second.considered, 2); assert.equal(second.realerted, 1);
    assert.equal(backend.fcmSends[0].message.data.orderId, 'z-new');
    const third = await runRealertSweep(backend.env);
    assert.equal(third.considered, 50, 'the cursor wraps after the last page');
  } finally { restore(); }
});

test('generic NOT_FOUND does not purge a possibly valid device registration', async () => {
  const backend = new FakeFirebase(), restore = backend.install();
  try {
    backend.fcmRefuseAll = true; backend.fcmStatus = 404; backend.fcmErrorCode = 'NOT_FOUND';
    const outcome = await sendToTopic(backend.env, 'undal_kitchen', { data: { type: 'order_created' } });
    assert.equal(outcome.ok, false);
    if (!outcome.ok) assert.equal(outcome.unregistered, false);
  } finally { restore(); }
});

test('transient retries stop after three attempts and report failure honestly', async () => {
  const backend = new FakeFirebase(), restore = backend.install();
  try {
    backend.fcmRefuseAll = true; backend.fcmStatus = 503; backend.fcmErrorCode = 'UNAVAILABLE';
    const outcome = await sendToTopic(backend.env, 'undal_kitchen', { data: { type: 'order_created' } });
    assert.equal(outcome.ok, false); assert.equal(backend.fcmSends.length, 3);
    if (!outcome.ok) assert.equal(outcome.unregistered, false);
  } finally { restore(); }
});

test('metadata cannot turn a real order into a self-stopping test alert', async () => {
  const backend = new FakeFirebase(), restore = backend.install();
  try {
    await raiseKitchenAlert(backend.env, { orderId: 'o', shortId: 'U1', title: 'Order', body: 'Review', extra: { test: 'true', kind: 'missed' } });
    assert.equal(backend.fcmSends[0].message.data.test, undefined);
    assert.equal(backend.fcmSends[0].message.data.kind, undefined);
  } finally { restore(); }
});

function request(token: string, orderId = 'answered') {
  return new Request('https://undal.test/notify/order-created', {
    method: 'POST', headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
    body: JSON.stringify({ orderId }),
  });
}

test('an answered order does not start another kitchen alarm through the customer relay', async () => {
  const backend = new FakeFirebase(), restore = backend.install();
  try {
    const token = await backend.withAccount('customer');
    backend.setDocument('orders/answered', { customerId: 'customer', status: 'accepted' });
    const response = await handleOrderCreated(backend.env, request(token));
    const body = await response.json() as Record<string, unknown>;
    assert.equal(body.skipped, true); assert.equal(backend.fcmSends.length, 0);
  } finally { restore(); }
});

test('a customer cannot trigger the vendor test-alarm endpoint', async () => {
  const backend = new FakeFirebase(), restore = backend.install();
  try {
    const token = await backend.withAccount('customer');
    await assert.rejects(() => handleTestAlert(backend.env, request(token)), (error: any) => error.status === 403);
    assert.equal(backend.fcmSends.length, 0);
  } finally { restore(); }
});

test('a customer cannot relay an order that belongs to another account', async () => {
  const backend = new FakeFirebase(), restore = backend.install();
  try {
    const token = await backend.withAccount('customer');
    backend.setDocument('orders/answered', { customerId: 'someone-else', status: 'placed' });
    await assert.rejects(() => handleOrderCreated(backend.env, request(token)), (error: any) => error.status === 403);
    assert.equal(backend.fcmSends.length, 0);
  } finally { restore(); }
});
