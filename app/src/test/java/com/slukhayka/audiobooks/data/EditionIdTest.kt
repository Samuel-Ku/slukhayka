package com.slukhayka.audiobooks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Test
import java.security.MessageDigest
import kotlin.system.measureNanoTime

/**
 * Pure tests for the deterministic Edition id (ADR-0007 + ADR-0010).
 *
 * ADR-0010 — the narrator is part of the edition id because the Work key no
 * longer carries it: two narrations of one Work must resolve to DIFFERENT
 * editions, or they would share listening state (ADR-0001 — incompatible
 * narrations never share timestamps).
 */
class EditionIdTest {

    @Test
    fun `same work and narrator and language - same edition`() {
        assertEquals(
            EditionId.forBook("кобзар|тарас шевченко", "b1", "Валерій Завалко"),
            EditionId.forBook("кобзар|тарас шевченко", "b1", "Валерій Завалко")
        )
    }

    @Test
    fun `different narrators of the same work - different editions`() {
        // ADR-0010 + ADR-0001: the narrator is a rendition property — two
        // narrations of the same text keep separate editions and thus
        // separate progress/bookmarks.
        assertNotEquals(
            EditionId.forBook("кобзар|тарас шевченко", "b1", "Валерій Завалко"),
            EditionId.forBook("кобзар|тарас шевченко", "b1", "Богдан Бенюк")
        )
    }

    @Test
    fun `narrator-free and narrator-ful books differ when narrators differ`() {
        assertNotEquals(
            EditionId.forBook("кобзар|тарас шевченко", "b1", ""),
            EditionId.forBook("кобзар|тарас шевченко", "b1", "Валерій Завалко")
        )
    }

    @Test
    fun `blank key falls back to the book id and still carries the narrator`() {
        assertNotEquals(
            EditionId.forBook("", "local-1"),
            EditionId.forBook("", "local-1", "Локальний аудіофайл")
        )
    }

    @Test
    fun `language keeps editions apart`() {
        assertNotEquals(
            EditionId.forBook("кобзар|тарас шевченко", "b1", "Валерій Завалко", ""),
            EditionId.forBook("кобзар|тарас шевченко", "b1", "Валерій Завалко", "uk")
        )
    }

    @Test
    fun `the legacy formula differs from the ADR-0010 formula`() {
        // The v13→v14-era id hashed the narrator INSIDE the mergeKey; the
        // ADR-0010 id hashes it as a separate input — the v16 migration
        // remaps every legacy id to the new one.
        assertNotEquals(
            EditionId.forBook("кобзар|тарас шевченко|валерій завалко", "b1"),
            EditionId.forBookLegacy("кобзар|тарас шевченко|валерій завалко", "b1")
        )
    }

    @Test
    fun `current formula stays exact across distinct and repeated working sets`() {
        repeat(2) {
            for (i in 0 until 6000) {
                val key = if (i % 7 == 0) "" else "Книга $i|Автор"
                val bookId = "local-$i"
                val narrator = "Читець ${i % 3}"
                val language = if (i % 5 == 0) "en" else "uk"
                val expected = reference(key, bookId, narrator, language)
                repeat(2) { assertEquals(expected, EditionId.forBook(key, bookId, narrator, language)) }
            }
        }
    }

    @Test
    fun `concurrent identity calls never exchange rendition values`() {
        val workers = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val calls = (0 until 4).map { worker ->
                workers.submit<List<String>> {
                    val values = java.util.ArrayList<String>()
                    for (i in 0 until 1500) {
                        values.add(EditionId.forBook("work-${i + worker * 1500}", "", "Читець $worker", "uk"))
                    }
                    values
                }
            }
            calls.forEachIndexed { worker, future ->
                future.get().forEachIndexed { i, actual ->
                    assertEquals(reference("work-${i + worker * 1500}", "", "Читець $worker", "uk"), actual)
                }
            }
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `repeated identity is reused and an old value is released by a full cache`() {
        val first = EditionId.forBook("cache-release-witness", "", "narrator", "uk")
        assertSame(first, EditionId.forBook("cache-release-witness", "", "narrator", "uk"))
        for (i in 0 until 6000) EditionId.forBook("cache-release-filler-$i", "", "narrator", "uk")
        val recomputed = EditionId.forBook("cache-release-witness", "", "narrator", "uk")
        assertEquals(first, recomputed)
        assertNotSame(first, recomputed)
    }

    private fun reference(key: String, bookId: String, narrator: String, language: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("${key.ifBlank { bookId }}\u0000$narrator\u0000$language".toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it.toInt() and 255) }
}


/** Manual comparative probe; JUnit does not assert wall-clock durations. */
object RunEditionMemoBenchmark {
 data class Input(val key:String,val book:String,val narrator:String,val lang:String)
 private var sink=0L
 private fun input(i:Int)=Input(if(i%11==0) "" else "Кобзар $i|Тарас Шевченко", "book-$i", "Читець ${i%3}", if(i%5==0)"en"else"uk")
 private fun id(x:Input,reference:Boolean)=if(reference)EditionMemoReference.forBook(x.key,x.book,x.narrator,x.lang)else EditionId.forBook(x.key,x.book,x.narrator,x.lang)
 @JvmStatic fun main(args:Array<String>) {
  for(i in 0 until 12000){val x=input(i);val raw="${x.key.ifBlank{x.book}}\u0000${x.narrator}\u0000${x.lang}";val expected=MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).take(12).joinToString(""){"%02x".format(it.toInt() and 255)};check(id(x,false)==expected);check(id(x,true)==expected)}
  val pool=List(20000){input(it)}
  fun run(name:String,reference:Boolean):Int {
   var checksum=0
   when(name){
    "hot20"->repeat(1000){for(i in 0 until 20)checksum+=id(pool[i],reference).hashCode()}
    "hot1000"->repeat(20){for(i in 0 until 1000)checksum+=id(pool[i],reference).hashCode()}
    "local-settled20"->for(bank in 0 until 500){val offset=bank*40;for(i in 0 until 20)checksum+=id(pool[offset+i],reference).hashCode();for(i in 0 until 40)checksum+=id(pool[offset+i],reference).hashCode()}
    "churn5000"->repeat(4){for(i in 0 until 5000)checksum+=id(pool[i],reference).hashCode()}
    "distinct20000"->for(i in pool.indices)checksum+=id(pool[i],reference).hashCode()
   };return checksum
  }
  println("12000 independent SHA output comparisons passed")
  for(name in listOf("hot20","hot1000","local-settled20","churn5000","distinct20000")){
   repeat(50){sink+=run(name,true);sink+=run(name,false)}
   val before=mutableListOf<Double>();val after=mutableListOf<Double>()
   fun time(reference:Boolean):Double {return measureNanoTime{repeat(5){sink+=run(name,reference)}}/5e6}
   repeat(9){if(it%2==0){before+=time(true);after+=time(false)}else{after+=time(false);before+=time(true)}}
   println("$name referenceMs=${before.sorted()[4]} candidateMs=${after.sorted()[4]} speedup=${before.sorted()[4]/after.sorted()[4]} before=$before after=$after")
  };println("checksum=$sink")
 }
}

private object EditionMemoReference {
    fun forBook(key: String, bookId: String, narrator: String, language: String): String =
        sha256Hex("${key.ifBlank { bookId }}\u0000$narrator\u0000$language".toByteArray(Charsets.UTF_8)).take(24)
}
