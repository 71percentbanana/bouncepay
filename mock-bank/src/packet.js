/**
 * BouncePay payment packet — shared shape and verification.
 *
 * A packet is signed once, on the payer's device, while it is offline. It is
 * then carried by relays that can read it but cannot alter it, and finally handed to
 * this service by whichever device reached the internet first.
 *
 * The signature covers `payload`, which travels as an opaque string rather
 * than a re-serialised object. Every platform serialises JSON slightly
 * differently — key order, spacing, number formatting — so re-encoding on the
 * server would produce different bytes from the ones Android signed and every
 * signature would fail. Signing and verifying the exact same string removes
 * that whole class of bug.
 */

import { createVerify, createPublicKey } from 'node:crypto';

export const PROTOCOL_VERSION = 1;

/** Offline spend ceiling. A packet cannot move more than this. */
export const MAX_AMOUNT_PAISE = 2_000_00; // ₹2,000

/** How stale a packet may be. Generous: it may have sat on a relay for days. */
export const MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000;

/** Small tolerance for clock skew between an offline phone and this service. */
export const FUTURE_SKEW_MS = 5 * 60 * 1000;

export class RejectedError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}

const reject = (code, message) => {
  throw new RejectedError(code, message);
};

/**
 * Parse and structurally validate the signed payload.
 * Throws RejectedError; never returns a partially valid object.
 */
export function parsePayload(payloadString) {
  if (typeof payloadString !== 'string' || payloadString.length === 0) {
    reject('MALFORMED', 'payload must be a non-empty string');
  }
  if (payloadString.length > 4096) {
    reject('MALFORMED', 'payload too large');
  }

  let p;
  try {
    p = JSON.parse(payloadString);
  } catch {
    reject('MALFORMED', 'payload is not valid JSON');
  }

  if (p.v !== PROTOCOL_VERSION) {
    reject('UNSUPPORTED_VERSION', `unsupported protocol version: ${p.v}`);
  }
  for (const field of ['txId', 'nonce', 'payerId', 'payeeId']) {
    if (typeof p[field] !== 'string' || !p[field]) {
      reject('MALFORMED', `missing or invalid field: ${field}`);
    }
  }
  if (!Number.isInteger(p.amountPaise)) {
    reject('MALFORMED', 'amountPaise must be an integer number of paise');
  }
  if (p.amountPaise <= 0) {
    reject('INVALID_AMOUNT', 'amount must be positive');
  }
  if (p.amountPaise > MAX_AMOUNT_PAISE) {
    reject('AMOUNT_EXCEEDS_LIMIT',
      `amount ${p.amountPaise} exceeds the offline ceiling of ${MAX_AMOUNT_PAISE}`);
  }
  if (!Number.isInteger(p.createdAt)) {
    reject('MALFORMED', 'createdAt must be epoch milliseconds');
  }
  return p;
}

/** Reject packets that are too old to trust, or dated in the future. */
export function checkFreshness(payload, now = Date.now()) {
  const age = now - payload.createdAt;
  if (age > MAX_AGE_MS) {
    reject('EXPIRED', `packet is ${Math.round(age / 86400000)} days old`);
  }
  if (age < -FUTURE_SKEW_MS) {
    reject('FUTURE_DATED', 'packet is dated in the future beyond clock skew');
  }
}

/**
 * Verify an ECDSA P-256 signature over the payload bytes.
 *
 * `payerPubKey` is base64 DER SPKI, which is what Android's KeyStore hands
 * back from `PublicKey.getEncoded()`, so the two ends agree without any
 * custom encoding.
 */
export function verifySignature({ payload, sig, payerPubKey }) {
  if (typeof sig !== 'string' || typeof payerPubKey !== 'string') {
    reject('MALFORMED', 'sig and payerPubKey must be base64 strings');
  }

  let key;
  try {
    key = createPublicKey({
      key: Buffer.from(payerPubKey, 'base64'),
      format: 'der',
      type: 'spki',
    });
  } catch {
    reject('BAD_PUBLIC_KEY', 'payerPubKey is not a valid SPKI public key');
  }

  if (key.asymmetricKeyType !== 'ec') {
    reject('BAD_PUBLIC_KEY', 'payerPubKey must be an EC key');
  }
  if (key.asymmetricKeyDetails?.namedCurve !== 'prime256v1') {
    reject('BAD_PUBLIC_KEY', 'payerPubKey must be on the P-256 curve');
  }

  const verifier = createVerify('SHA256');
  verifier.update(Buffer.from(payload, 'utf8'));
  verifier.end();

  let ok = false;
  try {
    ok = verifier.verify(key, Buffer.from(sig, 'base64'));
  } catch {
    ok = false;
  }
  if (!ok) {
    reject('BAD_SIGNATURE', 'signature does not verify against payerPubKey');
  }
}

/**
 * The payer id must be derived from the key that signed, otherwise a valid
 * signature from key A could be presented as a payment from account B.
 */
export function checkKeyBinding(payload, payerPubKey, fingerprintOf) {
  const expected = fingerprintOf(payerPubKey);
  if (payload.payerId !== expected) {
    reject('KEY_MISMATCH',
      `payerId ${payload.payerId} does not match the signing key ${expected}`);
  }
}
