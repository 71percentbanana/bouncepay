# BouncePay for Android

The phone side of BouncePay: sign a payment with no internet, and let it hop
over Bluetooth Low Energy through nearby phones until one of them can reach
the bank.

Every phone runs the same code and plays whichever part its situation calls
for. Nothing is configured as a "relay" or a "bridge".

| The phone can… | So it… |
| --- | --- |
| reach the bank | is a **bridge**: settles everything it holds |
| not reach the bank | is a **relay**: hands packets to nearby phones, carries others' |
| do nothing yet | advertises, so others can hand it packets |

## Build

Requires JDK 17 and the Android SDK (platform 36). Gradle is fetched by the
wrapper, pinned to a verified checksum.

```bash
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # also writes the packet the bank's interop test verifies
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open this folder in Android Studio.

Android 8.0+ (API 26). The phone needs BLE peripheral mode (advertising); most
phones from the last several years have it. Test the mesh on real phones — the
emulator's virtual Bluetooth says nothing about range or radio behaviour.

## Demo: three phones, one laptop

1. On the laptop: `cd ../mock-bank && npm start`. Open
   `http://localhost:4000` for the dashboard and note the LAN URL it prints.
2. Install the app on three phones and grant Bluetooth (and notifications).
3. On **each** phone, while it is online: **Settings → Bank URL** → the LAN
   URL, then **Load** on the wallet card. This opens the phone's account at
   the bank and loads ₹2,000 of offline spending.
4. Place them in a line, each within Bluetooth range of the next only:
   - **A — payer**: Settings → **Force offline** on
   - **B — relay**: Force offline on
   - **C — bridge**: Force offline off, on the laptop's Wi-Fi
5. On A, tap **Pay**. Watch the packet go A → B → C and land on the
   dashboard with its route.

*Force offline* is more dependable than airplane mode, which switches
Bluetooth off on many phones.

Without a laptop, a bridge with *On-device fallback bank* enabled settles
locally and labels it so. The wallet can then be loaded on-device too — a real
bank would refuse those later, which is correct.

## How it works

```
Pay  ─►  Packet.create  ─►  PacketStore  ─►  MeshNode loop (every 6 s, or on demand)
          (sign in Keystore)  (on disk)        ├─ bank reachable?  → RemoteBank.settle
                                               ├─ network, no bank → EmbeddedBank (if allowed)
                                               └─ otherwise        → MeshCentral: scan, connect, write
                                          MeshPeripheral: advertise, accept writes ─► PacketStore
```

| File | Role |
| --- | --- |
| `crypto/DeviceKey.kt` | P-256 key in Android Keystore; never leaves the device. Account id = `bp_` + first 8 bytes of SHA-256(public key) |
| `model/Packet.kt` | Signed payload (opaque string, never re-serialised) + unsigned hop list |
| `store/PacketStore.kt` | Store-and-forward queue, written atomically to disk on every change |
| `store/AppPrefs.kt` | Bank URL, force-offline, fallback, offline wallet |
| `ble/BleIds.kt` | Service/characteristic UUIDs and chunk framing |
| `ble/MeshPeripheral.kt` | GATT server + advertiser: receives packets |
| `ble/MeshCentral.kt` | Scanner + GATT client: sends packets, one awaited operation at a time |
| `bank/Bank.kt` | `RemoteBank` (the mock bank over HTTP) and `EmbeddedBank` (same rules, on-device) |
| `mesh/MeshNode.kt` | The loop: decides bridge or relay every cycle, settles or forwards |
| `mesh/MeshService.kt` | Foreground service so relaying continues with the screen off |

### Over the air

Each phone advertises service `b0cce000-9a11-4e6d-9f2c-000000000001` and
exposes two characteristics:

- `…0002` **write** — the packet, split into chunks framed as
  `[index u16][count u16][bytes]`, since a packet (~800 bytes) exceeds even a
  517-byte MTU.
- `…0003` **read** — the phone's account id. A sender reads it first and
  skips any phone already in the packet's hop list, so a packet never
  ping-pongs between two phones in range of each other.

A hand-off records the receiving phone in the hop list, so each packet goes
to each neighbour once. The sender keeps its copy until it sees the bank's
receipt — handing a packet on is not proof it arrived. If several copies
reach the bank by different routes, the bank settles one and answers the
rest with the same receipt.

### Guard rails on the phone

- **Offline wallet**: the app will not sign beyond what was loaded while
  online. When the phone later sees the bank refuse one of its payments (it
  resubmits its own packets once it is a bridge), the amount is returned.
- **₹2,000 ceiling** per packet, mirrored by the bank.
- **Scan throttle**: Android silently ignores apps that start more than five
  scans in 30 s, so scans are spaced at least 6.5 s apart.

## Status

Built and unit-tested (chunking, UUIDs, the packet envelope, and a JVM-signed
packet that the Node bank verifies byte-for-byte). The Bluetooth path has not
yet been exercised on physical phones; that needs two or more handsets.
