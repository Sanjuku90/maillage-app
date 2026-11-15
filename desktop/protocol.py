"""Experimental Maillage 3 channel envelopes, shared with the Android prototype."""
import base64
import hashlib
import json
import os
import struct
import time
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAX_FRAME = 500
TTL = 16
MAX_CLOCK_SKEW_SECONDS = 7200


def derive_channel_key(password: str) -> bytes:
    from argon2.low_level import Type, hash_secret_raw
    return hash_secret_raw(password.encode('utf-8'), b'maillage-salt-v3', time_cost=2,
                           memory_cost=19456, parallelism=1, hash_len=32,
                           type=Type.ID, version=19)


def channel_tag(key: bytes) -> str:
    return hashlib.sha256(key).digest()[:8].hex()


def _signed_payload(message_id: str, millis: int, name: bytes, body: bytes) -> bytes:
    return message_id.encode('utf-8') + struct.pack('>q', millis) + bytes([len(name)]) + name + body


class ChannelCodec:
    """Session identity is ephemeral. Never stores messages or private keys on disk."""
    def __init__(self, password: str, nickname: str, key: bytes | None = None):
        if len(password) < 8:
            raise ValueError('Mot de passe : 8 caractères minimum')
        name = ''.join(c for c in nickname if c != '#' and c.isprintable()).strip()[:12] or 'PC'
        self.name = name.encode('utf-8')
        if len(self.name) > 255:
            raise ValueError('Pseudo trop long')
        self.key = key if key is not None else derive_channel_key(password)
        if len(self.key) != 32:
            raise ValueError('Clé de canal invalide')
        self.channel_tag = channel_tag(self.key)
        self.identity = ec.generate_private_key(ec.SECP256R1())
        self.public_key = self.identity.public_key().public_bytes(
            serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
        self.tag = hashlib.sha256(self.public_key).digest()[:3].hex()

    def encode(self, text: str) -> bytes:
        data = text.encode('utf-8')
        if not data or len(data) > 100:
            raise ValueError('Message : 100 octets UTF-8 maximum')
        msg_id = os.urandom(8).hex()
        millis = int(time.time() * 1000)
        body = b'\x00' + data
        signature = self.identity.sign(_signed_payload(msg_id, millis, self.name, body), ec.ECDSA(hashes.SHA256()))
        plain = bytes([len(self.name)]) + self.name + self.public_key + struct.pack('>q', millis) + bytes([len(signature)]) + signature + body
        nonce = os.urandom(12)
        encrypted = nonce + AESGCM(self.key).encrypt(nonce, plain, msg_id.encode('ascii'))
        frame = json.dumps({'i': msg_id, 't': TTL, 'p': base64.b64encode(encrypted).decode('ascii')}, separators=(',', ':')).encode('utf-8')
        if len(frame) > MAX_FRAME:
            raise ValueError('Message trop long pour ce protocole')
        return frame

    def decode(self, raw: bytes):
        if not 1 <= len(raw) <= 520:
            return None
        try:
            obj = json.loads(raw.decode('utf-8'))
            if not isinstance(obj, dict):
                return None
            msg_id, ttl, payload = obj['i'], obj['t'], obj['p']
            if not isinstance(msg_id, str) or len(msg_id) != 16 or any(c not in '0123456789abcdef' for c in msg_id):
                return None
            if type(ttl) is not int or not 1 <= ttl <= TTL or not isinstance(payload, str):
                return None
            data = base64.b64decode(payload, validate=True)
            if len(data) < 29:
                return None
            plain = AESGCM(self.key).decrypt(data[:12], data[12:], msg_id.encode('ascii'))
            if len(plain) < 1 + 65 + 8 + 1 + 1:
                return None
            name_len = plain[0]; pos = 1
            if pos + name_len + 65 + 8 + 1 > len(plain):
                return None
            name = plain[pos:pos+name_len]; pos += name_len
            public = plain[pos:pos+65]; pos += 65
            millis = struct.unpack('>q', plain[pos:pos+8])[0]; pos += 8
            sig_len = plain[pos]; pos += 1
            if not 1 <= sig_len <= 80 or pos + sig_len + 1 > len(plain):
                return None
            signature = plain[pos:pos+sig_len]; pos += sig_len
            body = plain[pos:]
            if body[0] != 0 or abs(time.time() - millis / 1000) > MAX_CLOCK_SKEW_SECONDS:
                return None
            peer = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), public)
            peer.verify(signature, _signed_payload(msg_id, millis, name, body), ec.ECDSA(hashes.SHA256()))
            display_name = ''.join(c for c in name.decode('utf-8', errors='replace') if c != '#' and c.isprintable())[:12]
            display_text = ''.join(c if c.isprintable() else ' ' for c in body[1:].decode('utf-8', errors='replace'))[:200]
            return {'id': msg_id, 'name': display_name, 'tag': hashlib.sha256(public).digest()[:3].hex(), 'text': display_text, 'hops': TTL - ttl}
        except Exception:
            return None
