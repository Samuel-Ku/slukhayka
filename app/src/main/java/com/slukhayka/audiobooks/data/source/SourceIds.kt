package com.slukhayka.audiobooks.data.source

/**
 * spec-28 (#197) — the stable source identities the cross-source «Новинки»
 * rail and its adapter lists compare against, in ONE place. The rail must
 * never match a raw literal ("4read") scattered across call sites: a typo or
 * a rename would silently change which source feeds the rail (4read's news
 * would either disappear or render twice on Огляд). Persisted values (Room
 * `source` rows, `SourceEntity.type`) intentionally keep the plain strings —
 * this object is the *identity* the in-memory logic reasons with.
 */
object SourceIds {
    /** 4read.org — the catalogued source whose «Новинки» section feeds the rail. */
    const val FOUR_READ = "4read"
}
