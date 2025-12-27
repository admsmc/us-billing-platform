package com.example.usbilling.payroll.engine

import com.example.usbilling.payroll.model.*
import com.example.usbilling.shared.Money

/** Result of tax computation for a single paycheck. */
data class TaxComputationResult(
    val employeeTaxes: List<TaxLine>,
    val employerTaxes: List<TaxLine>,
    val traceSteps: List<TraceStep>,
)

/**
 * Simple tax calculator for demonstration:
 * - Uses an explicit map of TaxBasis -> Money (e.g. Gross, FederalTaxable).
 * - Applies employee FlatRateTax rules (federal/state/local) on their configured basis.
 * - Applies employer-specific FlatRateTax rules on their configured basis.
 */
object TaxesCalculator {

    private fun computeBracketedTax(basisMoney: Money, rule: TaxRule.BracketedIncomeTax): Pair<Money, List<BracketApplication>> {
        // Apply standard deduction if present
        val deductionCents = rule.standardDeduction?.amount ?: 0L
        val taxableTotal = (basisMoney.amount - deductionCents).coerceAtLeast(0L)
        if (taxableTotal == 0L) return Money(0L, basisMoney.currency) to emptyList()

        var remaining = taxableTotal
        var previousUpper = 0L
        var totalTaxCents = 0L

        val applications = ArrayList<BracketApplication>(rule.brackets.size)

        for (bracket in rule.brackets) {
            if (remaining <= 0L) break

            val upper = bracket.upTo?.amount ?: Long.MAX_VALUE
            if (upper <= previousUpper) continue

            val span = upper - previousUpper
            val applied = minOf(remaining, span)
            if (applied <= 0L) {
                previousUpper = upper
                continue
            }

            val taxCents = (applied * bracket.rate.value).toLong()
            totalTaxCents += taxCents

            applications.add(
                BracketApplication(
                    bracket = bracket,
                    appliedTo = Money(applied, basisMoney.currency),
                    amount = Money(taxCents, basisMoney.currency),
                ),
            )

            remaining -= applied
            previousUpper = upper
        }

        return Money(totalTaxCents, basisMoney.currency) to applications
    }

    fun computeTaxes(input: PaycheckInput, bases: Map<TaxBasis, Money>, basisComponents: Map<TaxBasis, Map<String, Money>>, includeTrace: Boolean = true): TaxComputationResult {
        // Basis steps for each known basis value (debug trace only)
        val basisTraceSteps: List<TraceStep> = if (includeTrace) {
            bases.map { (basis, amount) ->
                val components = basisComponents[basis] ?: mapOf(
                    when (basis) {
                        TaxBasis.Gross -> "gross"
                        TaxBasis.FederalTaxable -> "federalTaxable"
                        TaxBasis.StateTaxable -> "stateTaxable"
                        TaxBasis.SocialSecurityWages -> "socialSecurityWages"
                        TaxBasis.MedicareWages -> "medicareWages"
                        TaxBasis.SupplementalWages -> "supplementalWages"
                        TaxBasis.FutaWages -> "futaWages"
                    } to amount,
                )
                TraceStep.BasisComputed(
                    basis = basis,
                    components = components,
                    result = amount,
                )
            }
        } else {
            emptyList()
        }

        fun shouldSkipFicaOrMedicare(basis: TaxBasis, basisMoney: Money): Boolean {
            val snapshot = input.employeeSnapshot
            val employmentType = snapshot.employmentType

            val isFicaBasis = basis is TaxBasis.SocialSecurityWages || basis is TaxBasis.MedicareWages
            if (!isFicaBasis) return false

            // Global FICA exemption flag.
            if (snapshot.ficaExempt) return true

            // Special thresholds for household and election workers; below
            // these amounts in the year, FICA/Medicare should not apply.
            val thresholdCents: Long? = when (employmentType) {
                com.example.uspayroll.payroll.model.EmploymentType.HOUSEHOLD -> 2_800_00L
                com.example.uspayroll.payroll.model.EmploymentType.ELECTION_WORKER -> 2_400_00L
                else -> null
            }

            if (thresholdCents != null) {
                val priorWages = input.priorYtd.wagesByBasis[basis]?.amount ?: 0L
                if (priorWages + basisMoney.amount < thresholdCents) {
                    return true
                }
            }

            return false
        }

        fun TaxRule.localityFilterOrNull(): String? = when (this) {
            is TaxRule.FlatRateTax -> localityFilter
            is TaxRule.BracketedIncomeTax -> localityFilter
            is TaxRule.WageBracketTax -> localityFilter
        }

        fun normalizeLocalityKey(raw: String): String = raw.trim().uppercase()

        fun resolveLocalityAllocationsForLocals(localityKeys: List<String>): Map<String, Double> {
            val explicit = input.timeSlice.localityAllocations
                .mapNotNull { (k, v) ->
                    val key = k.trim()
                    if (key.isBlank()) return@mapNotNull null
                    val value = v
                    if (!value.isFinite() || value <= 0.0) return@mapNotNull null
                    normalizeLocalityKey(key) to value
                }
                .toMap()

            if (localityKeys.isEmpty()) return emptyMap()

            if (explicit.isEmpty()) {
                if (localityKeys.size == 1) {
                    return mapOf(localityKeys.single() to 1.0)
                }
                val even = 1.0 / localityKeys.size.toDouble()
                return localityKeys.associateWith { even }
            }

            val filtered = localityKeys
                .mapNotNull { key -> explicit[key]?.let { key to it } }
                .toMap()

            val sum = filtered.values.sum()
            if (sum <= 0.0) return emptyMap()

            // If the caller provided fractions summing to > 1, normalize down.
            val scale = if (sum > 1.0) 1.0 / sum else 1.0
            return filtered.mapValues { (_, v) -> v * scale }
        }

        fun allocateCentsByLocality(totalCents: Long, allocations: Map<String, Double>): Map<String, Long> {
            if (totalCents <= 0L || allocations.isEmpty()) return emptyMap()

            val sortedKeys = allocations.keys.sorted()
            val scaledSum = sortedKeys.sumOf { allocations.getValue(it) }
            if (scaledSum <= 0.0) return emptyMap()

            val targetTotal = (totalCents * minOf(1.0, scaledSum)).toLong()

            data class Part(val key: String, val floor: Long, val remainder: Double)

            val parts = sortedKeys.map { key ->
                val fraction = allocations.getValue(key)
                val raw = totalCents.toDouble() * fraction
                val floor = raw.toLong()
                Part(key = key, floor = floor, remainder = raw - floor.toDouble())
            }

            val floorSum = parts.sumOf { it.floor }
            val leftover = (targetTotal - floorSum).coerceAtLeast(0L)

            val ranked = parts.sortedWith(compareByDescending<Part> { it.remainder }.thenBy { it.key })

            val out = LinkedHashMap<String, Long>(parts.size)
            for (p in parts) out[p.key] = p.floor

            if (ranked.isNotEmpty() && leftover > 0L) {
                val maxIterations = leftover.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                for (i in 0 until maxIterations) {
                    val key = ranked[i % ranked.size].key
                    out[key] = (out[key] ?: 0L) + 1L
                }
            }

            return out
        }

        val employeeRules = input.taxContext.federal + input.taxContext.state + input.taxContext.local

        val localKeys = employeeRules
            .asSequence()
            .filter { it.jurisdiction.type == TaxJurisdictionType.LOCAL }
            .mapNotNull { it.localityFilterOrNull() }
            .map(::normalizeLocalityKey)
            .distinct()
            .sorted()
            .toList()

        val localityAllocations = resolveLocalityAllocationsForLocals(localKeys)
        val allocatedBasisCache = HashMap<TaxBasis, Map<String, Long>>()

        fun basisMoneyForRule(rule: TaxRule): Money? {
            val base = bases[rule.basis] ?: return null

            if (rule.jurisdiction.type != TaxJurisdictionType.LOCAL) {
                return base
            }

            val key = rule.localityFilterOrNull()?.let(::normalizeLocalityKey) ?: return base
            val fraction = localityAllocations[key] ?: return Money(0L, base.currency)

            if (fraction <= 0.0) return Money(0L, base.currency)

            val centsByLocality = allocatedBasisCache.getOrPut(rule.basis) {
                allocateCentsByLocality(base.amount, localityAllocations)
            }
            val cents = centsByLocality[key] ?: 0L
            return Money(cents, base.currency)
        }

        fun isFederalIncomeTaxRule(rule: TaxRule): Boolean {
            if (rule.jurisdiction.type != TaxJurisdictionType.FEDERAL) return false
            return rule.basis == TaxBasis.Gross || rule.basis == TaxBasis.FederalTaxable
        }

        var remainingEmployeeAdditionalWithholdingCents: Long = input.employeeSnapshot.additionalWithholdingPerPeriod?.amount ?: 0L

        fun applyFlatTax(
            rule: TaxRule.FlatRateTax,
            descriptionPrefix: String,
            basisMoney: Money,
            target: MutableList<TaxLine>,
            trace: MutableList<TraceStep>?,
            employeeAdditionalWithholdingCents: Long = 0L,
        ) {
            if (shouldSkipFicaOrMedicare(rule.basis, basisMoney)) {
                return
            }

            val taxableCents: Long = if (rule.annualWageCap != null) {
                val priorWages = input.priorYtd.wagesByBasis[rule.basis]?.amount ?: 0L
                val capCents = rule.annualWageCap.amount
                val remaining = capCents - priorWages
                if (remaining <= 0L) {
                    // Already above the wage base cap; no further tax this period.
                    return
                }
                minOf(basisMoney.amount, remaining)
            } else {
                basisMoney.amount
            }

            val amountCents = (taxableCents * rule.rate.value).toLong() + employeeAdditionalWithholdingCents
            if (amountCents == 0L) return

            val taxedBasis = Money(taxableCents, basisMoney.currency)
            val taxAmount = Money(amountCents, basisMoney.currency)

            target.add(
                TaxLine(
                    ruleId = rule.id,
                    jurisdiction = rule.jurisdiction,
                    description = "$descriptionPrefix ${rule.jurisdiction.code}",
                    basis = taxedBasis,
                    rate = rule.rate,
                    amount = taxAmount,
                ),
            )

            if (trace != null) {
                trace.add(
                    TraceStep.TaxApplied(
                        ruleId = rule.id,
                        jurisdiction = rule.jurisdiction,
                        basis = taxedBasis,
                        brackets = null,
                        rate = rule.rate,
                        amount = taxAmount,
                    ),
                )
                if (employeeAdditionalWithholdingCents != 0L) {
                    trace.add(TraceStep.AdditionalWithholdingApplied(amount = Money(employeeAdditionalWithholdingCents, basisMoney.currency)))
                }
            }
        }

        fun applyBracketedTax(
            rule: TaxRule.BracketedIncomeTax,
            descriptionPrefix: String,
            basisMoney: Money,
            target: MutableList<TaxLine>,
            trace: MutableList<TraceStep>?,
            employeeAdditionalWithholdingCents: Long = 0L,
        ) {
            if (shouldSkipFicaOrMedicare(rule.basis, basisMoney)) {
                return
            }

            // Special handling for Additional Medicare: apply the 0.9% rate
            // only to wages in this period that exceed the annual threshold
            // when combined with prior-year-to-date Medicare wages for this
            // employer, per IRS rules.
            if (rule.id == "US_FED_ADDITIONAL_MEDICARE_2025" && rule.basis is TaxBasis.MedicareWages) {
                val priorYtd = input.priorYtd.wagesByBasis[TaxBasis.MedicareWages]?.amount ?: 0L
                val current = basisMoney.amount
                val addlTaxCents = FicaPolicy.additionalMedicareForPeriod(
                    priorMedicareYtdCents = priorYtd,
                    currentMedicareCents = current,
                )
                if (addlTaxCents <= 0L) {
                    return
                }

                val taxAmount = Money(addlTaxCents, basisMoney.currency)
                target.add(
                    TaxLine(
                        ruleId = rule.id,
                        jurisdiction = rule.jurisdiction,
                        description = "$descriptionPrefix ${rule.jurisdiction.code}",
                        basis = basisMoney,
                        rate = null,
                        amount = taxAmount,
                    ),
                )
                trace?.add(
                    TraceStep.TaxApplied(
                        ruleId = rule.id,
                        jurisdiction = rule.jurisdiction,
                        basis = basisMoney,
                        brackets = emptyList(),
                        rate = null,
                        amount = taxAmount,
                    ),
                )
                return
            }

            val (bracketTax, brackets) = computeBracketedTax(basisMoney, rule)

            val ruleExtra = rule.additionalWithholding
            val totalTaxCents = bracketTax.amount + (ruleExtra?.amount ?: 0L) + employeeAdditionalWithholdingCents

            if (totalTaxCents == 0L) return

            val taxAmount = Money(totalTaxCents, basisMoney.currency)
            target.add(
                TaxLine(
                    ruleId = rule.id,
                    jurisdiction = rule.jurisdiction,
                    description = "$descriptionPrefix ${rule.jurisdiction.code}",
                    basis = basisMoney,
                    rate = null,
                    amount = taxAmount,
                ),
            )
            if (trace != null) {
                trace.add(
                    TraceStep.TaxApplied(
                        ruleId = rule.id,
                        jurisdiction = rule.jurisdiction,
                        basis = basisMoney,
                        brackets = brackets,
                        rate = null,
                        amount = taxAmount,
                    ),
                )
                if (employeeAdditionalWithholdingCents != 0L) {
                    trace.add(TraceStep.AdditionalWithholdingApplied(amount = Money(employeeAdditionalWithholdingCents, basisMoney.currency)))
                }
            }
        }

        fun applyWageBracketTax(
            rule: TaxRule.WageBracketTax,
            descriptionPrefix: String,
            basisMoney: Money,
            target: MutableList<TaxLine>,
            trace: MutableList<TraceStep>?,
            employeeAdditionalWithholdingCents: Long = 0L,
        ) {
            if (shouldSkipFicaOrMedicare(rule.basis, basisMoney)) {
                return
            }

            // Find the first bracket whose upper bound contains the basis amount.
            val amount = basisMoney.amount
            val row = rule.brackets.firstOrNull { bracket ->
                val upper = bracket.upTo?.amount ?: Long.MAX_VALUE
                amount <= upper
            } ?: return

            val totalTaxCents = row.tax.amount + employeeAdditionalWithholdingCents
            if (totalTaxCents == 0L) return

            val taxAmount = Money(totalTaxCents, basisMoney.currency)
            target.add(
                TaxLine(
                    ruleId = rule.id,
                    jurisdiction = rule.jurisdiction,
                    description = "$descriptionPrefix ${rule.jurisdiction.code}",
                    basis = basisMoney,
                    rate = null,
                    amount = taxAmount,
                ),
            )
            if (trace != null) {
                trace.add(
                    TraceStep.TaxApplied(
                        ruleId = rule.id,
                        jurisdiction = rule.jurisdiction,
                        basis = basisMoney,
                        brackets = emptyList(),
                        rate = null,
                        amount = taxAmount,
                    ),
                )
                if (employeeAdditionalWithholdingCents != 0L) {
                    trace.add(TraceStep.AdditionalWithholdingApplied(amount = Money(employeeAdditionalWithholdingCents, basisMoney.currency)))
                }
            }
        }

        val employeeTaxes = ArrayList<TaxLine>(employeeRules.size)
        val employerTaxes = ArrayList<TaxLine>(input.taxContext.employerSpecific.size)
        val traceSteps: MutableList<TraceStep>? = if (includeTrace) {
            ArrayList<TraceStep>(basisTraceSteps.size + employeeRules.size + input.taxContext.employerSpecific.size).apply {
                addAll(basisTraceSteps)
            }
        } else {
            null
        }

        for (rule in employeeRules) {
            val basisMoney = basisMoneyForRule(rule) ?: continue
            if (basisMoney.amount == 0L) continue

            val extraCents = if (remainingEmployeeAdditionalWithholdingCents != 0L && isFederalIncomeTaxRule(rule)) {
                val applied = remainingEmployeeAdditionalWithholdingCents
                remainingEmployeeAdditionalWithholdingCents = 0L
                applied
            } else {
                0L
            }

            when (rule) {
                is TaxRule.FlatRateTax -> applyFlatTax(rule, "Employee tax", basisMoney, employeeTaxes, traceSteps, employeeAdditionalWithholdingCents = extraCents)
                is TaxRule.BracketedIncomeTax -> applyBracketedTax(rule, "Employee tax", basisMoney, employeeTaxes, traceSteps, employeeAdditionalWithholdingCents = extraCents)
                is TaxRule.WageBracketTax -> applyWageBracketTax(rule, "Employee tax", basisMoney, employeeTaxes, traceSteps, employeeAdditionalWithholdingCents = extraCents)
            }
        }

        for (rule in input.taxContext.employerSpecific) {
            val basisMoney = basisMoneyForRule(rule) ?: continue
            if (basisMoney.amount == 0L) continue

            when (rule) {
                is TaxRule.FlatRateTax -> applyFlatTax(rule, "Employer tax", basisMoney, employerTaxes, traceSteps)
                is TaxRule.BracketedIncomeTax -> applyBracketedTax(rule, "Employer tax", basisMoney, employerTaxes, traceSteps)
                is TaxRule.WageBracketTax -> applyWageBracketTax(rule, "Employer tax", basisMoney, employerTaxes, traceSteps)
            }
        }

        return TaxComputationResult(
            employeeTaxes = employeeTaxes,
            employerTaxes = employerTaxes,
            traceSteps = traceSteps ?: emptyList(),
        )
    }
}
