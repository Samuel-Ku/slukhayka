"""Worker pipeline tests use known vectors, not an inference-quality claim."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("recommend_graph", Path(__file__).parents[1] / "recommend/build_graph.py")
graph = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(graph)
try:
    import numpy as np
except ImportError:
    np = None


class CatalogTest(unittest.TestCase):
    def test_rejects_listener_fields_and_duplicate_keys(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "catalog.json"
            for rows in ([{"workKey": "a", "text": "a", "rating": 5}, {"workKey": "b", "text": "b"}],
                         [{"workKey": "a", "text": "a"}, {"workKey": "a", "text": "b"}]):
                path.write_text(json.dumps({"works": rows}))
                with self.assertRaises(ValueError):
                    graph.read_catalog(path)

    def test_atomic_write_replaces_whole_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "graph.json"
            graph.atomic_write(path, b"old")
            graph.atomic_write(path, b"new")
            self.assertEqual(b"new", path.read_bytes())
            self.assertEqual([path], list(Path(directory).iterdir()))

    def test_artifact_is_trimmed_to_fit_the_android_limit(self):
        graph_doc = {"schemaVersion": 1, "workKeys": ["a", "b"], "neighbors": [
            [[0, 0.9], [1, 0.8], [0, 0.7]] for _ in range(200)]}
        content, width = graph.fit_artifact(graph_doc, max_bytes=5_000)
        self.assertLessEqual(len(content), 5_000)
        self.assertLess(width, 3)
        self.assertTrue(all(len(row) <= width for row in graph_doc["neighbors"]))

    def test_rejects_private_root_fields_and_oversized_input(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "catalog.json"
            path.write_text(json.dumps({"works": [], "uid": "private"}))
            with self.assertRaisesRegex(ValueError, "only a public"):
                graph.read_catalog(path)
            path.write_bytes(b"x" * 32)
            with patch.object(graph, "MAX_CATALOG_BYTES", 16):
                with self.assertRaisesRegex(ValueError, "exceeds"):
                    graph.read_catalog(path)

    def test_offline_model_does_not_import_downloader_or_fall_back(self):
        import sys
        from unittest.mock import Mock, patch
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / "REVISION").write_text("a" * 40)
            for name in ("model.safetensors", "tokenizer.json", "config.json"):
                (path / name).touch()
            fake_model = Mock()
            with patch.dict(sys.modules, {"model2vec": fake_model, "huggingface_hub": None}), patch.dict(graph.os.environ):
                graph.load_model("a" * 40, path)
                fake_model.StaticModel.from_pretrained.assert_called_once_with(str(path), force_download=False)
                self.assertEqual("1", graph.os.environ["HF_HUB_OFFLINE"])
                with self.assertRaisesRegex(ValueError, "revision mismatch"):
                    graph.load_model("b" * 40, path)
                (path / "tokenizer.json").unlink()
                with self.assertRaisesRegex(ValueError, "incomplete"):
                    graph.load_model("a" * 40, path)


@unittest.skipIf(np is None, "install scripts/recommend/requirements.txt for numerical pipeline tests")
class PipelineTest(unittest.TestCase):
    def test_only_new_texts_embed_and_revision_invalidates_cache(self):
        calls = []
        class Model:
            def encode(self, texts):
                calls.extend(texts)
                return [[1, len(text)] for text in texts]
        with tempfile.TemporaryDirectory() as directory:
            db = Path(directory) / "cache.sqlite"
            catalog = [("a", "first"), ("b", "second")]
            _, computed = graph.cached_vectors(catalog, db, "model", "v1", Model)
            self.assertEqual(2, computed)
            def forbidden():
                self.fail("unchanged catalog must not load a model")
            _, computed = graph.cached_vectors(catalog, db, "model", "v1", forbidden)
            self.assertEqual(0, computed)
            _, computed = graph.cached_vectors([("a", "changed"), catalog[1]], db, "model", "v1", Model)
            self.assertEqual(1, computed)
            _, computed = graph.cached_vectors(catalog, db, "model", "v2", Model)
            self.assertEqual(2, computed)

    def test_top_neighbors_exclude_self_and_nonpositive_scores(self):
        catalog = [("a", "a"), ("b", "b"), ("c", "c")]
        matrix = np.array([[1., 0], [.8, .6], [-1, 0]], dtype=np.float32)
        result = graph.build_graph(catalog, matrix, "a" * 40, 1000, top_k=2)
        self.assertEqual([[1, .8]], result["neighbors"][0])
        self.assertEqual([[0, .8]], result["neighbors"][1])
        self.assertEqual([], result["neighbors"][2])
        self.assertEqual(7 * 86_400_000, result["expiresAt"] - result["generatedAt"])
        self.assertEqual(["a", "b", "c"], result["workKeys"])

    def test_android_fixture_matches_worker_wire_format(self):
        import math
        catalog = [("noise", "noise"), ("seed", "seed"), ("target", "target")]
        matrix = np.array([[-1., 0.], [1., 0.], [.9, math.sqrt(.19)]], dtype=np.float32)
        expected = graph.build_graph(catalog, matrix, "a" * 40, 1_000_000, 2)
        path = Path(__file__).parents[2] / "app/src/test/resources/recommend/content-graph.json"
        self.assertEqual(expected, json.loads(path.read_text()))

    def test_zero_model_vector_fails_instead_of_publishing(self):
        class Broken:
            def encode(self, texts):
                return [[0., 0.] for _ in texts]
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "zero model"):
                graph.cached_vectors([("a", "a"), ("b", "b")], Path(directory) / "cache", "m", "v", Broken)
