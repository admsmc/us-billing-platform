package com.example.uspayroll.orchestrator.config

import com.example.uspayroll.payroll.model.DeductionCode
import com.example.uspayroll.payroll.model.EarningCode
import com.example.uspayroll.payroll.model.EmployerContributionCode
import com.example.uspayroll.payroll.model.TaxBasis
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.KeyDeserializer
import com.fasterxml.jackson.databind.Module
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class PayrollDomainJacksonConfig {

    @Bean
    fun payrollDomainKeyModule(): Module {
        val module = SimpleModule("PayrollDomainKeyModule")

        module.addKeySerializer(
            EarningCode::class.java,
            object : JsonSerializer<EarningCode>() {
                override fun serialize(value: EarningCode, gen: JsonGenerator, serializers: SerializerProvider) {
                    gen.writeFieldName(value.value)
                }
            },
        )
        module.addKeyDeserializer(
            EarningCode::class.java,
            object : KeyDeserializer() {
                override fun deserializeKey(key: String, ctxt: DeserializationContext): Any = EarningCode(key)
            },
        )

        module.addKeySerializer(
            DeductionCode::class.java,
            object : JsonSerializer<DeductionCode>() {
                override fun serialize(value: DeductionCode, gen: JsonGenerator, serializers: SerializerProvider) {
                    gen.writeFieldName(value.value)
                }
            },
        )
        module.addKeyDeserializer(
            DeductionCode::class.java,
            object : KeyDeserializer() {
                override fun deserializeKey(key: String, ctxt: DeserializationContext): Any = DeductionCode(key)
            },
        )

        module.addKeySerializer(
            EmployerContributionCode::class.java,
            object : JsonSerializer<EmployerContributionCode>() {
                override fun serialize(value: EmployerContributionCode, gen: JsonGenerator, serializers: SerializerProvider) {
                    gen.writeFieldName(value.value)
                }
            },
        )
        module.addKeyDeserializer(
            EmployerContributionCode::class.java,
            object : KeyDeserializer() {
                override fun deserializeKey(key: String, ctxt: DeserializationContext): Any = EmployerContributionCode(key)
            },
        )

        module.addKeySerializer(
            TaxBasis::class.java,
            object : JsonSerializer<TaxBasis>() {
                override fun serialize(value: TaxBasis, gen: JsonGenerator, serializers: SerializerProvider) {
                    gen.writeFieldName(taxBasisKey(value))
                }
            },
        )
        module.addKeyDeserializer(
            TaxBasis::class.java,
            object : KeyDeserializer() {
                override fun deserializeKey(key: String, ctxt: DeserializationContext): Any = parseTaxBasisKey(key)
            },
        )

        return module
    }

    private fun taxBasisKey(basis: TaxBasis): String = when (basis) {
        TaxBasis.Gross -> "Gross"
        TaxBasis.FederalTaxable -> "FederalTaxable"
        TaxBasis.StateTaxable -> "StateTaxable"
        TaxBasis.SocialSecurityWages -> "SocialSecurityWages"
        TaxBasis.MedicareWages -> "MedicareWages"
        TaxBasis.SupplementalWages -> "SupplementalWages"
        TaxBasis.FutaWages -> "FutaWages"
    }

    private fun parseTaxBasisKey(key: String): TaxBasis = when (key) {
        "Gross" -> TaxBasis.Gross
        "FederalTaxable" -> TaxBasis.FederalTaxable
        "StateTaxable" -> TaxBasis.StateTaxable
        "SocialSecurityWages" -> TaxBasis.SocialSecurityWages
        "MedicareWages" -> TaxBasis.MedicareWages
        "SupplementalWages" -> TaxBasis.SupplementalWages
        "FutaWages" -> TaxBasis.FutaWages
        else -> throw IllegalArgumentException("Unknown TaxBasis key '$key'")
    }
}
