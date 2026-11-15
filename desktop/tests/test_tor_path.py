import importlib.util
import os
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import patch


class TorRoutingTests(unittest.TestCase):
    def load_relay(self, calls):
        coincurve = types.ModuleType('coincurve')
        class PrivateKey:
            pass
        coincurve.PrivateKey = PrivateKey
        coincurve.PublicKeyXOnly = object
        websocket = types.ModuleType('websocket')
        class Socket:
            def __init__(self, url, **kwargs):
                self.url = url
            def run_forever(self, **kwargs):
                calls.append(kwargs)
                relay.stop_event.set()
                raise OSError('local Tor proxy unavailable')
            def close(self):
                pass
        websocket.WebSocketApp = Socket
        spec = importlib.util.spec_from_file_location('nostr_tor_test', Path(__file__).resolve().parents[1] / 'nostr.py')
        module = importlib.util.module_from_spec(spec)
        with patch.dict(sys.modules, {'coincurve': coincurve, 'websocket': websocket}):
            spec.loader.exec_module(module)
        relay = module.RelayClient('abc', lambda _: None, lambda _: None, tor_port=9050)
        return relay

    def test_tor_failure_never_retries_direct_and_overrides_no_proxy(self):
        calls = []
        relay = self.load_relay(calls)
        with patch.dict(os.environ, {'NO_PROXY': '*', 'no_proxy': '*'}):
            relay._run('wss://relay.damus.io')
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0]['http_proxy_host'], '127.0.0.1')
        self.assertEqual(calls[0]['http_proxy_port'], 9050)
        self.assertEqual(calls[0]['proxy_type'], 'socks5h')
        self.assertEqual(calls[0]['http_no_proxy'], [''])

    def test_invalid_proxy_port_is_rejected(self):
        relay = self.load_relay([])
        cls = type(relay)
        for bad in (0, 65536, '9050', True):
            with self.assertRaises(ValueError):
                cls('abc', lambda _: None, lambda _: None, tor_port=bad)


if __name__ == '__main__':
    unittest.main()
