package com.slukhayka.audiobooks.data.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookRecommendationTextTest {
    @Test
    fun `work text cleans source noise and includes rich metadata without narrator`() {
        val text = BookRecommendationText.build(
            title = "Дюна — Аудіокнига слухати онлайн",
            author = "Френк   Герберт",
            genres = "Фантастика",
            series = "Хроніки Дюни",
            effectiveDescription = "<p>Пустельна планета &amp; прянощі.</p>"
        )
        assertTrue(text.contains("Дюна"))
        assertTrue(text.contains("Френк Герберт"))
        assertTrue(text.contains("Хроніки Дюни"))
        assertTrue(text.contains("Пустельна планета & прянощі."))
        assertFalse(text.contains("слухати онлайн", ignoreCase = true))
        assertFalse(text.contains("<p>"))
    }

    @Test
    fun `work text removes exactly bound recording header and preserves the blurb`() {
        val text = BookRecommendationText.build(
            title = "The Lantern",
            author = "Mira Vale",
            effectiveDescription = "LibriVox recording of The Lantern by Mira Vale. A sailor returns home with a mysterious lantern."
        )

        assertEquals(
            "The Lantern\nMira Vale\nA sailor returns home with a mysterious lantern.",
            text
        )
    }

    @Test
    fun `work text removes the fixed volunteer clause after a bound recording header`() {
        val text = BookRecommendationText.build(
            title = "The Lantern",
            author = "Mira Vale",
            effectiveDescription = "LibriVox recording of The Lantern by Mira Vale. Read in English by Librivox volunteers. A sailor returns home with a mysterious lantern."
        )

        assertEquals(
            "The Lantern\nMira Vale\nA sailor returns home with a mysterious lantern.",
            text
        )
    }

    @Test
    fun `work text removes complete paired terminal recording instructions`() {
        val text = BookRecommendationText.build(
            title = "The Lantern",
            author = "Mira Vale",
            effectiveDescription = "A sailor returns home with a mysterious lantern. " +
                "For further information, including links to online text, reader information, RSS feeds, CD cover or other formats (if available), please go to the LibriVox catalog page for this recording. " +
                "For more free audio books or to become a volunteer reader, visit LibriVox.org ."
        )

        assertEquals(
            "The Lantern\nMira Vale\nA sailor returns home with a mysterious lantern.",
            text
        )
    }

    @Test
    fun `work text preserves ambiguous readers citations and unmatched metadata`() {
        val cases = listOf(
            arrayOf(
                "The Lantern", "Mira Vale",
                "LibriVox recording of The Lantern by Mira Vale. Read by Alice Green. The letter names Alice as the heir.",
                "The Lantern\nMira Vale\nRead by Alice Green. The letter names Alice as the heir."
            ),
            arrayOf(
                "The Lantern", "Mira Vale",
                "LibriVox recording of The Lantern by Mira Vale. Read by Students Worldwide.",
                "The Lantern\nMira Vale\nRead by Students Worldwide."
            ),
            arrayOf(
                "The Lantern", "Mira Vale",
                "LibriVox recording of The Lantern by Mira Vale. Read in German by Bernd Ungerer. Dr. Ernst König reads https://de.wikipedia.org/wiki/Die_Ahnen.",
                "The Lantern\nMira Vale\nRead in German by Bernd Ungerer. Dr. Ernst König reads https://de.wikipedia.org/wiki/Die_Ahnen."
            ),
            arrayOf(
                "The Lantern", "Mira Vale",
                "LibriVox recording of The Lantern by Mira Vale. Read by John Greenman collection contains: a letter and a voyage.",
                "The Lantern\nMira Vale\nRead by John Greenman collection contains: a letter and a voyage."
            ),
            arrayOf(
                "The Lantern", "Mira Vale",
                "LibriVox recording of The Lantern by Mira Vale. Read by Andrea Kotzer This audiobook contains https://www.faa.gov/regulations_policies/handbooks_manuals/aviation/airplane_handbook.",
                "The Lantern\nMira Vale\nRead by Andrea Kotzer This audiobook contains https://www.faa.gov/regulations_policies/handbooks_manuals/aviation/airplane_handbook."
            ),
            arrayOf(
                "The Lantern", "Mira Vale",
                "LibriVox recording of Another Book by Mira Vale. Read by Librivox volunteers. A sailor returns.",
                "The Lantern\nMira Vale\nLibriVox recording of Another Book by Mira Vale. Read by Librivox volunteers. A sailor returns."
            ),
            arrayOf(
                "The Lantern", "Mira Vale",
                "LibriVox recording of The Lantern by Another Author. Read by Librivox volunteers. A sailor returns.",
                "The Lantern\nMira Vale\nLibriVox recording of The Lantern by Another Author. Read by Librivox volunteers. A sailor returns."
            ),
            arrayOf(
                "", "Mira Vale", "LibriVox recording of  by Mira Vale. A sailor returns.",
                "Mira Vale\nLibriVox recording of by Mira Vale. A sailor returns."
            ),
            arrayOf(
                "The Lantern", "", "LibriVox recording of The Lantern by . A sailor returns.",
                "The Lantern\nLibriVox recording of The Lantern by . A sailor returns."
            ),
            arrayOf(
                "The Lantern", "Mira Vale", "LibriVox recording of The Lantern by Mira Vale.A sailor returns.",
                "The Lantern\nMira Vale\nLibriVox recording of The Lantern by Mira Vale.A sailor returns."
            ),
            arrayOf(
                "The Lantern", "Mira Vale",
                "LibriVox recording of The Lantern by Mira Vale. Read by Librivox volunteers.A sailor returns.",
                "The Lantern\nMira Vale\nRead by Librivox volunteers.A sailor returns."
            ),
            arrayOf(
                "The Lantern", "Mira Vale",
                "The letter says: \"Read in English by Librivox volunteers.\" A sailor signs it by Alice Green.",
                "The Lantern\nMira Vale\nThe letter says: \"Read in English by Librivox volunteers.\" A sailor signs it by Alice Green."
            ),
            arrayOf(
                "The Lantern", "Mira Vale",
                "Read along at http://www.gutenberg.org/ebooks/19994. The bibliography cites https://librivox.org/the-lantern/.",
                "The Lantern\nMira Vale\nRead along at http://www.gutenberg.org/ebooks/19994. The bibliography cites https://librivox.org/the-lantern/."
            )
        )

        cases.forEachIndexed { index, case ->
            assertEquals(
                "preservation case $index",
                case[3],
                BookRecommendationText.build(case[0], case[1], effectiveDescription = case[2])
            )
        }
    }

    @Test
    fun `work text preserves a quoted recording header after HTML cleanup`() {
        val text = BookRecommendationText.build(
            title = "The Lantern",
            author = "Mira Vale",
            effectiveDescription = "<blockquote>LibriVox recording of The Lantern by Mira Vale.</blockquote> A sailor returns home with a mysterious lantern."
        )

        assertEquals(
            "The Lantern\nMira Vale\nLibriVox recording of The Lantern by Mira Vale. A sailor returns home with a mysterious lantern.",
            text
        )
    }

    @Test
    fun `work text preserves quoted incomplete and nonterminal recording instructions`() {
        val footer = "For further information, including links to online text, reader information, RSS feeds, CD cover or other formats (if available), please go to the LibriVox catalog page for this recording. " +
            "For more free audio books or to become a volunteer reader, visit LibriVox.org ."
        val cases = listOf(
            "<q>$footer</q>" to "The Lantern\nMira Vale\n$footer",
            "<Q class='source'>$footer</Q>" to "The Lantern\nMira Vale\n$footer",
            "<BlOcKqUoTe cite='a-letter'>$footer</BlOcKqUoTe>" to "The Lantern\nMira Vale\n$footer",
            "<q>$footer" to "The Lantern\nMira Vale\n$footer",
            "</BLOCKQUOTE>$footer" to "The Lantern\nMira Vale\n$footer",
            "<blockquote class='source' $footer" to "The Lantern\nMira Vale\n<blockquote class='source' $footer",
            "<q $footer" to "The Lantern\nMira Vale\n<q $footer",
            "LibriVox recording of The Lantern by Mira Vale. Read by Librivox volunteers. <q>$footer</q>" to
                "The Lantern\nMira Vale\nLibriVox recording of The Lantern by Mira Vale. Read by Librivox volunteers. $footer",
            "The letter begins: \" $footer" to "The Lantern\nMira Vale\nThe letter begins: \" $footer",
            "The letter begins: “ $footer" to "The Lantern\nMira Vale\nThe letter begins: “ $footer",
            "$footer\"" to "The Lantern\nMira Vale\n$footer\"",
            "For further information, including links to online text, reader information, RSS feeds, CD cover or other formats (if available), please go to the LibriVox catalog page for this recording." to
                "The Lantern\nMira Vale\nFor further information, including links to online text, reader information, RSS feeds, CD cover or other formats (if available), please go to the LibriVox catalog page for this recording.",
            "For more free audio books or to become a volunteer reader, visit LibriVox.org ." to
                "The Lantern\nMira Vale\nFor more free audio books or to become a volunteer reader, visit LibriVox.org .",
            "For more free audio books or to become a volunteer reader, visit LibriVox.org . For further information, including links to online text, reader information, RSS feeds, CD cover or other formats (if available), please go to the LibriVox catalog page for this recording." to
                "The Lantern\nMira Vale\nFor more free audio books or to become a volunteer reader, visit LibriVox.org . For further information, including links to online text, reader information, RSS feeds, CD cover or other formats (if available), please go to the LibriVox catalog page for this recording.",
            "$footer The letter has another page." to "The Lantern\nMira Vale\n$footer The letter has another page.",
            "A sailor returns.$footer" to "The Lantern\nMira Vale\nA sailor returns.$footer",
            "$footer Read along at https://www.gutenberg.org/ebooks/19994." to
                "The Lantern\nMira Vale\n$footer Read along at https://www.gutenberg.org/ebooks/19994.",
            "$footer The handbook cites https://www.faa.gov/airplane_handbook." to
                "The Lantern\nMira Vale\n$footer The handbook cites https://www.faa.gov/airplane_handbook.",
            "$footer Compare https://en.wikipedia.org/wiki/Lantern." to
                "The Lantern\nMira Vale\n$footer Compare https://en.wikipedia.org/wiki/Lantern.",
            "$footer The bibliography cites https://librivox.org/the-lantern/." to
                "The Lantern\nMira Vale\n$footer The bibliography cites https://librivox.org/the-lantern/."
        )

        cases.forEachIndexed { index, (description, expected) ->
            assertEquals(
                "footer preservation case $index",
                expected,
                BookRecommendationText.build("The Lantern", "Mira Vale", effectiveDescription = description)
            )
        }
    }

    @Test
    fun `work text removes the four complete registered terminal sentence pairs`() {
        val catalogInstructions = listOf(
            "For further information, including links to online text, reader information, RSS feeds, CD cover or other formats (if available), please go to the LibriVox catalog page for this recording.",
            "For further information, including links to online text, reader information, RSS feeds, CD cover or other formats or languages (if available), please go to the LibriVox catalog page for this recording."
        )
        val invitations = listOf(
            "For more free audio books or to become a volunteer reader, visit LibriVox.org .",
            "For more free audio books or to become a volunteer reader, visit librivox.org ."
        )
        for (instruction in catalogInstructions) for (invitation in invitations) {
            assertEquals(
                "The Lantern\nMira Vale\nA sailor returns home with a mysterious lantern.",
                BookRecommendationText.build(
                    "The Lantern", "Mira Vale",
                    effectiveDescription = "A sailor returns home with a mysterious lantern. $instruction $invitation"
                )
            )
        }
    }

    @Test
    fun `work text removes only registered downloads following complete terminal instructions`() {
        val footer = "For further information, including links to online text, reader information, RSS feeds, CD cover or other formats (if available), please go to the LibriVox catalog page for this recording. " +
            "For more free audio books or to become a volunteer reader, visit LibriVox.org ."
        val downloads = listOf(
            "Download M4B (87MB)",
            "M4B Audiobook 01-08 (157MB) M4B Audiobook 09-16 (153MB)",
            "Download M4B (0.01MB)",
            "Download M4B (99999.99MB)",
            "Download M4B (100.00GB)",
            "M4B Audiobook 01-99 (1.25GB)"
        )
        downloads.forEach { download ->
            assertEquals(
                "The Lantern\nMira Vale\nA sailor returns home with a mysterious lantern.",
                BookRecommendationText.build(
                    "The Lantern", "Mira Vale",
                    effectiveDescription = "A sailor returns home with a mysterious lantern. $footer $download"
                )
            )
        }
    }

    @Test
    fun `work text removes recording URLs only in registered operational positions`() {
        val instruction = "For further information, including links to online text, reader information, RSS feeds, CD cover or other formats (if available), please go to the LibriVox catalog page for this recording."
        val invitation = "For more free audio books or to become a volunteer reader, visit LibriVox.org ."
        val cases = listOf(
            "$instruction https://librivox.org/the-lantern/ $invitation",
            "$instruction http://www.librivox.org:80/the-lantern/ $invitation Download M4B (87MB) https://archive.org/download/the-lantern/book.m4b",
            "$instruction https://www.archive.org:443/details/the-lantern $invitation M4B Audiobook 01-08 (157MB) http://archive.org:80/download/the-lantern/01.m4b M4B Audiobook 09-16 (153MB) https://www.librivox.org/the-lantern/09.m4b",
            "$instruction $invitation Download M4B (0.5GB) https://librivox.org/the-lantern/book.m4b?download=1#file",
            "$instruction $invitation Download M4B (87MB) https://archive.org/book.m4b?first=1&amp;second=2"
        )
        cases.forEach { operations ->
            assertEquals(
                "The Lantern\nMira Vale\nA sailor returns home with a mysterious lantern.",
                BookRecommendationText.build(
                    "The Lantern", "Mira Vale",
                    effectiveDescription = "A sailor returns home with a mysterious lantern. $operations"
                )
            )
        }
    }

    @Test
    fun `work text preserves raw URL quotation markup and control evidence`() {
        val instruction = "For further information, including links to online text, reader information, RSS feeds, CD cover or other formats (if available), please go to the LibriVox catalog page for this recording."
        val invitation = "For more free audio books or to become a volunteer reader, visit LibriVox.org ."
        val cases = listOf(
            "$instruction $invitation Download M4B (87MB) https://archive.org/book<bad>" to
                "The Lantern\nMira Vale\n$instruction $invitation Download M4B (87MB) https://archive.org/book",
            "$instruction $invitation Download M4B (87MB) https://archive.org/book”" to
                "The Lantern\nMira Vale\n$instruction $invitation Download M4B (87MB) https://archive.org/book”",
            "$instruction https://librivox.org/book'quote $invitation" to
                "The Lantern\nMira Vale\n$instruction https://librivox.org/book'quote $invitation",
            "$instruction $invitation Download M4B (87MB) https://archive.org/book\u0001" to
                "The Lantern\nMira Vale\n$instruction $invitation Download M4B (87MB) https://archive.org/book\u0001",
            "$instruction $invitation Download M4B (87MB) https://archive.org/book\u007f" to
                "The Lantern\nMira Vale\n$instruction $invitation Download M4B (87MB) https://archive.org/book\u007f"
        )
        cases.forEachIndexed { index, (description, expected) ->
            assertEquals(
                "raw URL preservation case $index",
                expected,
                BookRecommendationText.build("The Lantern", "Mira Vale", effectiveDescription = description)
            )
        }
    }

    @Test
    fun `work text retains an entire operation suffix with unsupported labels sizes ranges or URLs`() {
        val instruction = "For further information, including links to online text, reader information, RSS feeds, CD cover or other formats (if available), please go to the LibriVox catalog page for this recording."
        val invitation = "For more free audio books or to become a volunteer reader, visit LibriVox.org ."
        val downloads = listOf(
            "Download M4B (0MB)", "Download M4B (0.00GB)", "Download M4B (100000MB)",
            "Download M4B (100.01GB)", "Download M4B (1.001MB)", "Download M4B (1e2MB)",
            "Download M4B (+1MB)", "Download M4B (-1MB)", "Download M4B (1,5MB)",
            "Download M4B (١MB)", "Download M4B (1mb)", "Download M4B (1 MB)",
            "Download M4B part1(87MB)", "M4B audio book (87MB)",
            "M4B Audiobook 00-08 (87MB)", "M4B Audiobook 08-08 (87MB)",
            "M4B Audiobook 09-08 (87MB)", "M4B Audiobook 01-100 (87MB)",
            "M4B Audiobook 1-08 (87MB)", "Download M4B (87MB) The sailor has another chapter."
        )
        downloads.forEach { download ->
            assertEquals(
                "The Lantern\nMira Vale\nA sailor returns. $instruction $invitation $download",
                BookRecommendationText.build(
                    "The Lantern", "Mira Vale",
                    effectiveDescription = "A sailor returns. $instruction $invitation $download"
                )
            )
        }
        val urls = listOf(
            "https://librivox.org.evil.example/book", "https://evil-librivox.org/book",
            "https://librivox.org@evil.example/book", "https://user@librivox.org/book",
            "https://archive.org:80/book", "http://archive.org:443/book", "https://archive.org:8443/book",
            "//archive.org/book", "ftp://archive.org/book", "https://archive.org/%zz",
            "https://archive.org/book\\chapter", "https://archive.org/book'quote",
            "https://archive.org/book“quote”,", "https://archive.org/book«quote»",
            "https://www.gutenberg.org/ebooks/19994", "https://www.faa.gov/book",
            "https://en.wikipedia.org/wiki/Lantern"
        )
        urls.forEach { url ->
            assertEquals(
                "The Lantern\nMira Vale\nA sailor returns. $instruction $url $invitation",
                BookRecommendationText.build(
                    "The Lantern", "Mira Vale",
                    effectiveDescription = "A sailor returns. $instruction $url $invitation"
                )
            )
            assertEquals(
                "The Lantern\nMira Vale\nA sailor returns. $instruction $invitation Download M4B (87MB) $url",
                BookRecommendationText.build(
                    "The Lantern", "Mira Vale",
                    effectiveDescription = "A sailor returns. $instruction $invitation Download M4B (87MB) $url"
                )
            )
        }
        val misplaced = listOf(
            "$instruction $invitation https://archive.org/book",
            "$instruction https://librivox.org/book https://archive.org/book $invitation",
            "$instruction $invitation Download M4B (87MB) https://archive.org/one https://archive.org/two",
            "$instruction $invitation Download M4B (87MB) https://archive.org/book chapter",
            "$instruction $invitation Download M4B (87MB) https://archive.org/book\nchapter"
        )
        misplaced.forEach { operations ->
            val expected = if (operations.endsWith("book\nchapter")) {
                "The Lantern\nMira Vale\n$instruction $invitation Download M4B (87MB) https://archive.org/book chapter"
            } else {
                "The Lantern\nMira Vale\n$operations"
            }
            assertEquals(expected, BookRecommendationText.build("The Lantern", "Mira Vale", effectiveDescription = operations))
        }
    }

    @Test
    fun `work text preserves actual frozen fables and German narrative citations`() {
        // Frozen page-000 aesopforchildren_1308_librivox: exact raw description.
        assertEquals(
            "The Aesop for Children\nAesop\nA collection of Aesop's fables for children from the classic American book illustrated by Milo Winter. Read along and see the illustrations at: http://www.gutenberg.org/ebooks/19994. (Summary by Jill Engle)",
            BookRecommendationText.build("The Aesop for Children", "Aesop", effectiveDescription = "LibriVox recording of The Aesop for Children by Aesop. Read in English by Librivox volunteers. A collection of Aesop's fables for children from the classic American book illustrated by Milo Winter. Read along and see the illustrations at: http://www.gutenberg.org/ebooks/19994. (Summary by Jill Engle) For further information, including links to online text, reader information, RSS feeds, CD cover or other formats (if available), please go to the LibriVox catalog page for this recording. For more free audio books or to become a volunteer reader, visit LibriVox.org . Download M4B (87MB)")
        )
        // Frozen page-001 ahnen_band_6_2509_librivox: card title differs from its header.
        assertEquals(
            "Die Ahnen - Aus einer kleinen Stadt - Schluß der Ahnen\nGustav Freytag\nLibriVox recording of Die Ahnen Band 6: Aus einer kleinen Stadt - Schluß der Ahnen by Gustav Freytag. Read in German by Bernd Ungerer. Die Geschichte spielt zur Zeit Napoleons in Schlesien, wo der Arzt Dr. Ernst König sich in die Pfarrtochter Henriette verliebt. Diese jedoch wird von einem französischen Offizier beansprucht, der ihr einst das Leben rettete. Ein gesondertes Kapitel stellt der Schluss der Ahnen dar. „Hauptsache bei der kleinen Handlung des Schlusses war für mich, die poetische Idee, welche die einzelnen Geschichten verbindet, noch einmal vorzuführen und auf derselben Stätte, auf welcher sich die Katastrophe der ersten Geschichte vollzog, das Ganze zu schließen. (Gustav Freytag)“ (https://de.wikipedia.org/wiki/Die_Ahnen#Aus_einer_kleinen_Stadt) Von der Kritik wurde die Romanreihe weniger gut aufgenommen und dennoch gab es 27 Auflagen bis 1900 - das waren ca. 70.000 Bücher.. - Summary by Bernd Ungerer / Wikipedia",
            BookRecommendationText.build(
                "Die Ahnen - Aus einer kleinen Stadt - Schluß der Ahnen", "Gustav Freytag",
                effectiveDescription = "LibriVox recording of Die Ahnen Band 6: Aus einer kleinen Stadt - Schluß der Ahnen by Gustav Freytag. Read in German by Bernd Ungerer. Die Geschichte spielt zur Zeit Napoleons in Schlesien, wo der Arzt Dr. Ernst König sich in die Pfarrtochter Henriette verliebt. Diese jedoch wird von einem französischen Offizier beansprucht, der ihr einst das Leben rettete. Ein gesondertes Kapitel stellt der Schluss der Ahnen dar. „Hauptsache bei der kleinen Handlung des Schlusses war für mich, die poetische Idee, welche die einzelnen Geschichten verbindet, noch einmal vorzuführen und auf derselben Stätte, auf welcher sich die Katastrophe der ersten Geschichte vollzog, das Ganze zu schließen. (Gustav Freytag)“ (https://de.wikipedia.org/wiki/Die_Ahnen#Aus_einer_kleinen_Stadt) Von der Kritik wurde die Romanreihe weniger gut aufgenommen und dennoch gab es 27 Auflagen bis 1900 - das waren ca. 70.000 Bücher.. - Summary by Bernd Ungerer / Wikipedia For further information, including links to online text, reader information, RSS feeds, CD cover or other formats (if available), please go to the LibriVox catalog page for this recording. For more free audio books or to become a volunteer reader, visit librivox.org . M4B Audiobook 01-08 (157MB) M4B Audiobook 09-16 (153MB)"
            )
        )
    }

    @Test
    fun `work text keeps its description limit field order and repeat-cleaning behavior`() {
        val longBlurb = "a".repeat(1_199) + "Ztail"
        assertEquals(
            "The Lantern\nMira Vale\nFantasy\nHarbor Tales\n" + "a".repeat(1_199) + "Z",
            BookRecommendationText.build(
                "The Lantern", "Mira Vale", "Fantasy", "Harbor Tales",
                "LibriVox recording of The Lantern by Mira Vale. Read by Librivox volunteers. $longBlurb"
            )
        )
        val first = BookRecommendationText.build(
            "The Lantern", "Mira Vale",
            effectiveDescription = "LibriVox recording of The Lantern by Mira Vale. Read by Librivox volunteers. A sailor returns."
        )
        assertEquals("The Lantern\nMira Vale\nA sailor returns.", first)
        assertEquals(
            "The Lantern\nMira Vale\nA sailor returns.",
            BookRecommendationText.build("The Lantern", "Mira Vale", effectiveDescription = first.substringAfter("The Lantern\nMira Vale\n"))
        )
    }

    @Test
    fun `work text preserves quoted operations with named or numeric HTML quotation entities`() {
        val instruction = "For further information, including links to online text, reader information, RSS feeds, CD cover or other formats (if available), please go to the LibriVox catalog page for this recording."
        val invitation = "For more free audio books or to become a volunteer reader, visit LibriVox.org ."
        val entities = listOf(
            "&ldquo;" to "&rdquo;", "&quot;" to "&quot;", "&lsquo;" to "&rsquo;",
            "&#8220;" to "&#8221;", "&#x201c;" to "&#x201d;", "&laquo;" to "&raquo;",
            "&#8220" to "&#8221", "&#x201c" to "&#x201d", "&ldquo" to "&rdquo",
            "&unknown;" to "&unknown;"
        )
        entities.forEach { (opening, closing) ->
            assertEquals(
                "The Lantern\nMira Vale\nThe letter quotes ${opening}Notice: $instruction $invitation Download M4B (87MB) https://archive.org/download/the-lantern/book.m4b$closing",
                BookRecommendationText.build(
                    "The Lantern", "Mira Vale",
                    effectiveDescription = "The letter quotes ${opening}Notice: $instruction $invitation Download M4B (87MB) https://archive.org/download/the-lantern/book.m4b$closing"
                )
            )
        }
        val nested = listOf(
            "&amp;rdquo;" to "&rdquo;",
            "&amp;amp;b=2" to "&amp;b=2",
            "&amp;#8221;" to "&#8221;"
        )
        nested.forEach { (rawEnding, cleanedEnding) ->
            assertEquals(
                "The Lantern\nMira Vale\nThe letter quotes &ldquo;Notice: $instruction $invitation Download M4B (87MB) https://archive.org/book?first=1$cleanedEnding",
                BookRecommendationText.build(
                    "The Lantern", "Mira Vale",
                    effectiveDescription = "The letter quotes &ldquo;Notice: $instruction $invitation Download M4B (87MB) https://archive.org/book?first=1$rawEnding"
                )
            )
        }
        assertEquals(
            "The Lantern\nMira Vale\n$instruction $invitation Download M4B (87MB) https://archive.org/book&name?first=1",
            BookRecommendationText.build(
                "The Lantern", "Mira Vale",
                effectiveDescription = "$instruction $invitation Download M4B (87MB) https://archive.org/book&amp;name?first=1"
            )
        )
    }

    @Test
    fun `work text retains the footer when raw catalog sentinels cannot be bound uniquely`() {
        val instruction = "For further information, including links to online text, reader information, RSS feeds, CD cover or other formats (if available), please go to the LibriVox catalog page for this recording."
        val invitation = "For more free audio books or to become a volunteer reader, visit LibriVox.org ."
        assertEquals(
            "The Lantern\nMira Vale\nA sailor returns. $instruction $invitation Download M4B (87MB) https://archive.org/book",
            BookRecommendationText.build(
                "The Lantern", "Mira Vale",
                effectiveDescription = "A sailor returns. $instruction $invitation Download M4B (87MB) https://archive.org/book<bad> <span data-note=\"$instruction\"></span>"
            )
        )
    }

    @Test
    fun `work text retains the footer when its only full raw catalog sentinel is hidden markup`() {
        val instruction = "For further information, including links to online text, reader information, RSS feeds, CD cover or other formats (if available), please go to the LibriVox catalog page for this recording."
        val invitation = "For more free audio books or to become a volunteer reader, visit LibriVox.org ."
        val visibleInstruction = instruction.replace("including links", "including <i></i>links")
        assertEquals(
            "The Lantern\nMira Vale\nA sailor returns. $instruction $invitation Download M4B (87MB) https://archive.org/book",
            BookRecommendationText.build(
                "The Lantern", "Mira Vale",
                effectiveDescription = "A sailor returns. $visibleInstruction $invitation Download M4B (87MB) https://archive.org/book<bad> <span data-note=\"$instruction\"></span>"
            )
        )
        assertEquals(
            "The Lantern\nMira Vale\nA sailor returns. <span data-note=Intro $instruction $invitation Download M4B (87MB) https://archive.org/book",
            BookRecommendationText.build(
                "The Lantern", "Mira Vale",
                effectiveDescription = "A sailor returns. <span data-note=Intro $instruction $invitation Download M4B (87MB) https://archive.org/book"
            )
        )
    }
}
