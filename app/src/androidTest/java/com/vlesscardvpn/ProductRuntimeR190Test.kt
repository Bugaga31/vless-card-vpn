package com.vlesscardvpn

import com.vlesscardvpn.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class ProductRuntimeR190Test {
 private val now=1_800_000_000_000L
 private fun root()=Files.createTempDirectory("maintenance-test").toFile()
 private fun old(root:File,name:String="sample.tmp",body:String="abc")=File(root,name).apply {parentFile.mkdirs();writeText(body);check(setLastModified(now-CacheMaintenance.MIN_AGE_MS-1))}
 @Test fun previewDoesNotDeleteAnything() {val r=root();try {val f=old(r);val p=CacheMaintenance.preview(r,now);assertEquals(1,p.entries.size);assertTrue(f.exists());assertEquals(3L,p.bytes)} finally {r.deleteRecursively()}}
 @Test fun confirmedPlanDeletesOnlyListedOldFiles() {val r=root();try {val f=old(r);val p=CacheMaintenance.preview(r,now);val fresh=old(r,"created-after-preview.tmp");val result=CacheMaintenance.clean(r,p,now);assertEquals(1,result.deleted);assertEquals(3L,result.bytes);assertFalse(f.exists());assertTrue(fresh.exists())} finally {r.deleteRecursively()}}
 @Test fun freshFilesAreProtected() {val r=root();try {val f=old(r);f.setLastModified(now-100);assertTrue(CacheMaintenance.preview(r,now).entries.isEmpty())} finally {r.deleteRecursively()}}
 @Test fun futureTimestampIsProtected() {val r=root();try {val f=old(r);f.setLastModified(now+100);assertTrue(CacheMaintenance.preview(r,now).entries.isEmpty())} finally {r.deleteRecursively()}}
 @Test fun allApksAndDownloadsAreProtected() {val r=root();try {listOf("update.apk","update.part.apk","update.tmp","download.apk").forEach {old(r,it)};assertTrue(CacheMaintenance.preview(r,now).entries.isEmpty())} finally {r.deleteRecursively()}}
 @Test fun settingsKeysDatabasesAndBackupsAreProtected() {val r=root();try {listOf("state.json","key.pem","secret.key","database.db","config.yaml","backup.tmp").forEach {old(r,it)};assertTrue(CacheMaintenance.preview(r,now).entries.isEmpty())} finally {r.deleteRecursively()}}
 @Test fun changedSizeAfterPreviewIsSkipped() {val r=root();try {val f=old(r);val p=CacheMaintenance.preview(r,now);f.appendText("changed");val result=CacheMaintenance.clean(r,p,now);assertEquals(0,result.deleted);assertEquals(1,result.skipped);assertTrue(f.exists())} finally {r.deleteRecursively()}}
 @Test fun changedTimestampAfterPreviewIsSkipped() {val r=root();try {val f=old(r);val p=CacheMaintenance.preview(r,now);f.setLastModified(now-200);assertEquals(0,CacheMaintenance.clean(r,p,now).deleted);assertTrue(f.exists())} finally {r.deleteRecursively()}}
 @Test fun missingFileDoesNotInventFreedBytes() {val r=root();try {val f=old(r);val p=CacheMaintenance.preview(r,now);f.delete();val n=CacheMaintenance.clean(r,p,now);assertEquals(0,n.deleted);assertEquals(0L,n.bytes);assertEquals(1,n.skipped)} finally {r.deleteRecursively()}}
 @Test fun emptyPreviewIsNoOp() {val r=root();try {old(r);assertEquals(0,CacheMaintenance.clean(r,CacheMaintenance.Preview(emptyList()),now).deleted)} finally {r.deleteRecursively()}}
 @Test fun nestedCacheFilesAreEligible() {val r=root();try {old(r,"images/nested/sample.png");assertEquals(1,CacheMaintenance.preview(r,now).entries.size)} finally {r.deleteRecursively()}}
 @Test fun emptyDirectoriesAreNotDeleted() {val r=root();try {val d=File(r,"empty");d.mkdir();CacheMaintenance.clean(r,CacheMaintenance.preview(r,now),now);assertTrue(d.isDirectory)} finally {r.deleteRecursively()}}
 @Test fun scanAndDeletionAreBatchLimited() {val r=root();try {repeat(140){old(r,"$it.tmp")};val p=CacheMaintenance.preview(r,now);assertEquals(128,p.entries.size);assertTrue(p.limited);assertEquals(128,CacheMaintenance.clean(r,p,now).deleted);assertEquals(12,r.listFiles()!!.size)} finally {r.deleteRecursively()}}
 @Test fun deepTreesAreNotTraversedWithoutBound() {val r=root();try {old(r,(1..10).joinToString("/"){"d$it"}+"/x.tmp");val p=CacheMaintenance.preview(r,now);assertTrue(p.limited);assertTrue(p.entries.isEmpty())} finally {r.deleteRecursively()}}
 @Test fun symlinkedFileIsNeverReadOrDeletedAsTarget() {val r=root();val outside=root();try {val target=old(outside);val link=File(r,"link.tmp");Files.createSymbolicLink(link.toPath(),target.toPath());assertTrue(CacheMaintenance.preview(r,now).entries.isEmpty());assertTrue(target.exists())} finally {File(r,"link.tmp").delete();r.deleteRecursively();outside.deleteRecursively()}}
 @Test fun symlinkedDirectoryCannotEscapeSandbox() {val r=root();val outside=root();try {val target=old(outside);val link=File(r,"linked");Files.createSymbolicLink(link.toPath(),outside.toPath());assertTrue(CacheMaintenance.preview(r,now).entries.isEmpty());assertTrue(target.exists())} finally {File(r,"linked").delete();r.deleteRecursively();outside.deleteRecursively()}}
 @Test fun forgedTraversalCannotDeleteOutsideCache() {val r=root();val outside=old(r.parentFile,"outside-${r.name}.tmp");try {val p=CacheMaintenance.Preview(listOf(CacheMaintenance.Entry("../${outside.name}",outside.length(),outside.lastModified())));assertEquals(0,CacheMaintenance.clean(r,p,now).deleted);assertTrue(outside.exists())} finally {r.deleteRecursively();outside.delete()}}
 @Test fun replacedSymlinkAfterPreviewIsSkipped() {val r=root();val outside=root();try {val f=old(r);val p=CacheMaintenance.preview(r,now);val target=old(outside);f.delete();Files.createSymbolicLink(f.toPath(),target.toPath());assertEquals(0,CacheMaintenance.clean(r,p,now).deleted);assertTrue(target.exists())} finally {File(r,"sample.tmp").delete();r.deleteRecursively();outside.deleteRecursively()}}
 @Test fun duplicatePlanEntryCannotCountTwice() {val r=root();try {old(r);val p=CacheMaintenance.preview(r,now);val d=CacheMaintenance.clean(r,CacheMaintenance.Preview(p.entries+p.entries),now);assertEquals(1,d.deleted);assertEquals(3L,d.bytes)} finally {r.deleteRecursively()}}
 @Test fun zeroByteFileIsCountedHonestly() {val r=root();try {old(r,body="");val d=CacheMaintenance.clean(r,CacheMaintenance.preview(r,now),now);assertEquals(1,d.deleted);assertEquals(0L,d.bytes)} finally {r.deleteRecursively()}}
 @Test fun negativeSizePlanCannotDelete() {val r=root();try {val f=old(r);val p=CacheMaintenance.Preview(listOf(CacheMaintenance.Entry(f.name,-1,f.lastModified())));assertEquals(0,CacheMaintenance.clean(r,p,now).deleted)} finally {r.deleteRecursively()}}
 @Test fun failedSlotImmediatelyTakesNextCandidate()=runBlocking<Unit> {
  val seen=mutableListOf<Int>();val begun=System.nanoTime()
  withTimeout(1000) {ProbeRace.run((0..7).toList(),1,1,staggerMs=500,probe={i->seen+=i;if(i<4) Probe(0,null,null) else Probe(10,null,null)},onEach={_,_->})}
  assertEquals(listOf(0,1,2,3,4),seen);assertTrue((System.nanoTime()-begun)/1_000_000<800)
 }
 @Test fun pendingCatalogueDoesNotCreateCoroutinePerServer()=runBlocking<Unit> {
  val entered=CompletableDeferred<Unit>();val go=CompletableDeferred<Unit>();val children=AtomicInteger()
  val job=launch {coroutineScope {val owner=currentCoroutineContext()[Job]!!;ProbeRace.run((0..9999).toList(),3,1,probe={children.set(owner.children.sumOf {it.children.count()});entered.complete(Unit);go.await();Probe(10,null,null)},onEach={_,_->})}}
  entered.await();assertTrue(children.get()<=3);go.complete(Unit);job.join()
 }
 @Test fun everyIndexHasExactlyOneCallbackOnWinner()=runBlocking<Unit> {val counts=mutableMapOf<Int,Int>();ProbeRace.run((0..99).toList(),4,1,probe={delay(1);Probe(10,null,null)},onEach={i,_->counts[i]=(counts[i] ?: 0)+1});assertEquals(100,counts.size);assertTrue(counts.values.all {it==1})}
 @Test fun callerCancellationIsNotAReportedFailure()=runBlocking<Unit> {val entered=CompletableDeferred<Unit>();var count=0;val job=launch {ProbeRace.run((0..99).toList(),2,1,probe={entered.complete(Unit);awaitCancellation()},onEach={_,_->count++})};entered.await();job.cancelAndJoin();assertEquals(0,count)}
 @Test fun skipDoesNotCallTransport()=runBlocking<Unit> {var called=0;var results=0;ProbeRace.run((0..50).toList(),3,1,skip={true},probe={called++;Probe(10,null,null)},onEach={_,p->assertSame(Tester.SKIPPED,p);results++});assertEquals(0,called);assertEquals(51,results)}
 @Test fun workerPoolRespectsConcurrency()=runBlocking<Unit> {val active=AtomicInteger();val peak=AtomicInteger();ProbeRace.run((0..19).toList(),3,Int.MAX_VALUE,probe={val n=active.incrementAndGet();peak.updateAndGet{maxOf(it,n)};try {delay(3);Probe(0,null,null)} finally {active.decrementAndGet()}},onEach={_,_->});assertEquals(3,peak.get());assertEquals(0,active.get())}
 @Test fun emptyRaceHasNoCallbacks()=runBlocking<Unit> {ProbeRace.run(emptyList(),8,1,probe={fail();Probe(0,null,null)},onEach={_,_->fail()})}
 @Test fun rejectedTelegramCannotWin()=runBlocking<Unit> {val selected=mutableListOf<Int>();ProbeRace.run((0..4).toList(),1,1,probe={Probe(20,null,null,tgOk=it==3,tgMs=20)},accepted={AutoLatencyPolicy.accepts(it)},onEach={i,p->if(AutoLatencyPolicy.accepts(p)) selected+=i});assertEquals(listOf(3),selected)}
 @Test fun zeroParallelAndStopCountAreSafelyClamped()=runBlocking<Unit> {var calls=0;ProbeRace.run((0..3).toList(),0,0,probe={calls++;Probe(10,null,null)},onEach={_,_->});assertEquals(1,calls)}
}
