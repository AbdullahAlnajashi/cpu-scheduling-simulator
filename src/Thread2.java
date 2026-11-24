import java.util.ArrayList;

/**
 * Thread 2: long-term scheduler.
 * Every millisecond it moves jobs from the job queue to the ready queue while there is enough memory.
 */
public class Thread2 extends Thread {

    private int reportedBlockedPid = -1; // avoids printing the same "not enough memory" line every ms
    private int choice;                  // 1 = SJF, 2 = RR, 3 = Priority (same as the CPU scheduler)

    public Thread2(int choice) {
        this.choice = choice;
    }

    @Override
    public void run() {
        System.out.println("[Thread 2] Started. Monitoring job queue...");

        while (true) {
            Main.waitS(Main.t2Turn);

            if (Main.simulationDone) {
                Main.signal(Main.t3Turn);
                break;
            }

            admitJobs();
            Main.signal(Main.t3Turn);
        }

        System.out.println("[Thread 2] Done. All processes admitted and completed.");
    }

    private void admitJobs() {
        // While a process is suspended, memory that frees up is kept for it (Thread 3 swaps it back in).
        Main.waitS(Main.memoryMutex);
        boolean hasSuspended = !Main.suspendQueue.isEmpty();
        Main.signal(Main.memoryMutex);
        if (hasSuspended) {
            return;
        }

        ArrayList<PCB> admittedNow = new ArrayList<PCB>();
        boolean keepGoing = true;

        while (keepGoing && !Main.jobQueue.isEmpty()) {
            PCB next = nextJob();

            Main.waitS(Main.memoryMutex);
            if (Main.usedMemory + next.memoryRequired <= Main.TOTAL_MEMORY) {
                Main.jobQueue.remove(next);
                Main.usedMemory += next.memoryRequired;
                next.state = "ready";
                next.readyEnterTime = Main.clock;
                Main.readyQueue.add(next);
                admittedNow.add(next);
                reportedBlockedPid = -1;
                System.out.printf("[Time %d ms] [Thread 2] Admitted P%d into Ready Queue (memory used: %d/%d MB)%n",
                        Main.clock, next.pid, Main.usedMemory, Main.TOTAL_MEMORY);
            } else {
                keepGoing = false;
                if (reportedBlockedPid != next.pid) {
                    reportedBlockedPid = next.pid;
                    System.out.printf("[Time %d ms] [Thread 2] Not enough memory for P%d (%d MB needed, %d MB free). Waiting...%n",
                            Main.clock, next.pid, next.memoryRequired, Main.TOTAL_MEMORY - Main.usedMemory);
                }
            }
            Main.signal(Main.memoryMutex);
        }

        // A process is starved if it waits in the ready queue longer than N x 5 ms (N = ready queue size).
        int n = Main.readyQueue.size();
        for (int i = 0; i < admittedNow.size(); i++) {
            admittedNow.get(i).starvationThreshold = n * 5;
        }
    }

    /**
     * The job to load next, in the same order the CPU scheduler uses:
     * shortest burst for SJF, highest priority for Priority, arrival order (FIFO) for RR.
     * If this job does not fit, admission stops, so a lower job never takes the memory it is waiting for.
     */
    private PCB nextJob() {
        PCB best = null;
        for (PCB p : Main.jobQueue) {
            if (best == null || comesFirst(p, best)) {
                best = p;
            }
        }
        return best;
    }

    private boolean comesFirst(PCB a, PCB b) {
        if (choice == 1 && a.burstTime != b.burstTime) {
            return a.burstTime < b.burstTime;
        }
        if (choice == 3 && a.priority != b.priority) {
            return a.priority < b.priority;
        }
        return a.arrivalOrder < b.arrivalOrder;
    }

    /** Called by the CPU scheduler when a process terminates. */
    public static void freeMemory(PCB p) {
        Main.waitS(Main.memoryMutex);
        Main.usedMemory -= p.memoryRequired;
        System.out.printf("[Time %d ms] [Thread 2] Freed %d MB from P%d (memory used: %d/%d MB)%n",
                Main.clock, p.memoryRequired, p.pid, Main.usedMemory, Main.TOTAL_MEMORY);
        Main.signal(Main.memoryMutex);
    }
}
