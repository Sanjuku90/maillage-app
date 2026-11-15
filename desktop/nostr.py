"""Experimental NIP-01 relay client: transport encrypted frames only, not plaintext."""
import base64
import hashlib
import json
import threading
import time
from collections import OrderedDict
from coincurve import PrivateKey, PublicKeyXOnly
from websocket import WebSocketApp
from outbox import Outbox

RELAYS = ('wss://relay.damus.io', 'wss://nos.lol', 'wss://relay.nostr.band')


def event_id(event):
    body = [0, event['pubkey'], event['created_at'], event['kind'], event['tags'], event['content']]
    serialized = json.dumps(body, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
    return hashlib.sha256(serialized).digest()


def sign_event(key, channel, frame):
    now = int(time.time())
    event = {'pubkey': key.public_key_xonly.format().hex(), 'created_at': now, 'kind': 1,
             'tags': [['t', 'maillage_' + channel], ['expiration', str(now + 7200)]],
             'content': base64.b64encode(frame).decode('ascii')}
    digest = event_id(event)
    event['id'] = digest.hex()
    event['sig'] = key.sign_schnorr(digest).hex()
    return event


def verify_event(event, channel):
    try:
        if not isinstance(event, dict) or event.get('kind') != 1:
            return None
        stamp = event['created_at']
        if type(stamp) is not int or abs(time.time() - stamp) > 7200:
            return None
        tags = event['tags']
        if not isinstance(tags, list) or len(tags) > 32 or ['t', 'maillage_' + channel] not in tags:
            return None
        if any(not isinstance(t, list) or not 1 <= len(t) <= 8 or any(not isinstance(v, str) for v in t) for t in tags):
            return None
        content = event['content']
        if not isinstance(content, str) or len(content) > 4096:
            return None
        digest = event_id(event)
        if event.get('id') != digest.hex():
            return None
        pub = bytes.fromhex(event['pubkey']); sig = bytes.fromhex(event['sig'])
        if len(pub) != 32 or len(sig) != 64 or not PublicKeyXOnly(pub).verify(sig, digest):
            return None
        frame = base64.b64decode(content, validate=True)
        return frame if 1 <= len(frame) <= 520 else None
    except Exception:
        return None


class RelayClient:
    def __init__(self, channel, on_frame, on_status, tor_port=None):
        if tor_port is not None and (type(tor_port) is not int or not 1 <= tor_port <= 65535):
            raise ValueError('Port Tor invalide')
        self.channel, self.on_frame, self.on_status = channel, on_frame, on_status
        self.tor_port = tor_port
        self.key = PrivateKey()  # fresh session identity; deliberately not written to disk
        self.stop_event = threading.Event()
        self.lock = threading.Lock()
        self.sockets = {}
        self.seen = OrderedDict()
        self.outbox = Outbox()
        self.subscription = 'maillage-' + channel

    def start(self):
        for url in RELAYS:
            threading.Thread(target=self._run, args=(url,), daemon=True).start()

    def _run(self, url):
        while not self.stop_event.is_set():
            def opened(ws):
                with self.lock:
                    if self.stop_event.is_set():
                        ws.close(); return
                    self.sockets[url] = ws
                request = ['REQ', self.subscription, {'kinds': [1], '#t': ['maillage_' + self.channel],
                                                       'since': int(time.time()) - 7200, 'limit': 200}]
                ws.send(json.dumps(request, separators=(',', ':')))
                with self.lock:
                    flushed = self.outbox.flush(ws.send)
                suffix = f' · {flushed} message(s) remis au relais' if flushed else ''
                self.on_status(('Tor · ' if self.tor_port else '') + 'Relais connecté : ' + url + suffix)

            def message(ws, raw):
                if not isinstance(raw, str) or len(raw) > 64000 or self.stop_event.is_set():
                    return
                try:
                    msg = json.loads(raw)
                    if not isinstance(msg, list) or len(msg) < 3 or msg[0] != 'EVENT' or msg[1] != self.subscription:
                        return
                    frame = verify_event(msg[2], self.channel)
                    if frame is not None:
                        self.on_frame(frame)
                except (ValueError, TypeError):
                    return

            def closed(ws, status, reason):
                with self.lock:
                    if self.sockets.get(url) is ws:
                        del self.sockets[url]
                if not self.stop_event.is_set():
                    self.on_status('Relais déconnecté, reconnexion via Tor…' if self.tor_port else 'Relais déconnecté, reconnexion…')

            ws = WebSocketApp(url, on_open=opened, on_message=message, on_close=closed)
            try:
                options = {'ping_interval': 30, 'ping_timeout': 10}
                if self.tor_port is not None:
                    # A non-empty bypass list suppresses NO_PROXY environment overrides.
                    # An empty hostname cannot match any valid relay, including redirects.
                    options.update(http_proxy_host='127.0.0.1', http_proxy_port=self.tor_port,
                                   proxy_type='socks5h', http_no_proxy=[''])
                ws.run_forever(**options)
            except Exception:
                pass
            finally:
                closed(ws, None, None)
            self.stop_event.wait(5)

    def send(self, frame):
        """Return positive sockets sent to, -1 if queued, 0 if queue is full."""
        wire = json.dumps(['EVENT', sign_event(self.key, self.channel, frame)], separators=(',', ':'))
        with self.lock:
            if self.stop_event.is_set():
                return 0
            sent = 0
            for socket in list(self.sockets.values()):
                try:
                    socket.send(wire)
                    sent += 1
                except Exception:
                    pass
            if sent:
                return sent
            return -1 if self.outbox.add(wire) else 0

    def stop(self):
        self.stop_event.set()
        with self.lock:
            live = list(self.sockets.values()); self.sockets.clear(); self.outbox.clear()
        for socket in live:
            try:
                socket.close()
            except Exception:
                pass
