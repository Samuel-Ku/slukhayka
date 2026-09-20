package com.slukhayka.audiobooks.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #997 — a drawable is checked against its own extension, byte for byte.
 *
 * The symptom, as filed: `drawable/img_neuromancer_cover_1785247475170.jpg`
 * holds 492 267 `U+FFFD` replacement characters instead of a JPEG, so every
 * decoder rejects it and `BookCoverImage` paints the typographic fallback
 * (title + author) straight over the page content. The bytes are the same at
 * `HEAD`, so this is a committed artifact, not a local corruption.
 *
 * The audit behind this guard found the defect is not alone: all three JPGs in
 * `drawable/` are the same mojibake — a binary that was decoded as UTF-8 text
 * and re-encoded, which destroys every byte `>= 0x80` as one `U+FFFD`
 * (`EF BF BD`). None of the three contains so much as one `FF D8` pair. The
 * two extra files are currently unused by `R.drawable` (see the `UnusedResources`
 * entries in `app/lint-baseline.xml`), but "unused today" is not a format, and
 * they would fail exactly the same way the day they are wired in.
 *
 * This guard does NOT fix or replace any of that art — that needs real artwork
 * and is the owner's call (#997 says so). It does two things:
 *
 *  1. pins the set of files whose extension lies about their bytes, so the set
 *     can neither grow by accident nor shrink without anyone noticing; and
 *  2. checks every OTHER file in every `drawable*` directory against its
 *     extension's magic bytes, so a fourth broken file cannot be committed
 *     quietly.
 *
 * The split into two tests is deliberate and follows
 * [InstrumentedCoverageGuardTest]: one test says "the rest of the tree is
 * clean", the other says "the debt is exactly this list". A single test that
 * merely excluded the known offenders would pass forever on `main` and would
 * also hide the good news — the day the owner regenerates the art, the debt
 * list must shrink, and that edit has to be as visible as an addition.
 *
 * Like [SnapshotGoldenBytesGuardTest] and
 * [com.slukhayka.audiobooks.ui.HardcodedColorGuardTest], this is a disk read on
 * the JVM: no device and no Android runtime. It deliberately names no
 * screenshot-testing library, so `scripts/test_partitions.py` files it under
 * `pure-jvm` rather than under a driven partition.
 *
 * Scope note: only `drawable*` directories are scanned, and only the
 * extensions named in [EXPECTED_MAGIC]. A `.9.png` is an ordinary PNG for this
 * purpose (the `.9` lives in the name, not the extension), and an unknown
 * extension is left alone rather than guessed at.
 */
class DrawableMagicBytesGuardTest {

    private val resRoot: File by lazy {
        // Gradle resolves the module dir, but the other guards in this package
        // accept the repo root too, so the scan never depends on the cwd.
        listOf(
            File(System.getProperty("user.dir"), "src/main/res"),
            File(System.getProperty("user.dir"), "app/src/main/res")
        ).firstOrNull { it.isDirectory }
            ?: error("res root not found from ${System.getProperty("user.dir")}")
    }

    private val drawableDirs: List<File> by lazy {
        val dirs = resRoot
            .listFiles { file -> file.isDirectory && file.name.startsWith(DRAWABLE_PREFIX) }
            ?.sortedBy { it.name }
            .orEmpty()
        check(dirs.isNotEmpty()) {
            "no $DRAWABLE_PREFIX* directories under $resRoot — this guard would otherwise pass vacuously"
        }
        dirs
    }

    private val drawableFiles: List<DrawableFile> by lazy {
        drawableDirs.flatMap { dir ->
            dir.listFiles { file -> file.isFile }
                ?.sortedBy { it.name }
                ?.map { file -> inspect(dir, file) }
                .orEmpty()
        }
    }

    /**
     * The tree minus the recorded #997 debt. This is where a NEW offender
     * fails: exactly the recorded files may lie about their format, nothing
     * else.
     */
    @Test
    fun `every drawable outside the recorded 997 debt matches its extension`() {
        val offenders = drawableFiles
            .filterNot { it.isRecordedDebt }
            .filterNot { it.isValid }

        assertTrue(
            buildString {
                appendLine("these files declare a format their magic bytes contradict:")
                offenders.forEach {
                    appendLine("  ${it.relative}  [.${it.extension} -> ${it.expectedLabel}]")
                    appendLine("      ${it.detail}")
                }
                appendLine()
                appendLine("Scanned: ${drawableFiles.size} files in ${drawableDirs.joinToString { it.name }}.")
                appendLine("A file whose extension lies will not decode, and the UI silently falls back")
                appendLine("to the typographic cover (#997): the reader sees two texts on the same rows.")
                appendLine("Regenerate the asset as real art of the declared format. Do not paper over it")
                appendLine("by renaming the file to a matching extension — the content would still be broken.")
                appendLine("If this is genuinely impossible right now, add it to $DEBT_PROPERTY in this file")
                appendLine("with the reason and the owner who owns the fix — never silently.")
            },
            offenders.isEmpty()
        )
    }

    /**
     * The debt itself, frozen. Growth and shrinkage are both failures here, so
     * neither can happen quietly: one means the defect spread, the other means
     * the art was fixed and the list is now stale.
     */
    @Test
    fun `the recorded 997 debt is exactly the set of drawables that lie about their format`() {
        val actual = drawableFiles.filterNot { it.isValid }.map { it.relative }.toSet()
        val recorded = KNOWN_BROKEN.map { it.relative }.toSet()

        val newlyBroken = (actual - recorded).sorted()
        val noLongerBroken = (recorded - actual).sorted()

        assertTrue(
            buildString {
                if (newlyBroken.isNotEmpty()) {
                    appendLine("NEW files in drawable* do not match their extension — the #997 defect spread:")
                    newlyBroken.forEach { appendLine("  $it") }
                    appendLine("  Fix the bytes (real art), or record it in $DEBT_PROPERTY with a reason")
                    appendLine("  and the owner — the point of the list is that it cannot grow silently.")
                    appendLine()
                }
                if (noLongerBroken.isNotEmpty()) {
                    appendLine("these recorded #997 offenders are NO LONGER broken:")
                    noLongerBroken.forEach { appendLine("  $it") }
                    appendLine("  That is the fix we wanted — but the list must shrink in the SAME commit,")
                    appendLine("  otherwise the debt silently outlives the defect and the next reader")
                    appendLine("  cannot tell fixed files from broken ones. Delete the entry (or entries)")
                    appendLine("  from $DEBT_PROPERTY and say which asset was regenerated.")
                    appendLine()
                }
                appendLine("recorded: ${recorded.sorted()}")
                appendLine("actual:   ${actual.sorted()}")
            },
            newlyBroken.isEmpty() && noLongerBroken.isEmpty()
        )
    }

    /** Every entry has to carry a reason; a bare name is undocumented debt. */
    @Test
    fun `every recorded 997 offender explains itself`() {
        assertEquals(
            "every $DEBT_PROPERTY entry needs a non-blank reason (what the bytes are, and why " +
                "the file is still committed): " +
                KNOWN_BROKEN.filter { it.reason.isBlank() }.map { it.relative }.joinToString(),
            emptyList<String>(),
            KNOWN_BROKEN.filter { it.reason.isBlank() }.map { it.relative }
        )
    }

    private fun inspect(dir: File, file: File): DrawableFile {
        val extension = file.extension.lowercase()
        val head = file.readHead(HEAD_BYTES)
        val expectation = EXPECTED_MAGIC[extension]
        val valid = expectation?.matches(head) ?: true
        val relative = "${dir.name}/${file.name}"
        return DrawableFile(
            relative = relative,
            extension = extension,
            expectedLabel = expectation?.label ?: "(no format is declared by this extension)",
            isValid = valid,
            detail = if (valid) "" else describeFailure(file, expectation!!.label, head),
            isRecordedDebt = KNOWN_BROKEN.any { it.relative == relative }
        )
    }

    private fun describeFailure(file: File, expectedLabel: String, head: ByteArray): String = buildString {
        append("expected ")
        append(expectedLabel)
        append(", found ")
        append(head.joinToString(" ") { "%02X".format(it) })
        if (head.startsWithBytes(0xEF, 0xBF, 0xBD)) {
            append(" — U+FFFD (EF BF BD) replacement characters (")
            append(file.countReplacements())
            append(" of them in ")
            append(file.length())
            append(" bytes)")
            append(": this binary was decoded as UTF-8 text and re-encoded, so every byte >= 0x80 was lost")
        }
    }

    private data class DrawableFile(
        val relative: String,
        val extension: String,
        val expectedLabel: String,
        val isValid: Boolean,
        val detail: String,
        val isRecordedDebt: Boolean
    )

    private data class KnownBroken(val relative: String, val reason: String)

    private companion object {

        const val DRAWABLE_PREFIX = "drawable"

        /** Name of the debt list, so the failure text names it without repeating the literal. */
        const val DEBT_PROPERTY = "KNOWN_BROKEN"

        /** Enough to cover a JPEG SOI, a RIFF/WEBP header and an XML prolog. */
        const val HEAD_BYTES = 16

        /**
         * Extension -> the bytes that extension promises. `xml` is the only
         * text format here, so it is matched as text: an optional UTF-8 BOM,
         * then whitespace, then `<`.
         */
        val EXPECTED_MAGIC: Map<String, MagicExpectation> = mapOf(
            "jpg" to MagicExpectation("JPEG (FF D8 FF)") { it.startsWithBytes(0xFF, 0xD8, 0xFF) },
            "jpeg" to MagicExpectation("JPEG (FF D8 FF)") { it.startsWithBytes(0xFF, 0xD8, 0xFF) },
            "png" to MagicExpectation("PNG (89 50 4E 47)") { it.startsWithBytes(0x89, 0x50, 0x4E, 0x47) },
            "webp" to MagicExpectation("WebP (RIFF????WEBP)") {
                it.asciiAt(0, 4) == "RIFF" && it.asciiAt(8, 4) == "WEBP"
            },
            "gif" to MagicExpectation("GIF (GIF8)") { it.asciiAt(0, 4) == "GIF8" },
            "xml" to MagicExpectation("text/XML (starts with '<')") { it.looksLikeText() }
        )

        /**
         * The #997 debt, frozen rather than fixed. Each of these is a JPEG in
         * name only; all three are the same UTF-8 round-trip corruption and
         * none of them is decodable. They stay committed because regenerating
         * them needs real artwork, which is the owner's decision — this list
         * only makes that state explicit instead of invisible.
         */
        val KNOWN_BROKEN: List<KnownBroken> = listOf(
            KnownBroken(
                "drawable/" + "img_neuromancer_cover_1785247475170.jpg",
                "#997 — 492 267 U+FFFD, no FF D8 anywhere, 2 112 647 bytes at HEAD. The file is " +
                    "referenced as R.drawable from LibraryImport.kt, so BookCoverImage paints the " +
                    "title+author fallback over the page. Needs real art."
            ),
            KnownBroken(
                "drawable/" + "img_cyber_dystopia_1785247491038.jpg",
                "Same UTF-8 round-trip corruption: 529 496 U+FFFD, no FF D8, 2 262 856 bytes. " +
                    "Currently unused (app/lint-baseline.xml: UnusedResources), so it breaks nothing " +
                    "today — and would break the same way if wired in. Needs real art."
            ),
            KnownBroken(
                "drawable/" + "img_dune_art_1785247505658.jpg",
                "Same UTF-8 round-trip corruption: 407 056 U+FFFD, no FF D8, 1 771 912 bytes. " +
                    "Currently unused (app/lint-baseline.xml: UnusedResources). Needs real art."
            )
        )
    }
}

/** A declared format and the predicate its magic bytes must satisfy. */
private class MagicExpectation(val label: String, private val predicate: (ByteArray) -> Boolean) {
    fun matches(head: ByteArray): Boolean = predicate(head)
}

private fun ByteArray.startsWithBytes(vararg expected: Int): Boolean =
    size >= expected.size && expected.withIndex().all { (index, byte) -> this[index] == byte.toByte() }

private fun ByteArray.asciiAt(offset: Int, length: Int): String? =
    if (size < offset + length) null else String(this, offset, length, Charsets.US_ASCII)

private fun ByteArray.looksLikeText(): Boolean {
    var index = 0
    if (size >= 3 && startsWithBytes(0xEF, 0xBB, 0xBF)) index = 3
    while (index < size && this[index].toInt().toChar().isWhitespace()) index++
    return index < size && this[index] == '<'.code.toByte()
}

private fun File.readHead(count: Int): ByteArray {
    inputStream().use { stream ->
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val chunk = stream.read(buffer, read, count - read)
            if (chunk < 0) break
            read += chunk
        }
        return buffer.copyOf(read)
    }
}

/** How many `U+FFFD` the file carries as UTF-8 (`EF BF BD`). */
private fun File.countReplacements(): Int {
    val bytes = readBytes()
    var count = 0
    var index = 0
    while (index + 2 < bytes.size) {
        if (bytes[index] == 0xEF.toByte() &&
            bytes[index + 1] == 0xBF.toByte() &&
            bytes[index + 2] == 0xBD.toByte()
        ) {
            count++
            index += 3
        } else {
            index++
        }
    }
    return count
}
