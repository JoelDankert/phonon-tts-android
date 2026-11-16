package ai.gradium.phonon.tts

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

internal data class TextReplacementRule(val pattern: String, val replacement: String)

/** Ordered, process-independent text transformations shared by preview and system synthesis. */
internal object TextReplacementRules {
    private const val PREFS = "text-replacements"
    private const val RULES = "rules"

    fun load(context: Context): List<TextReplacementRule> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(RULES, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val pattern = item.optString("pattern", null) ?: continue
                val replacement = item.optString("replacement", null) ?: continue
                if (validate(pattern, replacement) == null) add(TextReplacementRule(pattern, replacement))
            }
        }
    }

    /** Returns a concise validation error, or null after the complete updated list is saved. */
    fun add(context: Context, pattern: String, replacement: String): String? {
        validate(pattern, replacement)?.let { return it }
        save(context, load(context) + TextReplacementRule(pattern, replacement))
        return null
    }

    fun update(context: Context, index: Int, pattern: String, replacement: String): String? {
        validate(pattern, replacement)?.let { return it }
        val rules = load(context)
        if (index !in rules.indices) return "Rule no longer exists"
        save(context, rules.toMutableList().apply { this[index] = TextReplacementRule(pattern, replacement) })
        return null
    }

    fun move(context: Context, index: Int, offset: Int) {
        val rules = load(context).toMutableList()
        val destination = index + offset
        if (index !in rules.indices || destination !in rules.indices) return
        val rule = rules.removeAt(index)
        rules.add(destination, rule)
        save(context, rules)
    }

    fun remove(context: Context, index: Int) {
        val rules = load(context)
        if (index !in rules.indices) return
        save(context, rules.filterIndexed { current, _ -> current != index })
    }

    fun apply(context: Context, text: String): String = load(context).fold(text) { current, rule ->
        // A manually corrupted preference or an unexpected replacement failure cannot stop TTS.
        runCatching {
            val alternatives = rule.replacement.split('|')
            val matcher = Pattern.compile(rule.pattern).matcher(current)
            val result = StringBuffer()
            while (matcher.find()) matcher.appendReplacement(result, alternatives.random())
            matcher.appendTail(result).toString()
        }.getOrDefault(current)
    }

    private fun save(context: Context, rules: List<TextReplacementRule>) {
        val array = JSONArray()
        rules.forEach { rule -> array.put(JSONObject().put("pattern", rule.pattern).put("replacement", rule.replacement)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(RULES, array.toString()).apply()
    }

    private fun validate(source: String, replacement: String): String? {
        try { Pattern.compile(source) }
        catch (error: PatternSyntaxException) { return error.description.ifBlank { "Invalid regex" } }
        // The empty alternative guarantees a match, making Matcher validate every group reference.
        return try {
            replacement.split('|').forEach { alternative ->
                Pattern.compile("(?:$source)|(?:)").matcher("").replaceFirst(alternative)
            }
            null
        } catch (error: RuntimeException) {
            error.message?.substringBefore('\n')?.take(120) ?: "Invalid replacement"
        }
    }
}
