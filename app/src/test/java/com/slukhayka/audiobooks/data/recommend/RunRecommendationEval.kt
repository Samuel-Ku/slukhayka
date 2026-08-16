package com.slukhayka.audiobooks.data.recommend

import java.io.File
import kotlin.system.exitProcess

/**
 * The reproducible spec-19 T3 eval gate (US11 / Q6): leave-one-out on saved
 * fixtures, real bundled ONNX embedder vs the genre+author keyword baseline.
 * Prints recall@20 / NDCG@20 for both sides and an explicit GO / NO-GO
 * decision, writing the report to `docs/recommend/EVAL-REPORT.md`.
 *
 * Run via `./gradlew runRecommendationEval` (JavaExec on the host JVM with
 * the desktop onnxruntime jar — see app/build.gradle.kts). Arguments:
 *   [0] assets dir containing model.onnx + tokenizer.json (default
 *       app/src/main/assets/models/e5)
 *   [1] distractor count per fold (default 40)
 *   [2] k cutoff (default 20)
 *
 * A NO-GO (semantic fails to beat the baseline) is a legitimate outcome —
 * the finding is reported with the numbers and the UI ticket (#120) simply
 * does not start; it never blocks the T1/T2 outputs. In CI the workflow sets
 * `FAIL_ON_NO_GO=true` so a NO-GO exits non-zero and fails the gate step;
 * local runs keep the report-only behaviour.
 */
object RunRecommendationEval {

    private val CATALOG_RESOURCE = "/recommend/eval-catalog.tsv"

    /** The listener's finished shelf — a realistic, topically coherent set. */
    private val COMPLETIONS = listOf(
        "lisova_pisnya",        // Леся Українка — mythic
        "tini_zabutyh_predkiv", // Коцюбинський — mythic Hutsul
        "mavka",                // Шкляр — mythic creature
        "storinka_temryavy",    // Дереш — mystic
        "legendy_starokyivski", // Королева — old-Kyiv legends
        "himerove_gnizdo",      // Дрозд — fantastical
        "lisovy_tsar"           // Винничук — forest myth
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val assetsDir = File(args.getOrElse(0) { "app/src/main/assets/models/e5" })
        val distractorCount = args.getOrNull(1)?.toIntOrNull() ?: 40
        val k = args.getOrNull(2)?.toIntOrNull() ?: 20

        val model = File(assetsDir, "model.onnx")
        val tokenizer = File(assetsDir, "tokenizer.json")
        require(model.exists()) {
            "model.onnx not found at $model — run the downloadE5Model Gradle task first"
        }
        require(tokenizer.exists()) { "tokenizer.json not found at $tokenizer" }

        val catalog = loadCatalog()
        val candidates = catalog.mapValues { (_, book) -> book.text }
        val semantic = OnnxEmbedder.fromFiles(model, tokenizer)
            ?: error("failed to load the ONNX embedder from $model")
        val baseline = KeywordEmbedder()

        // Production-path sanity (the CI gate's first line of defence): the
        // embedder must emit real L2-normalized vectors. A degenerate output
        // (zero mean-pooling or a broken tokenizer) would silently zero every
        // score and could fake a GO on a broken model — fail fast with a
        // clear message instead.
        val probe = semantic.embed("Кобзар Тарас Шевченко")
        val probeNorm = kotlin.math.sqrt(probe.sumOf { (it * it).toDouble() })
        require(probe.isNotEmpty() && probeNorm > 0.9) {
            "embedder produced a degenerate vector (dim=${probe.size}, norm=$probeNorm) — model or tokenizer broken"
        }
        println("embedder sanity: dim=${probe.size}, l2norm=${fmt(probeNorm)}")

        println("=== spec-19 T3 eval gate ===")
        println("fixtures: ${catalog.size} catalogue books, ${COMPLETIONS.size} completions")
        println("pool: $distractorCount distractors/fold, k=$k, seed=42")
        println()

        val report = RecommendationEval.evaluate(
            completions = COMPLETIONS,
            candidates = candidates,
            semanticEmbedder = semantic,
            baselineEmbedder = baseline,
            distractorCount = distractorCount,
            k = k,
            seed = 42L
        )

        semantic.close()

        println("semantic (ONNX e5-small): recall@$k = ${fmt(report.semanticRecallAtK)}  ndcg@$k = ${fmt(report.semanticNdcgAtK)}")
        println("baseline (genre+author):  recall@$k = ${fmt(report.baselineRecallAtK)}  ndcg@$k = ${fmt(report.baselineNdcgAtK)}")
        println()
        val decision = if (report.semanticWins) "GO" else "NO-GO"
        println("GATE DECISION: $decision")
        if (report.semanticWins) {
            println("The semantic row beats the genre+author baseline — the UI ticket (#120) may start.")
        } else {
            println("The semantic row does NOT beat the baseline — #120 waits; finding reported with numbers.")
        }
        // CI gate: with FAIL_ON_NO_GO=true the run exits non-zero on NO-GO so
        // the workflow step is a real gate, not a print. Local runs keep the
        // informative report-only behaviour (the finding never blocks T1/T2
        // outputs by construction).
        if (!report.semanticWins && System.getenv("FAIL_ON_NO_GO") == "true") {
            System.err.println("GATE FAILED: NO-GO — semantic ranking does not beat the baseline (CI gate, FAIL_ON_NO_GO=true).")
            exitProcess(1)
        }

        writeReport(report, decision, catalog.size, distractorCount, k)
        println()
        println("report: docs/recommend/EVAL-REPORT.md")
    }

    private fun loadCatalog(): Map<String, CatalogBook> {
        val stream = RunRecommendationEval::class.java.getResourceAsStream(CATALOG_RESOURCE)
            ?: error("fixture $CATALOG_RESOURCE not on the classpath")
        val result = LinkedHashMap<String, CatalogBook>()
        stream.bufferedReader().forEachLine { line ->
            if (line.isBlank() || line.startsWith("id\t")) return@forEachLine
            val parts = line.split('\t')
            if (parts.size >= 4) {
                result[parts[0]] = CatalogBook(parts[1], parts[2], parts[3])
            }
        }
        return result
    }

    private class CatalogBook(val title: String, val author: String, val genre: String) {
        /** Same text the engine embeds: title + author + genre (Q3). */
        val text: String get() = "$title $author $genre"
    }

    private fun fmt(value: Double): String = "%.4f".format(value)

    private fun writeReport(
        report: RecommendationEval.Report,
        decision: String,
        catalogSize: Int,
        distractorCount: Int,
        k: Int
    ) {
        // JavaExec runs with cwd = the app module dir; the report lives at
        // the repo root docs/.
        val file = File("../docs/recommend/EVAL-REPORT.md")
        file.parentFile.mkdirs()
        val content = """
            # spec-19 T3 — Recommendation eval gate

            **Date:** ${java.time.LocalDate.now()}
            **Decision:** $decision
            **Model:** multilingual-e5-small (384-dim, int8 ONNX, mean-pooled, L2-normalized)
            **Method:** seeded (42) leave-one-out over the listener's completed shelf;
            each fold ranks a pool of the other completions + $distractorCount distractors
            from a $catalogSize-book catalogue; recall@$k and NDCG@$k.

            | Embedder | recall@$k | ndcg@$k |
            |---|---|---|
            | semantic (ONNX e5-small) | ${fmt(report.semanticRecallAtK)} | ${fmt(report.semanticNdcgAtK)} |
            | baseline (genre+author) | ${fmt(report.baselineRecallAtK)} | ${fmt(report.baselineNdcgAtK)} |

            $decision: the semantic ranking ${if (report.semanticWins) "beats" else "does not beat"} the
            genre+author baseline. ${if (report.semanticWins) "The UI ticket (#120) may start." else "The UI ticket (#120) waits; the finding is reported with the numbers."}

            **Reproduce:** `./gradlew runRecommendationEval` (the ONNX model is fetched by
            `downloadE5Model` — it is not committed; see app/build.gradle.kts).
        """.trimIndent() + "\n"
        file.writeText(content)
    }
}
