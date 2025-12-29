package com.example.usbilling.tax.domain

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@Table("rate_tariff")
data class RateTariffEntity(
    @Id @Column("tariff_id") val tariffId: String,
    @Column("utility_id") val utilityId: String,
    @Column("tariff_name") val tariffName: String,
    @Column("tariff_code") val tariffCode: String,
    @Column("rate_structure") val rateStructure: String,
    @Column("utility_service_type") val utilityServiceType: String,
    @Column("customer_class") val customerClass: String?,
    @Column("effective_date") val effectiveDate: LocalDate,
    @Column("expiry_date") val expiryDate: LocalDate?,
    @Column("active") val active: Boolean = true,
    @Column("readiness_to_serve_cents") val readinessToServeCents: Int,
    @Column("created_at") val createdAt: Instant = Instant.now(),
    @Column("updated_at") val updatedAt: Instant = Instant.now(),
)

@Table("rate_component")
data class RateComponentEntity(
    @Id @Column("component_id") val componentId: String,
    @Column("tariff_id") val tariffId: String,
    @Column("charge_type") val chargeType: String,
    @Column("rate_value_cents") val rateValueCents: Int,
    @Column("threshold") val threshold: BigDecimal?,
    @Column("tou_period") val touPeriod: String?,
    @Column("season") val season: String?,
    @Column("component_order") val componentOrder: Int = 0,
    @Column("created_at") val createdAt: Instant = Instant.now(),
)

@Table("tou_schedule")
data class TouScheduleEntity(
    @Id @Column("schedule_id") val scheduleId: String,
    @Column("tariff_id") val tariffId: String,
    @Column("schedule_name") val scheduleName: String,
    @Column("season") val season: String?,
    @Column("tou_period") val touPeriod: String,
    @Column("start_hour") val startHour: Int,
    @Column("end_hour") val endHour: Int,
    @Column("day_of_week_mask") val dayOfWeekMask: Int,
    @Column("created_at") val createdAt: Instant = Instant.now(),
)

@Table("tariff_regulatory_charge")
data class TariffRegulatoryChargeEntity(
    @Id @Column("charge_id") val chargeId: String,
    @Column("tariff_id") val tariffId: String,
    @Column("charge_code") val chargeCode: String,
    @Column("charge_description") val chargeDescription: String,
    @Column("calculation_type") val calculationType: String,
    @Column("rate_value_cents") val rateValueCents: Int,
    @Column("created_at") val createdAt: Instant = Instant.now(),
)
