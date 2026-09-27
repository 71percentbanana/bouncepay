/**
 * Bank-signed receipts, and the fixture the Android suite verifies.
 *
 * test/fixtures/receipt.json was signed once by this code. The Android tests
 * verify that same file with the phone's code, and the test below checks the
 * bank still produces byte-identical payloads — so neither side can change
 * the receipt format without the other noticing.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createVerify } from 'node:crypto';
import { readFileSync } from 'node:fs';

import { loadBankKey, proveReceipt, receiptPayload } from '../src/receipts.js';

const fixture = JSON.parse(readFileSync(new URL('./fixtures/receipt.json', import.meta.url), 'utf8'));

function verifies(proof, spki) {
  const v = createVerify('SHA256');
  v.update(proof.payload);
  return v.verify({ key: Buffer.from(spki, 'base64'), format: 'der', type: 'spki' }, Buffer.from(proof.sig, 'base64'));
}

test('a receipt proof verifies with the bank key and nothing else', () => {
  const bank = loadBankKey(null);
  const other = loadBankKey(null);
  const proof = proveReceipt(bank, fixture.receipt);
  assert.ok(verifies(proof, bank.publicKeySpki));
  assert.ok(!verifies(proof, other.publicKeySpki));
});

test('a relay cannot edit a receipt', () => {
  const bank = loadBankKey(null);
  const proof = proveReceipt(bank, fixture.receipt);
  const forged = { ...proof, payload: proof.payload.replace('campus-stationery', 'bp_relay00000000') };
  assert.ok(!verifies(forged, bank.publicKeySpki));
});

test('the fixture still matches the format the bank produces', () => {
  assert.equal(receiptPayload(fixture.receipt), fixture.proof.payload);
  assert.ok(verifies(fixture.proof, fixture.bankPubKey));
});
