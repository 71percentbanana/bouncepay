#!/usr/bin/env python3
"""
A BouncePay phone written in Python, for testing the app's Bluetooth with one
emulator instead of two.

Two Android emulators need more memory than most laptops have spare. This
plays the second phone. Bumble, Google's Bluetooth stack in Python, joins the
emulator's virtual radio (netsim) as another device and speaks the app's GATT
protocol from its own implementation: the chunk framing, the profile read,
packets and receipt batches. The app's real MeshCentral and MeshPeripheral run
against a peer that shares none of their code, so a disagreement about the
protocol shows up here rather than between two phones in front of a judge.

    python phone.py bridge                    # settle what phones hand over, pass receipts back
    python phone.py pay 150 --to bp_…         # pay that phone, hand the packet to it

Needs an emulator running (netsim starts with it) and the mock bank. Every
field, UUID and byte order here is copied from the Kotlin sources on purpose;
change them there first.
"""

import argparse
import asyncio
import base64
import hashlib
import json
import secrets
import sys
import time
import urllib.error
import urllib.request
import uuid

from bumble.core import UUID, AdvertisingData
from bumble.device import Advertisement, Connection, Device, Peer
from bumble.gatt import Characteristic, CharacteristicValue, Service
from bumble.hci import Address
from bumble.transport import open_transport
from bumble.transport.common import TransportInitError
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

# ble/BleIds.kt
SERVICE = UUID('b0cce000-9a11-4e6d-9f2c-000000000001')
PACKET_IN = UUID('b0cce000-9a11-4e6d-9f2c-000000000002')
PROFILE = UUID('b0cce000-9a11-4e6d-9f2c-000000000003')
RECEIPT_IN = UUID('b0cce000-9a11-4e6d-9f2c-000000000004')

HEADER = 4  # [index u16 BE][count u16 BE]
MIN_MTU = 23
MAX_ATTRIBUTE_BYTES = 512  # GATT's cap on one attribute value, whatever the MTU

# mesh/ReceiptBook.kt: how long a bridge keeps offering a receipt.
RECEIPT_TTL_S = 10 * 60


def log(message: str) -> None:
    print(time.strftime('%H:%M:%S'), message, flush=True)


# ---- Chunking (ble/BleIds.kt) -------------------------------------------------

def split(data: bytes, mtu: int) -> list[bytes]:
    """Chunking.split: frames that each fit one ATT write, and GATT's 512-byte cap."""
    per_chunk = min(max(mtu, MIN_MTU) - 3, MAX_ATTRIBUTE_BYTES) - HEADER
    count = max(-(-len(data) // per_chunk), 1)
    return [
        i.to_bytes(2, 'big') + count.to_bytes(2, 'big') + data[i * per_chunk:(i + 1) * per_chunk]
        for i in range(count)
    ]


class Assembler:
    """One per connection and characteristic, like Chunking.Assembler."""

    def __init__(self) -> None:
        self.parts: dict[int, bytes] = {}
        self.count = -1

    def accept(self, frame: bytes) -> bytes | None:
        if len(frame) < HEADER:
            return None
        index = int.from_bytes(frame[0:2], 'big')
        count = int.from_bytes(frame[2:4], 'big')
        if count == 0:
            return None
        if count != self.count:
            self.parts, self.count = {}, count
        self.parts[index] = frame[HEADER:]
        if any(i not in self.parts for i in range(count)):
            return None
        data = b''.join(self.parts[i] for i in range(count))
        self.parts, self.count = {}, -1
        return data


# ---- Keys (crypto/DeviceKey.kt) -----------------------------------------------

def account_id(spki_b64: str) -> str:
    return 'bp_' + hashlib.sha256(base64.b64decode(spki_b64)).digest()[:8].hex()


def verifies(payload: str, sig_b64: str, spki_b64: str | None) -> bool:
    if not spki_b64:
        return False
    try:
        key = serialization.load_der_public_key(base64.b64decode(spki_b64))
        key.verify(base64.b64decode(sig_b64), payload.encode(), ec.ECDSA(hashes.SHA256()))
        return True
    except (InvalidSignature, ValueError, TypeError):
        return False


class Identity:
    """A fresh P-256 key per run; the account id is derived from it."""

    def __init__(self) -> None:
        self.key = ec.generate_private_key(ec.SECP256R1())
        der = self.key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        self.spki = base64.b64encode(der).decode()
        self.id = account_id(self.spki)

    def sign(self, payload: str) -> str:
        return base64.b64encode(self.key.sign(payload.encode(), ec.ECDSA(hashes.SHA256()))).decode()

    def packet(self, amount_paise: int, payee: str) -> dict:
        """Packet.create: the payload is concatenated by hand, in this order, and signed as-is."""
        payload = (
            '{"v":1,"txId":"%s","nonce":"%s","amountPaise":%d,"payerId":"%s","payeeId":"%s","createdAt":%d}'
            % (uuid.uuid4(), secrets.token_hex(12), amount_paise, self.id, payee, time.time_ns() // 1_000_000)
        )
        return {'payload': payload, 'sig': self.sign(payload), 'payerPubKey': self.spki, 'hops': [self.id]}


# ---- The mock bank ------------------------------------------------------------

class Bank:
    def __init__(self, url: str) -> None:
        self.url = url.rstrip('/')

    def call(self, path: str, body: dict | None = None) -> tuple[int, dict]:
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.url + path, data=data, headers={'content-type': 'application/json'})
        try:
            with urllib.request.urlopen(request, timeout=8) as response:
                return response.status, json.loads(response.read())
        except urllib.error.HTTPError as error:
            return error.code, json.loads(error.read() or b'{}')


def describe(packet: dict) -> str:
    f = json.loads(packet['payload'])
    return f"{f['txId'][:8]} ₹{f['amountPaise'] / 100:,.2f} {f['payerId']} → {f['payeeId']}"


# ---- The phone ----------------------------------------------------------------

class Phone:
    def __init__(self, device: Device, me: Identity, name: str, bank: Bank, bridge: bool, mtu: int) -> None:
        self.device, self.me, self.name, self.bank, self.bridge, self.mtu = device, me, name, bank, bridge, mtu
        self.bank_key: str | None = None
        self.assemblers: dict[tuple[int, UUID], Assembler] = {}
        self.held: dict[str, dict] = {}        # txId → packet, with this phone in its hops
        self.to_settle: asyncio.Queue[dict] = asyncio.Queue()
        self.receipts: dict[str, tuple[dict, float, set[str]]] = {}  # txId → (proof, first seen, awaited by)
        self.sent: set[tuple[str, str]] = set()                      # (txId, phone id)
        self.receipt_arrived = asyncio.Event()

    # -- what other phones see ---------------------------------------------------

    def service(self) -> Service:
        return Service(SERVICE, [
            Characteristic(PACKET_IN, Characteristic.Properties.WRITE, Characteristic.WRITEABLE,
                           CharacteristicValue(write=lambda c, v: self.on_write(c, PACKET_IN, v))),
            Characteristic(PROFILE, Characteristic.Properties.READ, Characteristic.READABLE,
                           CharacteristicValue(read=lambda c: self.profile())),
            Characteristic(RECEIPT_IN, Characteristic.Properties.WRITE, Characteristic.WRITEABLE,
                           CharacteristicValue(write=lambda c, v: self.on_write(c, RECEIPT_IN, v))),
        ])

    def profile(self) -> bytes:
        return json.dumps({'id': self.me.id, 'name': self.name}, separators=(',', ':')).encode()

    def advertisement(self) -> bytes:
        return bytes(AdvertisingData([
            (AdvertisingData.FLAGS, bytes([0x06])),
            (AdvertisingData.COMPLETE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS, bytes(SERVICE)),
        ]))

    async def keep_advertising(self) -> None:
        """Stay findable, as MeshPeripheral does. Bumble stops advertising when a
        phone connects, and overlapping connections can leave it stopped."""
        while True:
            if not self.device.is_advertising:
                try:
                    await self.device.start_advertising(advertising_data=self.advertisement())
                except Exception as error:  # noqa: BLE001 — busy connecting; try again shortly
                    log(f'  could not advertise yet: {error!r}')
            await asyncio.sleep(1)

    def on_connection(self, connection: Connection) -> None:
        log(f'  {connection.peer_address} connected')

        def forget() -> None:
            for key in [k for k in self.assemblers if k[0] == connection.handle]:
                del self.assemblers[key]
        connection.on('disconnection', lambda reason: forget())

    # -- receiving ---------------------------------------------------------------

    def on_write(self, connection: Connection, characteristic: UUID, value: bytes) -> None:
        assembler = self.assemblers.setdefault((connection.handle, characteristic), Assembler())
        data = assembler.accept(bytes(value))
        if data is None:
            return
        try:
            if characteristic == PACKET_IN:
                self.on_packet(json.loads(data))
            else:
                self.on_receipts(json.loads(data)['receipts'])
        except (ValueError, KeyError) as error:
            log(f'  ✗ malformed {characteristic}: {error!r}: {data[:120]!r}')

    def on_packet(self, packet: dict) -> None:
        fields = json.loads(packet['payload'])
        signed = verifies(packet['payload'], packet['sig'], packet['payerPubKey'])
        bound = account_id(packet['payerPubKey']) == fields['payerId']
        # MeshRouter.onPacket: the receiver adds itself to the route.
        if self.me.id not in packet['hops']:
            packet['hops'].append(self.me.id)
        new = fields['txId'] not in self.held
        self.held.setdefault(fields['txId'], packet)
        log(f'⇐ packet {describe(packet)}  route {" → ".join(packet["hops"])}  '
            f'signature {"verifies" if signed else "DOES NOT VERIFY"}, payer {"owns" if bound else "DOES NOT OWN"} the key'
            + ('' if new else '  (already had it)'))
        if new and self.bridge:
            self.to_settle.put_nowait(packet)

    def on_receipts(self, receipts: list[dict]) -> None:
        for proof in receipts:
            fields = json.loads(proof['payload'])
            ok = verifies(proof['payload'], proof['sig'], self.bank_key)
            log(f"⇐ receipt {fields['txId'][:8]} ₹{fields['amountPaise'] / 100:,.2f} → {fields['payeeId']}  "
                f"{'signed by the bank' if ok else 'NOT signed by the bank'}")
            if ok and fields['txId'] in self.held:
                self.receipt_arrived.set()

    # -- settling ------------------------------------------------------------------

    async def settle_forever(self) -> None:
        while True:
            packet = await self.to_settle.get()
            status, body = await asyncio.to_thread(self.bank.call, '/v1/settle', packet)
            if status != 200:
                log(f'✗ bank refused {describe(packet)}: {body.get("code")}: {body.get("message")}')
                continue
            proof = body.get('proof')
            log(f'✓ bank: {body["status"]} {describe(packet)}  via {" → ".join(packet["hops"])}')
            if not proof or not verifies(proof['payload'], proof['sig'], self.bank_key):
                log('✗ the bank\'s proof does not verify against its key')
                continue
            fields = json.loads(packet['payload'])
            # MeshRouter.othersAwait: whoever signed or carried it, and a payee that is a phone.
            waiting = {h for h in packet['hops'] if h != self.me.id}
            if fields['payeeId'].startswith('bp_') and fields['payeeId'] != self.me.id:
                waiting.add(fields['payeeId'])
            self.receipts[fields['txId']] = (proof, time.monotonic(), waiting)

    async def pass_receipts_back(self) -> None:
        """Offers each receipt to each phone once, like ReceiptBook, until everyone waiting has it."""
        while True:
            now = time.monotonic()
            for tx in [t for t, (_, seen, _) in self.receipts.items() if now - seen > RECEIPT_TTL_S]:
                del self.receipts[tx]
            outstanding = [(tx, waiting) for tx, (_, _, waiting) in self.receipts.items()
                           if any((tx, phone) not in self.sent for phone in waiting)]
            if not outstanding:
                await asyncio.sleep(1)
                continue
            for advertisement in await self.phones_in_range():
                await self.exchange(advertisement, receipts=True)
            await asyncio.sleep(2)

    # -- handing over ----------------------------------------------------------------

    async def phones_in_range(self, seconds: float = 4.0) -> list[Advertisement]:
        found: dict[str, Advertisement] = {}

        def on_advertisement(advertisement: Advertisement) -> None:
            groups = (advertisement.data.get_all(AdvertisingData.COMPLETE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS)
                      + advertisement.data.get_all(AdvertisingData.INCOMPLETE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS))
            if advertisement.is_connectable and any(SERVICE in group for group in groups):
                found[str(advertisement.address)] = advertisement

        self.device.on('advertisement', on_advertisement)
        await self.device.start_scanning(filter_duplicates=True)
        await asyncio.sleep(seconds)
        await self.device.stop_scanning()
        self.device.remove_listener('advertisement', on_advertisement)
        return sorted(found.values(), key=lambda a: -a.rssi)

    async def exchange(self, advertisement: Advertisement, packets: bool = False, receipts: bool = False) -> str | None:
        """GattSession.run: connect, learn who it is, hand over what it still needs."""
        address = advertisement.address
        try:
            connection = await self.device.connect(address, timeout=10)
        except Exception as error:  # noqa: BLE001 — any failure to connect is just "not now"
            log(f'  could not connect to {address}: {error!r}')
            return None
        try:
            peer = Peer(connection)
            mtu = await peer.request_mtu(self.mtu)
            await peer.discover_services([SERVICE])
            services = peer.get_services_by_uuid(SERVICE)
            if not services:
                log(f'  {address} does not run BouncePay')
                return None
            await services[0].discover_characteristics()
            chars = {c.uuid: c for c in services[0].characteristics}
            profile = json.loads(await chars[PROFILE].read_value())
            peer_id = profile['id']
            log(f'→ {address} is {profile.get("name")!r} {peer_id}  (MTU {mtu})')

            if packets:
                for tx, packet in self.held.items():
                    if peer_id in packet['hops']:
                        continue  # the anti-loop: it already has this one
                    data = json.dumps(packet).encode()
                    frames = split(data, mtu)
                    for frame in frames:
                        await chars[PACKET_IN].write_value(frame, with_response=True)
                    packet['hops'].append(peer_id)  # PacketStore.recordHandoff
                    log(f'⇒ packet {describe(packet)} to {peer_id} in {len(frames)} write(s) of ≤{max(map(len, frames))} bytes')

            if receipts:
                due = [(tx, proof) for tx, (proof, _, _) in self.receipts.items() if (tx, peer_id) not in self.sent]
                if due:
                    data = json.dumps({'receipts': [proof for _, proof in due]}).encode()
                    frames = split(data, mtu)
                    for frame in frames:
                        await chars[RECEIPT_IN].write_value(frame, with_response=True)
                    self.sent.update((tx, peer_id) for tx, _ in due)
                    log(f'⇒ {len(due)} receipt(s) to {peer_id} in {len(frames)} write(s)')
            return peer_id
        except Exception as error:  # noqa: BLE001 — report and carry on, as the app does
            log(f'  ✗ exchange with {address} failed: {error!r}')
            return None
        finally:
            if connection.handle in self.device.connections:
                await connection.disconnect()


# ---- Commands -------------------------------------------------------------------

async def bridge(phone: Phone) -> None:
    log('bridging: packets handed to this phone are settled at the bank, and receipts go back')
    await asyncio.gather(phone.settle_forever(), phone.pass_receipts_back())


async def pay(phone: Phone, amount_paise: int, payee: str, wait_s: float) -> None:
    status, body = await asyncio.to_thread(phone.bank.call, '/v1/enroll', {
        'payerPubKey': phone.me.spki, 'label': phone.name, 'openingPaise': 2_000_00})
    if status != 200:
        sys.exit(f'enrolment refused: {body}')
    log(f'enrolled: ₹{body["account"]["balancePaise"] / 100:,.2f} at the bank')

    packet = phone.me.packet(amount_paise, payee)
    tx = json.loads(packet['payload'])['txId']
    phone.held[tx] = packet
    log(f'signed {describe(packet)}')

    deadline = time.monotonic() + wait_s
    while time.monotonic() < deadline and len(phone.held[tx]['hops']) == 1:
        for advertisement in await phone.phones_in_range():
            await phone.exchange(advertisement, packets=True)
    if len(phone.held[tx]['hops']) == 1:
        sys.exit('no phone took the packet')

    log('handed over; waiting for the bank\'s receipt to come back over Bluetooth')
    try:
        await asyncio.wait_for(phone.receipt_arrived.wait(), max(deadline - time.monotonic(), 1))
        log('✓ receipt came back')
    except asyncio.TimeoutError:
        sys.exit('no receipt came back in time')


async def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0].strip())
    parser.add_argument('--bank', default='http://localhost:4000')
    parser.add_argument('--name', default='Mock phone')
    parser.add_argument('--transport', default='android-netsim', help='Bumble transport spec')
    parser.add_argument('--address', default='C0:BB:00:00:00:01', help='this phone\'s random static address')
    parser.add_argument('--mtu', type=int, default=517, help='largest ATT MTU to agree to; 23 forces tiny chunks')
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('bridge', help='settle packets at the bank and pass receipts back')
    p = commands.add_parser('pay', help='sign a payment and hand it to a phone in range')
    p.add_argument('rupees', type=int)
    p.add_argument('--to', required=True, help='payee account id, e.g. bp_… or campus-stationery')
    p.add_argument('--wait', type=float, default=120, help='seconds to wait for the hand-off and the receipt')
    args = parser.parse_args()

    bank = Bank(args.bank)
    try:
        status, health = await asyncio.to_thread(bank.call, '/v1/health')
    except urllib.error.URLError:
        status = 0
    if status != 200:
        sys.exit(f'No bank at {args.bank}. Start it first: cd mock-bank && npm start')

    me = Identity()
    try:
        transport = await open_transport(args.transport)
    except TransportInitError:
        sys.exit('No emulator Bluetooth to join. Start an emulator first: its virtual radio, netsim, starts with it.')
    async with transport as (source, sink):
        device = Device.with_hci(args.name, Address(args.address), source, sink)
        phone = Phone(device, me, args.name, bank, bridge=args.command == 'bridge', mtu=args.mtu)
        phone.bank_key = health['bankPubKey']
        device.add_service(phone.service())
        device.gatt_server.max_mtu = args.mtu
        device.on('connection', phone.on_connection)
        await device.power_on()
        advertiser = asyncio.create_task(phone.keep_advertising())
        log(f'{args.name} is {me.id}, advertising BouncePay on {args.transport}')

        if args.command == 'bridge':
            await bridge(phone)
        else:
            await pay(phone, args.rupees * 100, args.to, args.wait)
        advertiser.cancel()


if __name__ == '__main__':
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
