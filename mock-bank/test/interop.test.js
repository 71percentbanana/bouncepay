/**
 * Android ↔ bank interop.
 *
 * The Android test suite (WireFormatTest) builds a packet exactly the way the
 * app does — same payload concatenation, SHA256withECDSA, base64 DER SPKI —
 * and writes it to android/app/build/wire-sample.json. This test verifies that
 * very file with the bank's own, independent implementation.
 *
 * If the two sides ever drift by a byte, this is where it shows up, rather
 * than as a "bad signature" on a phone in front of a judge.
 *
 * Run `gradle testDebugUnitTest` in android/ first to produce the sample.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

import { Ledger, fingerprint } from '../src/ledger.js';
import { settlePacket } from '../src/settle.js';

const here = dirname(fileURLToPath(import.meta.url));
const SAMPLE = resolve(here, '../../android/app/build/wire-sample.json');

test('a packet signed the way Android signs it settles here', { skip: !existsSync(SAMPLE) && 'run the Android unit tests first' }, () => {
  const packet = JSON.parse(readFileSync(SAMPLE, 'utf8'));
  const payload = JSON.parse(packet.payload);

  // The account id is derived independently on each side; they must agree.
  assert.equal(fingerprint(packet.payerPubKey), payload.payerId,
    'Kotlin and Node must derive the same account id from the same key');

  const ledger = new Ledger();
  ledger.openAccount(payload.payerId, 'Android device', 2000_00);
  ledger.openAccount('campus-stationery', 'Campus Stationery', 0);

  // The sample may be a few minutes old by the time this runs; pin "now" to
  // its creation time so freshness does not make the test flaky.
  const { status, receipt } = settlePacket(ledger, packet, payload.createdAt + 1000);

  assert.equal(status, 'SETTLED');
  assert.equal(receipt.amountPaise, payload.amountPaise);
  assert.equal(ledger.account('campus-stationery').balancePaise, payload.amountPaise);
});
