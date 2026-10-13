import java.util.concurrent.CountDownLatch;

public final class JfrWorkload {
    private static final Object LOCK = new Object();
    private static volatile long sink;

    private JfrWorkload() {}

    public static void main(String[] args) throws InterruptedException {
        long endAt = System.nanoTime() + 15_000_000_000L;
        CountDownLatch lockHeld = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            while (System.nanoTime() < endAt) {
                synchronized (LOCK) {
                    lockHeld.countDown();
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "JfrFixture-LockHolder");
        holder.setDaemon(true);
        holder.start();
        lockHeld.await();

        Thread contender = new Thread(() -> {
            while (System.nanoTime() < endAt) {
                synchronized (LOCK) {
                    sink++;
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "JfrFixture-LockContender");
        contender.setDaemon(true);
        contender.start();

        while (System.nanoTime() < endAt) {
            for (int index = 1; index < 1_000_000; index++) {
                sink += index;
            }
        }
        holder.join();
        contender.join();
    }
}
