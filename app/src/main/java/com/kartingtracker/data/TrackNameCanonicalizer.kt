package com.kartingtracker.data

import java.util.Locale

object TrackNameCanonicalizer {
    private data class AliasGroup(
        val canonicalName: String,
        val aliases: Set<String>
    )

    private val aliasGroups = listOf(
        AliasGroup(
            canonicalName = "Lörrach VM Kart Racing",
            aliases = setOf(
                "Lörrach VM Kart Racing",
                "Loerrach VM Kart Racing",
                "Lorrach VM Kart Racing",
                "L_rrach VM Kart Racing",
                "Lörrach",
                "Loerrach",
                "Lorrach",
                "L_rrach"
            )
        ),
        AliasGroup(
            canonicalName = "Rheinfelden Kartbahn",
            aliases = setOf(
                "Rheinfelden Kartbahn",
                "Rheinfelden Kart Bahn",
                "Rheinfelden"
            )
        )
    )

    private val normalizedAliasLookup: Map<String, String> = buildMap {
        aliasGroups.forEach { group ->
            val canonicalKey = normalizeKey(group.canonicalName)
            put(canonicalKey, group.canonicalName)
            group.aliases.forEach { alias ->
                put(normalizeKey(alias), group.canonicalName)
            }
        }
    }

    fun canonicalizeDisplayName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return ""
        return normalizedAliasLookup[normalizeKey(trimmed)] ?: trimmed
    }

    fun possibleStorageKeys(name: String): List<String> {
        val canonical = canonicalizeDisplayName(name)
        val allNames = buildSet {
            add(name)
            add(canonical)
            aliasGroups.firstOrNull { it.canonicalName == canonical }?.aliases?.let(::addAll)
        }.filter { it.isNotBlank() }
        return allNames
            .map(FileNameNormalizer::normalize)
            .distinct()
    }

    private fun normalizeKey(name: String): String {
        return FileNameNormalizer.normalize(name)
            .replace("_", "")
            .lowercase(Locale.ROOT)
    }
}
