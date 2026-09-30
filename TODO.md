# What's left

As of 30 September 2026 (commit `51e99e9`, CI passing). What is built and
tested is in the README under [What actually works](README.md#what-actually-works);
this file is everything that isn't.

## 1. Run the mesh on real phones — blocks everything else

The GATT exchange itself now works. On 1 October 2026 the app on an Android 16
emulator traded packets and receipts over the emulator's virtual Bluetooth
with [`mock-phone/`](mock-phone/), a second phone written against Bumble. That
covered *Find nearby*, paying while forced offline and getting the bank's
receipt back, being paid by a phone and credited once, and MTU 23. It also
found two framing bugs. One of them made Android refuse **every** packet
hand-off at the MTU real phones negotiate. Both are fixed in `Chunking` and
pinned by `ChunkingTest`.

The same day, **app to app** worked too: two emulators on the discrete GPU
(`-gpu host`, the second with `-memory 1536`). A, forced offline, paid ₹250;
B, the real app online as a bridge, took the packet over Bluetooth. The bank
settled it with route A → B, and B's receipt reached A while A was still
offline. Two emulators only just fit in 13 GB next to a desktop session:
memory ran short a minute later. So use the mock phone for routine checks.

What is still untested is everything a virtual radio hides: range, real
radios, two real Android stacks talking, and a relay in a pocket with the
screen off.

- [ ] Run the three-phone demo in [`android/README.md`](android/README.md#demo-three-phones-one-laptop):
      A pays (Force offline), B relays (Force offline), C bridges on the laptop's Wi-Fi.
- [ ] Confirm the packet goes A → B → C and shows on the bank dashboard with that route.
- [ ] Confirm A flips to *Settled* with the bank's signed receipt **while still offline**.
- [ ] **Find nearby** → pay B directly: B shows it under *Received*, then *✓ Confirmed* exactly once.
- [ ] If there is an Android 10 or 11 phone, try it too, with Location on. It can
      only hand packets on while the app is open.

Each phone must support BLE advertising (peripheral mode). Before a phone
session, rerun the emulator check in [`mock-phone/README.md`](mock-phone/README.md)
after any change to the BLE code.

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
- [ ] `scripts/build-demo.mjs` looks for the prototype at `../bouncepay`. A
      default `git clone` of this repo is itself named `bouncepay`, so on any
      machine but the one with the `bpay/` folder that path is **this repo**.
      `npm run build:demo` would then build the site into `public/demo/` with
      `--emptyOutDir`, wiping the committed prototype. Refuse when the path
      resolves to the repo itself, or take the path from an environment variable.

## Known and deliberately out of scope

These are fine for a hackathon demo but would have to change before real money moves:

- The mock bank keeps its ledger in memory and serves plain HTTP with no authentication.
- `/v1/enroll` lets a phone choose its own opening balance.
- The on-device fallback bank settles locally with no signed receipt; a real
  bank would later refuse those payments.
