"""Experimental BLE central for nearby Maillage Android GATT servers."""
import asyncio
import queue
import threading
import time
from bleak import BleakClient, BleakScanner
from ble_frames import Reassembler, fragment

SERVICE = '7f3a1c10-5b2e-4c8a-9d11-6a0e4f2b9c01'
CHARACTERISTIC = '7f3a1c11-5b2e-4c8a-9d11-6a0e4f2b9c01'
MAX_PEERS = 4


class BleTransport:
    def __init__(self, on_frame, on_status):
        self.on_frame, self.on_status = on_frame, on_status
        self.stop_event = threading.Event()
        self.outbox = queue.Queue(maxsize=100)
        self.count_lock = threading.Lock()
        self.count = 0
        self.clients = {}
        self.reassembler = Reassembler()
        self.sequence = 0
        self.thread = None

    def start(self):
        if self.thread is not None:
            return
        self.thread = threading.Thread(target=self._run, name='maillage-ble', daemon=True)
        self.thread.start()

    def stop(self):
        self.stop_event.set()
        while True:
            try: self.outbox.get_nowait()
            except queue.Empty: break

    def send(self, frame):
        if self.stop_event.is_set() or not 1 <= len(frame) <= 520:
            return 0
        with self.count_lock:
            peers = self.count
        if not peers:
            return 0
        try:
            self.outbox.put_nowait((time.monotonic(), bytes(frame)))
        except queue.Full:
            return 0
        return peers

    def _run(self):
        try:
            asyncio.run(self._main())
        except Exception:
            self.on_status('Bluetooth PC indisponible; vérifie les permissions et l’adaptateur')

    async def _main(self):
        writer = asyncio.create_task(self._writer())
        try:
            while not self.stop_event.is_set():
                try:
                    devices = await BleakScanner.discover(timeout=4.0, service_uuids=[SERVICE])
                except Exception:
                    self.on_status('Recherche Bluetooth impossible; vérifie les permissions')
                    await asyncio.sleep(5)
                    continue
                for dev in devices:
                    if self.stop_event.is_set() or len(self.clients) >= MAX_PEERS:
                        break
                    if dev.address in self.clients:
                        continue
                    await self._connect(dev)
                for _ in range(10):
                    if self.stop_event.is_set(): break
                    await asyncio.sleep(0.5)
        finally:
            writer.cancel()
            try: await writer
            except asyncio.CancelledError: pass
            for c in list(self.clients.values()):
                try: await c.disconnect()
                except Exception: pass
            self.clients.clear()
            with self.count_lock: self.count = 0

    async def _connect(self, device):
        address = device.address
        def disconnected(client):
            if self.clients.get(address) is client:
                self.clients.pop(address, None)
                self.reassembler.discard_source(address)
                with self.count_lock: self.count = len(self.clients)
                if not self.stop_event.is_set():
                    self.on_status(f'Bluetooth : {len(self.clients)} téléphone(s) connecté(s)')
        client = BleakClient(device, disconnected_callback=disconnected, timeout=12.0)
        try:
            await client.connect()
            def notified(_characteristic, data):
                frame = self.reassembler.feed(address, data)
                if frame is not None and not self.stop_event.is_set():
                    self.on_frame(frame)
            await client.start_notify(CHARACTERISTIC, notified)
            if self.stop_event.is_set():
                await client.disconnect()
                return
            self.clients[address] = client
            with self.count_lock: self.count = len(self.clients)
            self.on_status(f'Bluetooth : {len(self.clients)} téléphone(s) connecté(s)')
        except Exception:
            try: await client.disconnect()
            except Exception: pass

    async def _send_peer(self, client, packet, sequence):
        try:
            if not client.is_connected:
                return False
            # Always use a 20-byte write budget: works with the minimum BLE MTU.
            for part in fragment(packet, 20, sequence):
                await client.write_gatt_char(CHARACTERISTIC, part, response=True)
            return True
        except Exception:
            try: await client.disconnect()
            except Exception: pass
            return False

    async def _writer(self):
        while not self.stop_event.is_set():
            if not self.clients:
                await asyncio.sleep(0.25)
                continue
            try:
                created, frame = self.outbox.get_nowait()
            except queue.Empty:
                await asyncio.sleep(0.25)
                continue
            if time.monotonic() - created > 60:
                continue
            self.sequence = (self.sequence + 1) & 0xffffffff
            results = await asyncio.gather(*(self._send_peer(peer, frame, self.sequence)
                                             for peer in list(self.clients.values())))
            if not any(results) and not self.stop_event.is_set():
                try: self.outbox.put_nowait((created, frame))
                except queue.Full: pass
                self.on_status('Bluetooth : en attente de reconnexion')
