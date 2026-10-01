# BouncePay mock phone

A second BouncePay phone written in Python, so the app's Bluetooth can be
tested on a laptop with **one** Android emulator.

Every Android emulator on a laptop joins the same virtual Bluetooth radio,
called netsim, and every device on it is in range of every other. The mock
phone joins that radio too, using [Bumble](https://github.com/google/bumble),
Google's Bluetooth stack in Python. It speaks the app's GATT protocol from its
own implementation: the chunk framing, the profile read, packets and receipt
batches. So the app's real `MeshCentral` and `MeshPeripheral` run against a
peer that shares none of their code.

```
  emulator (the app)  ── virtual Bluetooth (netsim) ──  mock phone (phone.py)
          │                                                     │
          └── http://10.0.2.2:4000 ──►  mock bank  ◄── http://localhost:4000
```

The emulator reaches the laptop at `10.0.2.2`. The mock phone runs on the
laptop itself, so it uses `localhost`.

It found the two framing bugs fixed in `Chunking`: frames too big for one ATT
write at MTU 23, and past GATT's 512-byte cap at MTU 517, where Android
refused every hand-off.

## One-time setup

```bash
# The mock phone's Python environment
cd mock-phone
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt

# Tell Gradle where the SDK is, and put the emulator on PATH (it isn't by
# default). Add these two lines to ~/.bashrc so every terminal has them.
export ANDROID_HOME=~/Android/Sdk
export PATH="$PATH:$ANDROID_HOME/emulator"

emulator -list-avds                        # e.g. Medium_Phone_API_36.1
```

Always start the emulator with **`-gpu host`**. On some Linux machines the
default software renderer crashes the emulator about 30 seconds into boot.

To render on a discrete NVIDIA GPU, start it from a terminal that uses that
GPU, or prefix the command with
`__NV_PRIME_RENDER_OFFLOAD=1 __GLX_VENDOR_LIBRARY_NAME=nvidia`. It helps less
than it sounds: graphics are only about 250 MB of an emulator's memory. The
emulated phone's 2 GB of RAM always stays in the laptop's RAM.

## Test 1: one emulator and the mock phone

The routine check. Rerun it after any change to the Bluetooth code. Each
command below gets its own terminal, started from the repo root.

**1. The bank.** Leave it running; the dashboard is at http://localhost:4000.

```bash
cd mock-bank && npm start
```

**2. The emulator and the app.** Wait for the home screen, then install:

```bash
emulator -avd Medium_Phone_API_36.1 -gpu host
cd android && ./gradlew installDebug       # in another terminal, once it has booted
```

In the emulator, open BouncePay and allow Bluetooth and notifications. Then:

- **Settings** (gear icon) → **Bank URL** → `http://10.0.2.2:4000` → **Save URL**.
- In the same sheet, turn **On-device fallback bank** off. Otherwise a wrong
  URL settles payments on the phone instead of failing where you can see it.
- Close the sheet. The status should read *Bridge · connected to the bank*.
- Tap **Load**. The wallet shows ₹2,000.

**3. The mock phone**, as a bridge:

```bash
cd mock-phone && .venv/bin/python phone.py bridge
```

Now, in the app:

| Do | Expect |
| --- | --- |
| **Find nearby** | *Mock phone* appears next to Campus Stationery |
| **Settings → Force offline** on, then **Pay ₹100** | The mock phone prints `⇐ packet … signature verifies`, `✓ bank: SETTLED`, then `⇒ 1 receipt(s)`. The dashboard shows the payment and its route. In the app it turns **Settled · ✓ Signed receipt from the bank**, while still offline |

To test **being paid**, stop the bridge with Ctrl-C. With the app still on
*Force offline*, run the mock phone as a payer. The account id to pay is shown
under *BouncePay* at the top of the app.

```bash
.venv/bin/python phone.py pay 150 --to bp_…
```

The app shows ₹150 under **Received · Waiting for the bank**. Turn *Force
offline* off: it settles, the wallet rises by ₹150 exactly once, and the mock
phone prints `✓ receipt came back`.

To test **tiny chunks**, as when a phone won't negotiate a bigger MTU, run the
bridge with `.venv/bin/python phone.py --mtu 23 bridge`.

## Test 2: two emulators, app to app

The real app on both ends. It needs about **6 GB of free memory**: each
emulator takes roughly 2.9 GB. Close browsers first, and keep `free -h`
running in a terminal. If *available* falls under about 1 GB, close an
emulator before the desktop starts swapping.

**1. Make a second AVD.** In Android Studio: **Device Manager → + → Create
Virtual Device → Medium Phone**, with the same Android 16 image. Give it a
name such as `Medium_Phone_B`.

Use two different AVDs, not two copies of one. Two copies of one AVD
(`-read-only`) share its saved state. If BouncePay was ever installed on it,
both copies have the same Keystore key, and each phone takes the other for
itself.

**2. Start both**, the second with less memory, then install on both (with
two running, `installDebug` installs on each):

```bash
emulator -avd Medium_Phone_API_36.1 -gpu host
emulator -avd Medium_Phone_B -gpu host -memory 1536
cd android && ./gradlew installDebug
```

**3. Set up each** as in Test 1: Bank URL, fallback off, **Load**. Give each
a different **Your name nearby** in Settings (then **Save name**), so you can
tell them apart.

**4. Run the demo:**

| Do | Expect |
| --- | --- |
| Phone A: **Force offline** on. Phone B: leave online (it is the bridge) | A reads *Relay · forced offline*, B reads *Bridge* |
| A: **Pay ₹250** | The dashboard shows the payment with route A → B. A turns **Settled** with the bank's signed receipt, while still offline |
| A: **Find nearby**, pick B, **Pay** | B shows it under **Received**, then **✓ Confirmed**, credited once |

Once its wallet is loaded, A never talks to the bank. Everything it sends and
receives goes through B, over Bluetooth.

## If something goes wrong

| Symptom | Cause |
| --- | --- |
| `No emulator Bluetooth to join` | No emulator is running. netsim starts with the first emulator, so start one before the mock phone |
| `No bank at http://localhost:4000` | The mock bank is not running (`cd mock-bank && npm start`) |
| The emulator exits about 30 s into boot | The software renderer crashed. Add `-gpu host` |
| The app says *Bridge · fallback* | It has a network but cannot reach the bank. The URL must be `http://10.0.2.2:4000` inside the emulator, not `localhost` or the laptop's LAN address |
| *Find nearby* finds nothing | The mock phone (or the second emulator's app) is not running, or Bluetooth is off in the emulator. The app shows a notice for that |
| Two emulators connect but never hand anything over | They share one key; see Test 2, step 1 |
| `Load` fails | The bank is unreachable. Check the URL, and that *Force offline* is off while loading |

## Scripted runs

The checks below were run headless, by script, which you don't need when you
can see the emulator. The emulator ran with `-no-window -no-audio`. Taps were
`adb shell input tap X Y`, with coordinates found in
`adb shell uiautomator dump`. Settings were written directly to the app's
preferences file with `adb shell run-as com.bouncepay`, which debug builds
allow.

## What was checked with it (Android 16 emulator, 1 October 2026)

| Scenario | Result |
| --- | --- |
| **Find nearby** on the emulator | Scans by service UUID, connects, negotiates MTU 517, reads the profile; the mock phone appears as a payee |
| Emulator on *Force offline* pays ₹100; mock phone bridges | Packet written in 2 frames, signature and key binding verified by Python, settled with route emulator → bridge; the bank's receipt written back; emulator shows *Settled* with the signed receipt **while still offline** |
| Mock phone pays the emulator ₹250 while it is offline | Emulator shows it under *Received · Waiting for the bank* and holds it (the payer is already in the route, so it does not bounce back); once online it settles, credits the wallet once, and the receipt reaches the payer over Bluetooth |
| Bridge limited to MTU 23 | The packet crosses and the receipt returns in 22 single writes |
| Two emulators, app to app | A, forced offline, paid ₹250; B took it over Bluetooth and settled it with route A → B; B's receipt reached A while A was still offline |

What this cannot tell you: range, real radios, Android's background limits on
a phone in a pocket, and stacks from other vendors. That still needs phones.
