"""Static guardrails for the worker systemd unit.

These are invariants that a green PR once missed and only a real host run
exposed: an ignored wall-clock limit, a sandbox flag that breaks threaded
BLAS, and a partial model-path contract. The unit test is cheap and runs in
the existing "Script unit tests" job.
"""
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location(
    "recommend_unit_graph", Path(__file__).parents[1] / "recommend/build_graph.py")
graph = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(graph)

UNIT = Path(__file__).parents[1] / "recommend/systemd/slukhayka-content-build.service"


class UnitFileTest(unittest.TestCase):
    def setUp(self):
        self.lines = [
            line.strip() for line in UNIT.read_text().splitlines()
            if line.strip() and not line.strip().startswith("#")
        ]

    def values(self, key):
        prefix = key + "="
        return [line[len(prefix):].strip() for line in self.lines if line.startswith(prefix)]

    def test_oneshot_has_an_enforced_wall_limit(self):
        self.assertEqual(["oneshot"], self.values("Type"))
        self.assertEqual([], self.values("RuntimeMaxSec"), "RuntimeMaxSec is ignored for Type=oneshot")
        self.assertEqual(["30min"], self.values("TimeoutStartSec"))

    def test_every_required_model_file_is_a_precondition(self):
        conditions = set(self.values("ConditionPathExists"))
        self.assertIn(
            "/opt/slukhayka-content-model/REVISION", conditions)
        for name in graph.MODEL_FILES:
            self.assertIn(f"/opt/slukhayka-content-model/{name}", conditions)
        self.assertIn("/srv/slukhayka-public/catalog.json", conditions)

    def test_threaded_python_is_not_blocked_by_mdwe(self):
        self.assertEqual(
            ["no"], self.values("MemoryDenyWriteExecute"),
            "MDWE breaks pthread_create for OpenBLAS/numpy on this workload")

    def test_core_sandbox_directives_stay_in_force(self):
        expected = {
            "DynamicUser": "yes",
            "StateDirectory": "slukhayka-content",
            "StateDirectoryMode": "0700",
            "WorkingDirectory": "/opt/slukhayka-content-worker",
            "NoNewPrivileges": "yes",
            "CapabilityBoundingSet": "",
            "ProtectSystem": "strict",
            "ProtectHome": "yes",
            "PrivateNetwork": "yes",
            "RestrictAddressFamilies": "AF_UNIX",
            "IPAddressDeny": "any",
            "ProtectProc": "invisible",
            "ProcSubset": "pid",
            "RestrictNamespaces": "yes",
            "SystemCallFilter": "@system-service",
            "SystemCallErrorNumber": "EPERM",
            "TasksMax": "64",
            "MemoryMax": "4G",
            "CPUQuota": "200%",
        }
        for key, value in expected.items():
            self.assertEqual([value], self.values(key), key)

    def test_execstart_stays_offline_and_pinned(self):
        execstart = self.values("ExecStart")
        self.assertEqual(1, len(execstart))
        command = execstart[0]
        self.assertIn(" -I ", command)
        self.assertIn("--catalog /srv/slukhayka-public/catalog.json", command)
        self.assertIn("--model-dir /opt/slukhayka-content-model", command)
        self.assertIn("--revision 73908c3438cf03b6a01bcb9611d62b23d0726f08", command)
        self.assertIn("--output /var/lib/slukhayka-content/graph.json", command)
        self.assertIn("HF_HUB_OFFLINE=1", " ".join(self.values("Environment")))


if __name__ == "__main__":
    unittest.main()
