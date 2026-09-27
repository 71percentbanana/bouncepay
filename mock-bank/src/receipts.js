/**
 * Bank-signed receipts.
 *
 * A receipt travels back to the payer through the same untrusted phones that
 * carried the payment. Any of them could claim "it settled", so the bank signs
 * each receipt and phones check it against the bank key they pinned while
 * online. A relay can carry a receipt; it cannot mint one.
 */

import { generateKeyPairSync, createPrivateKey, createPublicKey, createSign } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';

/**
 * Loads the bank's signing key from [path], creating it on first run.
 * With no path the key lives only as long as the process — fine for tests,
 * but phones pin the key, so a real run keeps it on disk.
 */
export function loadBankKey(path = null) {
  let privateKey;
  if (path && existsSync(path)) {
    privateKey = createPrivateKey(readFileSync(path, 'utf8'));
  } else {
    ({ privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' }));
    if (path) writeFileSync(path, privateKey.export({ format: 'pem', type: 'pkcs8' }), { mode: 0o600 });
  }
  const publicKeySpki = createPublicKey(privateKey)
    .export({ format: 'der', type: 'spki' })
    .toString('base64');
  return { privateKey, publicKeySpki };
}

/**
 * The exact string the bank signs. Like a packet payload it is carried and
 * verified as these bytes and never re-serialised, so the field order here is
 * the format.
 */
export function receiptPayload(receipt) {
  return JSON.stringify({
    v: 1,
    kind: 'receipt',
    txId: receipt.txId,
    amountPaise: receipt.amountPaise,
    payerId: receipt.payerId,
    payeeId: receipt.payeeId,
    settledAt: receipt.settledAt,
  });
}

/** `{payload, sig}` — SHA256withECDSA, base64 DER, same as the phones use. */
export function proveReceipt(bankKey, receipt) {
  const payload = receiptPayload(receipt);
  const signer = createSign('SHA256');
  signer.update(payload);
  signer.end();
  return { payload, sig: signer.sign(bankKey.privateKey).toString('base64') };
}
