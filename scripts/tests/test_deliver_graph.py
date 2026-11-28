"""Tests for the separate graph delivery process (sign + stage, never the model)."""
import base64
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "deliver_graph", Path(__file__).parents[1] / "recommend/deliver_graph.py")
delivery = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(delivery)

OPENSSL = shutil.which("openssl")


def graph_bytes(**overrides):
    payload = {
        "schemaVersion": 1,
        "model": "minishlab/potion-multilingual-128M",
        "revision": "a" * 40,
        "catalogSha256": "b" * 64,
        "generatedAt": 1_000_000,
        "expiresAt": 604_800_000,
        "workKeys": ["a", "b"],
        "neighbors": [[[1, 0.5]], [[0, 0.5]]],
    }
    payload.update(overrides)
    return json.dumps(payload, separators=(",", ":")).encode()


@unittest.skipIf(OPENSSL is None, "openssl is required for graph signing tests")
class DeliveryTest(unittest.TestCase):
    def setUp(self):
        self.directory = Path(tempfile.mkdtemp())
        self.key = self.directory / "graph-key.pem"
        self.public = self.directory / "graph-key.pub.pem"
        subprocess.run([OPENSSL, "ecparam", "-name", "prime256v1", "-genkey", "-noout",
                        "-out", str(self.key)], check=True, capture_output=True)
        subprocess.run([OPENSSL, "ec", "-in", str(self.key), "-pubout",
                        "-out", str(self.public)], check=True, capture_output=True)
        self.graph = self.directory / "graph.json"
        self.graph.write_bytes(graph_bytes())
        self.output = self.directory / "store"

    def tearDown(self):
        shutil.rmtree(self.directory, ignore_errors=True)

    def test_delivers_exact_bytes_and_a_verifiable_signature(self):
        result = delivery.deliver(self.graph, self.key, self.public, self.output)
        delivered = Path(result["graph"]).read_bytes()
        envelope = json.loads(delivered)
        captured = self.directory / "captured.json"
        captured.write_bytes(envelope["graph"].encode("utf-8"))
        self.assertEqual(self.graph.read_bytes(), captured.read_bytes())
        signature = base64.b64decode(envelope["signature"])
        self.assertTrue(delivery.verify(captured, self.public, signature))
        self.assertEqual(len(delivered), result["bytes"])
        self.assertEqual(1, envelope["formatVersion"])

    def test_failed_atomic_replace_preserves_the_previous_complete_envelope(self):
        delivery.deliver(self.graph, self.key, self.public, self.output)
        old = (self.output / "graph.json").read_bytes()
        self.graph.write_bytes(graph_bytes(workKeys=["a", "new"]))
        with patch.object(delivery.os, "replace", side_effect=OSError("interrupted")):
            with self.assertRaises(OSError):
                delivery.deliver(self.graph, self.key, self.public, self.output)
        self.assertEqual(old, (self.output / "graph.json").read_bytes())
        self.assertEqual(["graph.json"], [p.name for p in self.output.iterdir()])

    def test_signing_uses_the_captured_bytes_even_if_worker_input_changes(self):
        original = self.graph.read_bytes()
        real_sign = delivery.sign
        def changing_input(snapshot, key):
            self.graph.write_bytes(graph_bytes(workKeys=["a", "changed"]))
            return real_sign(snapshot, key)
        with patch.object(delivery, "sign", side_effect=changing_input):
            result = delivery.deliver(self.graph, self.key, self.public, self.output)
        envelope = json.loads(Path(result["graph"]).read_bytes())
        self.assertEqual(original, envelope["graph"].encode())
        captured = self.directory / "captured.json"
        captured.write_bytes(envelope["graph"].encode())
        self.assertTrue(delivery.verify(captured, self.public, base64.b64decode(envelope["signature"])))

    def test_delivery_cannot_overwrite_the_raw_worker_input(self):
        original = self.graph.read_bytes()
        with self.assertRaisesRegex(ValueError, "separate output"):
            delivery.deliver(self.graph, self.key, self.public, self.graph.parent)
        self.assertEqual(original, self.graph.read_bytes())

    def test_rejects_a_tampered_graph_and_a_tampered_signature(self):
        signature = delivery.sign(self.graph, self.key)
        self.graph.write_bytes(graph_bytes(workKeys=["a", "c"]))
        self.assertFalse(delivery.verify(self.graph, self.public, signature))
        self.graph.write_bytes(graph_bytes())
        self.assertFalse(delivery.verify(self.graph, self.public, signature[:-8] + b"00000000"))

    def test_refuses_a_public_key_from_another_keypair(self):
        other = self.directory / "other.pem"
        other_public = self.directory / "other.pub.pem"
        subprocess.run([OPENSSL, "ecparam", "-name", "prime256v1", "-genkey", "-noout",
                        "-out", str(other)], check=True, capture_output=True)
        subprocess.run([OPENSSL, "ec", "-in", str(other), "-pubout",
                        "-out", str(other_public)], check=True, capture_output=True)
        with self.assertRaisesRegex(ValueError, "does not verify"):
            delivery.deliver(self.graph, self.key, other_public, self.output)
        self.assertFalse((self.output / "graph.json").exists())

    def test_rejects_invalid_graph_before_signing(self):
        self.graph.write_bytes(b"{}")
        with self.assertRaisesRegex(ValueError, "schemaVersion"):
            delivery.deliver(self.graph, self.key, self.public, self.output)
        self.assertFalse((self.output / "graph.json").exists())

    def test_rejects_oversized_graph_without_reading_it_into_the_store(self):
        self.graph.write_bytes(b"x" * (delivery.MAX_BYTES + 1))
        with self.assertRaisesRegex(ValueError, "8 MiB"):
            delivery.deliver(self.graph, self.key, self.public, self.output)


if __name__ == "__main__":
    unittest.main()
