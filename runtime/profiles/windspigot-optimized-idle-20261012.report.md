# WindyPit JFR profile

- Recording: `windspigot-optimized-idle-20261012.jfr`
- JFR events parsed: 1014
- Slow duration threshold: 50 ms

## Expected pacing and idle waits

Recorded parks in an idle worker queue or the server tick pacer exceed the threshold because the thread is waiting normally. They are retained here for context and are not expensive calls.

1. **7900.39 ms** `jdk.ThreadPark` on `PaperSpigot - Lighting Thread`
   - `jdk/internal/misc/Unsafe.park`
   - `java/util/concurrent/locks/LockSupport.park:369`
   - `java/util/concurrent/locks/AbstractQueuedSynchronizer$ConditionNode.block:520`
   - `java/util/concurrent/ForkJoinPool.unmanagedBlock:4364`
   - `java/util/concurrent/ForkJoinPool.managedBlock:4310`
   - `java/util/concurrent/locks/AbstractQueuedSynchronizer$ConditionObject.await:1752`
   - `java/util/concurrent/LinkedBlockingQueue.take:435`
   - `java/util/concurrent/ThreadPoolExecutor.getTask:1016`
2. **4448.51 ms** `jdk.ThreadPark` on `PaperSpigot - Lighting Thread`
   - `jdk/internal/misc/Unsafe.park`
   - `java/util/concurrent/locks/LockSupport.park:369`
   - `java/util/concurrent/locks/AbstractQueuedSynchronizer$ConditionNode.block:520`
   - `java/util/concurrent/ForkJoinPool.unmanagedBlock:4364`
   - `java/util/concurrent/ForkJoinPool.managedBlock:4310`
   - `java/util/concurrent/locks/AbstractQueuedSynchronizer$ConditionObject.await:1752`
   - `java/util/concurrent/LinkedBlockingQueue.take:435`
   - `java/util/concurrent/ThreadPoolExecutor.getTask:1016`
3. **1148.36 ms** `jdk.ThreadPark` on `PaperSpigot - Lighting Thread`
   - `jdk/internal/misc/Unsafe.park`
   - `java/util/concurrent/locks/LockSupport.park:369`
   - `java/util/concurrent/locks/AbstractQueuedSynchronizer$ConditionNode.block:520`
   - `java/util/concurrent/ForkJoinPool.unmanagedBlock:4364`
   - `java/util/concurrent/ForkJoinPool.managedBlock:4310`
   - `java/util/concurrent/locks/AbstractQueuedSynchronizer$ConditionObject.await:1752`
   - `java/util/concurrent/LinkedBlockingQueue.take:435`
   - `java/util/concurrent/ThreadPoolExecutor.getTask:1016`
4. **251.45 ms** `jdk.ThreadPark` on `PaperSpigot - Lighting Thread`
   - `jdk/internal/misc/Unsafe.park`
   - `java/util/concurrent/locks/LockSupport.park:369`
   - `java/util/concurrent/locks/AbstractQueuedSynchronizer$ConditionNode.block:520`
   - `java/util/concurrent/ForkJoinPool.unmanagedBlock:4364`
   - `java/util/concurrent/ForkJoinPool.managedBlock:4310`
   - `java/util/concurrent/locks/AbstractQueuedSynchronizer$ConditionObject.await:1752`
   - `java/util/concurrent/LinkedBlockingQueue.take:435`
   - `java/util/concurrent/ThreadPoolExecutor.getTask:1016`
5. **251.00 ms** `jdk.ThreadPark` on `PaperSpigot - Lighting Thread`
   - `jdk/internal/misc/Unsafe.park`
   - `java/util/concurrent/locks/LockSupport.park:369`
   - `java/util/concurrent/locks/AbstractQueuedSynchronizer$ConditionNode.block:520`
   - `java/util/concurrent/ForkJoinPool.unmanagedBlock:4364`
   - `java/util/concurrent/ForkJoinPool.managedBlock:4310`
   - `java/util/concurrent/locks/AbstractQueuedSynchronizer$ConditionObject.await:1752`
   - `java/util/concurrent/LinkedBlockingQueue.take:435`
   - `java/util/concurrent/ThreadPoolExecutor.getTask:1016`
6. **250.92 ms** `jdk.ThreadPark` on `PaperSpigot - Lighting Thread`
   - `jdk/internal/misc/Unsafe.park`
   - `java/util/concurrent/locks/LockSupport.park:369`
   - `java/util/concurrent/locks/AbstractQueuedSynchronizer$ConditionNode.block:520`
   - `java/util/concurrent/ForkJoinPool.unmanagedBlock:4364`
   - `java/util/concurrent/ForkJoinPool.managedBlock:4310`
   - `java/util/concurrent/locks/AbstractQueuedSynchronizer$ConditionObject.await:1752`
   - `java/util/concurrent/LinkedBlockingQueue.take:435`
   - `java/util/concurrent/ThreadPoolExecutor.getTask:1016`
7. **250.56 ms** `jdk.ThreadPark` on `PaperSpigot - Lighting Thread`
   - `jdk/internal/misc/Unsafe.park`
   - `java/util/concurrent/locks/LockSupport.park:369`
   - `java/util/concurrent/locks/AbstractQueuedSynchronizer$ConditionNode.block:520`
   - `java/util/concurrent/ForkJoinPool.unmanagedBlock:4364`
   - `java/util/concurrent/ForkJoinPool.managedBlock:4310`
   - `java/util/concurrent/locks/AbstractQueuedSynchronizer$ConditionObject.await:1752`
   - `java/util/concurrent/LinkedBlockingQueue.take:435`
   - `java/util/concurrent/ThreadPoolExecutor.getTask:1016`
8. **250.26 ms** `jdk.ThreadPark` on `PaperSpigot - Lighting Thread`
   - `jdk/internal/misc/Unsafe.park`
   - `java/util/concurrent/locks/LockSupport.park:369`
   - `java/util/concurrent/locks/AbstractQueuedSynchronizer$ConditionNode.block:520`
   - `java/util/concurrent/ForkJoinPool.unmanagedBlock:4364`
   - `java/util/concurrent/ForkJoinPool.managedBlock:4310`
   - `java/util/concurrent/locks/AbstractQueuedSynchronizer$ConditionObject.await:1752`
   - `java/util/concurrent/LinkedBlockingQueue.take:435`
   - `java/util/concurrent/ThreadPoolExecutor.getTask:1016`
9. **249.51 ms** `jdk.ThreadPark` on `PaperSpigot - Lighting Thread`
   - `jdk/internal/misc/Unsafe.park`
   - `java/util/concurrent/locks/LockSupport.park:369`
   - `java/util/concurrent/locks/AbstractQueuedSynchronizer$ConditionNode.block:520`
   - `java/util/concurrent/ForkJoinPool.unmanagedBlock:4364`
   - `java/util/concurrent/ForkJoinPool.managedBlock:4310`
   - `java/util/concurrent/locks/AbstractQueuedSynchronizer$ConditionObject.await:1752`
   - `java/util/concurrent/LinkedBlockingQueue.take:435`
   - `java/util/concurrent/ThreadPoolExecutor.getTask:1016`
10. **248.87 ms** `jdk.ThreadPark` on `PaperSpigot - Lighting Thread`
   - `jdk/internal/misc/Unsafe.park`
   - `java/util/concurrent/locks/LockSupport.park:369`
   - `java/util/concurrent/locks/AbstractQueuedSynchronizer$ConditionNode.block:520`
   - `java/util/concurrent/ForkJoinPool.unmanagedBlock:4364`
   - `java/util/concurrent/ForkJoinPool.managedBlock:4310`
   - `java/util/concurrent/locks/AbstractQueuedSynchronizer$ConditionObject.await:1752`
   - `java/util/concurrent/LinkedBlockingQueue.take:435`
   - `java/util/concurrent/ThreadPoolExecutor.getTask:1016`
11. **248.69 ms** `jdk.ThreadPark` on `PaperSpigot - Lighting Thread`
   - `jdk/internal/misc/Unsafe.park`
   - `java/util/concurrent/locks/LockSupport.park:369`
   - `java/util/concurrent/locks/AbstractQueuedSynchronizer$ConditionNode.block:520`
   - `java/util/concurrent/ForkJoinPool.unmanagedBlock:4364`
   - `java/util/concurrent/ForkJoinPool.managedBlock:4310`
   - `java/util/concurrent/locks/AbstractQueuedSynchronizer$ConditionObject.await:1752`
   - `java/util/concurrent/LinkedBlockingQueue.take:435`
   - `java/util/concurrent/ThreadPoolExecutor.getTask:1016`
12. **248.29 ms** `jdk.ThreadPark` on `PaperSpigot - Lighting Thread`
   - `jdk/internal/misc/Unsafe.park`
   - `java/util/concurrent/locks/LockSupport.park:369`
   - `java/util/concurrent/locks/AbstractQueuedSynchronizer$ConditionNode.block:520`
   - `java/util/concurrent/ForkJoinPool.unmanagedBlock:4364`
   - `java/util/concurrent/ForkJoinPool.managedBlock:4310`
   - `java/util/concurrent/locks/AbstractQueuedSynchronizer$ConditionObject.await:1752`
   - `java/util/concurrent/LinkedBlockingQueue.take:435`
   - `java/util/concurrent/ThreadPoolExecutor.getTask:1016`
13. **50.07 ms** `jdk.ThreadPark` on `Server thread`
   - `jdk/internal/misc/Unsafe.park`
   - `java/util/concurrent/locks/LockSupport.parkNanos:271`
   - `net/minecraft/server/v1_8_R3/MinecraftServer.waitForTasks:858`
   - `net/minecraft/server/v1_8_R3/IAsyncTaskHandler.managedBlock:86`
   - `net/minecraft/server/v1_8_R3/MinecraftServer.waitUntilNextTick:849`
   - `net/minecraft/server/v1_8_R3/MinecraftServer.run:746`
   - `java/lang/Thread.runWith:1487`
   - `java/lang/Thread.run:1474`
14. **50.07 ms** `jdk.ThreadPark` on `Server thread`
   - `jdk/internal/misc/Unsafe.park`
   - `java/util/concurrent/locks/LockSupport.parkNanos:271`
   - `net/minecraft/server/v1_8_R3/MinecraftServer.waitForTasks:858`
   - `net/minecraft/server/v1_8_R3/IAsyncTaskHandler.managedBlock:86`
   - `net/minecraft/server/v1_8_R3/MinecraftServer.waitUntilNextTick:849`
   - `net/minecraft/server/v1_8_R3/MinecraftServer.run:746`
   - `java/lang/Thread.runWith:1487`
   - `java/lang/Thread.run:1474`

## Contention and long operations

These are measured event durations at or above the threshold, not inferred from CPU sampling. JFR's own recorder threads and expected idle/tick waits are excluded.

No contention or long-operation duration event met the threshold of 50 ms.

## Sampled CPU hotspots

`jdk.ExecutionSample` and `jdk.NativeMethodSample` are sampled at approximately 10 ms. The estimated sampled milliseconds equal sample count multiplied by this configured period; this does not measure a single invocation's duration. JFR internal stacks are excluded.

1. 947 samples (~9470.0 estimated sampled ms): `sun/nio/ch/WEPoll.wait <- sun/nio/ch/WEPollSelectorImpl.doSelect:108 <- sun/nio/ch/SelectorImpl.lockAndDoSelect:130 <- sun/nio/ch/SelectorImpl.select:147 <- io/netty/channel/nio/SelectedSelectionKeySetSelector.select:68`
2. 3 samples (~30.0 estimated sampled ms): `com/sun/management/internal/OperatingSystemImpl.getProcessCpuLoad0 <- com/sun/management/internal/OperatingSystemImpl.getProcessCpuLoad:89 <- com/windpvp/windspigot/performance/TickPerformanceMonitor.readProcessCpuLoad:122 <- net/minecraft/server/v1_8_R3/MinecraftServer.run:736 <- java/lang/Thread.runWith:1487`
3. 2 samples (~20.0 estimated sampled ms): `sun/nio/ch/WEPoll.wait <- sun/nio/ch/WEPollSelectorImpl.doSelect:108 <- sun/nio/ch/SelectorImpl.lockAndDoSelect:130 <- sun/nio/ch/SelectorImpl.select:142 <- io/netty/channel/nio/SelectedSelectionKeySetSelector.select:62`
4. 2 samples (~20.0 estimated sampled ms): `net/minecraft/server/v1_8_R3/WorldServer.h:562 <- net/minecraft/server/v1_8_R3/WorldServer.doTick:295 <- com/windpvp/windspigot/world/WorldTicker.run:24 <- com/windpvp/windspigot/world/WorldTickManager.tickWorlds:32 <- com/windpvp/windspigot/world/WorldTickManager.tick:20`
5. 2 samples (~20.0 estimated sampled ms): `net/minecraft/server/v1_8_R3/Chunk.getBlockData:522 <- net/minecraft/server/v1_8_R3/World.getType:1068 <- net/minecraft/server/v1_8_R3/World.getType:1042 <- net/minecraft/server/v1_8_R3/BlockLeaves.b:82 <- net/minecraft/server/v1_8_R3/Block.a:336`
6. 2 samples (~20.0 estimated sampled ms): `net/minecraft/server/v1_8_R3/ChunkSection.getType:36 <- net/minecraft/server/v1_8_R3/Chunk.getBlockData:522 <- net/minecraft/server/v1_8_R3/World.getType:1068 <- net/minecraft/server/v1_8_R3/World.getType:1042 <- net/minecraft/server/v1_8_R3/BlockLeaves.b:82`
7. 1 samples (~10.0 estimated sampled ms): `io/netty/buffer/PooledByteBuf.deallocate:174 <- io/netty/buffer/AbstractReferenceCountedByteBuf.handleRelease:111 <- io/netty/buffer/AbstractReferenceCountedByteBuf.release:101 <- io/netty/channel/ChannelOutboundBuffer.remove:296 <- io/netty/channel/ChannelOutboundBuffer.removeBytes:383`
8. 1 samples (~10.0 estimated sampled ms): `java/util/regex/Pattern$Node.<init>:3726 <- java/util/regex/Pattern$GroupHead.<init>:4963 <- java/util/regex/Pattern.createGroup:3315 <- java/util/regex/Pattern.group0:3146 <- java/util/regex/Pattern.sequence:2301`
9. 1 samples (~10.0 estimated sampled ms): `net/minecraft/server/v1_8_R3/Chunk.getBlockData:522 <- net/minecraft/server/v1_8_R3/World.getType:1068 <- net/minecraft/server/v1_8_R3/World.getType:1042 <- net/minecraft/server/v1_8_R3/World.a:2795 <- net/minecraft/server/v1_8_R3/World.c:2863`
10. 1 samples (~10.0 estimated sampled ms): `net/minecraft/server/v1_8_R3/World.updateLight:3013 <- net/minecraft/server/v1_8_R3/Chunk.a:347 <- net/minecraft/server/v1_8_R3/Chunk.c:337 <- net/minecraft/server/v1_8_R3/Chunk.h:311 <- net/minecraft/server/v1_8_R3/Chunk.access$000:21`
11. 1 samples (~10.0 estimated sampled ms): `net/minecraft/server/v1_8_R3/World.updateLight:3014 <- net/minecraft/server/v1_8_R3/Chunk.a:347 <- net/minecraft/server/v1_8_R3/Chunk.c:337 <- net/minecraft/server/v1_8_R3/Chunk.h:311 <- net/minecraft/server/v1_8_R3/Chunk.access$000:21`
12. 1 samples (~10.0 estimated sampled ms): `net/minecraft/server/v1_8_R3/Entity.K:383 <- net/minecraft/server/v1_8_R3/EntityLiving.K:195 <- net/minecraft/server/v1_8_R3/EntityInsentient.K:187 <- net/minecraft/server/v1_8_R3/Entity.t_:322 <- net/minecraft/server/v1_8_R3/EntityLiving.t_:1621`
13. 1 samples (~10.0 estimated sampled ms): `net/minecraft/server/v1_8_R3/World.c:2980 <- net/minecraft/server/v1_8_R3/World.updateLight:3022 <- net/minecraft/server/v1_8_R3/Chunk.a:347 <- net/minecraft/server/v1_8_R3/Chunk.c:337 <- net/minecraft/server/v1_8_R3/Chunk.h:311`
14. 1 samples (~10.0 estimated sampled ms): `com/sun/management/internal/OperatingSystemImpl.getCpuLoad0 <- com/sun/management/internal/OperatingSystemImpl.getCpuLoad:84 <- com/sun/management/OperatingSystemMXBean.getSystemCpuLoad:162 <- com/windpvp/windspigot/performance/TickPerformanceMonitor.readSystemCpuLoad:118 <- net/minecraft/server/v1_8_R3/MinecraftServer.run:735`
15. 1 samples (~10.0 estimated sampled ms): `net/minecraft/server/v1_8_R3/MinecraftServer.A:923 <- net/minecraft/server/v1_8_R3/MinecraftServer.run:743 <- java/lang/Thread.runWith:1487 <- java/lang/Thread.run:1474`
16. 1 samples (~10.0 estimated sampled ms): `net/minecraft/server/v1_8_R3/WorldServer.p:1257 <- net/minecraft/server/v1_8_R3/World.doTick:2500 <- net/minecraft/server/v1_8_R3/WorldServer.doTick:235 <- com/windpvp/windspigot/world/WorldTicker.run:24 <- com/windpvp/windspigot/world/WorldTickManager.tickWorlds:32`
17. 1 samples (~10.0 estimated sampled ms): `net/minecraft/server/v1_8_R3/EntityTrackerEntry.track:427 <- net/minecraft/server/v1_8_R3/EntityTrackerEntry.update:190 <- net/minecraft/server/v1_8_R3/EntityTrackerEntry.update:177 <- net/minecraft/server/v1_8_R3/EntityTracker.updatePlayers:151 <- com/windpvp/windspigot/world/WorldTicker.run:56`
18. 1 samples (~10.0 estimated sampled ms): `java/lang/StringBuilder.toString:478 <- jdk/jfr/internal/event/EventWriter.beginEvent:238 <- jdk/internal/event/ThreadSleepEvent.commit <- java/lang/Thread.afterSleep:492 <- java/lang/Thread.sleepNanos:512`
19. 1 samples (~10.0 estimated sampled ms): `net/minecraft/server/v1_8_R3/ChunkProviderServer.getOrCreateChunk:268 <- net/minecraft/server/v1_8_R3/World.getChunkAt:455 <- net/minecraft/server/v1_8_R3/World.getChunkAtWorldCoords:447 <- net/minecraft/server/v1_8_R3/World.getType:1066 <- net/minecraft/server/v1_8_R3/World.getType:1042`
20. 1 samples (~10.0 estimated sampled ms): `org/bukkit/craftbukkit/v1_8_R3/scheduler/CraftScheduler.parsePending:503 <- org/bukkit/craftbukkit/v1_8_R3/scheduler/CraftScheduler.mainThreadHeartbeat:401 <- com/windpvp/windspigot/world/WorldTickManager.tickWorlds:27 <- com/windpvp/windspigot/world/WorldTickManager.tick:20 <- net/minecraft/server/v1_8_R3/MinecraftServer.B:1064`
21. 1 samples (~10.0 estimated sampled ms): `com/google/common/base/Predicates.defensiveCopy:647 <- com/google/common/base/Predicates.defensiveCopy:642 <- com/google/common/base/Predicates.and:120 <- net/minecraft/server/v1_8_R3/PathfinderGoalAvoidTarget.a:55 <- net/minecraft/server/v1_8_R3/PathfinderGoalSelector.a:71`
22. 1 samples (~10.0 estimated sampled ms): `net/minecraft/server/v1_8_R3/EntityLiving.bL:1857 <- net/minecraft/server/v1_8_R3/EntityLiving.m:1840 <- net/minecraft/server/v1_8_R3/EntityInsentient.m:390 <- net/minecraft/server/v1_8_R3/EntityAgeable.m:142 <- net/minecraft/server/v1_8_R3/EntityAnimal.m:27`
23. 1 samples (~10.0 estimated sampled ms): `java/util/TreeMap.getEntry:386 <- java/util/TreeMap.get:291 <- net/minecraft/server/v1_8_R3/GameRules.getBoolean:68 <- net/minecraft/server/v1_8_R3/WorldServer.doTick:254 <- com/windpvp/windspigot/world/WorldTicker.run:24`
24. 1 samples (~10.0 estimated sampled ms): `java/util/HashMap.getNode:579 <- java/util/HashMap.containsKey:603 <- java/util/HashSet.contains:214 <- it/unimi/dsi/fastutil/objects/ObjectArrayList.removeAll:826 <- net/minecraft/server/v1_8_R3/World.tickEntities:1760`
25. 1 samples (~10.0 estimated sampled ms): `net/minecraft/server/v1_8_R3/ChunkSection.getType:36 <- net/minecraft/server/v1_8_R3/Chunk.getBlockData:522 <- net/minecraft/server/v1_8_R3/World.getType:1068 <- net/minecraft/server/v1_8_R3/World.getType:1042 <- net/minecraft/server/v1_8_R3/BlockGrass.b:63`
26. 1 samples (~10.0 estimated sampled ms): `net/minecraft/server/v1_8_R3/IEntitySelector$4.apply:31 <- net/minecraft/server/v1_8_R3/Chunk.a:1133 <- net/minecraft/server/v1_8_R3/Chunk.getEntitiesInAABB:1103 <- net/minecraft/server/v1_8_R3/World.a:3085 <- net/minecraft/server/v1_8_R3/World.getEntities:3071`
27. 1 samples (~10.0 estimated sampled ms): `net/minecraft/server/v1_8_R3/MinecraftServer.run:745 <- java/lang/Thread.runWith:1487 <- java/lang/Thread.run:1474`
28. 1 samples (~10.0 estimated sampled ms): `com/windpvp/windspigot/world/WorldTicker.run:23 <- com/windpvp/windspigot/world/WorldTickManager.tickWorlds:32 <- com/windpvp/windspigot/world/WorldTickManager.tick:20 <- net/minecraft/server/v1_8_R3/MinecraftServer.B:1064 <- net/minecraft/server/v1_8_R3/DedicatedServer.B:452`
29. 1 samples (~10.0 estimated sampled ms): `net/minecraft/server/v1_8_R3/NavigationAbstract.k:186 <- net/minecraft/server/v1_8_R3/EntityInsentient.doTick:551 <- net/minecraft/server/v1_8_R3/EntityLiving.m:1798 <- net/minecraft/server/v1_8_R3/EntityInsentient.m:390 <- net/minecraft/server/v1_8_R3/EntityAgeable.m:142`
30. 1 samples (~10.0 estimated sampled ms): `java/util/concurrent/CopyOnWriteArrayList.get:410 <- com/windpvp/windspigot/world/WorldTickManager.tickWorlds:31 <- com/windpvp/windspigot/world/WorldTickManager.tick:20 <- net/minecraft/server/v1_8_R3/MinecraftServer.B:1064 <- net/minecraft/server/v1_8_R3/DedicatedServer.B:452`
31. 1 samples (~10.0 estimated sampled ms): `net/minecraft/server/v1_8_R3/WorldServer.doTick:305 <- com/windpvp/windspigot/world/WorldTicker.run:24 <- com/windpvp/windspigot/world/WorldTickManager.tickWorlds:32 <- com/windpvp/windspigot/world/WorldTickManager.tick:20 <- net/minecraft/server/v1_8_R3/MinecraftServer.B:1064`

## Interpretation

Investigate repeatable slow events and CPU stacks under the same player-count and workload scenario. A sampled stack is a hotspot candidate; it is not proof that one invocation exceeded 50 ms. Compare equivalent captures before changing server or plugin behavior.
