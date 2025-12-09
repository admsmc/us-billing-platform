package com.example.uspayroll.tax.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import java.io.InputStreamReader

class StateIncomeTaxCsvParserTest {

    @Test
    fun `parse sample state income tax CSVs and validate CA rule`() {
        val rulesStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-rules.csv")
            ?: error("Missing state-income-tax-2025-rules.csv on test classpath")
        val bracketsStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-brackets.csv")
            ?: error("Missing state-income-tax-2025-brackets.csv on test classpath")

        val rules = InputStreamReader(rulesStream).use { rulesReader ->
            InputStreamReader(bracketsStream).use { bracketsReader ->
                StateIncomeTaxCsvParser.parse(rulesReader, bracketsReader)
            }
        }

        val ca = rules.firstOrNull { it.jurisdictionCode == "CA" && it.filingStatus == "SINGLE" }
        assertNotNull(ca, "Expected CA SINGLE rule in state-income-tax-2025 CSVs")

        assertEquals("STATE", ca.jurisdictionType)
        assertEquals("StateTaxable", ca.basis)
        assertEquals("BRACKETED", ca.ruleType)

        val brackets = ca.brackets
        assertNotNull(brackets)

        // We expect the full 2025 CA SINGLE bracket schedule (9 brackets).
        assertEquals(9, brackets.size)

        // First bracket: up to $10,756 at 1%
        assertEquals(1_075_600L, brackets[0].upToCents)
        assertEquals(0.010, brackets[0].rate)

        // Last bracket: open-ended at 12.3%
        assertEquals(null, brackets.last().upToCents)
        assertEquals(0.123, brackets.last().rate)
    }

    @Test
    fun `parse CSVs and validate NY single rule`() {
        val rulesStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-rules.csv")
            ?: error("Missing state-income-tax-2025-rules.csv on test classpath")
        val bracketsStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-brackets.csv")
            ?: error("Missing state-income-tax-2025-brackets.csv on test classpath")

        val rules = InputStreamReader(rulesStream).use { rulesReader ->
            InputStreamReader(bracketsStream).use { bracketsReader ->
                StateIncomeTaxCsvParser.parse(rulesReader, bracketsReader)
            }
        }

        val ny = rules.firstOrNull { it.jurisdictionCode == "NY" && it.filingStatus == "SINGLE" }
        assertNotNull(ny, "Expected NY SINGLE rule in state-income-tax-2025 CSVs")
        assertEquals("STATE", ny.jurisdictionType)
        assertEquals("StateTaxable", ny.basis)
        assertEquals("BRACKETED", ny.ruleType)

        val brackets = ny.brackets
        assertNotNull(brackets)
        // 9 brackets; top rate 10.9%
        assertEquals(9, brackets.size)
        assertEquals(null, brackets.last().upToCents)
        assertEquals(0.109, brackets.last().rate)
    }

    @Test
    fun `parse CSVs and validate NJ single rule`() {
        val rulesStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-rules.csv")
            ?: error("Missing state-income-tax-2025-rules.csv on test classpath")
        val bracketsStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-brackets.csv")
            ?: error("Missing state-income-tax-2025-brackets.csv on test classpath")

        val rules = InputStreamReader(rulesStream).use { rulesReader ->
            InputStreamReader(bracketsStream).use { bracketsReader ->
                StateIncomeTaxCsvParser.parse(rulesReader, bracketsReader)
            }
        }

        val nj = rules.firstOrNull { it.jurisdictionCode == "NJ" && it.filingStatus == "SINGLE" }
        assertNotNull(nj, "Expected NJ SINGLE rule in state-income-tax-2025 CSVs")
        assertEquals("STATE", nj.jurisdictionType)
        assertEquals("StateTaxable", nj.basis)
        assertEquals("BRACKETED", nj.ruleType)

        val brackets = nj.brackets
        assertNotNull(brackets)
        // 7 brackets; top rate 10.75%
        assertEquals(7, brackets.size)
        assertEquals(null, brackets.last().upToCents)
        assertEquals(0.1075, brackets.last().rate)
    }

    @Test
    fun `parse CSVs and validate MN single rule`() {
        val rulesStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-rules.csv")
            ?: error("Missing state-income-tax-2025-rules.csv on test classpath")
        val bracketsStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-brackets.csv")
            ?: error("Missing state-income-tax-2025-brackets.csv on test classpath")

        val rules = InputStreamReader(rulesStream).use { rulesReader ->
            InputStreamReader(bracketsStream).use { bracketsReader ->
                StateIncomeTaxCsvParser.parse(rulesReader, bracketsReader)
            }
        }

        val mn = rules.firstOrNull { it.jurisdictionCode == "MN" && it.filingStatus == "SINGLE" }
        assertNotNull(mn, "Expected MN SINGLE rule in state-income-tax-2025 CSVs")
        assertEquals("STATE", mn.jurisdictionType)
        assertEquals("StateTaxable", mn.basis)
        assertEquals("BRACKETED", mn.ruleType)

        val brackets = mn.brackets
        assertNotNull(brackets)
        // Top rate should be 9.85%
        assertEquals(0.0985, brackets.last().rate)
    }

    @Test
    fun `parse CSVs and validate OR single rule`() {
        val rulesStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-rules.csv")
            ?: error("Missing state-income-tax-2025-rules.csv on test classpath")
        val bracketsStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-brackets.csv")
            ?: error("Missing state-income-tax-2025-brackets.csv on test classpath")

        val rules = InputStreamReader(rulesStream).use { rulesReader ->
            InputStreamReader(bracketsStream).use { bracketsReader ->
                StateIncomeTaxCsvParser.parse(rulesReader, bracketsReader)
            }
        }

        val orRule = rules.firstOrNull { it.jurisdictionCode == "OR" && it.filingStatus == "SINGLE" }
        assertNotNull(orRule, "Expected OR SINGLE rule in state-income-tax-2025 CSVs")
        assertEquals("STATE", orRule.jurisdictionType)
        assertEquals("StateTaxable", orRule.basis)
        assertEquals("BRACKETED", orRule.ruleType)

        val brackets = orRule.brackets
        assertNotNull(brackets)
        // 4 brackets; top rate 9.9%
        assertEquals(4, brackets.size)
        assertEquals(null, brackets.last().upToCents)
        assertEquals(0.099, brackets.last().rate)
    }

    @Test
    fun `parse CSVs and validate CT single rule`() {
        val rulesStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-rules.csv")
            ?: error("Missing state-income-tax-2025-rules.csv on test classpath")
        val bracketsStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-brackets.csv")
            ?: error("Missing state-income-tax-2025-brackets.csv on test classpath")

        val rules = InputStreamReader(rulesStream).use { rulesReader ->
            InputStreamReader(bracketsStream).use { bracketsReader ->
                StateIncomeTaxCsvParser.parse(rulesReader, bracketsReader)
            }
        }

        val ct = rules.firstOrNull { it.jurisdictionCode == "CT" && it.filingStatus == "SINGLE" }
        assertNotNull(ct, "Expected CT SINGLE rule in state-income-tax-2025 CSVs")
        assertEquals("STATE", ct.jurisdictionType)
        assertEquals("StateTaxable", ct.basis)
        assertEquals("BRACKETED", ct.ruleType)

        val brackets = ct.brackets
        assertNotNull(brackets)
        // 7 brackets; top rate 6.99%
        assertEquals(7, brackets.size)
        assertEquals(null, brackets.last().upToCents)
        assertEquals(0.0699, brackets.last().rate)
    }

    @Test
    fun `parse CSVs and validate RI single rule`() {
        val rulesStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-rules.csv")
            ?: error("Missing state-income-tax-2025-rules.csv on test classpath")
        val bracketsStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-brackets.csv")
            ?: error("Missing state-income-tax-2025-brackets.csv on test classpath")

        val rules = InputStreamReader(rulesStream).use { rulesReader ->
            InputStreamReader(bracketsStream).use { bracketsReader ->
                StateIncomeTaxCsvParser.parse(rulesReader, bracketsReader)
            }
        }

        val ri = rules.firstOrNull { it.jurisdictionCode == "RI" && it.filingStatus == "SINGLE" }
        assertNotNull(ri, "Expected RI SINGLE rule in state-income-tax-2025 CSVs")
        assertEquals("STATE", ri.jurisdictionType)
        assertEquals("StateTaxable", ri.basis)
        assertEquals("BRACKETED", ri.ruleType)

        val brackets = ri.brackets
        assertNotNull(brackets)
        // 3 brackets; top rate 5.99%
        assertEquals(3, brackets.size)
        assertEquals(null, brackets.last().upToCents)
        assertEquals(0.0599, brackets.last().rate)
    }

    @Test
    fun `parse CSVs and validate AL single rule`() {
        val rulesStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-rules.csv")
            ?: error("Missing state-income-tax-2025-rules.csv on test classpath")
        val bracketsStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-brackets.csv")
            ?: error("Missing state-income-tax-2025-brackets.csv on test classpath")

        val rules = InputStreamReader(rulesStream).use { rulesReader ->
            InputStreamReader(bracketsStream).use { bracketsReader ->
                StateIncomeTaxCsvParser.parse(rulesReader, bracketsReader)
            }
        }

        val al = rules.firstOrNull { it.jurisdictionCode == "AL" && it.filingStatus == "SINGLE" }
        assertNotNull(al, "Expected AL SINGLE rule in state-income-tax-2025 CSVs")
        assertEquals("STATE", al.jurisdictionType)
        assertEquals("StateTaxable", al.basis)
        assertEquals("BRACKETED", al.ruleType)

        val brackets = al.brackets
        assertNotNull(brackets)
        // 3 brackets; top rate 5%
        assertEquals(3, brackets.size)
        assertEquals(null, brackets.last().upToCents)
        assertEquals(0.050, brackets.last().rate)
    }

    @Test
    fun `parse CSVs and validate VA single rule`() {
        val rulesStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-rules.csv")
            ?: error("Missing state-income-tax-2025-rules.csv on test classpath")
        val bracketsStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-brackets.csv")
            ?: error("Missing state-income-tax-2025-brackets.csv on test classpath")

        val rules = InputStreamReader(rulesStream).use { rulesReader ->
            InputStreamReader(bracketsStream).use { bracketsReader ->
                StateIncomeTaxCsvParser.parse(rulesReader, bracketsReader)
            }
        }

        val va = rules.firstOrNull { it.jurisdictionCode == "VA" && it.filingStatus == "SINGLE" }
        assertNotNull(va, "Expected VA SINGLE rule in state-income-tax-2025 CSVs")
        assertEquals("STATE", va.jurisdictionType)
        assertEquals("StateTaxable", va.basis)
        assertEquals("BRACKETED", va.ruleType)

        val brackets = va.brackets
        assertNotNull(brackets)
        // 4 brackets; top rate 5.75%
        assertEquals(4, brackets.size)
        assertEquals(null, brackets.last().upToCents)
        assertEquals(0.0575, brackets.last().rate)
    }

    @Test
    fun `parse CSVs and validate WI single rule`() {
        val rulesStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-rules.csv")
            ?: error("Missing state-income-tax-2025-rules.csv on test classpath")
        val bracketsStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-brackets.csv")
            ?: error("Missing state-income-tax-2025-brackets.csv on test classpath")

        val rules = InputStreamReader(rulesStream).use { rulesReader ->
            InputStreamReader(bracketsStream).use { bracketsReader ->
                StateIncomeTaxCsvParser.parse(rulesReader, bracketsReader)
            }
        }

        val wi = rules.firstOrNull { it.jurisdictionCode == "WI" && it.filingStatus == "SINGLE" }
        assertNotNull(wi, "Expected WI SINGLE rule in state-income-tax-2025 CSVs")
        assertEquals("STATE", wi.jurisdictionType)
        assertEquals("StateTaxable", wi.basis)
        assertEquals("BRACKETED", wi.ruleType)

        val brackets = wi.brackets
        assertNotNull(brackets)
        // 4 brackets; top rate 7.65%
        assertEquals(4, brackets.size)
        assertEquals(null, brackets.last().upToCents)
        assertEquals(0.0765, brackets.last().rate)
    }

    @Test
    fun `parse CSVs and validate DE single rule`() {
        val rulesStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-rules.csv")
            ?: error("Missing state-income-tax-2025-rules.csv on test classpath")
        val bracketsStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-brackets.csv")
            ?: error("Missing state-income-tax-2025-brackets.csv on test classpath")

        val rules = InputStreamReader(rulesStream).use { rulesReader ->
            InputStreamReader(bracketsStream).use { bracketsReader ->
                StateIncomeTaxCsvParser.parse(rulesReader, bracketsReader)
            }
        }

        val de = rules.firstOrNull { it.jurisdictionCode == "DE" && it.filingStatus == "SINGLE" }
        assertNotNull(de, "Expected DE SINGLE rule in state-income-tax-2025 CSVs")
        assertEquals("STATE", de.jurisdictionType)
        assertEquals("StateTaxable", de.basis)
        assertEquals("BRACKETED", de.ruleType)

        val brackets = de.brackets
        assertNotNull(brackets)
        // 7 brackets; top rate 6.6%
        assertEquals(7, brackets.size)
        assertEquals(null, brackets.last().upToCents)
        assertEquals(0.066, brackets.last().rate)
    }

    @Test
    fun `parse CSVs and validate MO single rule`() {
        val rulesStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-rules.csv")
            ?: error("Missing state-income-tax-2025-rules.csv on test classpath")
        val bracketsStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-brackets.csv")
            ?: error("Missing state-income-tax-2025-brackets.csv on test classpath")

        val rules = InputStreamReader(rulesStream).use { rulesReader ->
            InputStreamReader(bracketsStream).use { bracketsReader ->
                StateIncomeTaxCsvParser.parse(rulesReader, bracketsReader)
            }
        }

        val mo = rules.firstOrNull { it.jurisdictionCode == "MO" && it.filingStatus == "SINGLE" }
        assertNotNull(mo, "Expected MO SINGLE rule in state-income-tax-2025 CSVs")
        assertEquals("STATE", mo.jurisdictionType)
        assertEquals("StateTaxable", mo.basis)
        assertEquals("BRACKETED", mo.ruleType)

        val brackets = mo.brackets
        assertNotNull(brackets)
        // 7 brackets; top rate 4.7%
        assertEquals(7, brackets.size)
        assertEquals(null, brackets.last().upToCents)
        assertEquals(0.047, brackets.last().rate)
    }

    @Test
    fun `parse CSVs and validate NE single rule`() {
        val rulesStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-rules.csv")
            ?: error("Missing state-income-tax-2025-rules.csv on test classpath")
        val bracketsStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-brackets.csv")
            ?: error("Missing state-income-tax-2025-brackets.csv on test classpath")

        val rules = InputStreamReader(rulesStream).use { rulesReader ->
            InputStreamReader(bracketsStream).use { bracketsReader ->
                StateIncomeTaxCsvParser.parse(rulesReader, bracketsReader)
            }
        }

        val ne = rules.firstOrNull { it.jurisdictionCode == "NE" && it.filingStatus == "SINGLE" }
        assertNotNull(ne, "Expected NE SINGLE rule in state-income-tax-2025 CSVs")
        assertEquals("STATE", ne.jurisdictionType)
        assertEquals("StateTaxable", ne.basis)
        assertEquals("BRACKETED", ne.ruleType)

        val brackets = ne.brackets
        assertNotNull(brackets)
        // 4 brackets; top rate 5.2%
        assertEquals(4, brackets.size)
        assertEquals(null, brackets.last().upToCents)
        assertEquals(0.052, brackets.last().rate)
    }

    @Test
    fun `parse CSVs and validate HI single rule`() {
        val rulesStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-rules.csv")
            ?: error("Missing state-income-tax-2025-rules.csv on test classpath")
        val bracketsStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-brackets.csv")
            ?: error("Missing state-income-tax-2025-brackets.csv on test classpath")

        val rules = InputStreamReader(rulesStream).use { rulesReader ->
            InputStreamReader(bracketsStream).use { bracketsReader ->
                StateIncomeTaxCsvParser.parse(rulesReader, bracketsReader)
            }
        }

        val hi = rules.firstOrNull { it.jurisdictionCode == "HI" && it.filingStatus == "SINGLE" }
        assertNotNull(hi, "Expected HI SINGLE rule in state-income-tax-2025 CSVs")
        assertEquals("STATE", hi.jurisdictionType)
        assertEquals("StateTaxable", hi.basis)
        assertEquals("BRACKETED", hi.ruleType)

        val hiBrackets = hi.brackets
        assertNotNull(hiBrackets)
        // 12 brackets; top rate 11%
        assertEquals(12, hiBrackets.size)
        assertEquals(null, hiBrackets.last().upToCents)
        assertEquals(0.110, hiBrackets.last().rate)
    }

    @Test
    fun `parse CSVs and validate KS single rule`() {
        val rulesStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-rules.csv")
            ?: error("Missing state-income-tax-2025-rules.csv on test classpath")
        val bracketsStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-brackets.csv")
            ?: error("Missing state-income-tax-2025-brackets.csv on test classpath")

        val rules = InputStreamReader(rulesStream).use { rulesReader ->
            InputStreamReader(bracketsStream).use { bracketsReader ->
                StateIncomeTaxCsvParser.parse(rulesReader, bracketsReader)
            }
        }

        val ks = rules.firstOrNull { it.jurisdictionCode == "KS" && it.filingStatus == "SINGLE" }
        assertNotNull(ks, "Expected KS SINGLE rule in state-income-tax-2025 CSVs")
        assertEquals("STATE", ks.jurisdictionType)
        assertEquals("StateTaxable", ks.basis)
        assertEquals("BRACKETED", ks.ruleType)

        val ksBrackets = ks.brackets
        assertNotNull(ksBrackets)
        // 2 brackets; top rate 5.58%
        assertEquals(2, ksBrackets.size)
        assertEquals(null, ksBrackets.last().upToCents)
        assertEquals(0.0558, ksBrackets.last().rate)
    }

    @Test
    fun `parse CSVs and validate ME single rule`() {
        val rulesStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-rules.csv")
            ?: error("Missing state-income-tax-2025-rules.csv on test classpath")
        val bracketsStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-brackets.csv")
            ?: error("Missing state-income-tax-2025-brackets.csv on test classpath")

        val rules = InputStreamReader(rulesStream).use { rulesReader ->
            InputStreamReader(bracketsStream).use { bracketsReader ->
                StateIncomeTaxCsvParser.parse(rulesReader, bracketsReader)
            }
        }

        val me = rules.firstOrNull { it.jurisdictionCode == "ME" && it.filingStatus == "SINGLE" }
        assertNotNull(me, "Expected ME SINGLE rule in state-income-tax-2025 CSVs")
        assertEquals("STATE", me.jurisdictionType)
        assertEquals("StateTaxable", me.basis)
        assertEquals("BRACKETED", me.ruleType)

        val meBrackets = me.brackets
        assertNotNull(meBrackets)
        // 3 brackets; top rate 7.15%
        assertEquals(3, meBrackets.size)
        assertEquals(null, meBrackets.last().upToCents)
        assertEquals(0.0715, meBrackets.last().rate)
    }

    @Test
    fun `parse CSVs and validate AZ flat rule`() {
        val rulesStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-rules.csv")
            ?: error("Missing state-income-tax-2025-rules.csv on test classpath")
        val bracketsStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-brackets.csv")
            ?: error("Missing state-income-tax-2025-brackets.csv on test classpath")

        val rules = InputStreamReader(rulesStream).use { rulesReader ->
            InputStreamReader(bracketsStream).use { bracketsReader ->
                StateIncomeTaxCsvParser.parse(rulesReader, bracketsReader)
            }
        }

        val az = rules.firstOrNull { it.jurisdictionCode == "AZ" && it.filingStatus == null }
        assertNotNull(az, "Expected AZ flat rule in state-income-tax-2025 CSVs")
        assertEquals("STATE", az.jurisdictionType)
        assertEquals("StateTaxable", az.basis)
        assertEquals("FLAT", az.ruleType)
        assertEquals(0.025, az.rate)
    }

    @Test
    fun `parse CSVs and validate IA flat rule`() {
        val rulesStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-rules.csv")
            ?: error("Missing state-income-tax-2025-rules.csv on test classpath")
        val bracketsStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-brackets.csv")
            ?: error("Missing state-income-tax-2025-brackets.csv on test classpath")

        val rules = InputStreamReader(rulesStream).use { rulesReader ->
            InputStreamReader(bracketsStream).use { bracketsReader ->
                StateIncomeTaxCsvParser.parse(rulesReader, bracketsReader)
            }
        }

        val ia = rules.firstOrNull { it.jurisdictionCode == "IA" && it.filingStatus == null }
        assertNotNull(ia, "Expected IA flat rule in state-income-tax-2025 CSVs")
        assertEquals("STATE", ia.jurisdictionType)
        assertEquals("StateTaxable", ia.basis)
        assertEquals("FLAT", ia.ruleType)
        assertEquals(0.0380, ia.rate)
    }

    @Test
    fun `parse CSVs and validate MS hybrid rule`() {
        val rulesStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-rules.csv")
            ?: error("Missing state-income-tax-2025-rules.csv on test classpath")
        val bracketsStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-brackets.csv")
            ?: error("Missing state-income-tax-2025-brackets.csv on test classpath")

        val rules = InputStreamReader(rulesStream).use { rulesReader ->
            InputStreamReader(bracketsStream).use { bracketsReader ->
                StateIncomeTaxCsvParser.parse(rulesReader, bracketsReader)
            }
        }

        val ms = rules.firstOrNull { it.jurisdictionCode == "MS" && it.filingStatus == null }
        assertNotNull(ms, "Expected MS hybrid rule in state-income-tax-2025 CSVs")
        assertEquals("STATE", ms.jurisdictionType)
        assertEquals("StateTaxable", ms.basis)
        assertEquals("BRACKETED", ms.ruleType)

        val brackets = ms.brackets
        assertNotNull(brackets)
        // Two-bracket hybrid: 0% up to the threshold, then flat 4.4% above.
        assertEquals(2, brackets.size)
        assertEquals(1_000_000L, brackets[0].upToCents) // $10,000 in cents
        assertEquals(0.0, brackets[0].rate)
        assertEquals(0.044, brackets[1].rate)
    }

    @Test
    fun `parse CSVs and validate MD single rule`() {
        val rulesStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-rules.csv")
            ?: error("Missing state-income-tax-2025-rules.csv on test classpath")
        val bracketsStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-brackets.csv")
            ?: error("Missing state-income-tax-2025-brackets.csv on test classpath")

        val rules = InputStreamReader(rulesStream).use { rulesReader ->
            InputStreamReader(bracketsStream).use { bracketsReader ->
                StateIncomeTaxCsvParser.parse(rulesReader, bracketsReader)
            }
        }

        val md = rules.firstOrNull { it.jurisdictionCode == "MD" && it.filingStatus == "SINGLE" }
        assertNotNull(md, "Expected MD SINGLE rule in state-income-tax-2025 CSVs")
        assertEquals("STATE", md.jurisdictionType)
        assertEquals("StateTaxable", md.basis)
        assertEquals("BRACKETED", md.ruleType)

        val mdBrackets = md.brackets
        assertNotNull(mdBrackets)
        // 10 brackets; top rate 6.5%
        assertEquals(10, mdBrackets.size)
        assertEquals(null, mdBrackets.last().upToCents)
        assertEquals(0.065, mdBrackets.last().rate)
    }

    @Test
    fun `parse CSVs and validate MT single rule`() {
        val rulesStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-rules.csv")
            ?: error("Missing state-income-tax-2025-rules.csv on test classpath")
        val bracketsStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-brackets.csv")
            ?: error("Missing state-income-tax-2025-brackets.csv on test classpath")

        val rules = InputStreamReader(rulesStream).use { rulesReader ->
            InputStreamReader(bracketsStream).use { bracketsReader ->
                StateIncomeTaxCsvParser.parse(rulesReader, bracketsReader)
            }
        }

        val mt = rules.firstOrNull { it.jurisdictionCode == "MT" && it.filingStatus == "SINGLE" }
        assertNotNull(mt, "Expected MT SINGLE rule in state-income-tax-2025 CSVs")
        assertEquals("STATE", mt.jurisdictionType)
        assertEquals("StateTaxable", mt.basis)
        assertEquals("BRACKETED", mt.ruleType)

        val mtBrackets = mt.brackets
        assertNotNull(mtBrackets)
        // 2 brackets; top rate 5.9%
        assertEquals(2, mtBrackets.size)
        assertEquals(null, mtBrackets.last().upToCents)
        assertEquals(0.059, mtBrackets.last().rate)
    }

    @Test
    fun `parse CSVs and validate ND single rule`() {
        val rulesStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-rules.csv")
            ?: error("Missing state-income-tax-2025-rules.csv on test classpath")
        val bracketsStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-brackets.csv")
            ?: error("Missing state-income-tax-2025-brackets.csv on test classpath")

        val rules = InputStreamReader(rulesStream).use { rulesReader ->
            InputStreamReader(bracketsStream).use { bracketsReader ->
                StateIncomeTaxCsvParser.parse(rulesReader, bracketsReader)
            }
        }

        val nd = rules.firstOrNull { it.jurisdictionCode == "ND" && it.filingStatus == "SINGLE" }
        assertNotNull(nd, "Expected ND SINGLE rule in state-income-tax-2025 CSVs")
        assertEquals("STATE", nd.jurisdictionType)
        assertEquals("StateTaxable", nd.basis)
        assertEquals("BRACKETED", nd.ruleType)

        val ndBrackets = nd.brackets
        assertNotNull(ndBrackets)
        // 3 brackets; first at 0% and top rate 2.5%
        assertEquals(3, ndBrackets.size)
        assertEquals(0.0, ndBrackets.first().rate)
        assertEquals(0.0250, ndBrackets.last().rate)
    }

    @Test
    fun `parse CSVs and validate OH single rule`() {
        val rulesStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-rules.csv")
            ?: error("Missing state-income-tax-2025-rules.csv on test classpath")
        val bracketsStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-brackets.csv")
            ?: error("Missing state-income-tax-2025-brackets.csv on test classpath")

        val rules = InputStreamReader(rulesStream).use { rulesReader ->
            InputStreamReader(bracketsStream).use { bracketsReader ->
                StateIncomeTaxCsvParser.parse(rulesReader, bracketsReader)
            }
        }

        val oh = rules.firstOrNull { it.jurisdictionCode == "OH" && it.filingStatus == "SINGLE" }
        assertNotNull(oh, "Expected OH SINGLE rule in state-income-tax-2025 CSVs")
        assertEquals("STATE", oh.jurisdictionType)
        assertEquals("StateTaxable", oh.basis)
        assertEquals("BRACKETED", oh.ruleType)

        val ohBrackets = oh.brackets
        assertNotNull(ohBrackets)
        // 3 brackets; first at 0% and top rate 3.125%
        assertEquals(3, ohBrackets.size)
        assertEquals(0.0, ohBrackets.first().rate)
        assertEquals(0.03125, ohBrackets.last().rate)
    }

    @Test
    fun `parse CSVs and validate OK single rule`() {
        val rulesStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-rules.csv")
            ?: error("Missing state-income-tax-2025-rules.csv on test classpath")
        val bracketsStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-brackets.csv")
            ?: error("Missing state-income-tax-2025-brackets.csv on test classpath")

        val rules = InputStreamReader(rulesStream).use { rulesReader ->
            InputStreamReader(bracketsStream).use { bracketsReader ->
                StateIncomeTaxCsvParser.parse(rulesReader, bracketsReader)
            }
        }

        val ok = rules.firstOrNull { it.jurisdictionCode == "OK" && it.filingStatus == "SINGLE" }
        assertNotNull(ok, "Expected OK SINGLE rule in state-income-tax-2025 CSVs")
        assertEquals("STATE", ok.jurisdictionType)
        assertEquals("StateTaxable", ok.basis)
        assertEquals("BRACKETED", ok.ruleType)

        val okBrackets = ok.brackets
        assertNotNull(okBrackets)
        // 6 brackets; top rate 4.75%
        assertEquals(6, okBrackets.size)
        assertEquals(0.0475, okBrackets.last().rate)
    }

    @Test
    fun `parse CSVs and validate SC single rule`() {
        val rulesStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-rules.csv")
            ?: error("Missing state-income-tax-2025-rules.csv on test classpath")
        val bracketsStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-brackets.csv")
            ?: error("Missing state-income-tax-2025-brackets.csv on test classpath")

        val rules = InputStreamReader(rulesStream).use { rulesReader ->
            InputStreamReader(bracketsStream).use { bracketsReader ->
                StateIncomeTaxCsvParser.parse(rulesReader, bracketsReader)
            }
        }

        val sc = rules.firstOrNull { it.jurisdictionCode == "SC" && it.filingStatus == "SINGLE" }
        assertNotNull(sc, "Expected SC SINGLE rule in state-income-tax-2025 CSVs")
        assertEquals("STATE", sc.jurisdictionType)
        assertEquals("StateTaxable", sc.basis)
        assertEquals("BRACKETED", sc.ruleType)

        val scBrackets = sc.brackets
        assertNotNull(scBrackets)
        // 3 brackets; top rate 6.0%
        assertEquals(3, scBrackets.size)
        assertEquals(0.060, scBrackets.last().rate)
    }

    @Test
    fun `parse CSVs and validate VT single rule`() {
        val rulesStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-rules.csv")
            ?: error("Missing state-income-tax-2025-rules.csv on test classpath")
        val bracketsStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-brackets.csv")
            ?: error("Missing state-income-tax-2025-brackets.csv on test classpath")

        val rules = InputStreamReader(rulesStream).use { rulesReader ->
            InputStreamReader(bracketsStream).use { bracketsReader ->
                StateIncomeTaxCsvParser.parse(rulesReader, bracketsReader)
            }
        }

        val vt = rules.firstOrNull { it.jurisdictionCode == "VT" && it.filingStatus == "SINGLE" }
        assertNotNull(vt, "Expected VT SINGLE rule in state-income-tax-2025 CSVs")
        assertEquals("STATE", vt.jurisdictionType)
        assertEquals("StateTaxable", vt.basis)
        assertEquals("BRACKETED", vt.ruleType)

        val vtBrackets = vt.brackets
        assertNotNull(vtBrackets)
        // 4 brackets; top rate 8.75%
        assertEquals(4, vtBrackets.size)
        assertEquals(0.0875, vtBrackets.last().rate)
    }

    @Test
    fun `parse CSVs and validate WV single rule`() {
        val rulesStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-rules.csv")
            ?: error("Missing state-income-tax-2025-rules.csv on test classpath")
        val bracketsStream = javaClass.classLoader.getResourceAsStream("state-income-tax-2025-brackets.csv")
            ?: error("Missing state-income-tax-2025-brackets.csv on test classpath")

        val rules = InputStreamReader(rulesStream).use { rulesReader ->
            InputStreamReader(bracketsStream).use { bracketsReader ->
                StateIncomeTaxCsvParser.parse(rulesReader, bracketsReader)
            }
        }

        val wv = rules.firstOrNull { it.jurisdictionCode == "WV" && it.filingStatus == "SINGLE" }
        assertNotNull(wv, "Expected WV SINGLE rule in state-income-tax-2025 CSVs")
        assertEquals("STATE", wv.jurisdictionType)
        assertEquals("StateTaxable", wv.basis)
        assertEquals("BRACKETED", wv.ruleType)

        val wvBrackets = wv.brackets
        assertNotNull(wvBrackets)
        // 5 brackets; top rate 4.82%
        assertEquals(5, wvBrackets.size)
        assertEquals(0.0482, wvBrackets.last().rate)
    }
}
