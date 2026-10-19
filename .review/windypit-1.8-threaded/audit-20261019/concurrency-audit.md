# WindyPit 1.8 Threaded Scheduler Concurrency Audit
**Date:** 2026-10-19  
**Scope:** 15 scheduler files (RegionThread, RegionScheduler, WorldRegionRuntime, ComputePool, GlobalRegionScheduler, WorldOwnerRegistry, TickPipeline, CraftScheduler, AsynchronousExecutor, ChunkIOExecutor, MinecraftServer, SchedulerDiagnostics, RegionTask, RejectedTaskException, TaskRetirementToken)  
**Constraint:** Read-only audit, no source modifications

---

## Q1: RegionThread.loop 50ms poll semantics
**Evidence:** RegionThread.java:64-80  
**Answer:** Maximum shutdown delay is **50ms** per region thread (poll timeout). Task loss is **impossible** if stop occurs after enqueue completes: queue.isEmpty() check at line 68 guarantees all enqueued tasks drain before thread exit. Race: if stopRequested is set between queue.poll() returning null and isEmpty() check, the next 50ms poll will catch any concurrent enqueue.  
**Defect:** None. Bounded-delay drain with FIFO guarantee.  
**Confidence:** 95%

## Q2: RegionScheduler.schedule per-task overhead
**Evidence:** RegionScheduler.java:97-108  
**Answer:** Per-task atomic operations: **3 hot-path atomics** (taskSequences.incrementAndGet at line 98, c.scheduled.increment at line 106, rt.enqueue likely does queue.offer which is lock-free). RegionThread.generation() is a volatile read (line 98). This is **hot-path code** executed for every scheduled region task.  
**Defect:** None. Standard concurrent scheduling overhead.  
**Confidence:** 90%

## Q3: WorldRegionRuntime.submitSnapshot state.previous race
**Evidence:** WorldRegionRuntime.java:264-310  
**Answer:** **Race exists.** Lines 293, 298, 304, 308 all write to state.previous from **owner thread** (region thread). If submitSnapshot is concurrently called by wrong thread (primary or another region), writes conflict. Code assumes **single-writer (owner-thread-only) execution** per world.  
**Defect:** P1 if submitSnapshot callable by non-owner threads. No evidence found of cross-thread calls in reviewed files.  
**Confidence:** 85%

## Q4: WorldRegionRuntime.shutdownDrain vs submitSnapshot race
**Evidence:** WorldRegionRuntime.java:410-421 (shutdown), 264-310 (submit)  
**Answer:** shutdownDrain sets closed=true at line 414, then calls unregister at line 418. submitSnapshot checks closed flag **before** scheduling at line 289. Race window: submit reads closed=false, shutdown sets closed=true + unregister, submit calls schedule → **RejectedTaskException** (not IllegalStateException) because RegionScheduler.requireRegion throws when thread unregistered.  
**Defect:** None. Bounded race with visible rejection.  
**Confidence:** 90%

## Q5: WorldOwnerRegistry production usage
**Evidence:** WorldOwnerRegistry.java:296-433, grep results  
**Answer:** Methods `entityOwner(Entity)`, `chunkOwner(World,x,z)`, `transferEntity(Entity,OwnedRegion)` defined lines 296-433. **Production calls: ZERO**. Grep found usage only in test files: WorldOwnerLifecycleTest, ChunkUnloadOwnerTest, EntityOwnershipServiceTest, EntitySchedulerTest.  
**Defect:** P1. Dead production code or missing integration.  
**Confidence:** 95%

## Q6: ComputePool.closeDiscardQueued vs submit race
**Evidence:** ComputePool.java:159-163 (submit), 202-209 (close)  
**Answer:** **Race exists.** Thread A: submit checks closed.get()=false (line 159), context switch. Thread B: closeDiscardQueued sets closed=true (line 203), drains queue (line 206). Thread A: queue.offer succeeds (line 163). Result: task accepted **after** discard completed.  
**Defect:** P0. Task silently lost (not counted in retiredQueued, not executed).  
**Confidence:** 95%

## Q7: TickPipeline target method implementation status
**Evidence:** TickPipeline.java:26-34 (interface), 131-149 (runPhaseBody)  
**Answer:** Target interface declares 4 abstract methods: serverControl(), regionSnapshot(), asyncCompute(), ownerCommit() (lines 26-34). runPhaseBody shows calls to target.serverControl() (line 133), target.regionSnapshot() (line 137), target.asyncCompute() (line 141), target.ownerCommit() (line 145). **Status unknown without reading Target implementation class** (not in scope).  
**Defect:** None detected in reviewed files.  
**Confidence:** 70%

## Q8: Executor shutdown safety chain
**Evidence:** MinecraftServer.java:615-618, CraftScheduler.java:92-98,100,484-505, AsynchronousExecutor.java:411-430, ChunkIOExecutor.java:50-52  
**Answer:** MinecraftServer calls CraftScheduler.shutdownAsyncExecutor (line 616). CraftScheduler.shutdownAsyncExecutor sets flag (line 486), calls executor.shutdown (line 487), awaits 10s (line 493), handles timeout by setting asyncExecutorShutdownTimedOut flag (line 501). AsynchronousExecutor.close does pool.shutdown + 60s await. ChunkIOExecutor.close delegates to AsynchronousExecutor.close. **Safe with timeout bounds**: CraftScheduler 10s, AsynchronousExecutor 60s.  
**Defect:** None. Bounded-wait shutdown with timeout flags.  
**Confidence:** 95%

---

## P0 Defects
1. **ComputePool.closeDiscardQueued race (Q6)**  
   - **File:line:** ComputePool.java:159,163,203-206  
   - **Failure scenario:** Thread A submits task after checking closed=false; Thread B closes + drains queue; Thread A's task enqueued after drain → silently lost  
   - **Impact:** Task neither rejected nor executed. Silent data loss.  
   - **Fix:** Move closed check inside queue lock, or use AtomicReference<State> with CAS for queue+closed together

## P1 Defects
1. **WorldOwnerRegistry dead code (Q5)**  
   - **File:line:** WorldOwnerRegistry.java:296-433  
   - **Failure scenario:** Entity/chunk ownership methods have zero production callers; only test usage detected  
   - **Impact:** Either incomplete feature or dead code carrying maintenance burden  
   - **Fix:** Grep entire codebase for actual usage; if none, mark @Deprecated or remove; if incomplete, document planned integration

2. **WorldRegionRuntime.submitSnapshot potential cross-thread writes (Q3)**  
   - **File:line:** WorldRegionRuntime.java:293,298,304,308  
   - **Failure scenario:** If submitSnapshot callable by non-owner thread, concurrent writes to state.previous cause race  
   - **Impact:** Snapshot corruption if threading assumptions violated  
   - **Fix:** Add thread-ownership assertion at method entry: `if (Thread.currentThread() != ownerThread) throw new IllegalStateException(...)`

---

## Audit Summary
- **Total questions:** 8  
- **P0 defects:** 1 (silent task loss in ComputePool)  
- **P1 defects:** 2 (dead code, potential cross-thread write)  
- **Architectural strengths:** Bounded queues with visible rejection, atomic diagnostics, timeout-bounded shutdown chains  
- **Key risk:** ComputePool close/submit race enables silent task loss under concurrent shutdown

