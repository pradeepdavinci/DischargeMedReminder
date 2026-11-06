package com.discharge.medreminder

/**
 * Turns the text read from a discharge medication sheet into a draft list of medicines.
 * Understands common prescription styles, for example:
 *   "Tab Pantoprazole 40 mg  1-0-0  before food  x 5 days"
 *   "T. Dolo 650 1-1-1 x 3 days"
 *   "Cap Amoxicillin 500mg TDS for 7 days"
 *   "Syp Paracetamol 5 ml every 6 hours SOS"
 *   "Tab Methotrexate 7.5 mg once a week for 4 weeks"
 *   "Tab Prednisolone 10 mg 1-1-1 x 3 days then 1-0-1 x 3 days"
 *   "Tab Furosemide 40 mg ½-0-½ x 5 days" / "2-0-2 x 5 days"
 * It also repairs common OCR mistakes (I-O-I -> 1-0-1, "rng" -> "mg").
 * The result is ALWAYS shown to the user for checking before reminders are created.
 */
object MedParser {
    private val I = RegexOption.IGNORE_CASE

    private val FORM = Regex("""^\s*(?:[•*\-]\s*)?(?:\d{1,2}\s*(?:[.)]\s*|\s+))?(tab(?:let)?s?|cap(?:sule)?s?|syp|syrup|susp(?:ension)?|inj(?:ection)?|drops?|oint(?:ment)?|cream|gel|lotion|inhaler|inh|rotacaps?|respules?|neb|nebuli[sz]ation|sachet|powder|pwd|t(?=\.)|c(?=\.))\b\.?\s*""", I)
    private val SLOT_VAL = """([0-4]|½|1/2|¼|1/4|¾|3/4)"""
    private val PATTERN = Regex("""(?<![\d.])$SLOT_VAL\s*[-–—]\s*$SLOT_VAL\s*[-–—]\s*$SLOT_VAL(?:\s*[-–—]\s*$SLOT_VAL)?(?![\d./])""")
    private val NOISY_PATTERN = Regex("""(?<![A-Za-z0-9])([0-4IlOo|½])\s*[-–—]\s*([0-4IlOo|½])\s*[-–—]\s*([0-4IlOo|½])(?:\s*[-–—]\s*([0-4IlOo|½]))?(?![A-Za-z0-9])""")
    private val HOURLY = Regex("""\b(?:every|q)\s*(\d{1,2})\s*(?:h|hr|hrs|hours?)\b""", I)
    private val TAPER_SPLIT = Regex("""\s*(?:,?\s*\bthen\b|followed\s+by)\s+""", I)

    private enum class Freq { OD, BD, TDS, QID }
    private val FREQ = listOf(
        Regex("""\b(qid|qds|four times)\b""", I) to Freq.QID,
        Regex("""\b(tds|tid|thrice|three times)\b""", I) to Freq.TDS,
        Regex("""\b(bd|bid|twice)\b""", I) to Freq.BD,
        Regex("""\b(od|qd|once)\b""", I) to Freq.OD,
    )
    private val SLOT_WORDS: List<Pair<Regex, (Slots) -> String>> = listOf(
        Regex("""\b(morning|breakfast)\b""", I) to { s: Slots -> s.morning },
        Regex("""\b(afternoon|noon|lunch)\b""", I) to { s: Slots -> s.afternoon },
        Regex("""\b(evening)\b""", I) to { s: Slots -> s.evening },
        Regex("""\b(night|bed ?time|hs|dinner)\b""", I) to { s: Slots -> s.night },
    )
    private val DAILY = Regex("""\b(daily|a day|per day|everyday)\b""", I)
    private val PRN = Regex("""\b(sos|prn|as needed|when needed|if needed|when required|if required)\b""", I)
    private val WEEKLY = Regex("""\b(weekly|once a week|once weekly|every week)\b""", I)
    private val ALTERNATE = Regex("""\b(alternate days?|every other day|alt days?|eod)\b""", I)
    private val EVERY_N_DAYS = Regex("""\bevery\s*(\d)\s*days?\b""", I)
    private val ONGOING = Regex("""\b(continue|long ?term|lifelong|life long|till further|until further|indefinite)""", I)
    private val DUR1 = Regex("""(?:for|x|×|\*)\s*(\d{1,3})\s*(days?|d|weeks?|wks?|w|months?|mon)\b""", I)
    private val DUR2 = Regex("""\b(\d{1,3})\s*(days?|weeks?|wks?|months?)\b""", I)
    private val DOSE = Regex("""\b\d+(?:\.\d+)?\s*(?:mg|mcg|µg|gm|g|ml|iu|units?|%)(?![a-z])""", I)
    private val BEFORE = Regex("""\b(before food|before meals?|before breakfast|empty stomach|ac)\b""", I)
    private val AFTER = Regex("""\b(after food|after meals?|after breakfast|with food|pc)\b""", I)

    fun parse(raw: String, slots: Slots = Slots()): List<Med> {
        val lines = raw.lines().map { normalize(it) }.filter { it.isNotBlank() }
        val out = mutableListOf<Med>()
        var i = 0
        while (i < lines.size) {
            var line = lines[i]
            val hasForm = FORM.containsMatchIn(line)
            // Medicine name on one line and its instructions on the next: join them.
            if (hasForm && times(line, slots) == null && !PRN.containsMatchIn(line) && i + 1 < lines.size &&
                !FORM.containsMatchIn(lines[i + 1]) &&
                (times(lines[i + 1], slots) != null || PRN.containsMatchIn(lines[i + 1]))
            ) {
                line += "  " + lines[i + 1]
                i++
            }
            i++
            out.addAll(parseLineWithTaper(line, hasForm, slots))
        }
        return out
    }

    internal fun normalize(s: String): String {
        var t = NOISY_PATTERN.replace(s) { m ->
            m.groupValues.drop(1).filter { it.isNotEmpty() }.joinToString("-") {
                when (it) { "I", "l", "|" -> "1"; "O", "o" -> "0"; else -> it }
            }
        }
        t = t.replace(Regex("""(\d)\s*(rng|m9|mq)\b""", I), "\$1 mg")
        t = t.replace(Regex("""(?i)\b([a-z])\.(?=[a-z])"""), "\$1")   // b.i.d -> bid, t.d.s -> tds
        return t.replace('|', ' ').replace(Regex("""\s{3,}"""), "   ").trim()
    }

    private fun freqTimes(f: Freq, s: Slots) = when (f) {
        Freq.OD -> listOf(s.morning)
        Freq.BD -> listOf(s.morning, s.night)
        Freq.TDS -> listOf(s.morning, s.afternoon, s.night)
        Freq.QID -> listOf(s.morning, s.afternoon, s.evening, s.night)
    }

    private fun times(s: String, slots: Slots): List<String>? {
        PATTERN.find(s)?.let { m ->
            val parts = m.groupValues.drop(1).filter { it.isNotEmpty() }
            val at = if (parts.size == 4) listOf(slots.morning, slots.afternoon, slots.evening, slots.night)
                     else listOf(slots.morning, slots.afternoon, slots.night)
            val t = parts.indices.filter { parts[it] != "0" }.map { at[it] }
            if (t.isNotEmpty()) return t.distinct().sorted()
        }
        HOURLY.find(s)?.let { m ->
            val n = m.groupValues[1].toInt()
            val startHour = slots.morning.substringBefore(":").toIntOrNull() ?: 8
            if (n in 1..24) return (0 until 24 / n).map { (startHour + it * n) % 24 }.sorted().map { "%02d:00".format(it) }
        }
        for ((re, f) in FREQ) if (re.containsMatchIn(s)) return freqTimes(f, slots).distinct().sorted()
        val slotTimes = SLOT_WORDS.filter { it.first.containsMatchIn(s) }.map { it.second(slots) }
        if (slotTimes.isNotEmpty()) return slotTimes.distinct().sorted()
        if (DAILY.containsMatchIn(s) || WEEKLY.containsMatchIn(s) || ALTERNATE.containsMatchIn(s)) return listOf(slots.morning)
        return null
    }

    /** Returns a note like "Pattern 2-0-2" when non-zero slots are fractional or >1. */
    private fun patternQuantityNote(s: String): String? {
        val m = PATTERN.find(s) ?: return null
        val parts = m.groupValues.drop(1).filter { it.isNotEmpty() }
        val nonZero = parts.filter { it != "0" }
        if (nonZero.isNotEmpty() && nonZero.any { it != "1" }) {
            return "Pattern " + parts.joinToString("-")
        }
        return null
    }

    private fun prettyForm(f: String): String {
        val l = f.lowercase()
        return when {
            l == "t" -> "Tab"
            l == "c" -> "Cap"
            l.startsWith("tab") -> "Tab"
            l.startsWith("cap") -> "Cap"
            l.startsWith("sy") -> "Syp"
            l.startsWith("susp") -> "Susp"
            l.startsWith("inj") -> "Inj"
            l.startsWith("drop") -> "Drops"
            else -> l.replaceFirstChar { it.uppercase() }
        }
    }

    private fun parseLineWithTaper(line: String, hasForm: Boolean, slots: Slots): List<Med> {
        val parts = TAPER_SPLIT.split(line).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size <= 1) {
            return listOfNotNull(parseLine(line, hasForm, slots))
        }
        val first = parseLine(parts[0], hasForm, slots) ?: return listOfNotNull(parseLine(line, hasForm, slots))
        val results = mutableListOf(first)
        var nextStart = first.startMillis + first.days * 86_400_000L
        for (idx in 1 until parts.size) {
            val stepText = "${first.name} ${first.dose} ${parts[idx]}".trim()
            val stepMed = parseLine(stepText, true, slots) ?: continue
            stepMed.startMillis = nextStart
            val taperNote = "Taper step ${idx + 1}"
            stepMed.note = listOf(taperNote, stepMed.note).filter { it.isNotBlank() }.joinToString("; ")
            results.add(stepMed)
            nextStart += stepMed.days * 86_400_000L
        }
        return results
    }

    private fun parseLine(line: String, hasForm: Boolean, slots: Slots): Med? {
        val t = times(line, slots)
        val prn = PRN.containsMatchIn(line)
        val dose = DOSE.find(line)?.value?.trim() ?: ""
        if (!hasForm && t == null) return null
        if (!hasForm && dose.isEmpty() && !PATTERN.containsMatchIn(line)) return null

        val formMatch = FORM.find(line)
        val rawForm = formMatch?.groupValues?.get(1) ?: ""
        if (rawForm.length == 1 && t == null && dose.isEmpty() && !prn) return null   // "T." / "C." need more evidence
        val form = if (rawForm.isNotEmpty()) prettyForm(rawForm) else ""
        val rest = if (formMatch != null) line.substring(formMatch.range.last + 1)
                   else line.replace(Regex("""^\s*\d{1,2}\s*(?:[.)]\s*|\s+)"""), "")
        val cutters = listOf(DOSE, PATTERN, HOURLY, PRN, DUR1, DUR2, DAILY, WEEKLY, ALTERNATE, BEFORE, AFTER) +
            FREQ.map { it.first } + SLOT_WORDS.map { it.first }
        val cut = cutters.mapNotNull { it.find(rest)?.range?.first }.minOrNull() ?: rest.length
        val name = rest.substring(0, cut).trim().trim('-', ':', ',', '.', '(', ')', '/').trim()
        if (name.count { it.isLetter() } < 3) return null

        val notes = mutableListOf<String>()
        if (BEFORE.containsMatchIn(line)) notes.add("Before food")
        else if (AFTER.containsMatchIn(line)) notes.add("After food")
        patternQuantityNote(line)?.let { notes.add(it) }

        val every = when {
            WEEKLY.containsMatchIn(line) -> { notes.add("Weekly – check the correct day"); 7 }
            ALTERNATE.containsMatchIn(line) -> { notes.add("Alternate days"); 2 }
            else -> EVERY_N_DAYS.find(line)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 30) ?: 1
        }

        val durLine = EVERY_N_DAYS.replace(line, " ")
        val dm = DUR1.find(durLine) ?: DUR2.find(durLine)
        val days = when {
            dm != null -> {
                val n = dm.groupValues[1].toInt()
                val u = dm.groupValues[2].lowercase()
                (when { u.startsWith("w") -> n * 7; u.startsWith("m") -> n * 30; else -> n }).coerceIn(1, 365)
            }
            ONGOING.containsMatchIn(line) -> { notes.add("Ongoing – confirm duration with doctor"); 30 }
            else -> { notes.add("Duration not found – please check"); 7 }
        }
        val finalTimes = when {
            prn -> { notes.add("As needed – no automatic reminders"); emptyList() }
            t != null -> t
            else -> { notes.add("Timing not found – please set times"); listOf(slots.morning) }
        }
        return Med(0, "$form $name".trim(), dose, finalTimes.toMutableList(), days, todayMidnight(),
            notes.joinToString("; "), every)
    }
}
