package com.dshremote.app.json

/** Minimal JSON AST used on every target; no platform JSON library required. */
sealed interface JValue {
    data object Null : JValue
    data class Bool(val value: Boolean) : JValue
    data class Num(val raw: String) : JValue
    data class Str(val value: String) : JValue
    data class Arr(val values: List<JValue>) : JValue
    data class Obj(val values: Map<String, JValue>) : JValue
}

class JsonException(message: String) : Exception(message)

object Json {
    fun parse(text: String): JValue {
        val reader = Reader(text)
        val value = reader.value()
        reader.skipWhitespace()
        if (!reader.done()) throw JsonException("trailing content at ${reader.position}")
        return value
    }

    fun quote(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ') {
                    append("\\u")
                    append(c.code.toString(16).padStart(4, '0'))
                } else {
                    append(c)
                }
            }
        }
        append('"')
    }

    fun string(value: String): String = quote(value)

    fun obj(vararg fields: Pair<String, String>): String = buildString {
        append('{')
        fields.forEachIndexed { index, (key, raw) ->
            if (index > 0) append(',')
            append(quote(key)).append(':').append(raw)
        }
        append('}')
    }

    fun arr(vararg values: String): String = values.joinToString(",", "[", "]")

    private class Reader(private val text: String) {
        var position = 0
            private set

        fun done(): Boolean = position >= text.length
        fun skipWhitespace() {
            while (position < text.length && text[position].isWhitespace()) position++
        }

        fun value(): JValue {
            skipWhitespace()
            if (done()) throw JsonException("unexpected end")
            return when (text[position]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> JValue.Str(string())
                't' -> literal("true", JValue.Bool(true))
                'f' -> literal("false", JValue.Bool(false))
                'n' -> literal("null", JValue.Null)
                else -> number()
            }
        }

        private fun obj(): JValue {
            expect('{')
            val map = linkedMapOf<String, JValue>()
            skipWhitespace()
            if (peek() == '}') { position++; return JValue.Obj(map) }
            while (true) {
                skipWhitespace()
                val key = string()
                skipWhitespace()
                expect(':')
                map[key] = value()
                skipWhitespace()
                when (peek()) {
                    ',' -> { position++; continue }
                    '}' -> { position++; return JValue.Obj(map) }
                    else -> throw JsonException("expected , or } at $position")
                }
            }
        }

        private fun arr(): JValue {
            expect('[')
            val values = mutableListOf<JValue>()
            skipWhitespace()
            if (peek() == ']') { position++; return JValue.Arr(values) }
            while (true) {
                values.add(value())
                skipWhitespace()
                when (peek()) {
                    ',' -> { position++; continue }
                    ']' -> { position++; return JValue.Arr(values) }
                    else -> throw JsonException("expected , or ] at $position")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (done()) throw JsonException("unterminated string")
                val c = text[position++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (done()) throw JsonException("unterminated escape")
                        when (val e = text[position++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (position + 4 > text.length) throw JsonException("short unicode escape")
                                val hex = text.substring(position, position + 4)
                                out.append(hex.toInt(16).toChar())
                                position += 4
                            }
                            else -> throw JsonException("invalid escape $e")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number(): JValue {
            val start = position
            if (peek() == '-') position++
            while (!done() && text[position].isDigit()) position++
            if (!done() && text[position] == '.') {
                position++
                while (!done() && text[position].isDigit()) position++
            }
            if (!done() && (text[position] == 'e' || text[position] == 'E')) {
                position++
                if (!done() && (text[position] == '+' || text[position] == '-')) position++
                while (!done() && text[position].isDigit()) position++
            }
            if (start == position) throw JsonException("invalid value at $position")
            return JValue.Num(text.substring(start, position))
        }

        private fun literal(word: String, value: JValue): JValue {
            if (!text.startsWith(word, position)) throw JsonException("invalid literal at $position")
            position += word.length
            return value
        }

        private fun expect(c: Char) {
            skipWhitespace()
            if (done() || text[position] != c) throw JsonException("expected '$c' at $position")
            position++
        }

        private fun peek(): Char = if (done()) '\u0000' else text[position]
    }
}

fun JValue.objOrNull(): JValue.Obj? = this as? JValue.Obj
fun JValue.arrOrNull(): JValue.Arr? = this as? JValue.Arr
fun JValue.strOrNull(): String? = (this as? JValue.Str)?.value
fun JValue.longOrNull(): Long? = (this as? JValue.Num)?.raw?.toLongOrNull()
fun JValue.boolOrNull(): Boolean? = (this as? JValue.Bool)?.value
operator fun JValue.Obj.get(key: String): JValue? = values[key]
