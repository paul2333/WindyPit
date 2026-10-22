package net.minecraft.server;

import java.util.List;
import java.util.Set;

import com.google.common.collect.Lists;
import com.windpvp.windspigot.commons.ConcurrentIntHashMap;
import com.windpvp.windspigot.config.WindSpigotConfig;

import me.rastrian.dev.utils.IndexedLinkedHashSet;

public class EntityTracker {

	public Set<EntityTrackerEntry> c = new IndexedLinkedHashSet<>();
	
	public IntHashMap<EntityTrackerEntry> trackedEntities = new ConcurrentIntHashMap<>(); // WindSpigot

	private volatile int noTrackDistance = 0; // WindSpigot - volatile

	public int getNoTrackDistance() {
		return this.noTrackDistance;
	}

	public void setNoTrackDistance(int noTrackDistance) {
		this.noTrackDistance = noTrackDistance;
	}

	private final WorldServer world;
	private int e; 
	// WindyPit start - plan-driven visibility (W1-REGION-REAL)
	// When enabled, add/remove visibility decisions come from the immutable plan produced on the
	// world's owner thread; the primary thread only sends movement/velocity packets and applies
	// the plan's add/remove pairs exactly once. Compat mode leaves this false (legacy behaviour).
	private volatile boolean planDrivenVisibility;

	/**
	 * WindyPit: 最近一次"计划真正被应用"的时间戳（毫秒）。
	 *
	 * <p>【2026-10-23 可用性修复】plan-driven 模式下 {@link #updatePlayers()} 会走
	 * {@code updateMovementOnly}，<b>完全跳过 add/remove</b> —— 这是有意的（add/remove
	 * 由计划负责）。但它带来一个单点故障：只要计划流断了（完成队列满被丢弃、
	 * worker 挂起、applyPending 没跑到），玩家就<b>再也看不到任何实体</b>，
	 * 而且没有任何自动恢复机制。原实现里 {@code setPlanDrivenVisibility}
	 * 只在世界注册时被设置一次，运行期从不回检。</p>
	 *
	 * <p>现在记录"计划最后一次生效的时刻"，超过宽限期仍未生效就自动退回原版
	 * {@code entry.update(0)} 路径。宁可短暂多花一点 CPU，也不能让整世界的实体消失。</p>
	 *
	 * <p>用时间戳而不是 tick 序号：本类拿不到可靠的世界 tick 计数，
	 * 而墙钟语义在"服务端卡顿"时同样有效（卡顿本身也不该让实体消失）。</p>
	 */
	private volatile long lastPlanAppliedMillis = 0L;

	/** 计划流中断多久后自动退回原版可见性（毫秒）。1000ms ≈ 20 tick。 */
	private static final long PLAN_STALL_MILLIS = 1000L;

	/** 由 tick 集成在世界 owner 线程应用计划成功后调用。 */
	public void markPlanApplied() {
		this.lastPlanAppliedMillis = System.currentTimeMillis();
	}

	public boolean isPlanDrivenVisibility() {
		return this.planDrivenVisibility;
	}

	public void setPlanDrivenVisibility(boolean planDrivenVisibility) {
		this.planDrivenVisibility = planDrivenVisibility;
		// 开启时把基线拉到"现在"，避免刚开启就被判定为停滞。
		this.lastPlanAppliedMillis = System.currentTimeMillis();
	}

	/**
	 * 计划流是否已经停滞太久（需要退回原版可见性）。
	 *
	 * @return true 表示应当忽略 planDriven、走 {@code entry.update(0)}
	 */
	private boolean planStalled() {
		final long last = this.lastPlanAppliedMillis;
		if (last <= 0L) {
			// 从未收到过计划：开启后立刻就该有基线（setPlanDrivenVisibility 已拉齐），
			// 走到这里说明 baseline 丢失，按停滞处理以保安全。
			return true;
		}
		return System.currentTimeMillis() - last > PLAN_STALL_MILLIS;
	}
	// WindyPit end
	
	public EntityTracker(WorldServer worldserver) {
		this.world = worldserver;
		this.e = 128;
	}

	public void track(Entity entity) {
		if (entity instanceof EntityPlayer) {
			this.addEntity(entity, 512, 2);
			/*
			 * EntityPlayer entityplayer = (EntityPlayer) entity; for (EntityTrackerEntry
			 * entitytrackerentry : this.c) { if (entitytrackerentry.tracker !=
			 * entityplayer) { entitytrackerentry.updatePlayer(entityplayer); } }
			 */
		} else if (entity instanceof EntityFishingHook) {
			this.addEntity(entity, 64, 5, true);
		} else if (entity instanceof EntityArrow) {
			this.addEntity(entity, 64, 20, false);
		} else if (entity instanceof EntitySmallFireball) {
			this.addEntity(entity, 64, 10, false);
		} else if (entity instanceof EntityFireball) {
			this.addEntity(entity, 64, 10, false);
		} else if (entity instanceof EntitySnowball) {
			this.addEntity(entity, 64, 10, true);
		} else if (entity instanceof EntityEnderPearl) {
			this.addEntity(entity, 64, 10, true);
		} else if (entity instanceof EntityEnderSignal) {
			this.addEntity(entity, 64, 4, true);
		} else if (entity instanceof EntityEgg) {
			this.addEntity(entity, 64, 10, true);
		} else if (entity instanceof EntityPotion) {
			this.addEntity(entity, 64, 10, true);
		} else if (entity instanceof EntityThrownExpBottle) {
			this.addEntity(entity, 64, 10, true);
		} else if (entity instanceof EntityFireworks) {
			this.addEntity(entity, 64, 10, true);
		} else if (entity instanceof EntityItem) {
			this.addEntity(entity, 64, 20, true);
		} else if (entity instanceof EntityMinecartAbstract) {
			this.addEntity(entity, 80, 3, true);
		} else if (entity instanceof EntityBoat) {
			this.addEntity(entity, 80, 3, true);
		} else if (entity instanceof EntitySquid) {
			this.addEntity(entity, 64, 3, true);
		} else if (entity instanceof EntityWither) {
			this.addEntity(entity, 80, 3, false);
		} else if (entity instanceof EntityBat) {
			this.addEntity(entity, 80, 3, false);
		} else if (entity instanceof EntityEnderDragon) {
			this.addEntity(entity, 160, 3, true);
		} else if (entity instanceof IAnimal) {
			this.addEntity(entity, 80, 3, true);
		} else if (entity instanceof EntityTNTPrimed) {
			this.addEntity(entity, 160, 10, true);
		} else if (entity instanceof EntityFallingBlock) {
			this.addEntity(entity, 160, 20, true);
		} else if (entity instanceof EntityHanging) {
			this.addEntity(entity, 160, Integer.MAX_VALUE, false);
		} else if (entity instanceof EntityArmorStand) {
			this.addEntity(entity, 160, 3, true);
		} else if (entity instanceof EntityExperienceOrb) {
			this.addEntity(entity, 160, 20, true);
		} else if (entity instanceof EntityEnderCrystal) {
			this.addEntity(entity, 256, Integer.MAX_VALUE, false);
		}
	}

	public void addEntity(Entity entity, int i, int j) {
		this.addEntity(entity, i, j, false);
	}

	public void addEntity(Entity entity, int i, final int j, boolean flag) {
		org.spigotmc.AsyncCatcher.catchOp("entity track"); // Spigot
		i = org.spigotmc.TrackingRange.getEntityTrackingRange(entity, i); // Spigot
		if (i > this.e) {
			i = this.e;
		}

		try {
			if (this.trackedEntities.b(entity.getId())) {
				untrackEntity(entity);
			}

			EntityTrackerEntry entitytrackerentry = createTracker(entity, i, j, flag); // IonSpigot

			this.c.add(entitytrackerentry);
			this.trackedEntities.a(entity.getId(), entitytrackerentry);
			// entitytrackerentry.scanPlayers(this.world.players);
			entitytrackerentry.addNearPlayers();
		} catch (Throwable throwable) {
			throwable.printStackTrace();
		}
	}

	// IonSpigot start
	protected EntityTrackerEntry createTracker(Entity entity, int i, int j, boolean flag) { // WindSpigot - protected
		if (entity.isCannoningEntity && WindSpigotConfig.useFasterCannonTracker) {
			return new me.suicidalkids.ion.visuals.CannonTrackerEntry(this, entity, i, j, flag);
		} else {
			return new EntityTrackerEntry(this, entity, i, j, flag);
		}
	}
	// IonSpigot end
	
	public void untrackEntity(Entity entity) {
		org.spigotmc.AsyncCatcher.catchOp("entity untrack"); // Spigot
		if (entity instanceof EntityPlayer) {
			EntityPlayer entityplayer = (EntityPlayer) entity;
			for (EntityTrackerEntry entitytrackerentry : this.c) {
				entitytrackerentry.a(entityplayer);
			}
		}
		EntityTrackerEntry entry = this.trackedEntities.d(entity.getId());
		if (entry != null) {
			this.c.remove(entry);
			entry.a();
		}
	}

	public void updatePlayers() {
		// The tracker is single-writer on the primary thread. The old per-entry shard index was
		// only a packet-queue selector for the removed async tracker; it added a modulo per
		// entity and multiplied per-connection queues without any concurrency. One FIFO per
		// connection is both cheaper and strictly ordered.
		//
		// WindyPit: 计划流停滞时退回原版路径（见 planStalled 的说明）。
		// 这是可用性兜底：宁可让主线程多做一次 add/remove 计算，
		// 也不能让整世界的实体因为"计划没送到"而从所有客户端消失。
		final boolean planDriven = this.planDrivenVisibility && !this.planStalled();
		for (EntityTrackerEntry entry : c) {
			// WindyPit: when the world owner produced a visibility plan, the primary thread
			// only emits movement/velocity packets; add/remove is applied from the plan.
			if (planDriven) {
				entry.updateMovementOnly(0);
			} else {
				entry.update(0);
			}
		}
	}

	public void a(EntityPlayer entityplayer) {
		for (EntityTrackerEntry entitytrackerentry : this.c) {
			if (entitytrackerentry.getTracker() == entityplayer) {
				// entitytrackerentry.scanPlayers(this.world.players);
				entitytrackerentry.addNearPlayers();
			} else {
				entitytrackerentry.updatePlayer(entityplayer);
			}
		}
	}

	public void a(Entity entity, Packet<?> packet) {
		EntityTrackerEntry entitytrackerentry = this.trackedEntities.get(entity.getId());
		if (entitytrackerentry != null) {
			entitytrackerentry.broadcast(packet);
		}
	}

	public void sendPacketToEntity(Entity entity, Packet<?> packet) {
		EntityTrackerEntry entitytrackerentry = this.trackedEntities.get(entity.getId());
		if (entitytrackerentry != null) {
			entitytrackerentry.broadcastIncludingSelf(packet);
		}
	}
	
	public void untrackPlayer(EntityPlayer entityplayer) {
		for (EntityTrackerEntry entitytrackerentry : this.c) {
			entitytrackerentry.clear(entityplayer);
		}
	}
}
