"""Offline packaging smoke test. Never connects to relays or Bluetooth devices."""
import json
import sys
from pathlib import Path


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def run(output_path):
    try:
        # Import dependencies inside the bundled executable, not from the checkout.
        import argon2.low_level
        import bleak
        import coincurve
        import cryptography
        import python_socks
        import websocket
        import tkinter
        from protocol import ChannelCodec, derive_channel_key
        from nostr import sign_event, verify_event
        from ble_frames import fragment, Reassembler
        from ble_transport import BleTransport

        key = derive_channel_key('test-password')
        require(key.hex() == '37eba482a65562838255eae8fca8fa6107c5ab9aa6ce018b328a190764aec8d2', 'Argon2 mismatch')
        sender = ChannelCodec('test-password', 'Test PC', key=key)
        receiver = ChannelCodec('test-password', 'Test Android', key=key)
        frame = sender.encode('test hors ligne')
        decoded = receiver.decode(frame)
        require(decoded is not None and decoded['text'] == 'test hors ligne', 'Channel encryption failed')
        fragments = fragment(frame, 20, 123)
        reassembler = Reassembler()
        complete = None
        for piece in fragments:
            complete = reassembler.feed('fixture', piece)
        require(complete == frame, 'BLE frame roundtrip failed')
        event = sign_event(coincurve.PrivateKey(), sender.channel_tag, frame)
        require(verify_event(event, sender.channel_tag) == frame, 'Nostr signature verification failed')
        report = {'ok': True, 'checks': ['imports', 'argon2', 'aes-gcm', 'ecdsa', 'schnorr', 'ble-fragments'],
                  'network_used': False, 'python': sys.version.split()[0]}
    except Exception as exc:
        report = {'ok': False, 'error': type(exc).__name__ + ': ' + str(exc), 'network_used': False}
    Path(output_path).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    return 0 if report['ok'] else 1
