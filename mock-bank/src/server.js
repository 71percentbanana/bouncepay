/**
 * BouncePay mock bank — settlement service.
 *
 * Zero dependencies: node:http and node:crypto only, so it starts with
 * `node src/server.js` and needs no install step on demo day.
 *
 * A bridge phone — any device in the mesh that happens to have internet —
 * POSTs carried packets here. It binds on 0.0.0.0 so a handset on the same
 * Wi-Fi can reach it.
 */

import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { networkInterfaces } from 'node:os';
import { Ledger, fingerprint } from './ledger.js';
import { settlePacket } from './settle.js';
import { RejectedError } from './packet.js';

const PORT = Number(process.env.PORT ?? 4000);
const SNAPSHOT = process.env.BANK_SNAPSHOT ?? null;
const DASHBOARD = readFileSync(new URL('./dashboard.html', import.meta.url));

/** Best-effort txId for the audit trail, from a packet that may be malformed. */
function txIdOf(packet) {
  try { return JSON.parse(packet.payload).txId ?? '-'; } catch { return '-'; }
}

export function createBank({ snapshotPath = SNAPSHOT } = {}) {
  const ledger = new Ledger({ snapshotPath });

  // Merchant float, so a receiving account exists before the first payment.
  ledger.openAccount('campus-stationery', 'Campus Stationery', 2000_00);

  const json = (res, code, body) => {
    const text = JSON.stringify(body, null, 2);
    res.writeHead(code, {
      'content-type': 'application/json; charset=utf-8',
      'content-length': Buffer.byteLength(text),
      'access-control-allow-origin': '*',
      'access-control-allow-headers': 'content-type',
      'access-control-allow-methods': 'GET,POST,OPTIONS',
    });
    res.end(text);
  };

  const readBody = (req) => new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > 64 * 1024) {
        reject(new RejectedError('MALFORMED', 'request body too large'));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });

  const server = createServer(async (req, res) => {
    const url = new URL(req.url, `http://${req.headers.host ?? 'localhost'}`);

    if (req.method === 'OPTIONS') return json(res, 204, {});

    try {
      // The live view for the demo: open http://localhost:4000 on the laptop.
      if (req.method === 'GET' && (url.pathname === '/' || url.pathname === '/index.html')) {
        res.writeHead(200, {
          'content-type': 'text/html; charset=utf-8',
          'content-length': DASHBOARD.length,
          'cache-control': 'no-store',
        });
        return res.end(DASHBOARD);
      }

      if (req.method === 'GET' && url.pathname === '/v1/health') {
        return json(res, 200, {
          ok: true, service: 'bouncepay-mock-bank', now: Date.now(),
          // Lets the dashboard tell you which URL to type into the phones.
          lan: lanAddresses().map((ip) => `http://${ip}:${req.socket.localPort}`),
        });
      }

      if (req.method === 'GET' && url.pathname === '/v1/ledger') {
        return json(res, 200, ledger.snapshot());
      }

      if (req.method === 'GET' && url.pathname === '/v1/account') {
        const id = url.searchParams.get('id') ?? '';
        const account = ledger.account(id);
        return account
          ? json(res, 200, { account, history: ledger.history(id) })
          : json(res, 404, { code: 'UNKNOWN_ACCOUNT', message: `no account ${id}` });
      }

      if (req.method === 'GET' && url.pathname === '/v1/receipt') {
        const txId = url.searchParams.get('txId') ?? '';
        const receipt = ledger.existingReceipt(txId);
        return receipt
          ? json(res, 200, { status: 'SETTLED', receipt })
          : json(res, 404, { status: 'UNKNOWN', txId });
      }

      // Register a device so its payments have an account to draw on. In a
      // real system this is onboarding; here it hands the phone its offline
      // quota so a demo can start from nothing.
      if (req.method === 'POST' && url.pathname === '/v1/enroll') {
        const { payerPubKey, label, openingPaise } = JSON.parse(await readBody(req));
        if (typeof payerPubKey !== 'string') {
          return json(res, 400, { code: 'MALFORMED', message: 'payerPubKey required' });
        }
        const id = fingerprint(payerPubKey);
        const account = ledger.openAccount(id, label ?? id, openingPaise ?? 2000_00);
        ledger.persist();
        return json(res, 200, { accountId: id, account });
      }

      if (req.method === 'POST' && url.pathname === '/v1/settle') {
        const packet = JSON.parse(await readBody(req));
        let result;
        try {
          result = settlePacket(ledger, packet);
        } catch (err) {
          if (err instanceof RejectedError) err.txId = txIdOf(packet);
          throw err;
        }
        const { status, receipt } = result;
        // hops[0] is the payer; every entry after it is one phone-to-phone transfer.
        const transfers = Math.max((packet.hops?.length ?? 1) - 1, 0);
        console.log(`  ${status.padEnd(9)} ${receipt.txId}  ₹${(receipt.amountPaise / 100).toFixed(2)}  via ${transfers} hop(s)`);
        return json(res, 200, { status, receipt });
      }

      return json(res, 404, { code: 'NOT_FOUND', message: `no route for ${req.method} ${url.pathname}` });
    } catch (err) {
      if (err instanceof RejectedError) {
        ledger.recordRejection(err.txId ?? '-', err.code, err.message);
        ledger.persist();
        console.log(`  REJECTED  ${err.code}: ${err.message}`);
        return json(res, 422, { code: err.code, message: err.message });
      }
      if (err instanceof SyntaxError) {
        return json(res, 400, { code: 'MALFORMED', message: 'body is not valid JSON' });
      }
      console.error(err);
      return json(res, 500, { code: 'INTERNAL', message: 'unexpected error' });
    }
  });

  return { server, ledger };
}

function lanAddresses() {
  return Object.values(networkInterfaces()).flat()
    .filter((n) => n && n.family === 'IPv4' && !n.internal)
    .map((n) => n.address);
}

// Only start listening when run directly, so tests can import the app.
if (process.argv[1] && import.meta.url.endsWith(process.argv[1].replace(/\\/g, '/').split('/').pop())) {
  const { server } = createBank();
  server.listen(PORT, '0.0.0.0', () => {
    console.log(`\n  BouncePay mock bank listening on :${PORT}`);
    console.log(`  Dashboard                  http://localhost:${PORT}`);
    for (const ip of lanAddresses()) {
      console.log(`  Point the app's bridge at  http://${ip}:${PORT}`);
    }
    console.log('');
  });
}
