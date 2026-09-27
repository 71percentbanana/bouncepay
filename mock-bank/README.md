# BouncePay mock bank

The settlement service a bridge phone posts packets to. It verifies each
packet the way a real bank would — signature, key ownership, freshness,
limits, replay — then moves money on an in-memory ledger.

Zero dependencies: Node's own `http` and `crypto`. Needs Node 20+.

```bash
npm start            # listens on :4000, prints the LAN URL for the phones
npm test             # rules, HTTP surface, and Android ↔ Node interop
```

Open **http://localhost:4000** for the live dashboard: settlements as they
land (with the route each packet took), balances, and refused packets.

```
  BouncePay mock bank listening on :4000
  Dashboard                  http://localhost:4000
  Point the app's bridge at  http://192.168.1.23:4000
```

Type the last URL into each phone's **Settings → Bank URL**. Phones and laptop
must be on the same Wi-Fi or hotspot; the internet itself is not needed.

## Settlement rules

Checked in this order, so identity and integrity are settled before anything
that could move money or reveal whether an account exists:

| Check | Refusal code |
| --- | --- |
| Payload parses, has every field, version is 1 | `MALFORMED`, `UNSUPPORTED_VERSION` |
| 0 < amount ≤ ₹2,000 | `INVALID_AMOUNT`, `AMOUNT_EXCEEDS_LIMIT` |
| Public key is P-256 SPKI | `BAD_PUBLIC_KEY` |
| ECDSA signature over the exact payload bytes | `BAD_SIGNATURE` |
| `payerId` is the fingerprint of the signing key | `KEY_MISMATCH` |
| Created ≤ 7 days ago, ≤ 5 min in the future | `EXPIRED`, `FUTURE_DATED` |
| Same `txId` seen before | *not refused* — returns the original receipt as `DUPLICATE` |
| Same nonce under a different `txId` | `REPLAYED_NONCE` |
| Payer account exists and can cover it | `UNKNOWN_ACCOUNT`, `INSUFFICIENT_FUNDS` |

A duplicate is answered rather than refused because it is normal traffic:
relays forward opportunistically and none of them knows another already got
through. The merchant sees one settlement either way.

## API

| Method | Path | |
| --- | --- | --- |
| `GET` | `/` | Dashboard |
| `GET` | `/v1/health` | Liveness, plus the LAN URLs to give the phones |
| `GET` | `/v1/ledger` | Accounts, recent settlements and refusals |
| `GET` | `/v1/account?id=` | One account and its history |
| `GET` | `/v1/receipt?txId=` | Receipt for a settled packet |
| `POST` | `/v1/enroll` | `{payerPubKey, label?, openingPaise?}` → opens an account (idempotent; never resets a balance) |
| `POST` | `/v1/settle` | A packet → `{status: SETTLED \| DUPLICATE, receipt}`, or `422 {code, message}` |

The merchant `campus-stationery` is opened on start with a ₹2,000 float.

## Packet format

```json
{
  "payload": "{\"v\":1,\"txId\":\"…\",\"nonce\":\"…\",\"amountPaise\":10000,\"payerId\":\"bp_…\",\"payeeId\":\"campus-stationery\",\"createdAt\":1790000000000}",
  "sig": "base64 DER ECDSA signature over the payload string",
  "payerPubKey": "base64 DER SubjectPublicKeyInfo",
  "hops": ["bp_payer", "bp_relay", "bp_bridge"]
}
```

`payload` is an opaque string. It is signed, carried and verified as those
exact bytes and never re-serialised — two platforms will not produce identical
JSON from the same object. `hops` sits outside the signature so relays can
append themselves; it is provenance, not authorisation.

An account id is `bp_` + the first 8 bytes of SHA-256 over the DER public key,
in hex — computed identically here and on the phone.

## Keeping state across restarts

```bash
BANK_SNAPSHOT=bank-snapshot.json npm start
```

Without it the ledger starts fresh on every run, which is usually what a demo
wants.
