import base64
import json
import unittest
from protocol import ChannelCodec, channel_tag, derive_channel_key


class ProtocolTests(unittest.TestCase):
    def setUp(self):
        self.key = bytes(range(32))
        self.alice = ChannelCodec('test-password', 'Alice', key=self.key)
        self.bob = ChannelCodec('test-password', 'Bob', key=self.key)

    def test_argon2_matches_maillage_parameters(self):
        try:
            import argon2.low_level  # noqa: F401
        except ImportError:
            self.skipTest('argon2-cffi unavailable in this environment')
        self.assertEqual(derive_channel_key('test-password').hex(),
                         '37eba482a65562838255eae8fca8fa6107c5ab9aa6ce018b328a190764aec8d2')

    def test_two_independent_identities_share_encrypted_channel(self):
        frame = self.alice.encode('Bonjour du PC')
        self.assertNotIn(b'Bonjour du PC', frame)
        msg = self.bob.decode(frame)
        self.assertEqual(msg['text'], 'Bonjour du PC')
        self.assertEqual(msg['name'], 'Alice')
        self.assertEqual(msg['tag'], self.alice.tag)
        self.assertEqual(len(channel_tag(self.key)), 16)

    def test_wrong_password_key_cannot_read(self):
        other = ChannelCodec('test-password', 'Other', key=b'\xff' * 32)
        self.assertIsNone(other.decode(self.alice.encode('secret')))

    def test_tampered_ciphertext_is_rejected(self):
        obj = json.loads(self.alice.encode('hello'))
        raw = bytearray(base64.b64decode(obj['p']))
        raw[-1] ^= 1
        obj['p'] = base64.b64encode(raw).decode('ascii')
        self.assertIsNone(self.bob.decode(json.dumps(obj).encode()))

    def test_id_change_is_rejected(self):
        obj = json.loads(self.alice.encode('hello'))
        obj['i'] = '0123456789abcdef'
        self.assertIsNone(self.bob.decode(json.dumps(obj).encode()))

    def test_malformed_input_is_rejected(self):
        for value in (b'', b'[]', b'{}', b'\xff', b'x' * 521):
            self.assertIsNone(self.bob.decode(value))

    def test_size_and_password_limits(self):
        with self.assertRaises(ValueError): self.alice.encode('a' * 101)
        with self.assertRaises(ValueError): ChannelCodec('short', 'Bob', key=self.key)


if __name__ == '__main__':
    unittest.main()
