import java.util.LinkedList;
import java.util.Locale;
import java.util.Queue;
import java.util.Scanner;
import java.util.concurrent.Semaphore;

/**
 * Entry point and shared state of the simulator.
 *
 * Four threads cooperate in lock-step, one simulated millisecond at a time:
 *   main (short-term scheduler) -> Thread 2 (long-term) -> Thread 3 (medium-term) -> main ...
 * The hand-off is done with three semaphores that start at 0, and a binary semaphore
 * (memoryMutex) guards every access to the queues and to usedMemory.
 */
public class Main {

    // Queues shared by all schedulers
    public static Queue<PCB> jobQueue = new LinkedList<PCB>();     // created, waiting for memory
    public static Queue<PCB> readyQueue = new LinkedList<PCB>();   // in memory, waiting for the CPU
    public static Queue<PCB> suspendQueue = new LinkedList<PCB>(); // swapped out by Thread 3

    public static final int TOTAL_MEMORY = 2048; // MB
    public static int usedMemory = 0;
    public static int clock = 0;                 // current simulated time (ms)
    public static boolean simulationDone = false;

    // Synchronization
    public static Semaphore memoryMutex = new Semaphore(1); // protects queues + usedMemory
    public static Semaphore t2Turn = new Semaphore(0);      // main -> Thread 2
    public static Semaphore t3Turn = new Semaphore(0);      // Thread 2 -> Thread 3
    public static Semaphore schedTurn = new Semaphore(0);   // Thread 3 -> main

    public static void waitS(Semaphore s) {
        try {
            s.acquire();
        } catch (InterruptedException e) {
            System.out.println("Thread interrupted while waiting.");
        }
    }

    public static void signal(Semaphore s) {
        s.release();
    }

    public static void main(String[] args) throws InterruptedException {
        // Always print English digits (an Arabic system locale would print Arabic-Indic digits).
        Locale.setDefault(Locale.US);
        Scanner input = new Scanner(System.in);

        System.out.println("===========================================");
        System.out.println("  CPU Scheduling Simulator");
        System.out.println("===========================================");

        String filePath = "examples/job.txt";
        if (args.length > 0) {
            filePath = args[0];
        }

        System.out.println("\nSelect scheduling algorithm:");
        System.out.println("  1. Shortest Job First (SJF)");
        System.out.println("  2. Round Robin (RR, q=5ms)");
        System.out.println("  3. Priority Scheduling (Non-Preemptive)");
        System.out.print("Choice [1/2/3]: ");
        String line = "";
        if (input.hasNextLine()) {
            line = input.nextLine().trim();
        }
        int choice;
        if (line.equals("1")) {
            choice = 1;
        } else if (line.equals("2")) {
            choice = 2;
        } else if (line.equals("3")) {
            choice = 3;
        } else {
            System.out.println("Invalid choice. Defaulting to SJF.");
            choice = 1;
        }

        System.out.print("Enable medium-term scheduler (swapping)? [y/n]: ");
        String answer = "n";
        if (input.hasNextLine()) {
            answer = input.nextLine().trim().toLowerCase();
        }
        boolean mediumEnabled = answer.startsWith("y");

        Thread1 t1 = new Thread1(filePath);
        Thread2 t2 = new Thread2(choice);
        Thread3 t3 = new Thread3(mediumEnabled, choice);
        t1.start();
        t2.start();
        t3.start();

        t1.join(); // the simulation starts only after every job has been read

        if (jobQueue.isEmpty()) {
            System.out.println("\nNo valid jobs to schedule. Exiting.");
            stopWorkers();
            t2.join();
            t3.join();
            input.close();
            return;
        }

        System.out.println("\n--- Starting Simulation ---\n");
        Scheduler scheduler = new Scheduler(choice);
        scheduler.run();

        t2.join();
        t3.join();

        scheduler.printGanttChart();
        scheduler.printTable();
        scheduler.printMetrics();
        if (choice == 3) {
            scheduler.printStarvedProcesses();
        }

        input.close();
    }

    /** Tells Thread 2 and Thread 3 to finish, and waits until both have seen it. */
    public static void stopWorkers() {
        simulationDone = true;
        signal(t2Turn);
        waitS(schedTurn);
    }
}
