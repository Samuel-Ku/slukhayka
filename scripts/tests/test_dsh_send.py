import sys
import unittest
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from dsh_send import DeliveryError, send


def snapshot(text='', title='queue', posted=None):
    nodes = [{'resource-id': 'session-title', 'text': title},
             {'resource-id': 'composer-input', 'text': text},
             {'content-desc': 'Send', 'enabled': 'true'}]
    if posted is not None:
        nodes.append({'text': posted})
    return nodes


class Ui:
    def __init__(self, snapshots):
        self.snapshots = list(snapshots)
        self.position = 0
        self.taps = []
    def nodes(self):
        result = self.snapshots[min(self.position, len(self.snapshots) - 1)]
        self.position += 1
        return result
    def tap(self, node):
        self.taps.append(node)
    def type_text(self, message):
        pass


class DeliveryTest(unittest.TestCase):
    def test_corrupted_input_never_sent(self):
        ui = Ui([snapshot(), snapshot('changed')])
        with self.assertRaises(DeliveryError):
            send(ui, 'queue', 'hello')
        self.assertEqual(1, len(ui.taps))

    def test_wrong_session_never_touched(self):
        ui = Ui([snapshot(title='other')])
        with self.assertRaises(DeliveryError):
            send(ui, 'queue', 'hello')
        self.assertFalse(ui.taps)

    def test_existing_draft_preserved(self):
        ui = Ui([snapshot('owner draft')])
        with self.assertRaises(DeliveryError):
            send(ui, 'queue', 'hello')
        self.assertFalse(ui.taps)

    def test_delivery_does_not_claim_execution(self):
        ui = Ui([snapshot(), snapshot('hello'), snapshot(posted='hello')])
        result = send(ui, 'queue', 'hello')
        self.assertEqual('visible-in-history', result['delivery'])
        self.assertEqual('unconfirmed', result['execution'])
        self.assertEqual(2, len(ui.taps))

    def test_cleared_composer_alone_is_not_delivery(self):
        ui = Ui([snapshot(), snapshot('hello')] + [snapshot()] * 5)
        with patch('dsh_send.time.sleep'), self.assertRaises(DeliveryError):
            send(ui, 'queue', 'hello')
        self.assertEqual(2, len(ui.taps))

    def test_unsupported_text_never_touched(self):
        ui = Ui([])
        with self.assertRaises(DeliveryError):
            send(ui, 'queue', 'Привіт')
        self.assertFalse(ui.taps)

    def test_older_identical_message_is_not_a_delivery_receipt(self):
        ui = Ui([snapshot(posted='hello'), snapshot('hello', posted='hello')]
                + [snapshot(posted='hello')] * 5)
        with patch('dsh_send.time.sleep'), self.assertRaises(DeliveryError):
            send(ui, 'queue', 'hello')
        self.assertEqual(2, len(ui.taps))
