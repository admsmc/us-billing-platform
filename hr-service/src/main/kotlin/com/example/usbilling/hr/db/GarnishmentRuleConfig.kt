package com.example.usbilling.hr.db

import com.example.usbilling.payroll.model.Percent
import com.example.usbilling.payroll.model.TaxJurisdiction
import com.example.usbilling.payroll.model.TaxJurisdictionType
import com.example.usbilling.payroll.model.garnishment.GarnishmentFormula
import com.example.usbilling.payroll.model.garnishment.GarnishmentType
import com.example.usbilling.payroll.model.garnishment.ProtectedEarningsRule
import com.example.usbilling.shared.EmployerId
import com.example.usbilling.shared.Money
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Repository

/**
 * Configuration model for a statutory garnishment rule. In a real system this
 * would be loaded from CSV/JSON or a database; for Phase 4 we start with an
 * in-memory stub to prove out the mapping into GarnishmentOrder.
 */
data class GarnishmentRuleConfig(
    val type: GarnishmentType,
    val jurisdiction: TaxJurisdiction?,
    val formula: GarnishmentFormula,
    val protectedEarningsRule: ProtectedEarningsRule? = null,
    /** Optional human-readable description for admin/ops tooling. */
    val description: String? = null,
)

interface GarnishmentRuleConfigRepository {
    fun findRulesForEmployer(employerId: EmployerId): List<GarnishmentRuleConfig>
}

/**
 * JSON-backed implementation that loads generic garnishment rules from
 * classpath `garnishment-rules.json`. This is a starting point for Phase 4
 * compliance modeling; a future iteration can move this into a database or
 * admin-managed config.
 */
@Repository
class JsonGarnishmentRuleConfigRepository(
    private val objectMapper: ObjectMapper,
) : GarnishmentRuleConfigRepository {

    private data class JsonRule(
        val employerId: String?,
        val type: GarnishmentType,
        val jurisdictionType: TaxJurisdictionType?,
        val jurisdictionCode: String?,
        val percentOfDisposable: Double?,
        val protectedFloorCents: Long?,
        val description: String? = null,
        /**
         * Optional formula discriminator. When omitted, rules default to a
         * simple PercentOfDisposable formula for backward compatibility.
         *
         * Supported values:
         * - "PERCENT_OF_DISPOSABLE"
         * - "FIXED_AMOUNT_PER_PERIOD"
         * - "LESSER_OF_PERCENT_OR_AMOUNT"
         * - "LEVY_WITH_BANDS" (uses exemption bands from garnishment-levy-bands.json)
         */
        val formulaType: String? = null,
        /** Fixed amount in cents for FIXED_AMOUNT_PER_PERIOD or the
         * amount component of LESSER_OF_PERCENT_OR_AMOUNT. */
        val fixedAmountCents: Long? = null,
    )

    private data class LevyBandJson(
        val upToCents: Long?,
        val exemptCents: Long,
        val filingStatus: String? = null,
    )

    private data class LevyConfigJson(
        val type: GarnishmentType,
        val jurisdictionType: TaxJurisdictionType,
        val jurisdictionCode: String,
        val bands: List<LevyBandJson>,
    )

    private data class LoadedRule(
        val employerId: String?,
        val config: GarnishmentRuleConfig,
    )

    private val levyBandsByKey: Map<Pair<GarnishmentType, TaxJurisdiction>, List<com.example.uspayroll.payroll.model.garnishment.LevyBand>> by lazy {
        val resource = ClassPathResource("garnishment-levy-bands.json")
        if (!resource.exists()) return@lazy emptyMap()

        val jsonList: List<LevyConfigJson> = resource.inputStream.use { input ->
            objectMapper.readValue(input, object : TypeReference<List<LevyConfigJson>>() {})
        }

        jsonList.associate { cfg ->
            val jurisdiction = TaxJurisdiction(cfg.jurisdictionType, cfg.jurisdictionCode)
            val bands = cfg.bands.map { band ->
                val statusEnum = band.filingStatus
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.uppercase()
                    ?.let { com.example.uspayroll.payroll.model.FilingStatus.valueOf(it) }
                com.example.uspayroll.payroll.model.garnishment.LevyBand(
                    upToCents = band.upToCents,
                    exemptCents = band.exemptCents,
                    filingStatus = statusEnum,
                )
            }
            (cfg.type to jurisdiction) to bands
        }
    }

    private val rules: List<LoadedRule> by lazy {
        val resource = ClassPathResource("garnishment-rules.json")
        if (!resource.exists()) return@lazy emptyList<LoadedRule>()

        val jsonList: List<JsonRule> = resource.inputStream.use { input ->
            objectMapper.readValue(input, object : TypeReference<List<JsonRule>>() {})
        }

        jsonList.map { r ->
            val jurisdiction = if (r.jurisdictionType != null && r.jurisdictionCode != null) {
                TaxJurisdiction(r.jurisdictionType, r.jurisdictionCode)
            } else {
                null
            }

            val formula = when ((r.formulaType ?: "PERCENT_OF_DISPOSABLE").uppercase()) {
                "FIXED_AMOUNT_PER_PERIOD" -> GarnishmentFormula.FixedAmountPerPeriod(
                    Money(r.fixedAmountCents ?: 0L),
                )
                "LESSER_OF_PERCENT_OR_AMOUNT" -> {
                    val pct = r.percentOfDisposable ?: 0.0
                    val amt = r.fixedAmountCents ?: 0L
                    GarnishmentFormula.LesserOfPercentOrAmount(
                        percent = Percent(pct),
                        amount = Money(amt),
                    )
                }
                "LEVY_WITH_BANDS" -> {
                    val bands = jurisdiction?.let { j ->
                        levyBandsByKey[r.type to j]
                    }
                    if (bands.isNullOrEmpty()) {
                        // Fall back to a simple percent-of-disposable formula
                        // if no bands are configured.
                        GarnishmentFormula.PercentOfDisposable(
                            Percent(r.percentOfDisposable ?: 0.0),
                        )
                    } else {
                        GarnishmentFormula.LevyWithBands(bands)
                    }
                }
                else -> GarnishmentFormula.PercentOfDisposable(
                    Percent(r.percentOfDisposable ?: 0.0),
                )
            }

            val protectedRule = r.protectedFloorCents?.let { cents ->
                ProtectedEarningsRule.FixedFloor(Money(cents))
            }

            LoadedRule(
                employerId = r.employerId,
                config = GarnishmentRuleConfig(
                    type = r.type,
                    jurisdiction = jurisdiction,
                    formula = formula,
                    protectedEarningsRule = protectedRule,
                    description = r.description,
                ),
            )
        }
    }

    override fun findRulesForEmployer(employerId: EmployerId): List<GarnishmentRuleConfig> {
        val global = rules.filter { it.employerId == null }.map { it.config }
        val specific = rules.filter { it.employerId == employerId.value }.map { it.config }

        // Employer-specific rules should overlay global rules (override on key),
        // but we keep a deterministic order that puts employer-specific rules first.
        // This makes the /garnishments fallback mode predictable.
        val overriddenKeys = specific.map { it.type to it.jurisdiction }.toSet()
        val globalNotOverridden = global.filterNot { overriddenKeys.contains(it.type to it.jurisdiction) }

        return specific + globalNotOverridden
    }
}
