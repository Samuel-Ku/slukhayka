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
        val allowed = setOf("--snapshot", "--cohorts", "--cache", "--report", "--identities")
        while (index < args.size) {
            val flag = args[index++]
            if (flag == "--prepare") {
                require(flag !in options) { "Duplicate --prepare" }
                options[flag] = "true"
                continue
            }
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
        val identitiesFile = path("--identities", "docs/recommend/real-scale-identity-aliases.json")
        val identities = RecommendationEvalIdentityAliases.load(identitiesFile, records)
        val identityMetadata = MiniJson.parse(identitiesFile.readText()) as? Map<*, *> ?: error("Malformed identity manifest")
        require(identityMetadata["cohortsSha256"] == sha(registryFile) &&
            identityMetadata["feedManifestSha256"] == sha(File(snapshotDir, "manifest.properties"))) {
            "Identity audit belongs to different frozen source or relevance labels"
        }
        require(Instant.parse(identityMetadata["correctedAt"].toString()).isBefore(Instant.now()))
        val catalog = RecommendationEvalCatalog.fromRecords(records, identities)
        require(catalog.works.size >= 10_000 && catalog.distinctTitles >= 10_000) {
            "Real catalog gate requires >=10,000 Works and independently >=10,000 distinct titles"
        }
        val registry = MiniJson.parse(registryFile.readText()) as? Map<*, *> ?: error("Malformed registry")
        val k = (registry["k"] as? Number)?.toInt() ?: error("Registry k missing")
        require(k == 20) { "The preregistered gate uses K=20" }
        val registeredWorks = (registry["cohorts"] as List<*>).flatMap { ((it as Map<*, *>)["works"] as List<*>) }
            .map { (it as Map<*, *>).let { work -> work["title"].toString() to work["authorSurname"].toString() } }
        require(registeredWorks.size == 24 && identities.works.map { it.title to it.authorSurname }.toSet() == registeredWorks.toSet()) {
            "The identity audit must cover exactly all 24 preregistered relevance Works"
        }
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
        val tokenizerFiles = listOf("UnigramTokenizer.kt", "UnigramTextNormalizer.kt", "UnigramPreTokenizer.kt", "UnicodeGraphemes.kt", "UnicodeGraphemeTables.kt").map { File(sourceDir, it) }
        val backendHash = combinedHash(tokenizerFiles + listOf("OnnxEmbedder.kt", "E5RecommendationInput.kt").map { File(sourceDir, it) })
        val identityFiles = listOf("data/merge/MergeKey.kt", "data/metadata/MetadataAssertions.kt", "data/LanguageCode.kt", "data/source/SourceParsing.kt", "data/collections/MiniJson.kt").map {
            File(root, "app/src/main/java/com/slukhayka/audiobooks/$it")
        }
        val protocolFiles = identityFiles + tokenizerFiles + listOf("RecommendationEval.kt", "RecommendationEngine.kt", "RecommendationPersonalization.kt", "BookRecommendationText.kt", "TextEmbedder.kt", "E5RecommendationInput.kt", "EmbeddingPassGate.kt", "EmbeddingPassSnapshot.kt", "RoomEmbeddingCache.kt", "CatalogEmbeddingService.kt").map { File(sourceDir, it) } +
            listOf("RecommendationEvalCatalog.kt", "RecommendationEvalIdentityAliases.kt", "RecommendationEvalCohorts.kt", "RecommendationEvalVectorCache.kt", "RecommendationFeedSnapshot.kt", "RecommendationEvalModelLock.kt", "RunRecommendationEval.kt").map { File(hostDir, it) }
        val runtimeJar = File(OrtEnvironment::class.java.protectionDomain.codeSource.location.toURI())
        RecommendationEvalModelLock.verifyRuntime(runtimeJar)
        val semanticContext = E5RecommendationInput.cacheContext(
            RecommendationEvalModelLock.MODEL_SHA256, RecommendationEvalModelLock.TOKENIZER_SHA256,
            OrtEnvironment.getEnvironment().version
        )
        val inputs = linkedMapOf(
            "schemaVersion" to "3", "feedManifestSha256" to sha(manifest),
            "cohortsSha256" to sha(registryFile), "originalCohortsSha256" to sha(original),
            "identityManifestSha256" to sha(identitiesFile),
            "previousAttemptInputsSha256" to sha(File(reportFile.parentFile, "real-scale-inputs-pre-dedup-review.properties")),
            "runtimeJarSha256" to sha(runtimeJar), "modelLockSha256" to sha(modelLock), "backendSourceSha256" to backendHash,
            "protocolSourceSha256" to combinedHash(protocolFiles),
            "preprocessingContract" to "e5-input-v3-hf022-unicode16-template-mean-l2; parsed HF charsmap/Metaspace/raw added tokens; query: ; BOS+EOS within 512 tokens",
            "preprocessingPrimarySource" to "https://huggingface.co/intfloat/multilingual-e5-small/raw/main/README.md",
            "tokenizerPrimarySource" to "https://github.com/huggingface/tokenizers/tree/4630f94378998f68df3d021c61a7340b813e264b",
            "tokenizerReferenceVersion" to "HF tokenizers 0.22.0; spm_precompiled 0.1.4; unicode-segmentation 1.12.0 / Unicode 16.0.0",
            "semanticEmbeddingContext" to semanticContext.identity,
            "candidateTextSha256" to textHash(catalog.works), "works" to catalog.works.size.toString(),
            "distinctTitles" to catalog.distinctTitles.toString(), "folds" to cohorts.sumOf { it.workIds.size }.toString(),
            "k" to k.toString(), "labelProvenance" to "expert bibliographic proxy; no listener completion history"
        )
        val freezeFile = File(reportFile.parentFile, "real-scale-inputs.properties")
        freeze(freezeFile, inputs)
        println("Frozen before inference: ${sha(freezeFile)}; ${catalog.works.size} Works; ${catalog.distinctTitles} titles; ${inputs["folds"]} folds")
        println("Relevance: expert bibliographic proxy, not listener completion logs")
        println("Identity audit: ${identities.works.size} Works, ${identities.works.sumOf { it.recordIds.size }} source records, SHA=${sha(identitiesFile)}")
        if (options["--prepare"] == "true") return
        val model = File(assets, "model.onnx")
        val tokenizer = File(assets, "tokenizer.json")
        RecommendationEvalModelLock.verify(model, tokenizer)
        val semantic = OnnxEmbedder.fromFiles(model, tokenizer) ?: error("Pinned semantic ONNX backend failed to initialize; no fallback is allowed")
        require(semantic.cacheContext == semanticContext) { "Actual semantic backend differs from preregistered preprocessing" }
        val semanticIdentity = "${semanticContext.identity}:${RecommendationEvalModelLock.RUNTIME_SHA256}:$backendHash"
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
            temporary.outputStream().use { props.store(it, "Frozen before renewed inference and any fold score; prior partial attempt archived") }
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

            Перший прогін обчислив частину векторів і був зупинений із exit 130 до будь-якого fold або метрики. Незалежна перевірка знайшла дублікати одного Work у різних записах. [Старі входи](real-scale-inputs-pre-dedup-review.properties) збережені без змін. Потім до нових оцінок перевірено всі 24 цільові твори: [окремий реєстр тотожності](real-scale-identity-aliases.json) зводить 112 записів, зокрема 22 переклади, зі звіркою exact title, creator та SHA-256 опису. Усі URL збережені; окремі продовження, перекази, п'єси та збірки залишаються окремими Works. Текст представника взятий із справжньої картки, обраної за початковим правилом міток, однаково для обох моделей. Міток, груп і порогів не змінено. Новий протокол і входи зафіксовані до повторного інференсу.

            Мітки — **п'ять заздалегідь зафіксованих експертних бібліографічних груп**, ${result.folds.size} відкладені твори. Це офлайн-перевірка релевантності, **не журнал реальних завершень слухачів і не доказ користі під час живого користування**. Початкові 25 міток збережені. The Secret Adversary виключено лише через відсутність у замороженому фіді, до інференсу. Після результатів замін не було.

            Кожен fold навчає профіль на решті творів своєї групи. Єдина релевантна відповідь — відкладений твір. Змагається весь каталог, крім навчальних книг: ${result.folds.minOf { it.candidateCount }}–${result.folds.maxOf { it.candidateCount }} кандидатів. Обидві моделі отримують однакові production тексти, метадані та алгоритм ранжування, включно з diversity та exploration. У поточному контексті кожен Work обчислюється один раз на модель. Часткові вектори скасованого попереднього контексту не використовуються для нових оцінок. Випадкових негативних кандидатів, наближеного пошуку та вибору за метриками немає. K=$k. Seed 42 залишений у реєстрі для походження; цей протокол не використовує випадкове семплювання. NDCG для однієї релевантної книги дорівнює 1/log2(rank+1); книга поза top-K дає нуль.

            Ревізія моделі: `${RecommendationEvalModelLock.REVISION}`. Контрольні суми моделі й токенізатора перевіряються до створення backend. Кожен вектор має точну розмірність, скінченні значення та одиничну норму. Підміна keyword-моделлю не може дати семантичний результат. Перевіряється production Kotlin токенізатор і ONNX шлях v3: query: для симетричного порівняння книг, TemplateProcessing BOS/EOS 0/2 із зафіксованого tokenizer.json, максимум 512 токенів разом із межами за [офіційною E5 model card](https://huggingface.co/intfloat/multilingual-e5-small/raw/main/README.md). Tokenizer виконує оголошений HF 0.22.0 Unigram, Precompiled charsmap, Metaspace та raw added-token контракт із зафіксованими Unicode 16 grapheme tables. [Походження й перевірки](tokenizer-provenance.md) відділені від якості рекомендацій: 15 початкових і 34 незалежні тексти дали точний збіг усіх 98 raw/model порівнянь; production segmenter пройшов усі 1 093 офіційні Unicode випадки. Це сумісність перевірених входів, а не універсальний доказ будь-якої конфігурації HF. Context кешу включає суми фактичних assets, runtime та препроцесор; вектори v3 не змішуються з v1, v2 чи keyword. JVM: Java ${System.getProperty("java.version")}; ONNX Runtime 1.21.0. SHA-256 JVM jar: `${RecommendationEvalModelLock.RUNTIME_SHA256}`, звірений із [Maven Central](https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime/1.21.0/onnxruntime-1.21.0.jar.sha256). Модель і локальні журнали векторів не закомічені.

            Відтворення після встановлення зафіксованих assets: `./gradlew runRecommendationEval --no-configuration-cache`. Наявний JavaExec використовує desktop ONNX backend. Перший запуск наповнює журнал; наступні перевіряють контрольні суми та повторно використовують його. Новий окремий фід можна зібрати через production бюджет: `./gradlew runRecommendationEval --args="--acquire /path/to/new-snapshot" --no-configuration-cache`. Це не замінює закомічені сторінки та мітки. Знімок: `${snapshot.name}`.

            Докази: [зафіксовані входи](real-scale-inputs.properties), [ранги й ID усіх top-K](real-scale-folds.tsv), [походження Works та суми текстів](real-scale-catalog.tsv), [суми результатів](real-scale-results.sha256), [модель](real-scale-model.json), [поточні мітки](real-scale-cohorts.json), [початкові мітки](real-scale-cohorts-original.json), [реєстр тотожності](real-scale-identity-aliases.json). Мітки зафіксовані до першого інференсу; виправлений реєстр тотожності, поточні входи та код протоколу — до повторного. Суми локальних журналів включають збережені попередні контексти; поточні оцінки використовують тільки контекст із повною перевіркою поточних текстів і моделі. Історичний прогін на 140 книгах із 40 негативними кандидатами не доводить цей гейт; його замінює поточний протокол.

            Межа доказу: користувач дозволив довільну бібліографічну вибірку. Гейт оцінює ці незмінні експертні мітки; відсутність особистого журналу не є окремою вимогою цього експерименту. Навіть GO не доводить користь під час живого користування. Повний [v1](experiments/2026-10-04-production-tokenizer-v1/README.md) та повний [v2](experiments/2026-10-04-e5-model-input-v2/README.md) збережено без змін, разом із їхніми NO-GO. v3 виправляє обґрунтований tokenizer контракт за незалежними текстами поза relevance cohorts; модель, labels, candidate texts, ranking weights і пороги незмінні. Tokenizer parity не визначає рішення quality gate.
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
