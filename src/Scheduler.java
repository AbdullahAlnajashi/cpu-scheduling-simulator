import java.util.ArrayList;

/**
 * Short-term (CPU) scheduler, run by the main thread.
 * Simulates the CPU one millisecond at a time with SJF, Round Robin or non-preemptive Priority
 * (with starvation detection and aging), then prints the Gantt chart, the table and the averages.
 */
public class Scheduler {

    private static final int QUANTUM = 5;          // RR time slice (ms)
    private static final int AGING_INTERVAL = 4;   // a starved process gains 1 priority level every 4 ms
    private static final int IDLE_LIMIT = 100000;  // safety stop if the CPU could never get work

    private int choice; // 1 = SJF, 2 = RR, 3 = Priority

    // Each entry: {pid, start, end, burst before, burst after}
    private ArrayList<int[]> gantt = new ArrayList<int[]>();
    private ArrayList<PCB> finished = new ArrayList<PCB>();

    public Scheduler(int choice) {
        this.choice = choice;
    }

    public void run() {
        int time = 0;
        PCB running = null;
        PCB preempted = null;  // RR process whose quantum ended; re-queued after this ms's admissions
        int sliceStart = 0;
        int burstBefore = 0;
        int sliceUsed = 0;
        int idleTicks = 0;
        boolean done = false;

        while (!done) {
            // Let Thread 2 and then Thread 3 do their work for this millisecond.
            Main.clock = time;
            Main.signal(Main.t2Turn);
            Main.waitS(Main.schedTurn);

            // Critical section: the queues are shared with Thread 2 and Thread 3.
            Main.waitS(Main.memoryMutex);
            if (preempted != null) {
                preempted.state = "ready";
                preempted.readyEnterTime = time;
                Main.readyQueue.add(preempted);
                preempted = null;
            }

            if (choice == 3) {
                applyStarvationAndAging(time);
            }

            if (running == null) {
                running = selectNext();
                if (running != null) {
                    running.state = "running";
                    if (running.startTime == -1) {
                        running.startTime = time;
                    }
                    sliceStart = time;
                    burstBefore = running.remainingBurst;
                    sliceUsed = 0;
                }
            }
            boolean nothingLeft = (running == null) && allQueuesEmpty();
            Main.signal(Main.memoryMutex);

            if (running == null) {
                if (nothingLeft) {
                    done = true;
                } else {
                    idleTicks++;
                    if (idleTicks > IDLE_LIMIT) {
                        System.out.println("[Scheduler] ERROR: CPU idle for too long, stopping.");
                        done = true;
                    } else {
                        time++;
                    }
                }
            } else {
                // Run the process for one millisecond.
                idleTicks = 0;
                running.remainingBurst--;
                sliceUsed++;
                time++;
                Main.clock = time;

                if (running.remainingBurst == 0) {
                    terminate(running, sliceStart, burstBefore, time);
                    running = null;
                } else if (choice == 2 && sliceUsed == QUANTUM) {
                    gantt.add(new int[]{running.pid, sliceStart, time, burstBefore, running.remainingBurst});
                    System.out.printf("[RR] P%d ran t=%d -> t=%d  (remaining=%dms)%n",
                            running.pid, sliceStart, time, running.remainingBurst);
                    preempted = running;
                    running = null;
                }
            }
        }

        Main.stopWorkers();
    }

    private void terminate(PCB p, int sliceStart, int burstBefore, int time) {
        p.terminationTime = time;
        p.turnaroundTime = p.terminationTime - p.arrivalTime;
        p.waitingTime = p.turnaroundTime - p.burstTime;
        p.state = "terminated";

        gantt.add(new int[]{p.pid, sliceStart, time, burstBefore, 0});
        finished.add(p);
        Thread2.freeMemory(p);

        if (choice == 3) {
            String mark = "";
            if (p.starved) {
                mark = "  *** STARVED ***";
            }
            System.out.printf("[Priority] P%d ran t=%d -> t=%d (priority=%d)%s%n",
                    p.pid, sliceStart, time, p.priority, mark);
        } else if (choice == 2) {
            System.out.printf("[RR] P%d ran t=%d -> t=%d  (finished)%n", p.pid, sliceStart, time);
        } else {
            System.out.printf("[SJF] P%d ran t=%d -> t=%d%n", p.pid, sliceStart, time);
        }
    }

    private boolean allQueuesEmpty() {
        return Main.jobQueue.isEmpty() && Main.readyQueue.isEmpty() && Main.suspendQueue.isEmpty();
    }

    /** RR takes the head of the queue; SJF and Priority take the best process (ties: arrival order). */
    private PCB selectNext() {
        if (Main.readyQueue.isEmpty()) {
            return null;
        }
        if (choice == 2) {
            return Main.readyQueue.poll();
        }

        PCB best = null;
        for (PCB p : Main.readyQueue) {
            if (best == null || isBetter(p, best)) {
                best = p;
            }
        }
        Main.readyQueue.remove(best);
        return best;
    }

    private boolean isBetter(PCB a, PCB b) {
        int valueA, valueB;
        if (choice == 1) {
            valueA = a.burstTime;
            valueB = b.burstTime;
        } else {
            valueA = a.priority;
            valueB = b.priority;
        }
        if (valueA != valueB) {
            return valueA < valueB;
        }
        return a.arrivalOrder < b.arrivalOrder;
    }

    /** Marks processes that waited longer than their threshold as starved and ages them every 4 ms. */
    private void applyStarvationAndAging(int time) {
        for (PCB p : Main.readyQueue) {
            int waited = time - p.readyEnterTime;

            if (!p.starved && waited > p.starvationThreshold) {
                p.starved = true;
                p.lastAgedAt = time;
                System.out.printf("[Starvation] P%d waited %d ms (> %d ms) at t=%d%n",
                        p.pid, waited, p.starvationThreshold, time);
            }

            if (p.starved && p.priority > 1 && time - p.lastAgedAt >= AGING_INTERVAL) {
                p.priority--;
                p.lastAgedAt = time;
                System.out.printf("[Aging] P%d priority boosted to %d at t=%d%n", p.pid, p.priority, time);
            }
        }
    }

    public void printGanttChart() {
        System.out.println("\n========== GANTT CHART ==========");
        System.out.println("Format: Pid [burst before -> burst after]  time start -> end\n");

        for (int i = 0; i < gantt.size(); i++) {
            int[] e = gantt.get(i);
            System.out.printf("P%d [%d -> %d]  time %d -> %d%n", e[0], e[3], e[4], e[1], e[2]);
        }

        // Bar chart with the end time of each slice under its right edge.
        String bar = "";
        String times = "";
        if (gantt.size() > 0) {
            times = "" + gantt.get(0)[1];
        }
        for (int i = 0; i < gantt.size(); i++) {
            int[] e = gantt.get(i);
            String cell = "| P" + e[0] + " ";
            bar = bar + cell;
            String end = "" + e[2];
            times = times + spaces(cell.length() - end.length()) + end;
        }
        System.out.println("\n" + bar + "|");
        System.out.println(times + "\n");
    }

    /** n spaces, but always at least one. */
    private String spaces(int n) {
        String s = " ";
        for (int i = 1; i < n; i++) {
            s = s + " ";
        }
        return s;
    }

    public void printTable() {
        sortByPid();

        System.out.println("========== PROCESS TABLE ==========");
        System.out.printf("%-6s %-12s %-12s %-16s %-14s %-16s%n",
                "PID", "Burst(ms)", "Start(ms)", "Terminate(ms)", "Waiting(ms)", "Turnaround(ms)");
        System.out.println("--------------------------------------------------------------------------------");

        for (int i = 0; i < finished.size(); i++) {
            PCB p = finished.get(i);
            System.out.printf("%-6d %-12d %-12d %-16d %-14d %-16d%n",
                    p.pid, p.burstTime, p.startTime, p.terminationTime, p.waitingTime, p.turnaroundTime);
        }
        System.out.println();
    }

    private void sortByPid() {
        finished.sort((a, b) -> Integer.compare(a.pid, b.pid));
    }

    public void printMetrics() {
        double totalWT = 0;
        double totalTAT = 0;
        for (int i = 0; i < finished.size(); i++) {
            totalWT += finished.get(i).waitingTime;
            totalTAT += finished.get(i).turnaroundTime;
        }
        double avgWT = 0;
        double avgTAT = 0;
        if (finished.size() > 0) {
            avgWT = totalWT / finished.size();
            avgTAT = totalTAT / finished.size();
        }

        System.out.println("========== PERFORMANCE METRICS ==========");
        System.out.printf("  Average Waiting Time:     %.2f ms%n", avgWT);
        System.out.printf("  Average Turnaround Time:  %.2f ms%n", avgTAT);
        System.out.println();
    }

    public void printStarvedProcesses() {
        System.out.println("========== STARVATION REPORT ==========");
        boolean any = false;
        for (int i = 0; i < finished.size(); i++) {
            PCB p = finished.get(i);
            if (p.starved) {
                any = true;
                System.out.printf("  P%d suffered starvation  (original priority: %d  ->  final priority: %d)%n",
                        p.pid, p.originalPriority, p.priority);
            }
        }
        if (!any) {
            System.out.println("  No processes suffered from starvation.");
        }
        System.out.println();
    }
}
