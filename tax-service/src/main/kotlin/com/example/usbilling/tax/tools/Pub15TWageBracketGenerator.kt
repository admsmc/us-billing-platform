package com.example.usbilling.tax.tools

import com.example.usbilling.payroll.engine.TaxesCalculator
import com.example.usbilling.payroll.model.*
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import com.example.usbilling.shared.Money
import com.example.usbilling.shared.BillRunId
import com.example.usbilling.shared.BillId
import com.example.usbilling.tax.config.TaxBracketConfig
import com.example.usbilling.tax.config.TaxRuleConfig
import com.example.usbilling.tax.config.TaxRuleFile
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.time.LocalDate

/**
 * Small offline tool that derives a WAGE_BRACKET JSON config from the
 * canonical Pub 15-T BRACKETED config by running the core tax engine over a
 * grid of wages for a given filing status and pay frequency.
 *
 * Usage (example):
 *   ./gradlew :tax-service:run -q --args="\
 *     --pub15tResource=tax-config/federal-2025-pub15t.json \
 *     --filingStatus=SINGLE \
 *     --frequency=BIWEEKLY \
 *     --min=0 --max=500000 --step=5000 \
 *     --output=federal-2025-pub15t-wage-bracket-biweekly-generated.json"
 */
object Pub15TWageBracketGenerator {

    private val objectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(SerializationFeature.INDENT_OUTPUT)

    @JvmStatic
    fun main(args: Array<String>) {
        val params = parseArgs(args.toList())

        val outFile = generateFromResource(
            pub15tResource = params.pub15tResource,
            filingStatus = params.filingStatus,
            frequency = params.frequency,
            minCents = params.minCents,
            maxCents = params.maxCents,
            stepCents = params.stepCents,
        )

        val output = File(params.outputPath)
        output.parentFile?.mkdirs()
        objectMapper.writeValue(output, outFile)

        println("WAGE_BRACKET config written to ${output.absolutePath}")
    }

    /**
     * Programmatic API used by tests and tools: generate a WAGE_BRACKET
     * TaxRuleFile in-memory from the canonical Pub 15-T BRACKETED config.
     */
    fun generateFromResource(pub15tResource: String, filingStatus: FilingStatus, frequency: PayFrequency, minCents: Long, maxCents: Long, stepCents: Long): TaxRuleFile {
        val pub15tFile = loadPub15T(pub15tResource)
        val bracketRule = findBracketedFitRule(pub15tFile, filingStatus)

        val wageBracketRule = generateWageBracketRule(
            bracketRule = bracketRule,
            filingStatus = filingStatus,
            frequency = frequency,
            minWageCents = minCents,
            maxWageCents = maxCents,
            stepCents = stepCents,
        )

        return TaxRuleFile(rules = listOf(wageBracketRule))
    }

    data class Params(
        val pub15tResource: String,
        val filingStatus: FilingStatus,
        val frequency: PayFrequency,
        val minCents: Long,
        val maxCents: Long,
        val stepCents: Long,
        val outputPath: String,
    )

    private fun parseArgs(args: List<String>): Params {
        fun argValue(name: String, default: String? = null): String {
            val prefix = "--$name="
            val raw = args.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
            return raw ?: default ?: error("Missing required argument --$name")
        }

        val pub15tResource = argValue("pub15tResource", "tax-config/federal-2025-pub15t.json")
        val filingStatus = FilingStatus.valueOf(argValue("filingStatus", "SINGLE"))
        val frequency = PayFrequency.valueOf(argValue("frequency", "BIWEEKLY"))

        val minCents = argValue("min", "0").toLong()
        val maxCents = argValue("max", "500000").toLong()
        val stepCents = argValue("step", "5000").toLong()

        val outputPath = argValue(
            "output",
            "build/generated/pub15t-wage-bracket-${filingStatus.name}-${frequency.name.lowercase()}.json",
        )

        require(minCents >= 0) { "min must be >= 0" }
        require(maxCents > minCents) { "max must be > min" }
        require(stepCents > 0) { "step must be > 0" }

        return Params(
            pub15tResource = pub15tResource,
            filingStatus = filingStatus,
            frequency = frequency,
            minCents = minCents,
            maxCents = maxCents,
            stepCents = stepCents,
            outputPath = outputPath,
        )
    }

    private fun loadPub15T(resourcePath: String): TaxRuleFile {
        val stream = Thread.currentThread().contextClassLoader
            .getResourceAsStream(resourcePath)
            ?: error("Could not find resource '$resourcePath' on classpath")
        stream.use {
            return objectMapper.readValue(it)
        }
    }

    private fun findBracketedFitRule(file: TaxRuleFile, filingStatus: FilingStatus): TaxRuleConfig {
        val targetStatusName = filingStatus.name
        return file.rules.firstOrNull { rule ->
            rule.jurisdictionType == "FEDERAL" &&
                rule.jurisdictionCode == "US" &&
                rule.basis == "FederalTaxable" &&
                rule.ruleType == "BRACKETED" &&
                rule.filingStatus == targetStatusName
        } ?: error("No BRACKETED FederalTaxable FIT rule found for filingStatus=$targetStatusName")
    }

    private fun generateWageBracketRule(bracketRule: TaxRuleConfig, filingStatus: FilingStatus, frequency: PayFrequency, minWageCents: Long, maxWageCents: Long, stepCents: Long): TaxRuleConfig {
        val periodsPerYear = when (frequency) {
            PayFrequency.WEEKLY -> 52
            PayFrequency.BIWEEKLY -> 26
            PayFrequency.FOUR_WEEKLY -> 13
            PayFrequency.SEMI_MONTHLY -> 24
            PayFrequency.MONTHLY -> 12
            PayFrequency.QUARTERLY -> 4
            PayFrequency.ANNUAL -> 1
        }

        // Adapt TaxRuleConfig -> domain BracketedIncomeTax
        val domainRule = toDomainBracketedRule(bracketRule)

        // Compute per-period FIT across the grid using annualize -> compute -> de-annualize
        data class Point(val wagePerPeriodCents: Long, val taxPerPeriodCents: Long)

        val points = mutableListOf<Point>()
        var w = minWageCents
        while (w <= maxWageCents) {
            val annualWages = w * periodsPerYear
            val annualTax = computeAnnualFit(domainRule, filingStatus, annualWages)
            val perPeriodTax = if (periodsPerYear > 0) annualTax / periodsPerYear else annualTax
            points += Point(wagePerPeriodCents = w, taxPerPeriodCents = perPeriodTax)
            w += stepCents
        }

        if (points.isEmpty()) {
            error("No points generated for wage grid; check min/max/step")
        }

        // Compress points into bands where tax is constant across contiguous wages.
        data class Band(val upperWageCents: Long?, val taxCents: Long)

        val bands = mutableListOf<Band>()
        var currentTax = points.first().taxPerPeriodCents
        var bandStart = points.first().wagePerPeriodCents
        var lastWage = bandStart

        for (p in points.drop(1)) {
            if (p.taxPerPeriodCents != currentTax) {
                // Close previous band at lastWage
                bands += Band(upperWageCents = lastWage, taxCents = currentTax)
                currentTax = p.taxPerPeriodCents
                bandStart = p.wagePerPeriodCents
            }
            lastWage = p.wagePerPeriodCents
        }
        // Last band is open-ended
        bands += Band(upperWageCents = null, taxCents = currentTax)

        val wageBrackets: List<TaxBracketConfig> = bands.map { band ->
            TaxBracketConfig(
                upToCents = band.upperWageCents,
                rate = 0.0,
                taxCents = band.taxCents,
            )
        }

        return TaxRuleConfig(
            id = deriveId(bracketRule.id, frequency),
            jurisdictionType = bracketRule.jurisdictionType,
            jurisdictionCode = bracketRule.jurisdictionCode,
            basis = bracketRule.basis,
            ruleType = "WAGE_BRACKET",
            rate = null,
            annualWageCapCents = null,
            brackets = wageBrackets,
            standardDeductionCents = null,
            additionalWithholdingCents = null,
            employerId = null,
            effectiveFrom = bracketRule.effectiveFrom,
            effectiveTo = bracketRule.effectiveTo,
            filingStatus = filingStatus.name,
            residentStateFilter = bracketRule.residentStateFilter,
            workStateFilter = bracketRule.workStateFilter,
            localityFilter = bracketRule.localityFilter,
        )
    }

    private fun deriveId(baseId: String, frequency: PayFrequency): String = when (frequency) {
        PayFrequency.BIWEEKLY -> baseId.replace("_2025_", "_2025_WB_BI_")
        PayFrequency.WEEKLY -> baseId.replace("_2025_", "_2025_WB_WK_")
        else -> baseId + "_WAGE_BRACKET_${frequency.name}"
    }

    private fun toDomainBracketedRule(cfg: TaxRuleConfig): TaxRule.BracketedIncomeTax {
        val jurisdiction = TaxJurisdiction(
            type = TaxJurisdictionType.valueOf(cfg.jurisdictionType),
            code = cfg.jurisdictionCode,
        )
        val brackets = cfg.brackets.orEmpty().map { bCfg ->
            TaxBracket(
                upTo = bCfg.upToCents?.let { Money(it) },
                rate = Percent(bCfg.rate),
            )
        }
        return TaxRule.BracketedIncomeTax(
            id = cfg.id,
            jurisdiction = jurisdiction,
            basis = TaxBasis.FederalTaxable,
            brackets = brackets,
            standardDeduction = cfg.standardDeductionCents?.let { Money(it) },
            additionalWithholding = cfg.additionalWithholdingCents?.let { Money(it) },
            filingStatus = cfg.filingStatus?.let { FilingStatus.valueOf(it) },
        )
    }

    private fun computeAnnualFit(rule: TaxRule.BracketedIncomeTax, filingStatus: FilingStatus, annualWagesCents: Long): Long {
        val employerId = UtilityId("GEN-PUB15T-WB")
        val asOfDate = LocalDate.of(2025, 6, 30)

        val bases: Map<TaxBasis, Money> = mapOf(
            TaxBasis.FederalTaxable to Money(annualWagesCents),
        )
        val basisComponents: Map<TaxBasis, Map<String, Money>> = mapOf(
            TaxBasis.FederalTaxable to mapOf("federalTaxable" to Money(annualWagesCents)),
        )

        val period = PayPeriod(
            id = "PUB15T-ANNUAL-$annualWagesCents",
            employerId = employerId,
            dateRange = LocalDateRange(asOfDate, asOfDate),
            checkDate = asOfDate,
            frequency = PayFrequency.ANNUAL,
        )

        val snapshot = EmployeeSnapshot(
            employerId = employerId,
            employeeId = CustomerId("EE-PUB15T-$annualWagesCents"),
            homeState = "CA",
            workState = "CA",
            filingStatus = filingStatus,
            baseCompensation = BaseCompensation.Salaried(
                annualSalary = Money(annualWagesCents),
                frequency = PayFrequency.ANNUAL,
            ),
        )

        val taxContext = TaxContext(
            federal = listOf(rule),
            state = emptyList(),
            local = emptyList(),
            employerSpecific = emptyList(),
        )

        val input = PaycheckInput(
            paycheckId = BillId("CHK-PUB15T-$annualWagesCents"),
            payRunId = BillRunId("RUN-PUB15T-WB"),
            employerId = employerId,
            employeeId = snapshot.employeeId,
            period = period,
            employeeSnapshot = snapshot,
            timeSlice = TimeSlice(
                period = period,
                regularHours = 0.0,
                overtimeHours = 0.0,
            ),
            taxContext = taxContext,
            priorYtd = YtdSnapshot(year = asOfDate.year),
        )

        val result = TaxesCalculator.computeTaxes(input, bases, basisComponents)
        val fitLine = result.employeeTaxes.firstOrNull { it.ruleId == rule.id }
            ?: return 0L

        return fitLine.amount.amount
    }
}
