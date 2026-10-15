package com.agentdeck.hotel

/** Stable and prerelease SemVer ordering; historical CI run-number tags are not versions. */
object ReleaseVersion {
    private val syntax = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")
    private data class Version(val numbers: List<Long>, val pre: List<String>)

    private fun parse(text: String): Version? {
        val match = syntax.matchEntire(text.removePrefix("v")) ?: return null
        val numbers = (1..3).map { match.groupValues[it].toLongOrNull() ?: return null }
        val pre = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
        if (pre.any { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') }) return null
        return Version(numbers, pre)
    }

    fun valid(text: String): Boolean = parse(text) != null

    fun newer(candidate: String, installed: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(installed) ?: return false
        for (i in 0..2) if (a.numbers[i] != b.numbers[i]) return a.numbers[i] > b.numbers[i]
        if (a.pre.isEmpty() || b.pre.isEmpty()) return a.pre.isEmpty() && b.pre.isNotEmpty()
        for (i in 0 until minOf(a.pre.size, b.pre.size)) {
            val x = a.pre[i]
            val y = b.pre[i]
            if (x == y) continue
            val xn = x.all(Char::isDigit)
            val yn = y.all(Char::isDigit)
            if (xn != yn) return !xn
            if (xn && x.length != y.length) return x.length > y.length
            return x > y
        }
        return a.pre.size > b.pre.size
    }

    fun offer(tag: String, installedName: String, installedCode: Long, candidateCode: Long): Boolean =
        candidateCode > installedCode && newer(tag, installedName)
}
