/**
 * Settlement rules.
 *
 * These sign with a real ECDSA P-256 key and verify through the same code the
 * server uses, so the crypto path is exercised rather than mocked. The Android
 * client produces byte-identical input: SHA256withECDSA over the payload
 * string, with the public key as base64 DER SPKI.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, createSign, randomUUID, randomBytes } from 'node:crypto';

import { Ledger, fingerprint } from '../src/ledger.js';
import { settlePacket } from '../src/settle.js';
import { RejectedError, MAX_AMOUNT_PAISE } from '../src/packet.js';

function newDevice() {
  const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const spki = publicKey.export({ format: 'der', type: 'spki' }).toString('base64');
  return { privateKey, spki, id: fingerprint(spki) };
}

function sign(device, overrides = {}) {
  const payloadObj = {
    v: 1,
    txId: randomUUID(),
    nonce: randomBytes(12).toString('hex'),
    amountPaise: 500_00,
    payerId: device.id,
    payeeId: 'campus-stationery',
    createdAt: Date.now(),
    ...overrides,
  };
  const payload = JSON.stringify(payloadObj);
  const signer = createSign('SHA256');
  signer.update(Buffer.from(payload, 'utf8'));
  signer.end();
  return {
    payload,
    sig: signer.sign(device.privateKey).toString('base64'),
    payerPubKey: device.spki,
    hops: [],
  };
}

function freshBank(openingPaise = 2000_00) {
  const ledger = new Ledger();
  ledger.openAccount('campus-stationery', 'Campus Stationery', 0);
  const device = newDevice();
  ledger.openAccount(device.id, 'Customer', openingPaise);
  return { ledger, device };
}

const rejects = (fn, code) => {
  try {
    fn();
    assert.fail(`expected rejection ${code}, but it succeeded`);
  } catch (err) {
    assert.ok(err instanceof RejectedError, `expected RejectedError, got ${err}`);
    assert.equal(err.code, code);
  }
};

test('a valid packet settles and moves the money', () => {
  const { ledger, device } = freshBank();
  const { status, receipt } = settlePacket(ledger, sign(device));

  assert.equal(status, 'SETTLED');
  assert.equal(receipt.amountPaise, 500_00);
  assert.equal(ledger.account(device.id).balancePaise, 1500_00);
  assert.equal(ledger.account('campus-stationery').balancePaise, 500_00);
});

test('the same packet arriving twice settles once', () => {
  const { ledger, device } = freshBank();
  const packet = sign(device);

  const first = settlePacket(ledger, packet);
  const second = settlePacket(ledger, packet);

  assert.equal(first.status, 'SETTLED');
  assert.equal(second.status, 'DUPLICATE');
  assert.equal(second.receipt.settledAt, first.receipt.settledAt, 'same receipt returned');
  assert.equal(ledger.account(device.id).balancePaise, 1500_00, 'debited once');
});

test('a packet arriving by three routes still settles once', () => {
  const { ledger, device } = freshBank();
  const packet = sign(device);

  const results = [1, 2, 3].map((hops) =>
    settlePacket(ledger, { ...packet, hops: Array(hops).fill('relay') }));

  assert.deepEqual(results.map((r) => r.status), ['SETTLED', 'DUPLICATE', 'DUPLICATE']);
  assert.equal(ledger.account('campus-stationery').balancePaise, 500_00);
});

test('a reused nonce under a new txId is refused', () => {
  const { ledger, device } = freshBank();
  const first = sign(device);
  settlePacket(ledger, first);

  const nonce = JSON.parse(first.payload).nonce;
  rejects(() => settlePacket(ledger, sign(device, { nonce })), 'REPLAYED_NONCE');
  assert.equal(ledger.account(device.id).balancePaise, 1500_00, 'no second debit');
});

test('a tampered amount breaks the signature', () => {
  const { ledger, device } = freshBank();
  const packet = sign(device);
  const tampered = JSON.parse(packet.payload);
  tampered.amountPaise = 1;

  rejects(() => settlePacket(ledger, { ...packet, payload: JSON.stringify(tampered) }),
    'BAD_SIGNATURE');
});

test('a relay cannot re-sign a packet as someone else', () => {
  const { ledger, device } = freshBank();
  const attacker = newDevice();
  // Attacker signs correctly, but claims the victim's payerId.
  const forged = sign(attacker, { payerId: device.id });

  rejects(() => settlePacket(ledger, forged), 'KEY_MISMATCH');
  assert.equal(ledger.account(device.id).balancePaise, 2000_00, 'victim untouched');
});

test('spending beyond the balance is refused', () => {
  const { ledger, device } = freshBank(100_00);
  rejects(() => settlePacket(ledger, sign(device, { amountPaise: 500_00 })),
    'INSUFFICIENT_FUNDS');
  assert.equal(ledger.account(device.id).balancePaise, 100_00);
});

test('the offline ceiling is enforced', () => {
  const { ledger, device } = freshBank(100000_00);
  rejects(() => settlePacket(ledger, sign(device, { amountPaise: MAX_AMOUNT_PAISE + 1 })),
    'AMOUNT_EXCEEDS_LIMIT');
});

test('zero and negative amounts are refused', () => {
  const { ledger, device } = freshBank();
  rejects(() => settlePacket(ledger, sign(device, { amountPaise: 0 })), 'INVALID_AMOUNT');
  rejects(() => settlePacket(ledger, sign(device, { amountPaise: -500 })), 'INVALID_AMOUNT');
});

test('a packet older than the carry window expires', () => {
  const { ledger, device } = freshBank();
  const eightDaysAgo = Date.now() - 8 * 24 * 60 * 60 * 1000;
  rejects(() => settlePacket(ledger, sign(device, { createdAt: eightDaysAgo })), 'EXPIRED');
});

test('a packet carried for two days still settles', () => {
  const { ledger, device } = freshBank();
  const twoDaysAgo = Date.now() - 2 * 24 * 60 * 60 * 1000;
  const { status } = settlePacket(ledger, sign(device, { createdAt: twoDaysAgo }));
  assert.equal(status, 'SETTLED', 'store-and-forward must tolerate real delay');
});

test('a future-dated packet is refused', () => {
  const { ledger, device } = freshBank();
  const nextHour = Date.now() + 60 * 60 * 1000;
  rejects(() => settlePacket(ledger, sign(device, { createdAt: nextHour })), 'FUTURE_DATED');
});

test('malformed input is refused rather than crashing', () => {
  const { ledger } = freshBank();
  rejects(() => settlePacket(ledger, null), 'MALFORMED');
  rejects(() => settlePacket(ledger, {}), 'MALFORMED');
  rejects(() => settlePacket(ledger, { payload: 'not json', sig: 'x', payerPubKey: 'y' }), 'MALFORMED');
});

test('a non-P-256 key is refused', () => {
  const { ledger } = freshBank();
  const { publicKey } = generateKeyPairSync('ec', { namedCurve: 'secp384r1' });
  const spki = publicKey.export({ format: 'der', type: 'spki' }).toString('base64');
  const device = newDevice();
  const packet = sign(device);
  rejects(() => settlePacket(ledger, { ...packet, payerPubKey: spki }), 'BAD_PUBLIC_KEY');
});

test('an unknown payer cannot settle', () => {
  const { ledger } = freshBank();
  const stranger = newDevice();
  rejects(() => settlePacket(ledger, sign(stranger)), 'UNKNOWN_ACCOUNT');
});
