"""Bounded in-memory queue for already encrypted, signed relay events."""
from collections import deque
import time


class Outbox:
    def __init__(self, maximum=100, lifetime_seconds=3600, clock=time.monotonic):
        self.maximum = maximum
        self.lifetime = lifetime_seconds
        self.clock = clock
        self.items = deque()

    def prune(self):
        now = self.clock()
        while self.items and now - self.items[0][0] >= self.lifetime:
            self.items.popleft()

    def add(self, wire):
        self.prune()
        if len(self.items) >= self.maximum:
            return False
        self.items.append((self.clock(), wire))
        return True

    def flush(self, sender):
        self.prune()
        sent = 0
        while self.items:
            try:
                sender(self.items[0][1])
            except Exception:
                break
            self.items.popleft()
            sent += 1
        return sent

    def clear(self):
        self.items.clear()

    def __len__(self):
        self.prune()
        return len(self.items)
