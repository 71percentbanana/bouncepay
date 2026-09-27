/**
 * The settlement decision.
 *
 * Kept separate from the HTTP layer so the whole rule set can be tested
 * directly, without sockets.
 */

import {
  RejectedError, parsePayload, checkFreshness, verifySignature, checkKeyBinding,
} from './packet.js';
import { fingerprint } from './ledger.js';

/**
 * @returns {{status:'SETTLED'|'DUPLICATE', receipt:object}}
 * @throws  {RejectedError} with a stable `.code` for anything refused
 */
export function settlePacket(ledger, packet, now = Date.now()) {
  if (!packet || typeof packet !== 'object') {
    throw new RejectedError('MALFORMED', 'body must be a JSON object');
  }

  const payload = parsePayload(packet.payload);

  // Order matters. Identity and integrity come before anything that could
  // move money or leak whether an account exists.
  verifySignature(packet);
  checkKeyBinding(payload, packet.payerPubKey, fingerprint);
  checkFreshness(payload, now);

  // Replay: the same packet may legitimately arrive by several routes, since
  // relays forward opportunistically and none of them knows the others
  // succeeded. That is expected, not an attack, so it is answered with the
  // original receipt rather than an error — the merchant sees one settlement
  // either way.
  const already = ledger.existingReceipt(payload.txId);
  if (already) {
    return { status: 'DUPLICATE', receipt: already };
  }

  // A reused nonce under a *different* txId is not a duplicate delivery. That
  // is a replayed authorisation, and it is refused.
  if (ledger.hasNonce(payload.nonce)) {
    throw new RejectedError('REPLAYED_NONCE',
      'nonce has already been used by a different transaction');
  }

  try {
    const receipt = ledger.settle(payload, packet.hops);
    return { status: 'SETTLED', receipt };
  } catch (err) {
    throw new RejectedError(err.code ?? 'SETTLEMENT_FAILED', err.message);
  }
}
