/**
 * The HTTP surface, end to end over a real socket: what a bridge phone and the
 * dashboard actually talk to.
 */

import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, createSign, createVerify, randomUUID, randomBytes } from 'node:crypto';

import { createBank } from '../src/server.js';
import { fingerprint } from '../src/ledger.js';

let server;
let base;

before(async () => {
  ({ server } = createBank({ snapshotPath: null }));
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  base = `http://127.0.0.1:${server.address().port}`;
});

after(() => new Promise((resolve) => server.close(resolve)));

const post = (path, body) => fetch(base + path, {
  method: 'POST',
  headers: { 'content-type': 'application/json' },
  body: typeof body === 'string' ? body : JSON.stringify(body),
});

function newDevice() {
  const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const spki = publicKey.export({ format: 'der', type: 'spki' }).toString('base64');
  return { privateKey, spki, id: fingerprint(spki) };
}

function signed(device, amountPaise, hops) {
  const payload = JSON.stringify({
    v: 1, txId: randomUUID(), nonce: randomBytes(12).toString('hex'),
    amountPaise, payerId: device.id, payeeId: 'campus-stationery', createdAt: Date.now(),
  });
  const signer = createSign('SHA256');
  signer.update(payload);
  return { payload, sig: signer.sign(device.privateKey).toString('base64'), payerPubKey: device.spki, hops };
}

test('the dashboard is served at /', async () => {
  const res = await fetch(base + '/');
  assert.equal(res.status, 200);
  assert.match(res.headers.get('content-type'), /text\/html/);
  assert.match(await res.text(), /BouncePay/);
});

test('health reports the addresses phones should use', async () => {
  const body = await (await fetch(base + '/v1/health')).json();
  assert.equal(body.ok, true);
  assert.ok(Array.isArray(body.lan));
});

test('enrol, pay across two relays, and see it everywhere', async () => {
  const phone = newDevice();

  const enrolled = await (await post('/v1/enroll', { payerPubKey: phone.spki, label: 'Pixel', openingPaise: 1000_00 })).json();
  assert.equal(enrolled.accountId, phone.id);
  assert.equal(enrolled.account.balancePaise, 1000_00);

  // Enrolling again must not reset a balance that has been spent from.
  const again = await (await post('/v1/enroll', { payerPubKey: phone.spki, openingPaise: 5000_00 })).json();
  assert.equal(again.account.balancePaise, 1000_00);

  const packet = signed(phone, 250_00, [phone.id, 'bp_relay000000001', 'bp_bridge00000001']);
  const res = await post('/v1/settle', packet);
  assert.equal(res.status, 200);
  const { status, receipt, proof } = await res.json();
  assert.equal(status, 'SETTLED');
  assert.equal(receipt.payerBalancePaise, 750_00);

  // The proof is signed by the key the bank advertised at enrolment.
  const v = createVerify('SHA256');
  v.update(proof.payload);
  assert.ok(v.verify({ key: Buffer.from(enrolled.bankPubKey, 'base64'), format: 'der', type: 'spki' },
    Buffer.from(proof.sig, 'base64')), 'receipt proof verifies with the enrolment key');
  assert.equal(JSON.parse(proof.payload).txId, receipt.txId);

  // The same packet via another route is answered, not re-applied.
  const dup = await (await post('/v1/settle', packet)).json();
  assert.equal(dup.status, 'DUPLICATE');

  const account = await (await fetch(`${base}/v1/account?id=${phone.id}`)).json();
  assert.equal(account.account.balancePaise, 750_00);
  assert.equal(account.history.length, 1);

  const ledger = await (await fetch(base + '/v1/ledger')).json();
  assert.equal(ledger.recentSettlements[0].txId, receipt.txId);
  assert.deepEqual(ledger.recentSettlements[0].hops, packet.hops);
});

test('a tampered packet is refused and audited under its txId', async () => {
  const phone = newDevice();
  await post('/v1/enroll', { payerPubKey: phone.spki });

  const packet = signed(phone, 100_00, [phone.id]);
  const txId = JSON.parse(packet.payload).txId;
  packet.payload = packet.payload.replace('"amountPaise":10000', '"amountPaise":190000');

  const res = await post('/v1/settle', packet);
  assert.equal(res.status, 422);
  assert.equal((await res.json()).code, 'BAD_SIGNATURE');

  const ledger = await (await fetch(base + '/v1/ledger')).json();
  const audit = ledger.recentRejections.find((r) => r.txId === txId);
  assert.ok(audit, 'the rejection is recorded against the packet it came from');
  assert.equal(audit.code, 'BAD_SIGNATURE');
});

test('unknown accounts and routes are 404s, bad JSON is a 400', async () => {
  assert.equal((await fetch(base + '/v1/account?id=bp_nobody')).status, 404);
  assert.equal((await fetch(base + '/nope')).status, 404);
  assert.equal((await post('/v1/settle', '{not json')).status, 400);
});
