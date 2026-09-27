/**
 * The bank's book of record.
 *
 * Deliberately small and in-memory, with an optional JSON snapshot so a demo
 * survives a restart. The parts that matter for the story are the ones that
 * make settlement safe: every txId is remembered so a packet that arrives by
 * three different routes settles exactly once, and balances only move inside
 * a single synchronous step so a duplicate cannot interleave.
 */

import { createHash } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';

/** Short, stable identifier for a public key. Mirrors the Android side. */
export function fingerprint(spkiBase64) {
  const digest = createHash('sha256').update(Buffer.from(spkiBase64, 'base64')).digest();
  return 'bp_' + digest.subarray(0, 8).toString('hex');
}

export class Ledger {
  constructor({ snapshotPath = null } = {}) {
    this.snapshotPath = snapshotPath;
    this.accounts = new Map();   // id -> { id, label, balancePaise }
    this.settled = new Map();    // txId -> receipt
    this.nonces = new Set();     // every nonce ever accepted
    this.rejections = [];        // audit trail for the demo

    if (snapshotPath && existsSync(snapshotPath)) {
      this.#restore(JSON.parse(readFileSync(snapshotPath, 'utf8')));
    }
  }

  #restore(data) {
    for (const a of data.accounts ?? []) this.accounts.set(a.id, a);
    for (const r of data.settled ?? []) this.settled.set(r.txId, r);
    for (const n of data.nonces ?? []) this.nonces.add(n);
    this.rejections = data.rejections ?? [];
  }

  persist() {
    if (!this.snapshotPath) return;
    writeFileSync(this.snapshotPath, JSON.stringify({
      accounts: [...this.accounts.values()],
      settled: [...this.settled.values()],
      nonces: [...this.nonces],
      rejections: this.rejections.slice(-100),
    }, null, 2));
  }

  openAccount(id, label, openingPaise = 0) {
    if (!this.accounts.has(id)) {
      this.accounts.set(id, { id, label, balancePaise: openingPaise });
    }
    return this.accounts.get(id);
  }

  account(id) {
    return this.accounts.get(id) ?? null;
  }

  /** A packet already settled — return the original receipt, do not re-apply. */
  existingReceipt(txId) {
    return this.settled.get(txId) ?? null;
  }

  hasNonce(nonce) {
    return this.nonces.has(nonce);
  }

  recordRejection(txId, code, reason) {
    this.rejections.push({ txId, code, reason, at: new Date().toISOString() });
    if (this.rejections.length > 500) this.rejections.shift();
  }

  /**
   * Move the money. Callers must have verified the packet first.
   * Returns a receipt; throws if the payer cannot cover it.
   */
  settle(payload, hops) {
    const payer = this.accounts.get(payload.payerId);
    if (!payer) {
      const err = new Error(`unknown payer account ${payload.payerId}`);
      err.code = 'UNKNOWN_ACCOUNT';
      throw err;
    }
    if (payer.balancePaise < payload.amountPaise) {
      const err = new Error(
        `payer balance ${payer.balancePaise} is short of ${payload.amountPaise}`);
      err.code = 'INSUFFICIENT_FUNDS';
      throw err;
    }
    const payee = this.openAccount(payload.payeeId, payload.payeeId, 0);

    payer.balancePaise -= payload.amountPaise;
    payee.balancePaise += payload.amountPaise;

    const receipt = {
      txId: payload.txId,
      amountPaise: payload.amountPaise,
      payerId: payload.payerId,
      payeeId: payload.payeeId,
      createdAt: payload.createdAt,
      settledAt: Date.now(),
      hops: hops ?? [],
      payerBalancePaise: payer.balancePaise,
      payeeBalancePaise: payee.balancePaise,
    };

    this.settled.set(payload.txId, receipt);
    this.nonces.add(payload.nonce);
    this.persist();
    return receipt;
  }

  /** Receipts involving an account, newest first. */
  history(id, limit = 20) {
    return [...this.settled.values()]
      .filter((r) => r.payerId === id || r.payeeId === id)
      .slice(-limit)
      .reverse();
  }

  snapshot() {
    const receipts = [...this.settled.values()];
    return {
      accounts: [...this.accounts.values()],
      settledCount: receipts.length,
      totalSettledPaise: receipts.reduce((sum, r) => sum + r.amountPaise, 0),
      recentSettlements: receipts.slice(-20).reverse(),
      rejectedCount: this.rejections.length,
      recentRejections: this.rejections.slice(-10).reverse(),
    };
  }
}
