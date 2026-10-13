# WindyPit JFR profile

- Recording: `windspigot-idle-20261012.jfr`
- JFR events parsed: 991
- Slow duration threshold: 50 ms

## Slow duration events

These are recorded event durations, not inferred from CPU sampling. JFR's own recorder threads and stacks are excluded.

1. **50.04 ms** `jdk.ThreadPark` on `Server thread`
   - `jdk/internal/misc/Unsafe.park`
   - `java/util/concurrent/locks/LockSupport.parkNanos:271`
   - `net/minecraft/server/v1_8_R3/MinecraftServer.waitForTasks:855`
   - `net/minecraft/server/v1_8_R3/IAsyncTaskHandler.managedBlock:86`
   - `net/minecraft/server/v1_8_R3/MinecraftServer.waitUntilNextTick:846`
   - `net/minecraft/server/v1_8_R3/MinecraftServer.run:743`
   - `java/lang/Thread.runWith:1487`
   - `java/lang/Thread.run:1474`
2. **50.02 ms** `jdk.ThreadPark` on `Server thread`
   - `jdk/internal/misc/Unsafe.park`
   - `java/util/concurrent/locks/LockSupport.parkNanos:271`
   - `net/minecraft/server/v1_8_R3/MinecraftServer.waitForTasks:855`
   - `net/minecraft/server/v1_8_R3/IAsyncTaskHandler.managedBlock:86`
   - `net/minecraft/server/v1_8_R3/MinecraftServer.waitUntilNextTick:846`
   - `net/minecraft/server/v1_8_R3/MinecraftServer.run:743`
   - `java/lang/Thread.runWith:1487`
   - `java/lang/Thread.run:1474`

## Sampled CPU hotspots

`jdk.ExecutionSample` and `jdk.NativeMethodSample` are sampled at approximately 10 ms. The estimated sampled milliseconds equal sample count multiplied by this configured period; this does not measure a single invocation's duration. JFR internal stacks are excluded.

1. 910 samples (~9100.0 estimated sampled ms): `sun/nio/ch/WEPoll.wait <- sun/nio/ch/WEPollSelectorImpl.doSelect:108 <- sun/nio/ch/SelectorImpl.lockAndDoSelect:130 <- sun/nio/ch/SelectorImpl.select:147 <- io/netty/channel/nio/SelectedSelectionKeySetSelector.select:68`
2. 32 samples (~320.0 estimated sampled ms): `com/sun/management/internal/OperatingSystemImpl.getProcessCpuLoad0 <- com/sun/management/internal/OperatingSystemImpl.getProcessCpuLoad:89 <- com/windpvp/windspigot/performance/TickPerformanceMonitor.readProcessCpuLoad:113 <- net/minecraft/server/v1_8_R3/MinecraftServer.run:731 <- java/lang/Thread.runWith:1487`
3. 2 samples (~20.0 estimated sampled ms): `net/minecraft/server/v1_8_R3/World.c:1676 <- net/minecraft/server/v1_8_R3/World.a:1664 <- net/minecraft/server/v1_8_R3/WorldServer.doTick:277 <- com/windpvp/windspigot/world/WorldTicker.run:24 <- com/windpvp/windspigot/world/WorldTickManager.tickWorlds:32`
4. 1 samples (~10.0 estimated sampled ms): `net/minecraft/server/v1_8_R3/Block.a:294 <- net/minecraft/server/v1_8_R3/World.getCubes:1564 <- net/minecraft/server/v1_8_R3/Entity.move:622 <- net/minecraft/server/v1_8_R3/EntityItem.t_:94 <- net/minecraft/server/v1_8_R3/World.entityJoinedWorld:2020`
5. 1 samples (~10.0 estimated sampled ms): `java/util/ArrayList.clear:735 <- org/bukkit/craftbukkit/v1_8_R3/scheduler/CraftScheduler.mainThreadHeartbeat:442 <- com/windpvp/windspigot/world/WorldTickManager.tickWorlds:27 <- com/windpvp/windspigot/world/WorldTickManager.tick:20 <- net/minecraft/server/v1_8_R3/MinecraftServer.B:1061`
6. 1 samples (~10.0 estimated sampled ms): `net/minecraft/server/v1_8_R3/Entity.setSize:259 <- net/minecraft/server/v1_8_R3/EntityAgeable.a:199 <- net/minecraft/server/v1_8_R3/EntityAgeable.a:183 <- net/minecraft/server/v1_8_R3/EntityAgeable.setAgeRaw:121 <- net/minecraft/server/v1_8_R3/EntityAgeable.inactiveTick:25`
7. 1 samples (~10.0 estimated sampled ms): `org/spigotmc/ActivationRange.checkIfActive:232 <- net/minecraft/server/v1_8_R3/World.entityJoinedWorld:1995 <- net/minecraft/server/v1_8_R3/World.g:1986 <- net/minecraft/server/v1_8_R3/World.tickEntities:1810 <- net/minecraft/server/v1_8_R3/WorldServer.tickEntities:679`
8. 1 samples (~10.0 estimated sampled ms): `java/util/HashMap.getNode:579 <- java/util/HashMap.containsKey:603 <- java/util/HashSet.contains:214 <- it/unimi/dsi/fastutil/objects/ObjectArrayList.removeAll:826 <- net/minecraft/server/v1_8_R3/World.tickEntities:1760`
9. 1 samples (~10.0 estimated sampled ms): `net/minecraft/server/v1_8_R3/FileIOThread.run:29 <- java/lang/Thread.runWith:1487 <- java/lang/Thread.run:1474`
10. 1 samples (~10.0 estimated sampled ms): `net/minecraft/server/v1_8_R3/Navigation.d:63 <- net/minecraft/server/v1_8_R3/NavigationAbstract.a:163 <- net/minecraft/server/v1_8_R3/NavigationAbstract.a:108 <- net/minecraft/server/v1_8_R3/PathfinderGoalRandomStroll.c:56 <- net/minecraft/server/v1_8_R3/PathfinderGoalSelector.a:72`
11. 1 samples (~10.0 estimated sampled ms): `org/spigotmc/ActivationRange.checkEntityImmunities:169 <- org/spigotmc/ActivationRange.checkIfActive:232 <- net/minecraft/server/v1_8_R3/World.entityJoinedWorld:1995 <- net/minecraft/server/v1_8_R3/World.g:1986 <- net/minecraft/server/v1_8_R3/World.tickEntities:1810`
12. 1 samples (~10.0 estimated sampled ms): `net/minecraft/server/v1_8_R3/World.entityJoinedWorld:1997 <- net/minecraft/server/v1_8_R3/World.g:1986 <- net/minecraft/server/v1_8_R3/World.tickEntities:1810 <- net/minecraft/server/v1_8_R3/WorldServer.tickEntities:679 <- com/windpvp/windspigot/world/WorldTicker.run:40`
13. 1 samples (~10.0 estimated sampled ms): `java/math/MutableBigInteger.divideKnuth:1319 <- java/math/MutableBigInteger.divideKnuth:1255 <- java/math/BigInteger.divideAndRemainderKnuth:2521 <- java/math/BigInteger.divideAndRemainder:2509 <- java/math/BigDecimal.fullDoubleValue:4063`
14. 1 samples (~10.0 estimated sampled ms): `net/minecraft/server/v1_8_R3/World.a:1653 <- net/minecraft/server/v1_8_R3/Entity.j:2060 <- net/minecraft/server/v1_8_R3/EntityItem.t_:93 <- net/minecraft/server/v1_8_R3/World.entityJoinedWorld:2020 <- net/minecraft/server/v1_8_R3/World.g:1986`
15. 1 samples (~10.0 estimated sampled ms): `java/util/concurrent/CopyOnWriteArrayList.size:178 <- com/windpvp/windspigot/world/WorldTickManager.tickWorlds:30 <- com/windpvp/windspigot/world/WorldTickManager.tick:20 <- net/minecraft/server/v1_8_R3/MinecraftServer.B:1061 <- net/minecraft/server/v1_8_R3/DedicatedServer.B:452`
16. 1 samples (~10.0 estimated sampled ms): `net/minecraft/server/v1_8_R3/IEntitySelector$4.apply:31 <- net/minecraft/server/v1_8_R3/Chunk.a:1133 <- net/minecraft/server/v1_8_R3/Chunk.getEntitiesInAABB:1103 <- net/minecraft/server/v1_8_R3/World.a:3085 <- net/minecraft/server/v1_8_R3/World.getEntities:3071`

## Interpretation

Investigate repeatable slow events and CPU stacks under the same player-count and workload scenario. A sampled stack is a hotspot candidate; it is not proof that one invocation exceeded 50 ms. Compare equivalent captures before changing server or plugin behavior.
