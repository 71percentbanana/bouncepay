# BouncePay

**Payments that bounce until they connect.**

A payment created on a phone with no internet hops over Bluetooth Low Energy
through nearby devices until it reaches one that *is* online, which carries it
to settlement. Neither the buyer nor the merchant needs connectivity — only
someone in the chain does.

Built for iQOO City Battles 2026 · Track 01: Fintech & Commerce.

---

## What's in here

This repo is the **marketing site**, which embeds the interactive prototype.

| Path | What it is |
| --- | --- |
| `index.html` | The whole site — hand-written HTML/CSS/JS, no framework |
| `public/demo/` | Build output of the prototype, served at `/demo/` |
| `scripts/build-demo.mjs` | Builds the prototype and applies the site's theme |
| `scripts/demo-theme.css` | Dark Material You overlay for the prototype |
| `scripts/generate_deck.py` | Generates the pitch deck |
| `public/images/` | Artwork used by the site |
| `assets/deck/` | Artwork used only by the deck (not deployed) |

The site is a single self-contained HTML file. Vite is used only to serve and
copy `public/` — there is no bundling step for the page itself.

## Running it

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

The prototype demonstrates the **routing model** end to end: store-and-forward,
opportunistic relay selection, and the full seven-step settlement sequence.

The radio, the cryptography and the backend are **designed but not built** —
BLE, signatures, replay protection and the settlement API are specified in the
site's *Built with* section and labelled as planned. Nothing on the site claims
them as implemented.

## Team

Alan James · Anuroop Phukan · Ishaan Sridharan
