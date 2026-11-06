package com.ammargais.accountapp.core

import java.math.BigInteger
import java.security.MessageDigest
import java.time.LocalDate

/** Domain rules for the future authoritative online service. Never send this whole state to a client.
 * Actor identity must come from verified server authentication, not a user-supplied role or entity. */
data class Actor(val userId: String, val entityId: String)
data class Membership(val actor: Actor, val canRecord: Boolean = false, val canReview: Boolean = false,
    val canApprove: Boolean = false, val active: Boolean = true)
data class BlindItem(val id: String, val units: BigInteger, val reference: String = "")
data class BlindInput(val userId: String, val items: List<BlindItem> = emptyList(), val complete: Boolean = false) {
    val total get() = items.fold(BigInteger.ZERO) { sum, item -> sum + item.units }
}
data class ReviewDecision(val id: String, val reviewer: Actor, val units: BigInteger, val reason: String,
    val reviewedUsers: Set<String>, val at: Long)
data class MatchBatch(val id: String, val recordingEntity: String, val fromEntity: String, val toEntity: String,
    val currency: String, val date: String, val inputs: List<BlindInput> = emptyList(), val decisions: List<ReviewDecision> = emptyList()) {
    val total get() = decisions.lastOrNull()?.units ?: inputs.firstOrNull()?.total ?: BigInteger.ZERO
    val needsReview get() = inputs.firstOrNull()?.complete == true && inputs.any { it.complete && it.total != total && it.userId !in (decisions.lastOrNull()?.reviewedUsers ?: emptySet()) }
    val matchingPeople get() = inputs.count { it.complete && it.total == total }
}
data class TotalKey(val currency: String, val fromEntity: String, val toEntity: String)
data class PeriodApproval(val actor: Actor, val fingerprint: String, val at: Long)
data class ClosedPeriod(val from: String, val to: String, val approvals: List<PeriodApproval> = emptyList(),
    val snapshotTotals: Map<TotalKey, BigInteger> = emptyMap(), val locked: Boolean = false)
data class SharedAudit(val actor: Actor, val action: String, val objectId: String, val at: Long, val reason: String = "")

/** Immutable state transitions allow a backend to persist each operation in one atomic transaction. */
data class SharedLedger(val entities: Set<String>, val currencies: Set<String>, val members: List<Membership>,
    val batches: List<MatchBatch> = emptyList(), val periods: List<ClosedPeriod> = emptyList(), val audit: List<SharedAudit> = emptyList()) {
    init {
        require(entities.size == 2 && entities.none { it.isBlank() })
        require(members.map { it.actor }.distinct().size == members.size && members.all { it.actor.entityId in entities })
    }
    private fun member(actor: Actor) = members.find { it.actor == actor && it.active } ?: throw IllegalArgumentException("لا توجد عضوية نشطة")
    private fun batch(id: String) = batches.find { it.id == id } ?: throw IllegalArgumentException("مجموعة غير موجودة")
    private fun open(date: String) { require(periods.none { it.locked && date in it.from..it.to }) { "الفترة مقفلة" } }
    private fun changed(batch: MatchBatch, event: SharedAudit): SharedLedger {
        // Any change to the evidence invalidates pending approval of that exact period.
        return copy(batches = batches.filterNot { it.id == batch.id } + batch,
            periods = periods.map { if(!it.locked && batch.date in it.from..it.to) it.copy(approvals=emptyList(), snapshotTotals=emptyMap()) else it }, audit = audit + event)
    }
    fun createBatch(actor: Actor, batch: MatchBatch, at: Long): SharedLedger {
        require(member(actor).canRecord && actor.entityId == batch.recordingEntity) { "لا تملك صلاحية الإدخال لهذه الجهة" }
        require(batch.id.isNotBlank() && batches.none { it.id == batch.id } && batch.inputs.isEmpty() && batch.decisions.isEmpty()) { "مجموعة مكررة أو غير صالحة" }
        require(setOf(batch.fromEntity,batch.toEntity) == entities && batch.currency in currencies)
        require(LocalDate.parse(batch.date).toString() == batch.date); open(batch.date)
        return changed(batch, SharedAudit(actor,"create",batch.id,at))
    }
    fun record(actor: Actor, batchId: String, item: BlindItem, at: Long): SharedLedger {
        val b=batch(batchId); open(b.date)
        require(member(actor).canRecord && actor.entityId == b.recordingEntity) { "لا تملك صلاحية الإدخال لهذه الجهة" }
        require(item.id.isNotBlank() && item.units.signum() > 0 && item.units.toString().length <= 23 && item.reference.length <= 100)
        val input=b.inputs.find { it.userId == actor.userId } ?: BlindInput(actor.userId)
        val previous=input.items.find { it.id == item.id }
        if(previous != null) { require(previous == item) { "إعادة إرسال متعارضة" }; return this }
        require(!input.complete) { "الإدخال مكتمل؛ التصحيح عبر المراجع" }
        val next=input.copy(items=input.items + item)
        val inputs = if(b.inputs.any { it.userId == actor.userId }) b.inputs.map { if(it.userId == actor.userId) next else it } else b.inputs + next
        return changed(b.copy(inputs=inputs), SharedAudit(actor,"record",batchId,at))
    }
    fun complete(actor: Actor, batchId: String, at: Long): SharedLedger {
        val b=batch(batchId); open(b.date)
        require(member(actor).canRecord && actor.entityId == b.recordingEntity)
        val input=b.inputs.find { it.userId == actor.userId } ?: throw IllegalArgumentException("أدخل مبلغًا أولًا")
        if(input.complete) return this
        return changed(b.copy(inputs=b.inputs.map { if(it.userId == actor.userId) it.copy(complete=true) else it }), SharedAudit(actor,"complete",batchId,at))
    }
    /** A projection, not merely hidden UI fields. Other users' amounts never enter this response. */
    fun visibleInputs(actor: Actor, batchId: String): List<BlindInput> {
        val permission=member(actor); val b=batch(batchId)
        require(actor.entityId == b.recordingEntity) { "إدخالات الجهة الأخرى خاصة بها" }
        return if(permission.canReview) b.inputs else b.inputs.filter { it.userId == actor.userId }
    }
    fun review(actor: Actor, batchId: String, decisionId: String, units: BigInteger, reason: String, at: Long): SharedLedger {
        val b=batch(batchId); open(b.date)
        require(member(actor).canReview && actor.entityId == b.recordingEntity) { "حسم الاختلاف يتطلب صلاحية المراجع" }
        val previous=b.decisions.find { it.id == decisionId }
        if(previous != null) { require(previous.reviewer == actor && previous.units == units && previous.reason == reason.trim()); return this }
        require(b.inputs.firstOrNull()?.complete == true && decisionId.isNotBlank() && units.signum() > 0 && units.toString().length <= 23 && reason.trim().length in 1..500)
        val decision=ReviewDecision(decisionId,actor,units,reason.trim(),b.inputs.filter { it.complete }.map { it.userId }.toSet(),at)
        return changed(b.copy(decisions=b.decisions+decision),SharedAudit(actor,"review",batchId,at,reason.trim()))
    }
    private fun totals(from: String,to: String): Map<TotalKey,BigInteger> = batches.filter { it.date in from..to }.groupBy { TotalKey(it.currency,it.fromEntity,it.toEntity) }
        .mapValues { (_,items) -> items.fold(BigInteger.ZERO) { sum,b -> sum+b.total } }.filterValues { it.signum()!=0 }
    fun periodTotals(actor: Actor, from: String, to: String): Map<TotalKey,BigInteger> {
        require(member(actor).canApprove || member(actor).canReview) { "عرض إجمالي الجهة يتطلب صلاحية" }
        return totals(from,to)
    }
    fun fingerprint(actor: Actor, from: String, to: String): String {
        require(member(actor).canApprove)
        fun frame(values: List<String>) = values.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }
        val snapshot=batches.filter { it.date in from..to }.sortedBy { it.id }.map { b ->
            val inputs=frame(b.inputs.map { input -> frame(listOf(input.userId,input.complete.toString(),frame(input.items.map { frame(listOf(it.id,it.units.toString(),it.reference)) }))) })
            val decisions=frame(b.decisions.map { d -> frame(listOf(d.id,d.reviewer.userId,d.reviewer.entityId,d.units.toString(),d.reason,frame(d.reviewedUsers.sorted()),d.at.toString())) })
            frame(listOf(b.id,b.recordingEntity,b.date,b.currency,b.fromEntity,b.toEntity,b.total.toString(),inputs,decisions))
        }
        return MessageDigest.getInstance("SHA-256").digest(frame(listOf(from,to,frame(snapshot))).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    fun approve(actor: Actor, from: String, to: String, expectedFingerprint: String, declaredTotals: Map<TotalKey,BigInteger>, at: Long): SharedLedger {
        require(member(actor).canApprove) { "لا تملك صلاحية اعتماد الفترة" }
        require(LocalDate.parse(from).toString()==from && LocalDate.parse(to).toString()==to && from<=to)
        require(periods.none { (it.from!=from || it.to!=to) && it.from<=to && it.to>=from }) { "فترة متداخلة" }
        val current=periods.find { it.from==from && it.to==to } ?: ClosedPeriod(from,to)
        require(!current.locked) { "الفترة مقفلة بالفعل" }
        val selected=batches.filter { it.date in from..to }
        require(selected.isNotEmpty() && selected.all { it.inputs.firstOrNull()?.complete == true && !it.needsReview }) { "أكمل الإدخال الأول واحسم الفروق قبل إقفال الفترة" }
        val fingerprint=fingerprint(actor,from,to)
        require(expectedFingerprint==fingerprint) { "تغيرت البيانات؛ راجع الكشف مجددًا" }
        val totals=totals(from,to)
        require(declaredTotals.filterValues { it.signum()!=0 }==totals) { "إجمالي الجهة لا يطابق الكشف" }
        val approvals=current.approvals.filter { it.actor.entityId!=actor.entityId && it.fingerprint==fingerprint } + PeriodApproval(actor,fingerprint,at)
        val period=current.copy(approvals=approvals,snapshotTotals=totals,locked=approvals.map { it.actor.entityId }.toSet()==entities)
        return copy(periods=periods.filterNot { it.from==from && it.to==to }+period,audit=audit+SharedAudit(actor,"approve", "$from/$to",at))
    }
}
