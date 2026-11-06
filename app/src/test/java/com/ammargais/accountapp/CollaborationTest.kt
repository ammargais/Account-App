package com.ammargais.accountapp

import com.ammargais.accountapp.core.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class CollaborationTest {
    private val first=Actor("one","a"); private val second=Actor("two","a"); private val third=Actor("three","a")
    private val reviewer=Actor("reviewer","a"); private val approver=Actor("owner","a"); private val other=Actor("other","b")
    private val base=SharedLedger(setOf("a","b"),setOf("SDG"),listOf(Membership(first,canRecord=true),Membership(second,canRecord=true),Membership(third,canRecord=true),
        Membership(reviewer,canReview=true,canApprove=true),Membership(approver,canApprove=true),Membership(other,canApprove=true)))
        .createBatch(first,MatchBatch("batch","a","b","a","SDG","2026-11-01"),1)
    private fun amount(value: String)=BigInteger(value)
    private fun item(id:String,value:String)=BlindItem(id,amount(value))
    private fun reject(f:()->Unit) { try { f(); fail("Expected rejection") } catch(_:IllegalArgumentException){} }
    private fun original()=base.record(first,"batch",item("one","15000000"),2).complete(first,"batch",3)
    private fun approve(book:SharedLedger,actor:Actor,hash:String=book.fingerprint(actor,"2026-11-01","2026-11-06"))=
        book.approve(actor,"2026-11-01","2026-11-06",hash,book.periodTotals(actor,"2026-11-01","2026-11-06"),20)

    @Test fun firstEntryImmediatelyCountsAndOthersAreOptional() {
        val posted=base.record(first,"batch",item("one","15000000"),2)
        assertEquals(amount("15000000"),posted.periodTotals(reviewer,"2026-11-01","2026-11-06").values.single())
        val closed=approve(approve(posted.complete(first,"batch",3),approver),other)
        assertTrue(closed.periods.single().locked)
    }
    @Test fun oneVersusThreeEntriesMatchesAndNeverDoublesBalance() {
        var book=original()
        repeat(3){ book=book.record(second,"batch",item("split$it","5000000"),4L+it) }
        assertEquals(1,book.batches.single().matchingPeople)
        assertFalse(book.batches.single().needsReview)
        book=book.complete(second,"batch",9)
        assertEquals(2,book.batches.single().matchingPeople)
        assertFalse(book.batches.single().needsReview)
        assertEquals(amount("15000000"),book.batches.single().total)
        assertEquals(book,book.record(second,"batch",item("split0","5000000"),10))
        reject { book.record(second,"batch",item("split0","5000001"),10) }
    }
    @Test fun blindProjectionNeverLeaksOtherAmountsIncludingCounterparty() {
        val book=original().record(second,"batch",item("other","14000000"),4)
        assertEquals(listOf("two"),book.visibleInputs(second,"batch").map { it.userId })
        assertTrue(book.visibleInputs(third,"batch").isEmpty())
        assertEquals(2,book.visibleInputs(reviewer,"batch").size)
        reject { book.visibleInputs(other,"batch") }
        reject { book.periodTotals(second,"2026-11-01","2026-11-06") }
        reject { book.visibleInputs(Actor("intruder","a"),"batch") }
    }
    @Test fun reviewerChoosesAmountEvenWhenMinorityAndKeepsHistory() {
        var book=original().record(second,"batch",item("s","14000000"),4).complete(second,"batch",5)
            .record(third,"batch",item("t","15000000"),6).complete(third,"batch",7)
        assertTrue(book.batches.single().needsReview)
        assertEquals(amount("15000000"),book.batches.single().total)
        reject { book.review(first,"batch","decision",amount("14000000"),"صحيح",8) }
        reject { approve(book,approver) }
        book=book.review(reviewer,"batch","decision",amount("14000000"),"حسب المستند الأصلي",8)
        assertFalse(book.batches.single().needsReview)
        assertEquals(amount("14000000"),book.batches.single().total)
        assertEquals(amount("15000000"),book.visibleInputs(first,"batch").single().total)
        assertEquals(1,book.batches.single().decisions.size)
    }
    @Test fun sameEntityCannotProvideBothApprovalsAndChangesInvalidateApproval() {
        var book=approve(approve(original(),approver),reviewer)
        assertEquals(1,book.periods.single().approvals.size);assertFalse(book.periods.single().locked)
        val stale=book.fingerprint(other,"2026-11-01","2026-11-06")
        book=book.record(second,"batch",item("two","15000000"),22).complete(second,"batch",23)
        assertTrue(book.periods.single().approvals.isEmpty())
        reject { approve(book,other,stale) }
        book=approve(approve(book,approver),other)
        assertTrue(book.periods.single().locked)
    }
    @Test fun periodLockBlocksBackdatedChangesButAllowsNextPeriod() {
        val book=approve(approve(original(),approver),other)
        reject { book.record(second,"batch",item("late","15000000"),30) }
        reject { book.review(reviewer,"batch","late",amount("14000000"),"تصحيح",31) }
        reject { book.createBatch(first,MatchBatch("late","a","a","b","SDG","2026-11-03"),32) }
        val next=book.createBatch(first,MatchBatch("next","a","a","b","SDG","2026-11-07"),33)
            .record(first,"next",item("new","100"),34)
        assertEquals(book.periods,next.periods)
    }
    @Test fun oppositeDirectionCannotCancelAndDeclaredTotalsMustMatch() {
        val book=original().createBatch(first,MatchBatch("out","a","a","b","SDG","2026-11-02"),4)
            .record(first,"out",item("out","15000000"),5).complete(first,"out",6)
        assertEquals(2,book.periodTotals(approver,"2026-11-01","2026-11-06").size)
        reject { book.approve(approver,"2026-11-01","2026-11-06",book.fingerprint(approver,"2026-11-01","2026-11-06"),emptyMap(),8) }
    }
    @Test fun revokedMembershipCannotReadOrWrite() {
        val book=original().copy(members=base.members.map { if(it.actor==first) it.copy(active=false) else it })
        reject { book.visibleInputs(first,"batch") }
        reject { book.record(first,"batch",item("later","10"),4) }
    }
}
