package com.slukhayka.audiobooks.data.recommend

/** The declared Metaspace encoder; it never trims a segment. */
internal class UnigramPreTokenizer private constructor(private val metaspace: Boolean) {
    fun split(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        if (!metaspace) return listOf(text)
        val replaced = text.replace(' ', '▁')
        val prefixed = if (replaced.startsWith('▁')) replaced else "▁$replaced"
        val segments = ArrayList<String>()
        var start = 0
        for (index in 1 until prefixed.length) {
            if (prefixed[index] == '▁') {
                segments.add(prefixed.substring(start, index))
                start = index
            }
        }
        segments.add(prefixed.substring(start))
        return segments
    }

    companion object {
        fun parse(value: Any?): UnigramPreTokenizer {
            if (value == null) return UnigramPreTokenizer(false)
            val config = value as? Map<*, *> ?: throw IllegalArgumentException("Invalid tokenizer pre-tokenizer")
            require(config["type"] == "Metaspace" && config["replacement"] == "▁") {
                "Unsupported tokenizer pre-tokenizer"
            }
            require(config["add_prefix_space"] == null || config["add_prefix_space"] == true) { "Unsupported Metaspace add_prefix_space" }
            require(config["prepend_scheme"] == null || config["prepend_scheme"] == "always") { "Unsupported Metaspace prepend_scheme" }
            require(config["split"] == null || config["split"] == true) { "Unsupported Metaspace split" }
            require(config["str_rep"] == null || config["str_rep"] == "▁") { "Unsupported Metaspace str_rep" }
            return UnigramPreTokenizer(true)
        }
    }
}
