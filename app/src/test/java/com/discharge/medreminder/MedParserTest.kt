package com.discharge.medreminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MedParserTest {
    private fun one(line: String): Med {
        val r = MedParser.parse(line)
        assertEquals("Expected one medicine from: $line", 1, r.size)
        return r[0]
    }

    @Test fun indianPatternWithFoodAndDuration() {
        val m = one("1. Tab. Pantoprazole 40 mg  1-0-0  before food x 5 days")
        assertEquals("Tab Pantoprazole", m.name)
        assertEquals("40 mg", m.dose)
        assertEquals(listOf("08:00"), m.times)
        assertEquals(5, m.days)
        assertTrue(m.note.contains("Before food"))
    }

    @Test fun dottedAbbreviation() {
        val m = one("Cap Amoxicillin 500mg t.d.s. for 7 days")
        assertEquals("Cap Amoxicillin", m.name)
        assertEquals(listOf("08:00", "14:00", "21:00"), m.times)
        assertEquals(7, m.days)
    }

    @Test fun asNeededHasNoReminders() {
        val m = one("Syp Paracetamol 5 ml SOS")
        assertEquals("Syp Paracetamol", m.name)
        assertTrue(m.times.isEmpty())
    }

    @Test fun datesAreNotMedicines() {
        assertTrue(MedParser.parse("Date of discharge 12-05-2026").isEmpty())
    }

    @Test fun weeklyMedicine() {
        val m = one("Tab Methotrexate 7.5 mg once a week for 4 weeks")
        assertEquals(7, m.every)
        assertEquals(28, m.days)
        assertEquals(listOf("08:00"), m.times)
    }

    @Test fun ocrNoiseIsRepaired() {
        val m = one("Tab Metformin 500 rng I-O-I after food")
        assertEquals("500 mg", m.dose)
        assertEquals(listOf("08:00", "21:00"), m.times)
        assertTrue(m.note.contains("After food"))
    }

    @Test fun instructionsOnNextLine() {
        val m = one("Tab Atorvastatin 10 mg\n0-0-1 x 30 days")
        assertEquals(listOf("21:00"), m.times)
        assertEquals(30, m.days)
    }

    @Test fun everyEightHours() {
        val m = one("Inj Ceftriaxone 1 g every 8 hours")
        assertEquals("Inj Ceftriaxone", m.name)
        assertEquals(listOf("00:00", "08:00", "16:00"), m.times)
    }

    @Test fun shortFormT() {
        val m = one("T. Dolo 650 1-1-1 x 3 days")
        assertEquals("Tab Dolo 650", m.name)
        assertEquals(3, m.days)
        assertEquals(3, m.times.size)
    }

    @Test fun customSlotTimes() {
        val s = Slots(morning = "07:00", night = "22:00")
        val m = MedParser.parse("Tab Amlodipine 5 mg BD", s).single()
        assertEquals(listOf("07:00", "22:00"), m.times)
    }

    @Test fun tableWithSerialNumbersAndNoise() {
        val sheet = """
            S.No  Medicine  Dose  Frequency  Duration
            2   TAB. ECOSPRIN 75MG   0-1-0   AFTER LUNCH   30 DAYS
            Follow up after 7 days with reports
            Diet: soft diet
            Tab Shelcal 500 OD x 1 month
        """.trimIndent()
        val r = MedParser.parse(sheet)
        assertEquals(2, r.size)
        assertEquals("Tab ECOSPRIN", r[0].name)
        assertEquals(listOf("14:00"), r[0].times)
        assertEquals(30, r[0].days)
        assertEquals("Tab Shelcal 500", r[1].name)
        assertEquals(30, r[1].days)
    }
}
