/** Process Control Block: everything the simulator knows about one process. */
public class PCB {

    // From the input file
    public int pid;
    public int burstTime;
    public int remainingBurst;
    public int priority;          // 1 = highest; lowered by aging
    public int originalPriority;
    public int memoryRequired;    // MB
    public int arrivalTime;       // all jobs arrive at t = 0
    public int arrivalOrder;      // line order in the file, used to break ties

    // Results
    public int startTime = -1;
    public int terminationTime = -1;
    public int waitingTime = 0;
    public int turnaroundTime = 0;
    public String state;          // new, ready, running, suspended, terminated

    // Starvation and aging (Priority scheduling)
    public int readyEnterTime = 0;
    public int starvationThreshold = Integer.MAX_VALUE;
    public boolean starved = false;
    public int lastAgedAt = -1;

    public PCB(int pid, int burstTime, int priority, int memoryRequired, int arrivalTime, int arrivalOrder) {
        this.pid = pid;
        this.burstTime = burstTime;
        this.remainingBurst = burstTime;
        this.priority = priority;
        this.originalPriority = priority;
        this.memoryRequired = memoryRequired;
        this.arrivalTime = arrivalTime;
        this.arrivalOrder = arrivalOrder;
        this.state = "new";
    }

    @Override
    public String toString() {
        return "P" + pid;
    }
}
