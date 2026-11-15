import copy
import unittest
try:
    from coincurve import PrivateKey
    from nostr import sign_event, verify_event
    AVAILABLE = True
except ImportError:
    AVAILABLE = False


@unittest.skipUnless(AVAILABLE, 'Desktop relay dependencies unavailable')
class NostrTests(unittest.TestCase):
    def test_signed_event_roundtrip_and_tampering(self):
        key = PrivateKey()
        event = sign_event(key, '0011223344556677', b'encrypted-frame')
        self.assertEqual(verify_event(event, '0011223344556677'), b'encrypted-frame')
        changed = copy.deepcopy(event)
        changed['content'] = 'YWJj'
        self.assertIsNone(verify_event(changed, '0011223344556677'))
        self.assertIsNone(verify_event(event, 'other-channel'))

    def test_wrong_signature_rejected(self):
        event = sign_event(PrivateKey(), '0011223344556677', b'hello')
        event['sig'] = '00' * 64
        self.assertIsNone(verify_event(event, '0011223344556677'))


if __name__ == '__main__':
    unittest.main()
