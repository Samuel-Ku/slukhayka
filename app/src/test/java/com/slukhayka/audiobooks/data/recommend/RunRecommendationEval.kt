package com.slukhayka.audiobooks.data.recommend

import ai.onnxruntime.OrtEnvironment
import com.slukhayka.audiobooks.data.collections.MiniJson
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.Properties
import kotlin.system.exitProcess

/** Real frozen catalog, actual ONNX backend, full-candidate production LOO, strict offline gate. */
object RunRecommendationEval {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "--acquire") {
            require(args.size == 2) { "Usage: --acquire snapshot-directory" }
            runBlocking { RecommendationFeedSnapshot.acquire(File(args[1])) }
            return
        }
        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "CONTEXT.md").isFile } ?: error("Run inside the repository")
        val options = linkedMapOf<String, String>()
        var index = 0
        var assets = File(root, "app/src/main/assets/models/e5")
        if (args.firstOrNull()?.startsWith("--") == false) { assets = File(args[0]); index++ }
        val allowed = setOf("--snapshot", "--cohorts", "--cache", "--report")
        while (index < args.size) {
            val flag = args[index++]
            require(flag in allowed && index < args.size && flag !in options) { "Unknown, duplicate or incomplete option: $flag" }
            options[flag] = args[index++]
        }
        fun path(flag: String, default: String) = options[flag]?.let(::File) ?: File(root, default)
        val snapshotDir = path("--snapshot", "docs/recommend/snapshots/librivox-2026-10-04")
        val registryFile = path("--cohorts", "docs/recommend/real-scale-cohorts.json")
        val reportFile = path("--report", "docs/recommend/EVAL-REPORT.md")
        val cacheDir = path("--cache", ".gradle/recommendation-eval-cache")
        val snapshot = RecommendationFeedSnapshot.load(snapshotDir)
        val records = RecommendationFeedSnapshot.records(snapshot)
        val catalog = RecommendationEvalCatalog.fromRecords(records)
        require(catalog.works.size >= 10_000 && catalog.distinctTitles >= 10_000) {
            "Real catalog gate requires >=10,000 Works and independently >=10,000 distinct titles"
        }
        val registry = MiniJson.parse(registryFile.readText()) as? Map<*, *> ?: error("Malformed registry")
        val k = (registry["k"] as? Number)?.toInt() ?: error("Registry k missing")
        require(k == 20) { "The preregistered gate uses K=20" }
        val cohorts = RecommendationEvalCohorts.load(registryFile, catalog)
        val manifest = File(snapshotDir, "manifest.properties")
        require(registry["frozenFeedManifestSha256"] == sha(manifest)) { "Labels belong to another snapshot" }
        val original = File(registryFile.parentFile, registry["originalRegistry"] as? String ?: error("Original registry absent"))
        require(original.canonicalFile.parentFile == registryFile.canonicalFile.parentFile)
        require(sha(original) == registry["originalRegistrySha256"]) { "Original preregistration was altered" }
        verifyAvailability(registry, original, records)
        val modelLock = File(root, "docs/recommend/real-scale-model.json")
        val modelMetadata = MiniJson.parse(modelLock.readText()) as? Map<*, *> ?: error("Model lock malformed")
        require(modelMetadata["revision"] == RecommendationEvalModelLock.REVISION &&
            modelMetadata["modelSha256"] == RecommendationEvalModelLock.MODEL_SHA256 &&
            modelMetadata["tokenizerSha256"] == RecommendationEvalModelLock.TOKENIZER_SHA256 &&
            modelMetadata["runtimeJarSha256"] == RecommendationEvalModelLock.RUNTIME_SHA256 &&
            (modelMetadata["dimension"] as? Number)?.toInt() == 384 && modelMetadata["onnxruntime"] == "1.21.0") {
            "Model manifest differs from the supported pinned backend"
        }
        val sourceDir = File(root, "app/src/main/java/com/slukhayka/audiobooks/data/recommend")
        val hostDir = File(root, "app/src/test/java/com/slukhayka/audiobooks/data/recommend")
        val backendHash = combinedHash(listOf(File(sourceDir, "OnnxEmbedder.kt"), File(sourceDir, "UnigramTokenizer.kt")))
        val identityFiles = listOf("data/merge/MergeKey.kt", "data/metadata/MetadataAssertions.kt", "data/LanguageCode.kt", "data/source/SourceParsing.kt", "data/collections/MiniJson.kt").map {
            File(root, "app/src/main/java/com/slukhayka/audiobooks/$it")
        }
        val protocolFiles = identityFiles + listOf("RecommendationEval.kt", "RecommendationEngine.kt", "RecommendationPersonalization.kt", "BookRecommendationText.kt", "TextEmbedder.kt").map { File(sourceDir, it) } +
            listOf("RecommendationEvalCatalog.kt", "RecommendationEvalCohorts.kt", "RecommendationEvalVectorCache.kt", "RecommendationFeedSnapshot.kt", "RecommendationEvalModelLock.kt", "RunRecommendationEval.kt").map { File(hostDir, it) }
        val runtimeJar = File(OrtEnvironment::class.java.protectionDomain.codeSource.location.toURI())
        RecommendationEvalModelLock.verifyRuntime(runtimeJar)
        val inputs = linkedMapOf(
            "schemaVersion" to "1", "feedManifestSha256" to sha(manifest),
            "cohortsSha256" to sha(registryFile), "originalCohortsSha256" to sha(original),
            "runtimeJarSha256" to sha(runtimeJar), "modelLockSha256" to sha(modelLock), "backendSourceSha256" to backendHash,
            "protocolSourceSha256" to combinedHash(protocolFiles),
            "candidateTextSha256" to textHash(catalog.works), "works" to catalog.works.size.toString(),
            "distinctTitles" to catalog.distinctTitles.toString(), "folds" to cohorts.sumOf { it.workIds.size }.toString(),
            "k" to k.toString(), "labelProvenance" to "expert bibliographic proxy; no listener completion history"
        )
        val freezeFile = File(reportFile.parentFile, "real-scale-inputs.properties")
        freeze(freezeFile, inputs)
        println("Frozen before inference: ${sha(freezeFile)}; ${catalog.works.size} Works; ${catalog.distinctTitles} titles; ${inputs["folds"]} folds")
        println("Relevance: expert bibliographic proxy, not listener completion logs")
        val model = File(assets, "model.onnx")
        val tokenizer = File(assets, "tokenizer.json")
        RecommendationEvalModelLock.verify(model, tokenizer)
        val semantic = OnnxEmbedder.fromFiles(model, tokenizer) ?: error("Pinned semantic ONNX backend failed to initialize; no fallback is allowed")
        val semanticIdentity = "onnx-e5:${RecommendationEvalModelLock.RUNTIME_SHA256}:${RecommendationEvalModelLock.MODEL_SHA256}:${RecommendationEvalModelLock.TOKENIZER_SHA256}:$backendHash"
        val semanticVectors = semantic.use {
            RecommendationEvalVectorCache.loadOrCompute(File(cacheDir, "semantic"), semanticIdentity, catalog.works, 384, it)
        }
        val baselineVectors = RecommendationEvalVectorCache.loadOrCompute(File(cacheDir, "baseline"),
            "keyword-512:${sha(File(sourceDir, "TextEmbedder.kt"))}", catalog.works, 512, KeywordEmbedder())
        val result = RecommendationEval.evaluateLeaveOneOut(cohorts.map { it.workIds }, catalog.works, semanticVectors, baselineVectors, k)
        writeEvidence(reportFile, freezeFile, snapshotDir, catalog, cohorts, result, k, cacheDir)
        println("semantic recall@$k=${fmt(result.report.semanticRecallAtK)} NDCG@$k=${fmt(result.report.semanticNdcgAtK)}")
        println("baseline recall@$k=${fmt(result.report.baselineRecallAtK)} NDCG@$k=${fmt(result.report.baselineNdcgAtK)}")
        println("GATE DECISION: ${if (result.passesGate) "GO" else "NO-GO"}; report=${reportFile.canonicalPath}")
        if (!result.passesGate) exitProcess(1)
    }

    private fun verifyAvailability(registry: Map<*, *>, originalFile: File, records: List<Map<*, *>>) {
        val original = MiniJson.parse(originalFile.readText()) as? Map<*, *> ?: error("Malformed original registry")
        fun labels(root: Map<*, *>): Set<String> = (root["cohorts"] as List<*>).flatMap { cohort ->
            ((cohort as Map<*, *>)["works"] as List<*>).map { work ->
                val w = work as Map<*, *>
                "${w["title"]}|${w["authorSurname"]}"
            }
        }.toSet()
        val current = labels(registry)
        val previous = labels(original)
        require(previous.containsAll(current)) { "A relevance label was substituted after preregistration" }
        val exclusions = (registry["availabilityExclusions"] as? List<*>).orEmpty().map { it as Map<*, *> }
        require(previous - current == exclusions.map { "${it["title"]}|${it["authorSurname"]}" }.toSet()) { "Unexplained label exclusion" }
        for (exclusion in exclusions) {
            val title = RecommendationEvalCatalog.normalizedTitle(exclusion["title"] as String)
            require(records.none { RecommendationEvalCatalog.normalizedTitle(it["title"].toString()) == title }) {
                "Availability exclusion is contradicted by the raw frozen feed"
            }
        }
        require(Instant.parse(registry["availabilityCheckedAt"].toString()).isBefore(Instant.now()))
    }

    private fun freeze(file: File, inputs: Map<String, String>) {
        file.parentFile.mkdirs()
        if (file.isFile) {
            val saved = Properties().apply { file.inputStream().use(::load) }
            require(inputs.all { (key, value) -> saved.getProperty(key) == value }) {
                "Frozen evaluation inputs changed. Investigate and explicitly preregister a new protocol before recomputing scores."
            }
        } else {
            val temporary = File(file.parentFile, file.name + ".partial")
            val props = Properties().apply { putAll(inputs); setProperty("frozenAt", Instant.now().toString()) }
            temporary.outputStream().use { props.store(it, "Frozen before any semantic inference or score") }
            require(temporary.renameTo(file))
        }
    }

    private fun writeEvidence(file: File, freeze: File, snapshot: File, catalog: RecommendationEvalCatalog.Catalog,
                              cohorts: List<RecommendationEvalCohorts.Cohort>, result: RecommendationEval.LeaveOneOutReport, k: Int, cache: File) {
        val folder = file.parentFile
        val foldsFile = File(folder, "real-scale-folds.tsv")
        foldsFile.bufferedWriter().use { out ->
            out.appendLine("cohort\theldOutWork\tcandidateCount\tsemanticRankAtK\tbaselineRankAtK\tsemanticTopK\tbaselineTopK")
            for (fold in result.folds) out.appendLine(listOf(cohorts[fold.cohortIndex].id, fold.heldOutId, fold.candidateCount,
                fold.semanticRank ?: "outside-top-$k", fold.baselineRank ?: "outside-top-$k",
                fold.semanticTopIds.joinToString(";"), fold.baselineTopIds.joinToString(";")).joinToString("\t") { clean(it.toString()) })
        }
        val catalogFile = File(folder, "real-scale-catalog.tsv")
        catalogFile.bufferedWriter().use { out ->
            out.appendLine("workId\ttitle\tauthor\tgenre\ttextSha256\tsourceUrls")
            for (work in catalog.works) out.appendLine(listOf(work.id, work.title, work.author, work.genre,
                digest(work.text.toByteArray()), catalog.sourceUrls.getValue(work.id).joinToString(";")).joinToString("\t", transform = ::clean))
        }
        val vectors = cache.walkTopDown().filter { it.isFile && it.extension == "vectors" }.sortedBy { it.path }.toList()
        val hashes = listOf(freeze, foldsFile, catalogFile) + vectors
        val hashesFile = File(folder, "real-scale-results.sha256")
        hashesFile.writeText(hashes.joinToString("\n") { "${sha(it)}  ${if (it in vectors) it.relativeTo(cache).path else it.name}" } + "\n")
        val r = result.report
        val decision = if (result.passesGate) "GO" else "NO-GO"
        file.writeText("""
            # Перевірка рекомендацій на реальному каталозі

            Рішення: **$decision**. Recall семантичної моделі має бути строго більшим за baseline, а NDCG — не нижчим. NO-GO завжди повертає ненульовий код завершення, локально й у CI, після запису звіту.

            | Модель | recall@$k | NDCG@$k |
            |---|---:|---:|
            | Справжня ONNX E5, 384 виміри | ${fmt(r.semanticRecallAtK)} | ${fmt(r.semanticNdcgAtK)} |
            | Production keyword baseline, 512 вимірів | ${fmt(r.baselineRecallAtK)} | ${fmt(r.baselineNdcgAtK)} |

            ${catalog.rawRecords} реальних карток Archive.org для LibriVox дали ${catalog.works.size} авторських Works і ${catalog.distinctTitles} різних нормалізованих назв. Відкинуто ${catalog.excludedRussian} російськомовних карток і ${catalog.missingIdentity} карток без відомої або однозначної авторської ідентичності. ${catalog.duplicateEditions} повторних записів об'єднано в Works. Це обмежене зіставлення метаданих, а не універсальний довідник ідентичності. 44 збережені відповіді джерела не є атомарним знімком усього світового каталогу.

            Мітки — **п'ять заздалегідь зафіксованих експертних бібліографічних груп**, ${result.folds.size} відкладені твори. Це офлайн-перевірка релевантності, **не журнал реальних завершень слухачів і не доказ користі під час живого користування**. Початкові 25 міток збережені. The Secret Adversary виключено лише через відсутність у замороженому фіді, до інференсу. Після результатів замін не було.

            Кожен fold навчає профіль на решті творів своєї групи. Єдина релевантна відповідь — відкладений твір. Змагається весь каталог, крім навчальних книг: ${result.folds.minOf { it.candidateCount }}–${result.folds.maxOf { it.candidateCount }} кандидатів. Обидві моделі отримують однакові production тексти, метадані та алгоритм ранжування, включно з diversity та exploration. Кожен Work обчислюється один раз на модель. Випадкових негативних кандидатів, наближеного пошуку та вибору за метриками немає. K=$k. Seed 42 залишений у реєстрі для походження; цей протокол не використовує випадкове семплювання. NDCG для однієї релевантної книги дорівнює 1/log2(rank+1); книга поза top-K дає нуль.

            Ревізія моделі: `${RecommendationEvalModelLock.REVISION}`. Контрольні суми моделі й токенізатора перевіряються до створення backend. Кожен вектор має точну розмірність, скінченні значення та одиничну норму. Підміна keyword-моделлю не може дати семантичний результат. Перевіряється наявний Kotlin токенізатор і ONNX шлях із префіксом passage; тотожність токенізатору Hugging Face не стверджується. JVM: Java ${System.getProperty("java.version")}; ONNX Runtime 1.21.0. SHA-256 JVM jar: `${RecommendationEvalModelLock.RUNTIME_SHA256}`, звірений із [Maven Central](https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime/1.21.0/onnxruntime-1.21.0.jar.sha256). Модель і локальні журнали векторів не закомічені.

            Відтворення після встановлення зафіксованих assets: `./gradlew runRecommendationEval --no-configuration-cache`. Наявний JavaExec використовує desktop ONNX backend. Перший запуск наповнює журнал; наступні перевіряють контрольні суми та повторно використовують його. Новий окремий фід можна зібрати через production бюджет: `./gradlew runRecommendationEval --args="--acquire /path/to/new-snapshot" --no-configuration-cache`. Це не замінює закомічені сторінки та мітки. Знімок: `${snapshot.name}`.

            Докази: [зафіксовані входи](real-scale-inputs.properties), [ранги й ID усіх top-K](real-scale-folds.tsv), [походження Works та суми текстів](real-scale-catalog.tsv), [суми результатів](real-scale-results.sha256), [модель](real-scale-model.json), [поточні мітки](real-scale-cohorts.json), [початкові мітки](real-scale-cohorts-original.json). Входи та код протоколу зафіксовані до інференсу. Історичний прогін на 140 книгах із 40 негативними кандидатами не доводить цей гейт; його замінює поточний протокол.

            Межа доказу: авторизованої реальної історії завершень немає. GO на експертній вибірці сам по собі не закриває вимогу про справжні завершення слухачів. Для неї потрібен окремо дозволений і заздалегідь зафіксований набір історій.
        """.trimIndent() + "\n")
    }

    private fun clean(value: String) = value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')
    private fun sha(file: File) = RecommendationFeedSnapshot.sha256(file)
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun combinedHash(files: List<File>): String = digest(files.sortedBy { it.name }.joinToString("\n") { "${it.name}:${sha(it)}" }.toByteArray())
    private fun textHash(works: List<RecommendationEngine.Candidate>): String {
        val hash = MessageDigest.getInstance("SHA-256")
        for (work in works.sortedBy { it.id }) for (value in listOf(work.id, work.text)) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); hash.update(bytes)
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    private fun fmt(value: Double) = String.format(Locale.ROOT, "%.6f", value)
}
