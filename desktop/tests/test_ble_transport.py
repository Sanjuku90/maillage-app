import importlib.util
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import patch
from ble_frames import Reassembler, fragment


def load_transport():
    fake_bleak = types.ModuleType('bleak')
    class FakeClient:
        def __init__(self, device, disconnected_callback=None, timeout=None):
            self.device = device
            self.disconnected_callback = disconnected_callback
            self.is_connected = False
            self.notifications = None
            self.writes = []
        async def connect(self): self.is_connected = True
        async def start_notify(self, characteristic, callback):
            self.characteristic = characteristic
            self.notifications = callback
        async def write_gatt_char(self, characteristic, data, response):
            self.writes.append((characteristic, bytes(data), response))
        async def disconnect(self):
            self.is_connected = False
            if self.disconnected_callback: self.disconnected_callback(self)
    class FakeScanner: pass
    fake_bleak.BleakClient = FakeClient
    fake_bleak.BleakScanner = FakeScanner
    spec = importlib.util.spec_from_file_location('test_ble_transport_impl', Path(__file__).resolve().parents[1] / 'ble_transport.py')
    module = importlib.util.module_from_spec(spec)
    with patch.dict(sys.modules, {'bleak': fake_bleak}):
        spec.loader.exec_module(module)
    return module


class BleTransportTests(unittest.IsolatedAsyncioTestCase):
    async def test_connect_subscribe_send_and_receive(self):
        module = load_transport()
        received = []; status = []
        transport = module.BleTransport(received.append, status.append)
        await transport._connect(types.SimpleNamespace(address='simulated-device'))
        self.assertEqual(transport.count, 1)
        client = transport.clients['simulated-device']
        frame = b'{' + b'x' * 450
        self.assertTrue(await transport._send_peer(client, frame, 12))
        self.assertTrue(all(c == module.CHARACTERISTIC and ok and len(p) <= 20 for c,p,ok in client.writes))
        r = Reassembler(); reconstructed = None
        for _, part, _ in client.writes: reconstructed = r.feed('android', part)
        self.assertEqual(reconstructed, frame)
        for part in fragment(frame, 20, 99): client.notifications(None, part)
        self.assertEqual(received, [frame])
        await client.disconnect()
        self.assertEqual(transport.count, 0)

    async def test_queue_requires_connected_peer(self):
        module = load_transport()
        transport = module.BleTransport(lambda _: None, lambda _: None)
        self.assertEqual(transport.send(b'{hi}'), 0)
        await transport._connect(types.SimpleNamespace(address='simulated-device'))
        self.assertEqual(transport.send(b'{hi}'), 1)
        transport.stop()
        self.assertEqual(transport.send(b'{hi}'), 0)

if __name__ == '__main__': unittest.main()
