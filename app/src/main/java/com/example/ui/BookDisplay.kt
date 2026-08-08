package com.example.ui

import com.example.data.db.AudiobookEntity

/**
 * Author label for the UI. The repository seeds several placeholder authors
 * for books without a real one ("4read.org", "Аудиокнига 4read.org");
 * rendering them under every title makes screens repeat "4read" over and
 * over. Blank any of them here — the source badge / pill already tells the
 * user where the book came from. Safe to match on "4read": no real author
 * name contains it.
 */
val AudiobookEntity.displayAuthor: String
    get() = author.takeUnless { it.contains("4read", ignoreCase = true) }.orEmpty()
