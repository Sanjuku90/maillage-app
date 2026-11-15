"""Bounded wire-compatible fragmentation for the experimental Maillage BLE link."""
import struct
import time
from collections import OrderedDict

MAGIC = b'MF'
HEADER = 8
MAX_FRAME = 520
MAX_FRAGMENTS = 64


def fragment(frame, max_write, sequence):
    if not isinstance(frame, bytes) or not 1 <= len(frame) <= MAX_FRAME:
        raise ValueError('invalid BLE frame')
    if max_write < 20:
        raise ValueError('unsupported BLE MTU')
    if len(frame) <= max_write:
        return [frame]
    capacity = max_write - HEADER
    count = (len(frame) + capacity - 1) // capacity
    if not 1 <= count <= MAX_FRAGMENTS:
        raise ValueError('too many BLE fragments')
    seq = sequence & 0xffffffff
    return [MAGIC + struct.pack('>IBB', seq, part, count) + frame[part*capacity:(part+1)*capacity]
            for part in range(count)]


class Reassembler:
    def __init__(self, maximum=64, ttl=15, clock=time.monotonic):
        self.maximum, self.ttl, self.clock = maximum, ttl, clock
        self.parts = OrderedDict()

    def feed(self, source, packet):
        packet = bytes(packet)
        if len(packet) < 2 or packet[:2] != MAGIC:
            return packet if 1 <= len(packet) <= MAX_FRAME else None
        if not HEADER < len(packet) <= MAX_FRAME:
            return None
        seq, part, count = struct.unpack('>IBB', packet[2:HEADER])
        if not 1 <= count <= MAX_FRAGMENTS or part >= count:
            return None
        now = self.clock()
        for key, value in list(self.parts.items()):
            if now - value['time'] > self.ttl:
                del self.parts[key]
        key = (source, seq)
        if key not in self.parts:
            while len(self.parts) >= self.maximum:
                self.parts.popitem(last=False)
            self.parts[key] = {'time': now, 'count': count, 'pieces': {}, 'size': 0}
        state = self.parts[key]
        if state['count'] != count or part in state['pieces']:
            return None
        payload = packet[HEADER:]
        if state['size'] + len(payload) > MAX_FRAME:
            del self.parts[key]
            return None
        state['pieces'][part] = payload
        state['size'] += len(payload)
        if len(state['pieces']) != count:
            return None
        complete = b''.join(state['pieces'][i] for i in range(count))
        del self.parts[key]
        return complete if 1 <= len(complete) <= MAX_FRAME else None

    def discard_source(self, source):
        for key in list(self.parts):
            if key[0] == source:
                del self.parts[key]
