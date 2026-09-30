# What's left

As of 30 September 2026 (commit `51e99e9`, CI passing). What is built and
tested is in the README under [What actually works](README.md#what-actually-works);
this file is everything that isn't.

## 1. Run the mesh on real phones — blocks everything else

Every link in the chain has been exercised except the one the project is
about: **a packet crossing from one phone to another over Bluetooth.** Routing
is simulated on the JVM, and signing, settlement and receipts have run on an
emulator against the real bank, but no two phones have talked yet.

- [ ] Run the three-phone demo in [`android/README.md`](android/README.md#demo-three-phones-one-laptop):
      A pays (Force offline), B relays (Force offline), C bridges on the laptop's Wi-Fi.
- [ ] Confirm the packet goes A → B → C and shows on the bank dashboard with that route.
- [ ] Confirm A flips to *Settled* with the bank's signed receipt **while still offline**.
- [ ] **Find nearby** → pay B directly: B shows it under *Received*, then *✓ Confirmed* exactly once.
- [ ] If there is an Android 10 or 11 phone, try it too, with Location on. It can
      only hand packets on while the app is open.

Each phone must support BLE advertising (peripheral mode). A cheaper first
check is two emulators linked by the emulator's virtual Bluetooth. The laptop
now has disk space for a second instance. That shows the GATT exchange works
end to end, but says nothing about range or real radios.

## 2. Make the site and deck match what's built

Do this once step 1 passes.

**Site, `index.html` → *Built with*:**

- [ ] Remove the *Planned implementation* label.
- [ ] Fix chips that describe things that don't exist. Either build them or reword:

| Chip | Says | Actually |
| --- | --- | --- |
| Security › Encryption | Payload sealed end to end | Packets are **signed, not encrypted**. Relays can read the amount and payee. |
| Intelligence › On-device relay selection | A breadth-first search picks the nearest phone that can reach the internet | Each packet goes to **every** neighbour once, and the first copy to reach a bridge wins |
| Intelligence › Connectivity prediction | Learns when a phone tends to regain signal | Not built |
| Intelligence › Local ML model | On-device scoring of neighbours | Not built |
| Backend › REST API | Hands the packet up over HTTPS | The mock bank is plain HTTP on the local network |
| Backend › Payment gateway | The existing rails that move the funds | An in-memory ledger; no gateway |

**Deck, `scripts/generate_deck.py` slide 5:**

- [ ] The slide claims deep integration with the iQOO flagship and Snapdragon NPU,
      and scores itself against the *On-Device AI* rubric. Nothing in the app uses
      the NPU or any model. Decide: build a small on-device model (for example,
      scoring which neighbour to hand to first), or drop the claim.

## 3. Housekeeping

- [ ] `src/` (`App.jsx`, `main.jsx`, React and Vite logos) is the leftover Vite
      React template. `index.html` doesn't load it. Delete it, drop React,
      Tailwind and their Vite plugins, and check `npm run build` still produces the site.
- [ ] The prototype in `public/demo/` is built from a sibling folder
      (`../bouncepay`) that isn't in any repo here, so only this laptop can
      rebuild it. Push it to GitHub and point the README at it.

## Known and deliberately out of scope

These are fine for a hackathon demo but would have to change before real money moves:

- The mock bank keeps its ledger in memory and serves plain HTTP with no authentication.
- `/v1/enroll` lets a phone choose its own opening balance.
- The on-device fallback bank settles locally with no signed receipt; a real
  bank would later refuse those payments.
