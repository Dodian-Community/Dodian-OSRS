package io.nozemi.runescape;

import io.netty.handler.traffic.TrafficCounter;
import io.nozemi.runescape.model.World;
import io.nozemi.runescape.task.*;
import it.unimi.dsi.fastutil.objects.Object2IntArrayMap;
import it.unimi.dsi.fastutil.objects.Object2LongArrayMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class ServerProcessor extends Thread {

    private static final Logger logger = LogManager.getLogger(ServerProcessor.class);

    private World world;

    private List<Task> tasks = new LinkedList<>();

    private boolean running = true;

    private int infotick = 10;

    private ExecutorService taskExecutor;

    private ConcurrentLinkedQueue<Runnable> logicJobs;

    public static boolean forceLog;

    public static Object2IntArrayMap<Class> times = new Object2IntArrayMap<>();
    public static Object2LongArrayMap<String> computeTimes = new Object2LongArrayMap<>();

    public ServerProcessor() {
        super("ServerProcessor");

        this.world = GameInitializer.world();
        taskExecutor = Executors.newWorkStealingPool();
        logicJobs = new ConcurrentLinkedQueue<>();

        tasks.add(new PacketProcessingTask());
        tasks.add(new ScriptProcessingTask());
        tasks.add(new PlayerProcessingTask());
        tasks.add(new NpcProcessingTask());

        tasks.add(new PlayerPreSyncTask());
        tasks.add(new NpcViewportTask());
        tasks.add(new PlayerSyncTask());
        tasks.add(new NpcSyncTask());
        tasks.add(new PlayerPostSyncTask());
        tasks.add(new NpcPostSyncTask());

        tasks.add(new NetworkFlushTask());

        GameInitializer.scriptRepository().triggerWorldInit(this.world);

        start();
    }

    public void submitLogic(Runnable r) {
        logicJobs.add(r);
    }

    @Override
    public void run() {
        while (running) {
            process();
        }
    }
    private void process() {
        computeTimes.clear();
        long start = System.currentTimeMillis();

        // Execute logic jobs
        logicJobs.forEach((runnable) -> {
            try {
                runnable.run();
            } catch (Exception e) {
                logger.error("Error executing logic job!", e);
            }
        });
        logicJobs.clear();


        times.clear();
        for (Task t : tasks) {
            long l = System.currentTimeMillis();
            try {
                if (t.isAsyncSafe()) { // Is this job distributable across multiple workers?
                    Collection<SubTask> jobs = t.createJobs(world);
                    List<Future<Object>> futures = taskExecutor.invokeAll(jobs);

                    while (futures.stream().anyMatch(f -> !f.isDone())) {
                        // TODO: Look at replacing Strand from Quasar
                        Thread.sleep(1); // Sleep a small millisecond to go easy on the cpu.
                    }
                } else { // Simple non-thread safe job that must execute all by itself.
                    t.execute(world);
                }
            } catch (Throwable throwable) {
                logger.error("An exception occurred when executing " + t.getClass().getSimpleName() + ".", throwable);
            }
            times.put(t.getClass(), (int) (System.currentTimeMillis() - l));
        }

        long delay = 600 - (System.currentTimeMillis() - start);

        if (infotick-- == 0) {
            infotick = 10;

            long totalMem = Runtime.getRuntime().totalMemory();
            long freeMem = Runtime.getRuntime().freeMemory();
            long maxMem = Runtime.getRuntime().maxMemory();
            TrafficCounter traffic = GameInitializer.clientInitializer().trafficStats();
            if (traffic != null && GameInitializer.isDevServer())
                logger.info("Cycle time: {}ms, players: {}, npcs: {}. Memory usage: {}MB/{}MB. Reserved: {}MB. Rx: {}KB/s, Tx: {}KB/s.",
                        System.currentTimeMillis() - start, world.players().size(), world.npcs().size(), (totalMem - freeMem) / 1024 / 1024,
                        totalMem / 1024 / 1024, maxMem / 1024 / 1024, traffic.lastReadThroughput() / 1024, traffic.lastWriteThroughput() / 1024);
        }

        if (forceLog) {
            logger.error(times);
            logger.error(computeTimes);
            logger.error(GameInitializer.scriptRepository().getTimerProfiler());
            forceLog = false;
        }

        if (delay < 0) {
            logger.error("Server cannot keep up! Cycle overdue: {}ms.", -delay);
            logger.error(times);
            logger.error(computeTimes);
        } else {
            try {
                Thread.sleep(delay); // TODO: Look into how to replace Quasar's Strand
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void terminate() {
        running = false;
    }
}
