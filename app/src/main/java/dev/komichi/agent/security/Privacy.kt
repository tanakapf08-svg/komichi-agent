package dev.komichi.agent.security

object Privacy {
    private val patterns = listOf(
        Regex("(?i)(?:password|passwd|パスワード|暗証番号|認証コード|ワンタイム|otp|secret|token|口座|銀行|bank|credit|カード)[^\\n]{0,100}"),
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
        Regex("(?<![0-9])[0-9](?:[ -]?[0-9]){12,18}(?![0-9])"),
        Regex("(?<![0-9])[0-9]{4,8}(?![0-9])"),
        Regex("(?i)(?:bearer\\s+|sk-)[a-z0-9_./+\\-=]{12,}"),
        Regex("(?<![0-9])(?:\\+?[0-9][ -]?){9,12}(?![0-9])")
    )
    fun mask(value: String): String = patterns.fold(value) { text, regex -> regex.replace(text, "[非表示]") }
    fun sensitive(value: String): Boolean = patterns.any { it.containsMatchIn(value) }
}
