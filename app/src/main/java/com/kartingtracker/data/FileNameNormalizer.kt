package com.kartingtracker.data

import java.text.Normalizer

object FileNameNormalizer {
    fun normalize(input: String, fallback: String = "track"): String {
        val trimmed = input.trim().ifBlank { fallback }
        val umlautExpanded = trimmed
            .replace("Ä", "Ae")
            .replace("Ö", "Oe")
            .replace("Ü", "Ue")
            .replace("ä", "ae")
            .replace("ö", "oe")
            .replace("ü", "ue")
            .replace("ß", "ss")
        val ascii = Normalizer.normalize(umlautExpanded, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^A-Za-z0-9]+"), "_")
            .trim('_')
        return ascii.ifBlank { fallback }
    }
}
