package com.example.usbilling.customer.model

import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import java.time.Instant
import java.time.LocalDate

/**
 * Customer entity - represents an individual or organization that interacts with the utility.
 * A customer can have multiple roles on different accounts.
 * Bitemporal tracking for customer profile changes.
 */
data class Customer(
    val customerId: CustomerId,
    val utilityId: UtilityId,
    val customerType: CustomerType,
    val profile: CustomerProfile,

    // Bitemporal fields
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate,
    val systemFrom: Instant,
    val systemTo: Instant,

    // Audit
    val createdBy: String,
    val modifiedBy: String,
    val versionSequence: Int,
)

enum class CustomerType {
    INDIVIDUAL, // Person
    BUSINESS, // Business entity
    GOVERNMENT, // Government agency
    NON_PROFIT, // Non-profit organization
}

/**
 * Customer profile information.
 */
data class CustomerProfile(
    val firstName: String?,
    val lastName: String?,
    val middleName: String?,
    val businessName: String?,
    val taxId: String?,
    val identityVerified: Boolean,
    val identityVerificationDate: LocalDate?,
    val dateOfBirth: LocalDate?,
    val preferredLanguage: String = "en",
    val preferredName: String?,
)

/**
 * Account-Customer relationship - links customers to accounts with specific roles.
 * Multiple customers can be associated with one account (e.g., property owner, bill payer, tenant).
 * Bitemporal tracking to support role changes over time.
 */
data class AccountCustomerRole(
    val relationshipId: String,
    val accountId: String,
    val customerId: CustomerId,
    val roleType: CustomerRoleType,
    val isPrimary: Boolean, // Primary contact for this role type
    val authorizationLevel: AuthorizationLevel,
    val notes: String?,

    // Bitemporal fields
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate,
    val systemFrom: Instant,
    val systemTo: Instant,

    // Audit
    val createdBy: String,
    val modifiedBy: String,
)

/**
 * Customer role types on an account.
 */
enum class CustomerRoleType {
    // Primary roles
    ACCOUNT_HOLDER, // Legal account holder
    BILL_PAYER, // Responsible for payments
    AUTHORIZED_USER, // Can make changes to account

    // Property-related roles
    PROPERTY_OWNER, // Owns the property
    LANDLORD, // Rents out the property
    TENANT, // Rents the property
    PROPERTY_MANAGER, // Manages property on behalf of owner
    MANAGEMENT_COMPANY, // Property management company

    // Additional roles
    EMERGENCY_CONTACT, // Contact in emergencies
    AUTHORIZED_THIRD_PARTY, // Third party with limited access (e.g., energy consultant)
    GUARDIAN, // Legal guardian for account holder
    POWER_OF_ATTORNEY, // Has power of attorney
    OCCUPANT, // Lives at the property but not primary tenant
}

/**
 * Authorization level determines what actions a customer can take on the account.
 */
enum class AuthorizationLevel {
    FULL, // Can perform all account operations
    LIMITED, // Can view and make limited changes
    READ_ONLY, // View-only access
    EMERGENCY_ONLY, // Only emergency contact, no account access
}

/**
 * Property information - represents the physical property being serviced.
 * One property can have multiple accounts (e.g., separate electric and gas accounts).
 */
data class Property(
    val propertyId: String,
    val utilityId: UtilityId,
    val address: ServiceAddress,
    val propertyType: PropertyType,
    val propertyStatus: PropertyStatus,
    val propertyClass: String?, // Zoning class
    val squareFootage: Int?,
    val numberOfUnits: Int?, // For multi-unit properties
    val yearBuilt: Int?,
    val notes: String?,

    // Bitemporal
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate,
    val systemFrom: Instant,
    val systemTo: Instant,

    // Audit
    val createdBy: String,
    val modifiedBy: String,
)

enum class PropertyType {
    SINGLE_FAMILY,
    MULTI_FAMILY,
    APARTMENT,
    CONDO,
    TOWNHOUSE,
    COMMERCIAL_OFFICE,
    COMMERCIAL_RETAIL,
    COMMERCIAL_WAREHOUSE,
    INDUSTRIAL,
    AGRICULTURAL,
    MIXED_USE,
    GOVERNMENT,
    INSTITUTIONAL,
}

enum class PropertyStatus {
    ACTIVE,
    VACANT,
    UNDER_CONSTRUCTION,
    CONDEMNED,
    DEMOLISHED,
}

/**
 * Customer interaction - comprehensive log of all customer contacts.
 * Append-only, immutable.
 */
data class CustomerInteraction(
    val interactionId: String,
    val utilityId: UtilityId,
    val accountId: String?,
    val customerId: CustomerId?,
    val interactionType: InteractionType,
    val interactionChannel: InteractionChannel,
    val interactionReason: InteractionReason,
    val direction: InteractionDirection,
    val initiatedBy: String?, // CSR ID or system identifier
    val summary: String,
    val details: String?,
    val outcome: InteractionOutcome?,
    val followUpRequired: Boolean,
    val followUpDate: LocalDate?,
    val durationSeconds: Int?,
    val timestamp: Instant,
    val sentiment: InteractionSentiment?,
    val tags: List<String> = emptyList(),
    val relatedCaseId: String?,
)

enum class InteractionType {
    INQUIRY,
    SERVICE_REQUEST,
    COMPLAINT,
    PAYMENT,
    BILLING_QUESTION,
    USAGE_QUESTION,
    OUTAGE_REPORT,
    METER_READING,
    ACCOUNT_CHANGE,
    MOVE_IN,
    MOVE_OUT,
    START_SERVICE,
    STOP_SERVICE,
    GENERAL,
    EMERGENCY,
}

enum class InteractionChannel {
    PHONE,
    EMAIL,
    SMS,
    CHAT,
    WEB_PORTAL,
    MOBILE_APP,
    IN_PERSON,
    MAIL,
    FAX,
    IVR, // Interactive voice response
    FIELD_VISIT,
}

enum class InteractionReason {
    BILLING_INQUIRY,
    PAYMENT_ARRANGEMENT,
    DISPUTE_CHARGE,
    HIGH_BILL,
    USAGE_QUESTION,
    METER_ISSUE,
    SERVICE_CONNECTION,
    SERVICE_DISCONNECTION,
    OUTAGE_REPORT,
    POWER_QUALITY,
    METER_READING_QUESTION,
    ACCOUNT_UPDATE,
    CHANGE_ADDRESS,
    CHANGE_BILLING_INFO,
    ADD_AUTHORIZED_USER,
    START_SERVICE,
    STOP_SERVICE,
    TRANSFER_SERVICE,
    PAYMENT_EXTENSION,
    PAYMENT_PLAN,
    COMPLAINT_SERVICE,
    COMPLAINT_CSR,
    COMPLAINT_BILLING,
    EMERGENCY,
    GENERAL_INQUIRY,
    OTHER,
}

enum class InteractionDirection {
    INBOUND, // Customer initiated
    OUTBOUND, // Utility initiated
}

enum class InteractionOutcome {
    RESOLVED,
    ESCALATED,
    CASE_CREATED,
    FOLLOW_UP_SCHEDULED,
    TRANSFERRED,
    ABANDONED,
    VOICEMAIL,
    UNRESOLVED,
    INFORMATION_PROVIDED,
    ACTION_TAKEN,
}

enum class InteractionSentiment {
    POSITIVE,
    NEUTRAL,
    NEGATIVE,
    VERY_NEGATIVE,
}

/**
 * Case record - structured workflow for customer issues requiring resolution.
 */
data class CaseRecord(
    val caseId: String,
    val caseNumber: String, // User-friendly case number
    val utilityId: UtilityId,
    val accountId: String?,
    val customerId: CustomerId?,
    val caseType: CaseType,
    val caseCategory: CaseCategory,
    val status: CaseStatus,
    val priority: CasePriority,
    val severity: CaseSeverity?,
    val title: String,
    val description: String,
    val resolutionNotes: String?,
    val rootCause: String?,
    val preventativeAction: String?,
    val estimatedResolutionDate: LocalDate?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val openedBy: String,
    val assignedTo: String?,
    val assignedTeam: String?,
    val closedAt: Instant?,
    val closedBy: String?,
    val tags: List<String> = emptyList(),
    val relatedCaseIds: List<String> = emptyList(),
)

enum class CaseType {
    SERVICE_REQUEST,
    COMPLAINT,
    DISPUTE,
    INVESTIGATION,
    ESCALATION,
    EMERGENCY,
    FOLLOW_UP,
    INTERNAL,
}

enum class CaseCategory {
    BILLING,
    PAYMENT,
    METER,
    SERVICE_CONNECTION,
    OUTAGE,
    POWER_QUALITY,
    ACCOUNT_MANAGEMENT,
    CUSTOMER_SERVICE,
    SAFETY,
    FRAUD,
    REGULATORY,
    OTHER,
}

enum class CaseStatus {
    OPEN,
    IN_PROGRESS,
    PENDING_CUSTOMER,
    PENDING_INTERNAL,
    PENDING_THIRD_PARTY,
    RESOLVED,
    CLOSED,
    ESCALATED,
    CANCELLED,
}

enum class CasePriority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

enum class CaseSeverity {
    MINOR, // Cosmetic or low impact
    MODERATE, // Impacts some functionality
    MAJOR, // Significant impact
    CRITICAL, // Safety or regulatory issue
}

/**
 * Case status history - tracks all status transitions.
 * Append-only.
 */
data class CaseStatusHistory(
    val historyId: String,
    val caseId: String,
    val fromStatus: CaseStatus?,
    val toStatus: CaseStatus,
    val changedBy: String,
    val changedAt: Instant,
    val reason: String?,
    val notes: String?,
)

/**
 * Case note - comments and updates on a case.
 * Append-only.
 */
data class CaseNote(
    val noteId: String,
    val caseId: String,
    val noteType: CaseNoteType,
    val content: String,
    val isInternal: Boolean, // Internal notes not visible to customer
    val createdBy: String,
    val createdAt: Instant,
    val attachmentIds: List<String> = emptyList(),
)

enum class CaseNoteType {
    COMMENT,
    RESOLUTION_ATTEMPT,
    CUSTOMER_CONTACT,
    INTERNAL_NOTE,
    STATUS_UPDATE,
    ESCALATION_REASON,
}

/**
 * CSR (Customer Service Representative) identity.
 */
data class CsrIdentity(
    val csrId: String,
    val utilityId: UtilityId,
    val employeeId: String,
    val firstName: String,
    val lastName: String,
    val email: String,
    val teamId: String?,
    val role: CsrRole,
    val isActive: Boolean,
    val hireDate: LocalDate,
    val terminationDate: LocalDate?,
    val skillSet: Set<CaseCategory> = emptySet(),
    val maxConcurrentCases: Int = 20,
)

enum class CsrRole {
    CSR, // Standard customer service rep
    SENIOR_CSR, // Senior CSR with elevated permissions
    SUPERVISOR, // Team supervisor
    MANAGER, // Department manager
    SPECIALIST, // Domain specialist (billing, technical, etc.)
}

/**
 * Team - organizational unit for CSRs.
 */
data class Team(
    val teamId: String,
    val utilityId: UtilityId,
    val teamName: String,
    val teamType: TeamType,
    val supervisorId: String?,
    val isActive: Boolean,
    val specialization: Set<CaseCategory> = emptySet(),
)

enum class TeamType {
    TIER_1, // First line support
    TIER_2, // Escalated/complex cases
    TIER_3, // Technical specialists
    BILLING, // Billing specialist team
    TECHNICAL, // Technical support
    FIELD_SERVICES, // Field operations coordination
    EMERGENCY, // Emergency response
    BACK_OFFICE, // Administrative support
}

/**
 * Case assignment - tracks CSR/team assignment with history.
 */
data class CaseAssignment(
    val assignmentId: String,
    val caseId: String,
    val assignedToCsrId: String?,
    val assignedToTeamId: String?,
    val assignedBy: String,
    val assignedAt: Instant,
    val unassignedAt: Instant?,
    val unassignedBy: String?,
    val reason: String?,
)

/**
 * SLA (Service Level Agreement) configuration for case types.
 */
data class SlaConfiguration(
    val slaId: String,
    val utilityId: UtilityId,
    val caseType: CaseType,
    val casePriority: CasePriority,
    val responseTimeMinutes: Int, // Time to first response
    val resolutionTimeMinutes: Int, // Time to resolution
    val escalationThresholdMinutes: Int, // Auto-escalate after this time
    val isActive: Boolean,
)

/**
 * Customer 360 view - comprehensive customer snapshot for CSR workbench.
 */
data class Customer360View(
    val customerId: CustomerId,
    val accountSummary: AccountSummary,
    val recentInteractions: List<CustomerInteraction>,
    val openCases: List<CaseRecord>,
    val recentBills: List<BillSummary>,
    val servicePoints: List<ServicePointSummary>,
    val paymentHistory: List<PaymentSummary>,
    val alerts: List<CustomerAlert>,
    val lifetimeValue: CustomerLifetimeValue,
)

data class AccountSummary(
    val accountId: String,
    val accountNumber: String,
    val accountStatus: String,
    val accountType: String,
    val currentBalance: String, // Money as string for display
    val daysDelinquent: Int?,
    val serviceAddress: String,
    val billingAddress: String,
    val primaryContactName: String,
    val primaryContactPhone: String?,
    val primaryContactEmail: String?,
    val accountOpenDate: LocalDate,
)

data class BillSummary(
    val billId: String,
    val billDate: LocalDate,
    val dueDate: LocalDate,
    val amount: String, // Money as string
    val isPaid: Boolean,
    val paidDate: LocalDate?,
)

data class ServicePointSummary(
    val servicePointId: String,
    val serviceType: String,
    val address: String,
    val meterSerial: String?,
    val lastReadingDate: LocalDate?,
    val lastReadingValue: String?,
)

data class PaymentSummary(
    val paymentId: String,
    val paymentDate: LocalDate,
    val amount: String, // Money as string
    val paymentMethod: String,
    val status: String,
)

data class CustomerAlert(
    val alertType: AlertType,
    val severity: AlertSeverity,
    val message: String,
    val actionRequired: Boolean,
    val createdAt: Instant,
)

enum class AlertType {
    PAST_DUE,
    DISCONNECTION_PENDING,
    HIGH_USAGE,
    PAYMENT_ARRANGEMENT_DUE,
    CASE_SLA_BREACH,
    METER_READ_FAILURE,
    FRAUD_SUSPECTED,
    VIP_CUSTOMER,
}

enum class AlertSeverity {
    INFO,
    WARNING,
    ERROR,
    CRITICAL,
}

data class CustomerLifetimeValue(
    val totalRevenue: String, // Money as string
    val averageMonthlyRevenue: String,
    val customerTenureMonths: Int,
    val paymentReliabilityScore: Double, // 0.0 to 1.0
    val interactionCount: Int,
    val caseCount: Int,
    val escalationRate: Double, // Percentage
)
