# BouncePay

[![CI](https://github.com/71percentbanana/bouncepay/actions/workflows/ci.yml/badge.svg)](https://github.com/71percentbanana/bouncepay/actions/workflows/ci.yml)

**Payments that bounce until they connect.**

A payment created on a phone with no internet hops over Bluetooth Low Energy
through nearby devices until it reaches one that *is* online, which carries it
to settlement. Neither the buyer nor the merchant needs connectivity — only
someone in the chain does.

Built for iQOO City Battles 2026 · Track 01: Fintech & Commerce.

---

## What's in here

| Path | What it is |
| --- | --- |
| [`android/`](android/) | The Android app — real BLE mesh, Keystore signing, store-and-forward |
| [`mock-bank/`](mock-bank/) | The settlement service the bridge phone posts to, with a live dashboard |
| `index.html` | The marketing site — hand-written HTML/CSS/JS, no framework |
| `public/demo/` | Build output of the prototype, served at `/demo/` |
| `scripts/build-demo.mjs` | Builds the prototype and applies the site's theme |
| `scripts/demo-theme.css` | Dark Material You overlay for the prototype |
| `scripts/generate_deck.py` | Generates the pitch deck |
| `public/images/` | Artwork used by the site |
| `assets/deck/` | Artwork used only by the deck (not deployed) |

The site is a single self-contained HTML file. Vite is used only to serve and
copy `public/` — there is no bundling step for the page itself.

## The app and the bank

```bash
cd mock-bank && npm start                 # settlement service + dashboard on :4000
cd android && ./gradlew assembleDebug     # APK for the phones
```

[`android/README.md`](android/README.md) walks through a three-phone demo:
payer and relay forced offline, a bridge on the laptop's Wi-Fi, and the
payment appearing on the bank's dashboard with the route it took.

The two halves are written in different languages but must agree on every
byte that is signed. `./gradlew testDebugUnitTest` signs a packet exactly as
the phone does and writes it out; `npm test` in `mock-bank/` then verifies
that same file with Node's independent crypto.

## Running the site

```bash
npm install
npm run dev          # http://localhost:3000
npm run build        # → dist/
```

## The prototype

The interactive demo in the **Demo** section is a real app, not a
reimplementation. It lives in a **sibling repo** and is built into this one:

```
Desktop/
├── bouncepay/     ← the prototype (separate project)
└── bpay/          ← this repo
```

```bash
npm run build:demo   # rebuilds public/demo/ from ../bouncepay
```

That script does three things beyond a plain build:

1. Builds with a relative base so it can be served from `/demo/`.
2. Links `scripts/demo-theme.css`, which re-points the prototype's
   `--md-sys-color-*` tokens at the site's dark palette. Both are Material You
   seeded from `#6750A4`, so this is a token swap rather than a restyle.
3. Applies the post-build polish: numbers the route nodes instead of emoji,
   removes the internal POS device id, de-brands the handset, hides the
   duplicate step ticker, and injects the packet-absorption script.

> **Do not hand-edit `public/demo/`.** It is build output — `--emptyOutDir`
> wipes it on every build. Anything that needs to survive belongs in
> `build-demo.mjs` or `demo-theme.css`.

## The deck

```bash
npm run build:deck   # → BouncePay_iQOO_Final_Deck.pptx
```

Requires `python-pptx`. Generated decks are gitignored — rebuild rather than
commit them.

## What actually works

| | Status |
| --- | --- |
| Signing: ECDSA P-256 in Android Keystore, payer bound to key | Built; Keystore-signed payments from an Android 16 emulator settle at the Node bank, and the wire format is also checked byte-for-byte in CI |
| Settlement: signature, key binding, freshness, limits, replay, idempotent duplicates | Built and tested (24 bank tests) |
| CI | Every push builds the APK and runs both suites on GitHub Actions; the APK is kept as a build artifact |
| Store-and-forward queue that survives restarts | Built |
| Bank-signed receipts passed back through the mesh to an offline payer | Built; verified across Node and Kotlin with a shared fixture |
| Paying any phone in range, not just a merchant; payee credited on the bank's receipt | Built; simulated in unit tests |
| Mesh routing: forwarding, loop avoidance, dedup, receipt gossip | Built; simulated across chains, diamonds and a 4×4 grid of phones in unit tests |
| BLE transport: advertise, scan, GATT transfer with chunking | Built; GATT server, advertising and scanning start on the emulator. **Phone-to-phone transfer not yet tried on physical phones** |
| Routing model, end to end, in the browser | The interactive prototype on the site |

The site's *Built with* section still labels these layers as planned; update
it once the BLE path has been run on real handsets. Everything still to do is
in [`TODO.md`](TODO.md).

## Team

Alan James · Anuroop Phukan · Ishaan Sridharan
