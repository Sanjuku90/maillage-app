import unittest
from ble_frames import fragment, Reassembler

class BleFrameTests(unittest.TestCase):
    def test_roundtrip_minimum_mtu_out_of_order_and_duplicate(self):
        payload = b'{' + b'x' * 499
        pieces = fragment(payload, 20, 42)
        self.assertGreater(len(pieces), 1)
        self.assertTrue(all(len(p) <= 20 for p in pieces))
        r = Reassembler()
        self.assertIsNone(r.feed('peer', pieces[0]))
        self.assertIsNone(r.feed('peer', pieces[0]))
        result = None
        for p in reversed(pieces[1:]):
            result = r.feed('peer', p)
        self.assertEqual(result, payload)

    def test_matches_android_minimum_mtu_fixture(self):
        parts = fragment(b'abcdefghijklmnopqrstuvwxyz', 20, 0x01020304)
        self.assertEqual([p.hex() for p in parts], [
            '4d460102030400036162636465666768696a6b6c',
            '4d460102030401036d6e6f707172737475767778',
            '4d46010203040203797a',
        ])

    def test_peer_isolation_and_expiry(self):
        now = [0]
        r = Reassembler(ttl=5, clock=lambda: now[0])
        a, b = fragment(b'{' + b'a' * 20, 20, 7)
        self.assertIsNone(r.feed('one', a))
        self.assertIsNone(r.feed('two', b))
        now[0] = 6
        self.assertIsNone(r.feed('one', b))

    def test_invalid_and_direct(self):
        r = Reassembler()
        self.assertEqual(r.feed('p', b'{ok}'), b'{ok}')
        self.assertIsNone(r.feed('p', b'MF' + b'\x00' * 6))
        with self.assertRaises(ValueError): fragment(b'x'*521, 20, 1)
        with self.assertRaises(ValueError): fragment(b'x'*30, 19, 1)

if __name__ == '__main__': unittest.main()
