/**
 * Thread 3: medium-term scheduler (optional).
 * Swaps a ready process out when memory use is above 90% and back in when it drops below 70%.
 * The victim is the process the CPU scheduler would run last, so a high-priority (or short)
 * process is never pushed out to make room for a lower one.
 */
public class Thread3 extends Thread {

    public static final double HIGH_THRESHOLD = 90.0;
    public static final double LOW_THRESHOLD = 70.0;

    private boolean enabled;
    private int choice; // 1 = SJF, 2 = RR, 3 = Priority (same as the CPU scheduler)

    public Thread3(boolean enabled, int choice) {
        this.enabled = enabled;
        this.choice = choice;
    }

    @Override
    public void run() {
        if (enabled) {
            System.out.println("[Thread 3] Started. Medium-term scheduler is ENABLED.");
        } else {
            System.out.println("[Thread 3] Started. Medium-term scheduler is DISABLED.");
        }

        while (true) {
            Main.waitS(Main.t3Turn);

            if (Main.simulationDone) {
                Main.signal(Main.schedTurn);
                break;
            }

            if (enabled) {
                checkMemory();
            }

            Main.signal(Main.schedTurn);
        }

        System.out.println("[Thread 3] Done.");
    }

    private double utilization() {
        return Main.usedMemory * 100.0 / Main.TOTAL_MEMORY;
    }

    /** Critical section: reads and changes usedMemory, readyQueue and suspendQueue. */
    private void checkMemory() {
        Main.waitS(Main.memoryMutex);
        try {
            double util = utilization();
            if (util > HIGH_THRESHOLD) {
                swapOut(util);
            } else if (util < LOW_THRESHOLD && !Main.suspendQueue.isEmpty()) {
                swapIn(util);
            }
        } finally {
            Main.signal(Main.memoryMutex);
        }
    }

    /**
     * True if a is a better swap-out victim than b: the process the CPU scheduler would run LAST
     * (longest job for SJF, lowest priority for Priority); ties go to the larger memory.
     * RR has no such order, so the largest memory is chosen.
     */
    private boolean betterVictim(PCB a, PCB b) {
        if (choice == 1 && a.burstTime != b.burstTime) {
            return a.burstTime > b.burstTime;
        }
        if (choice == 3 && a.priority != b.priority) {
            return a.priority > b.priority;
        }
        return a.memoryRequired > b.memoryRequired;
    }

    /** True if a should be swapped in before b: the process the CPU scheduler would run FIRST. */
    private boolean betterSwapIn(PCB a, PCB b) {
        if (choice == 1 && a.burstTime != b.burstTime) {
            return a.burstTime < b.burstTime;
        }
        if (choice == 3 && a.priority != b.priority) {
            return a.priority < b.priority;
        }
        return a.arrivalOrder < b.arrivalOrder;
    }

    private void swapOut(double before) {
        PCB victim = null;
        for (PCB p : Main.readyQueue) {
            if (victim == null || betterVictim(p, victim)) {
                victim = p;
            }
        }

        // Never swap out the only process in memory: that would not help anyone.
        if (victim == null || victim.memoryRequired == Main.usedMemory) {
            return;
        }

        String oldState = victim.state;
        Main.readyQueue.remove(victim);
        Main.usedMemory -= victim.memoryRequired;
        victim.state = "suspended";
        Main.suspendQueue.add(victim);

        printIntervention(before, "Swap out", victim, oldState);
    }

    private void swapIn(double before) {
        PCB p = null;
        for (PCB s : Main.suspendQueue) {
            if (p == null || betterSwapIn(s, p)) {
                p = s;
            }
        }

        // Only swap in if memory will not go above 90% again (prevents immediate thrashing).
        double utilAfter = (Main.usedMemory + p.memoryRequired) * 100.0 / Main.TOTAL_MEMORY;
        if (Main.usedMemory > 0 && utilAfter > HIGH_THRESHOLD) {
            return;
        }

        String oldState = p.state;
        Main.suspendQueue.remove(p);
        Main.usedMemory += p.memoryRequired;
        p.state = "ready";
        // readyEnterTime is NOT reset: time spent suspended still counts as waiting,
        // so a swapped-out process can still be detected as starved and aged.
        Main.readyQueue.add(p);
        p.starvationThreshold = Main.readyQueue.size() * 5;

        printIntervention(before, "Swap in", p, oldState);
    }

    private void printIntervention(double before, String action, PCB p, String oldState) {
        System.out.println("[Time " + Main.clock + " ms]");
        System.out.println("Medium-Term Scheduler Intervention");
        System.out.printf("Memory utilization: %.1f%%%n", before);
        System.out.println("Action: " + action + " Process " + p.pid);
        System.out.println("Process " + p.pid + ": " + capitalize(oldState) + " -> " + capitalize(p.state));
        System.out.printf("Memory utilization after intervention: %.1f%%%n%n", utilization());
    }

    private String capitalize(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
