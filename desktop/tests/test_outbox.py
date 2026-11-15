import unittest
from outbox import Outbox


class OutboxTests(unittest.TestCase):
    def setUp(self):
        self.now = 100.0
        self.box = Outbox(maximum=2, lifetime_seconds=10, clock=lambda: self.now)

    def test_bounded_fifo(self):
        self.assertTrue(self.box.add('encrypted-event-1'))
        self.assertTrue(self.box.add('encrypted-event-2'))
        self.assertFalse(self.box.add('encrypted-event-3'))
        sent = []
        self.assertEqual(self.box.flush(sent.append), 2)
        self.assertEqual(sent, ['encrypted-event-1', 'encrypted-event-2'])

    def test_keeps_items_on_failed_send(self):
        self.box.add('ciphertext')
        def fail(_): raise OSError('disconnected')
        self.assertEqual(self.box.flush(fail), 0)
        self.assertEqual(len(self.box), 1)

    def test_expiration_and_clear(self):
        self.box.add('ciphertext')
        self.now += 10
        self.assertEqual(len(self.box), 0)
        self.assertTrue(self.box.add('next'))
        self.box.clear()
        self.assertEqual(len(self.box), 0)


if __name__ == '__main__':
    unittest.main()
