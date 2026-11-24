import java.io.File;
import java.io.FileNotFoundException;
import java.util.HashSet;
import java.util.Scanner;

/**
 * Thread 1: reads the job file, creates a PCB for every valid line and puts it in the job queue.
 * Line format:  pid:burst:priority;memory   (for example  1:25:4;500)
 */
public class Thread1 extends Thread {

    private String filePath;
    private HashSet<Integer> usedPids = new HashSet<Integer>();

    public Thread1(String filePath) {
        this.filePath = filePath;
    }

    @Override
    public void run() {
        System.out.println("[Thread 1] Started. Reading from: " + filePath);

        Scanner file;
        try {
            file = new Scanner(new File(filePath));
        } catch (FileNotFoundException e) {
            System.out.println("[Thread 1] ERROR: File not found: " + filePath);
            return;
        }

        int arrivalOrder = 0;
        int lineNo = 0;
        while (file.hasNextLine()) {
            String line = file.nextLine().trim();
            lineNo++;
            if (line.isEmpty()) {
                continue;
            }

            PCB pcb = parseLine(line, lineNo, arrivalOrder);
            if (pcb != null) {
                Main.jobQueue.add(pcb);
                System.out.printf("[Thread 1] Created PCB -> PID=%d  Burst=%dms  Priority=%d  Memory=%dMB%n",
                        pcb.pid, pcb.burstTime, pcb.priority, pcb.memoryRequired);
                arrivalOrder++;
            }
        }
        file.close();

        System.out.println("[Thread 1] Done. All PCBs inserted into Job Queue.");
    }

    /** Returns a PCB for a valid line, or null (with a message) for an invalid one. */
    private PCB parseLine(String line, int lineNo, int arrivalOrder) {
        String[] parts = line.replace(';', ':').split(":");
        if (parts.length != 4) {
            System.out.println("[Thread 1] Line " + lineNo + ": wrong format, skipped: " + line);
            return null;
        }

        int pid, burst, priority, memory;
        try {
            pid = Integer.parseInt(parts[0].trim());
            burst = Integer.parseInt(parts[1].trim());
            priority = Integer.parseInt(parts[2].trim());
            memory = Integer.parseInt(parts[3].trim());
        } catch (NumberFormatException e) {
            System.out.println("[Thread 1] Line " + lineNo + ": not a number, skipped: " + line);
            return null;
        }

        if (pid <= 0) {
            System.out.println("[Thread 1] Line " + lineNo + ": PID must be > 0, skipped.");
            return null;
        }
        if (usedPids.contains(pid)) {
            System.out.println("[Thread 1] Line " + lineNo + ": duplicate PID " + pid + ", skipped.");
            return null;
        }
        if (burst <= 0) {
            System.out.println("[Thread 1] Line " + lineNo + ": burst must be > 0, skipped.");
            return null;
        }
        if (priority < 1 || priority > 30) {
            System.out.println("[Thread 1] Line " + lineNo + ": priority must be 1-30, skipped.");
            return null;
        }
        // A job larger than the whole memory could never be loaded and would block forever.
        if (memory <= 0 || memory > Main.TOTAL_MEMORY) {
            System.out.println("[Thread 1] Line " + lineNo + ": memory must be 1-" + Main.TOTAL_MEMORY + " MB, skipped.");
            return null;
        }

        usedPids.add(pid);
        return new PCB(pid, burst, priority, memory, 0, arrivalOrder);
    }
}
