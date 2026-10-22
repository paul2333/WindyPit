package org.bukkit.craftbukkit.scheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang.Validate;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scheduler.BukkitWorker;

/**
 * The fundamental concepts for this implementation:
 * <li>Main thread owns {@link #head} and {@link #currentTick}, but it may be
 * read from any thread</li>
 * <li>Main thread exclusively controls {@link #temp} and {@link #pending}. They
 * are never to be accessed outside of the main thread; alternatives exist to
 * prevent locking.</li>
 * <li>{@link #head} to {@link #tail} act as a linked list/queue, with 1
 * consumer and infinite producers. Adding to the tail is atomic and very
 * efficient; utility method is {@link #handle(CraftTask, long)} or
 * {@link #addTask(CraftTask)}.</li>
 * <li>Changing the period on a task is delicate. Any future task needs to
 * notify waiting threads. Async tasks must be synchronized to make sure that
 * any thread that's finishing will remove itself from {@link #runners}. Another
 * utility method is provided for this, cancelTask(CraftTask)</li>
 * <li>{@link #runners} provides a moderately up-to-date view of active tasks.
 * If the linked head to tail set is read, all remaining tasks that were active
 * at the time execution started will be located in runners.</li>
 * <li>Async tasks are responsible for removing themselves from runners</li>
 * <li>Sync tasks are only to be removed from runners on the main thread when
 * coupled with a removal from pending and temp.</li>
 * <li>Most of the design in this scheduler relies on queuing special tasks to
 * perform any data changes on the main thread. When executed from inside a
 * synchronous method, the scheduler will be updated before next execution by
 * virtue of the frequent {@link #parsePending()} calls.</li>
 */
public class CraftScheduler implements BukkitScheduler {

	/**
	 * Counter for IDs. Order doesn't matter, only uniqueness.
	 */
	private final AtomicInteger ids = new AtomicInteger(1);
	/**
	 * Current head of linked-list. This reference is always stale, CraftTask#next
	 * is the live reference.
	 */
	private volatile CraftTask head = new CraftTask();
	/**
	 * Tail of a linked-list. AtomicReference only matters when adding to queue
	 */
	private final AtomicReference<CraftTask> tail = new AtomicReference<CraftTask>(head);
	/**
	 * Main thread logic only
	 */
	private final PriorityQueue<CraftTask> pending = new PriorityQueue<CraftTask>(10, new Comparator<CraftTask>() {
		@Override
		public int compare(final CraftTask o1, final CraftTask o2) {
			return (int) (o1.getNextRun() - o2.getNextRun());
		}
	});
	/**
	 * Main thread logic only
	 */
	private final List<CraftTask> temp = new ArrayList<CraftTask>();
	/**
	 * These are tasks that are currently active. It's provided for 'viewing' the
	 * current state.
	 */
	private final ConcurrentHashMap<Integer, CraftTask> runners = new ConcurrentHashMap<Integer, CraftTask>();
	private volatile int currentTick = -1;
	private static final long ASYNC_REJECTION_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30L);
	private final AtomicInteger rejectedAsyncTaskCount = new AtomicInteger();
	private final AtomicLong lastAsyncRejectionLogNanos = new AtomicLong();
	/**
	 * 异步池线程数（2026-10-22 修复"池永远不扩容"）。
	 *
	 * <p>原配置是 core={@code min(cores,8)}、max={@code min(cores*2,16)} + 有界队列。
	 * 但 {@link ThreadPoolExecutor} 只在<b>队列满</b>时才把线程数扩到 max，所以实际并发度
	 * 永远等于 core —— 4 核机器只有 4 条异步线程。只要有任何插件把这几条线程长期占住
	 * （真实事故：每首歌一条 {@code while + Thread.sleep} 的音乐播放器），全服所有
	 * {@code runTaskAsynchronously} 就会永久排队：<b>不抛异常、不打日志</b>，
	 * 表现为玩家卡「正在加载档案」、/login 与 /register 无响应。</p>
	 *
	 * <p>现在改为 core = max = clamp(cores*2, 4, 16)，并允许核心线程空闲超时回收：
	 * 空闲时不占资源，繁忙时也不会因为"队列还没满"而拒绝扩容。</p>
	 */
	private static final int ASYNC_THREADS =
			Math.max(4, Math.min(Runtime.getRuntime().availableProcessors() * 2, 16));
	private static final int ASYNC_QUEUE_CAPACITY = 4096;

	private static ThreadPoolExecutor newAsyncExecutor() {
		final ThreadPoolExecutor pool = new ThreadPoolExecutor(
				ASYNC_THREADS, ASYNC_THREADS,
				30L, TimeUnit.SECONDS,
				new ArrayBlockingQueue<Runnable>(ASYNC_QUEUE_CAPACITY),
				new com.google.common.util.concurrent.ThreadFactoryBuilder()
						.setNameFormat("WindyPit Scheduler Thread - %1$d").setDaemon(true).build(),
				new ThreadPoolExecutor.AbortPolicy());
		pool.allowCoreThreadTimeOut(true);
		return pool;
	}

	private final ThreadPoolExecutor executor = newAsyncExecutor(); // bounded async scheduler
	private static final Logger LOGGER = Logger.getLogger(CraftScheduler.class.getName());
	private volatile boolean asyncExecutorShutdown;
	private volatile boolean asyncExecutorShutdownTimedOut;
	private CraftAsyncDebugger debugHead = new CraftAsyncDebugger(-1, null, null) {
		@Override
		StringBuilder debugTo(StringBuilder string) {
			return string;
		}
	};
	private CraftAsyncDebugger debugTail = debugHead;
	private static final int RECENT_TICKS;

	static {
		RECENT_TICKS = 30;
	}

	@Override
	public int scheduleSyncDelayedTask(final Plugin plugin, final Runnable task) {
		return this.scheduleSyncDelayedTask(plugin, task, 0l);
	}

	@Override
	public BukkitTask runTask(Plugin plugin, Runnable runnable) {
		return runTaskLater(plugin, runnable, 0l);
	}

	@Override
	@Deprecated
	public int scheduleAsyncDelayedTask(final Plugin plugin, final Runnable task) {
		return this.scheduleAsyncDelayedTask(plugin, task, 0l);
	}

	@Override
	public BukkitTask runTaskAsynchronously(Plugin plugin, Runnable runnable) {
		return runTaskLaterAsynchronously(plugin, runnable, 0l);
	}

	@Override
	public int scheduleSyncDelayedTask(final Plugin plugin, final Runnable task, final long delay) {
		return this.scheduleSyncRepeatingTask(plugin, task, delay, -1l);
	}

	@Override
	public BukkitTask runTaskLater(Plugin plugin, Runnable runnable, long delay) {
		return runTaskTimer(plugin, runnable, delay, -1l);
	}

	@Override
	@Deprecated
	public int scheduleAsyncDelayedTask(final Plugin plugin, final Runnable task, final long delay) {
		return this.scheduleAsyncRepeatingTask(plugin, task, delay, -1l);
	}

	@Override
	public BukkitTask runTaskLaterAsynchronously(Plugin plugin, Runnable runnable, long delay) {
		return runTaskTimerAsynchronously(plugin, runnable, delay, -1l);
	}

	@Override
	public int scheduleSyncRepeatingTask(final Plugin plugin, final Runnable runnable, long delay, long period) {
		return runTaskTimer(plugin, runnable, delay, period).getTaskId();
	}

	@Override
	public BukkitTask runTaskTimer(Plugin plugin, Runnable runnable, long delay, long period) {
		validate(plugin, runnable);
		if (delay < 0l) {
			delay = 0;
		}
		if (period == 0l) {
			period = 1l;
		} else if (period < -1l) {
			period = -1l;
		}
		return handle(new CraftTask(plugin, runnable, nextId(), period), delay);
	}

	@Override
	@Deprecated
	public int scheduleAsyncRepeatingTask(final Plugin plugin, final Runnable runnable, long delay, long period) {
		return runTaskTimerAsynchronously(plugin, runnable, delay, period).getTaskId();
	}

	@Override
	public BukkitTask runTaskTimerAsynchronously(Plugin plugin, Runnable runnable, long delay, long period) {
		validate(plugin, runnable);
		if (delay < 0l) {
			delay = 0;
		}
		if (period == 0l) {
			period = 1l;
		} else if (period < -1l) {
			period = -1l;
		}
		return handle(new CraftAsyncTask(runners, plugin, runnable, nextId(), period), delay);
	}

	@Override
	public <T> Future<T> callSyncMethod(final Plugin plugin, final Callable<T> task) {
		validate(plugin, task);
		final CraftFuture<T> future = new CraftFuture<T>(task, plugin, nextId());
		handle(future, 0l);
		return future;
	}

	@Override
	public void cancelTask(final int taskId) {
		if (taskId <= 0) {
			return;
		}
		CraftTask task = runners.get(taskId);
		if (task != null) {
			task.cancel0();
		}
		task = new CraftTask(new Runnable() {
			@Override
			public void run() {
				if (!check(CraftScheduler.this.temp)) {
					check(CraftScheduler.this.pending);
				}
			}

			private boolean check(final Iterable<CraftTask> collection) {
				final Iterator<CraftTask> tasks = collection.iterator();
				while (tasks.hasNext()) {
					final CraftTask task = tasks.next();
					if (task.getTaskId() == taskId) {
						task.cancel0();
						tasks.remove();
						if (task.isSync()) {
							runners.remove(taskId);
						}
						return true;
					}
				}
				return false;
			}
		}) {
			{
				this.timings = co.aikar.timings.SpigotTimings.getCancelTasksTimer();
			}
		}; // Spigot
		handle(task, 0l);
		for (CraftTask taskPending = head.getNext(); taskPending != null; taskPending = taskPending.getNext()) {
			if (taskPending == task) {
				return;
			}
			if (taskPending.getTaskId() == taskId) {
				taskPending.cancel0();
			}
		}
	}

	@Override
	public void cancelTasks(final Plugin plugin) {
		Validate.notNull(plugin, "Cannot cancel tasks of null plugin");
		final CraftTask task = new CraftTask(new Runnable() {
			@Override
			public void run() {
				check(CraftScheduler.this.pending);
				check(CraftScheduler.this.temp);
			}

			void check(final Iterable<CraftTask> collection) {
				final Iterator<CraftTask> tasks = collection.iterator();
				while (tasks.hasNext()) {
					final CraftTask task = tasks.next();
					if (task.getOwner().equals(plugin)) {
						task.cancel0();
						tasks.remove();
						if (task.isSync()) {
							runners.remove(task.getTaskId());
						}
					}
				}
			}
		}) {
			{
				this.timings = co.aikar.timings.SpigotTimings.getCancelTasksTimer(plugin);
			}
		}; // Spigot
		handle(task, 0l);
		for (CraftTask taskPending = head.getNext(); taskPending != null; taskPending = taskPending.getNext()) {
			if (taskPending == task) {
				return;
			}
			if (taskPending.getTaskId() != -1 && taskPending.getOwner().equals(plugin)) {
				taskPending.cancel0();
			}
		}
		for (CraftTask runner : runners.values()) {
			if (runner.getOwner().equals(plugin)) {
				runner.cancel0();
			}
		}
	}

	@Override
	public void cancelAllTasks() {
		final CraftTask task = new CraftTask(new Runnable() {
			@Override
			public void run() {
				Iterator<CraftTask> it = CraftScheduler.this.runners.values().iterator();
				while (it.hasNext()) {
					CraftTask task = it.next();
					task.cancel0();
					if (task.isSync()) {
						it.remove();
					}
				}
				CraftScheduler.this.pending.clear();
				CraftScheduler.this.temp.clear();
			}
		}) {
			{
				this.timings = co.aikar.timings.SpigotTimings.getCancelTasksTimer();
			}
		}; // Spigot
		handle(task, 0l);
		for (CraftTask taskPending = head.getNext(); taskPending != null; taskPending = taskPending.getNext()) {
			if (taskPending == task) {
				break;
			}
			taskPending.cancel0();
		}
		for (CraftTask runner : runners.values()) {
			runner.cancel0();
		}
	}

	@Override
	public boolean isCurrentlyRunning(final int taskId) {
		final CraftTask task = runners.get(taskId);
		if (task == null || task.isSync()) {
			return false;
		}
		final CraftAsyncTask asyncTask = (CraftAsyncTask) task;
		synchronized (asyncTask.getWorkers()) {
			return asyncTask.getWorkers().isEmpty();
		}
	}

	@Override
	public boolean isQueued(final int taskId) {
		if (taskId <= 0) {
			return false;
		}
		for (CraftTask task = head.getNext(); task != null; task = task.getNext()) {
			if (task.getTaskId() == taskId) {
				return task.getPeriod() >= -1l; // The task will run
			}
		}
		CraftTask task = runners.get(taskId);
		return task != null && task.getPeriod() >= -1l;
	}

	@Override
	public List<BukkitWorker> getActiveWorkers() {
		final ArrayList<BukkitWorker> workers = new ArrayList<BukkitWorker>();
		for (final CraftTask taskObj : runners.values()) {
			// Iterator will be a best-effort (may fail to grab very new values) if called
			// from an async thread
			if (taskObj.isSync()) {
				continue;
			}
			final CraftAsyncTask task = (CraftAsyncTask) taskObj;
			synchronized (task.getWorkers()) {
				// This will never have an issue with stale threads; it's state-safe
				workers.addAll(task.getWorkers());
			}
		}
		return workers;
	}

	@Override
	public List<BukkitTask> getPendingTasks() {
		final ArrayList<CraftTask> truePending = new ArrayList<CraftTask>();
		for (CraftTask task = head.getNext(); task != null; task = task.getNext()) {
			if (task.getTaskId() != -1) {
				// -1 is special code
				truePending.add(task);
			}
		}

		final ArrayList<BukkitTask> pending = new ArrayList<BukkitTask>();
		for (CraftTask task : runners.values()) {
			if (task.getPeriod() >= -1l) {
				pending.add(task);
			}
		}

		for (final CraftTask task : truePending) {
			if (task.getPeriod() >= -1l && !pending.contains(task)) {
				pending.add(task);
			}
		}
		return pending;
	}

	/**
	 * This method is designed to never block or wait for locks; an immediate
	 * execution of all current tasks.
	 */
	public void mainThreadHeartbeat(final int currentTick) {
		this.currentTick = currentTick;
		final List<CraftTask> temp = this.temp;
		parsePending();
		while (isReady(currentTick)) {
			final CraftTask task = pending.remove();
			if (task.getPeriod() < -1l) {
				if (task.isSync()) {
					runners.remove(task.getTaskId(), task);
				}
				parsePending();
				continue;
			}
			if (task.isSync()) {
				try {
					task.run();
				} catch (final Throwable throwable) {
					task.getOwner().getLogger().log(Level.WARNING,
							String.format("Task #%s for %s generated an exception", task.getTaskId(),
									task.getOwner().getDescription().getFullName()),
							throwable);
				}
				parsePending();
			} else {
				debugTail = debugTail.setNext(
						new CraftAsyncDebugger(currentTick + RECENT_TICKS, task.getOwner(), task.getTaskClass()));
				try {
					executor.execute(task);
				} catch (RejectedExecutionException rejected) {
					rejectAsyncTask(task);
				}
				// We don't need to parse pending
				// (async tasks must live with race-conditions if they attempt to cancel between
				// these few lines of code)
			}
			final long period = task.getPeriod(); // State consistency
			if (period > 0) {
				task.setNextRun(currentTick + period);
				temp.add(task);
			} else if (task.isSync()) {
				runners.remove(task.getTaskId());
			}
		}
		pending.addAll(temp);
		temp.clear();
		debugHead = debugHead.getNextHead(currentTick);
	}

	/**
	 * Reject saturated async work without ever executing plugin code on the
	 * submitting thread (which is normally the server thread). Cancelling the
	 * CraftTask also removes an idle async task from the runners map and prevents
	 * repeating tasks from being re-queued by mainThreadHeartbeat.
	 */
	private void rejectAsyncTask(final Runnable runnable) {
		final CraftTask task = runnable instanceof CraftTask ? (CraftTask) runnable : null;
		if (task != null) {
			task.cancel0();
		}

		rejectedAsyncTaskCount.incrementAndGet();
		final long now = System.nanoTime();
		final long lastLog = lastAsyncRejectionLogNanos.get();
		if ((lastLog == 0L || now - lastLog >= ASYNC_REJECTION_LOG_INTERVAL_NANOS)
				&& lastAsyncRejectionLogNanos.compareAndSet(lastLog, now)) {
			final int rejectedSinceLastLog = rejectedAsyncTaskCount.getAndSet(0);
			if (task != null && task.getOwner() != null) {
				try {
					task.getOwner().getLogger().warning("WindSpigot async scheduler is saturated; rejected "
							+ rejectedSinceLastLog + " task(s) (latest task #" + task.getTaskId() + ").");
				} catch (RuntimeException ignored) {
					// A logger failure must never escape the rejection handler and interrupt a server tick.
				}
			}
		}
	}

	/**
	 * Shutdown drain for the bounded async task pool: rejects new async submissions, lets already
	 * accepted tasks finish and waits for the worker threads to terminate. Idempotent.
	 *
	 * @return true when the pool terminated within {@code timeoutMs}; false when work is still
	 *         running and the explicit timeout state remains visible for the shutdown report
	 */
	public boolean shutdownAsyncExecutor(final long timeoutMs) {
		asyncExecutorShutdown = true;
		executor.shutdown();
		final long timeout = Math.max(0L, timeoutMs);
		try {
			final boolean terminated = executor.awaitTermination(timeout, TimeUnit.MILLISECONDS);
			asyncExecutorShutdownTimedOut = !terminated;
			if (!terminated) {
				LOGGER.warning("CraftScheduler async executor did not terminate within " + timeout + "ms (active="
						+ executor.getActiveCount() + ", queued=" + executor.getQueue().size()
						+ "); accepted async tasks may be abandoned at JVM exit");
			}
			return terminated;
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			asyncExecutorShutdownTimedOut = true;
			LOGGER.log(Level.WARNING, "CraftScheduler async executor shutdown interrupted", interrupted);
			return false;
		}
	}

	public boolean isAsyncExecutorShutdown() {
		return asyncExecutorShutdown;
	}

	public boolean asyncExecutorShutdownTimedOut() {
		return asyncExecutorShutdownTimedOut;
	}

	public int pendingAsyncTaskCount() {
		return executor.getQueue().size();
	}

	// ------------------------------------------------------------------
	// 异步池诊断（2026-10-22）：插件 /tps 与 /windypit-tps 直接读这些计数。
	// 之前这些数字完全不可见，所以"池被占满"只能靠猜。
	// ------------------------------------------------------------------

	public int asyncPoolActiveCount() {
		return executor.getActiveCount();
	}

	public int asyncPoolQueueSize() {
		return executor.getQueue().size();
	}

	public int asyncPoolCoreSize() {
		return executor.getCorePoolSize();
	}

	public int asyncPoolMaxSize() {
		return executor.getMaximumPoolSize();
	}

	public int asyncPoolRejectedCount() {
		return rejectedAsyncTaskCount.get();
	}

	/** 饱和判定：所有线程都在忙且还有任务排队。 */
	public boolean asyncPoolSaturated() {
		return executor.getActiveCount() >= executor.getCorePoolSize() && !executor.getQueue().isEmpty();
	}

	/** 一行摘要，供 /windypit-tps 与启动日志使用。 */
	public String asyncPoolSummary() {
		return "asyncPool{active=" + executor.getActiveCount()
				+ ", core=" + executor.getCorePoolSize()
				+ ", max=" + executor.getMaximumPoolSize()
				+ ", queued=" + executor.getQueue().size()
				+ ", rejected=" + rejectedAsyncTaskCount.get()
				+ ", saturated=" + asyncPoolSaturated() + "}";
	}

	private void addTask(final CraftTask task) {
		final AtomicReference<CraftTask> tail = this.tail;
		CraftTask tailTask = tail.get();
		while (!tail.compareAndSet(tailTask, task)) {
			tailTask = tail.get();
		}
		tailTask.setNext(task);
	}

	private CraftTask handle(final CraftTask task, final long delay) {
		task.setNextRun(currentTick + delay);
		addTask(task);
		return task;
	}

	private static void validate(final Plugin plugin, final Object task) {
		Validate.notNull(plugin, "Plugin cannot be null");
		Validate.notNull(task, "Task cannot be null");
		if (!plugin.isEnabled()) {
			throw new IllegalPluginAccessException("Plugin attempted to register task while disabled");
		}
	}

	private int nextId() {
		return ids.incrementAndGet();
	}

	private void parsePending() {
		CraftTask head = this.head;
		CraftTask task = head.getNext();
		CraftTask lastTask = head;
		for (; task != null; task = (lastTask = task).getNext()) {
			if (task.getTaskId() == -1) {
				task.run();
			} else if (task.getPeriod() >= -1l) {
				pending.add(task);
				runners.put(task.getTaskId(), task);
			}
		}
		// We split this because of the way things are ordered for all of the async
		// calls in CraftScheduler
		// (it prevents race-conditions)
		for (task = head; task != lastTask; task = head) {
			head = task.getNext();
			task.setNext(null);
		}
		this.head = lastTask;
	}

	private boolean isReady(final int currentTick) {
		return !pending.isEmpty() && pending.peek().getNextRun() <= currentTick;
	}

	@Override
	public String toString() {
		int debugTick = currentTick;
		StringBuilder string = new StringBuilder("Recent tasks from ").append(debugTick - RECENT_TICKS).append('-')
				.append(debugTick).append('{');
		debugHead.debugTo(string);
		return string.append('}').toString();
	}

	@Deprecated
	@Override
	public int scheduleSyncDelayedTask(Plugin plugin, BukkitRunnable task, long delay) {
		return scheduleSyncDelayedTask(plugin, (Runnable) task, delay);
	}

	@Deprecated
	@Override
	public int scheduleSyncDelayedTask(Plugin plugin, BukkitRunnable task) {
		return scheduleSyncDelayedTask(plugin, (Runnable) task);
	}

	@Deprecated
	@Override
	public int scheduleSyncRepeatingTask(Plugin plugin, BukkitRunnable task, long delay, long period) {
		return scheduleSyncRepeatingTask(plugin, (Runnable) task, delay, period);
	}

	@Deprecated
	@Override
	public BukkitTask runTask(Plugin plugin, BukkitRunnable task) throws IllegalArgumentException {
		return runTask(plugin, (Runnable) task);
	}

	@Deprecated
	@Override
	public BukkitTask runTaskAsynchronously(Plugin plugin, BukkitRunnable task) throws IllegalArgumentException {
		return runTaskAsynchronously(plugin, (Runnable) task);
	}

	@Deprecated
	@Override
	public BukkitTask runTaskLater(Plugin plugin, BukkitRunnable task, long delay) throws IllegalArgumentException {
		return runTaskLater(plugin, (Runnable) task, delay);
	}

	@Deprecated
	@Override
	public BukkitTask runTaskLaterAsynchronously(Plugin plugin, BukkitRunnable task, long delay)
			throws IllegalArgumentException {
		return runTaskLaterAsynchronously(plugin, (Runnable) task, delay);
	}

	@Deprecated
	@Override
	public BukkitTask runTaskTimer(Plugin plugin, BukkitRunnable task, long delay, long period)
			throws IllegalArgumentException {
		return runTaskTimer(plugin, (Runnable) task, delay, period);
	}

	@Deprecated
	@Override
	public BukkitTask runTaskTimerAsynchronously(Plugin plugin, BukkitRunnable task, long delay, long period)
			throws IllegalArgumentException {
		return runTaskTimerAsynchronously(plugin, (Runnable) task, delay, period);
	}
}
