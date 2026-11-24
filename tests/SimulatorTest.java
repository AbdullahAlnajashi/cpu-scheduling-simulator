import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/**
 * Black-box tests for the CSC 227 simulator.
 * Each case runs "java Main <jobfile>" in its own JVM (the simulator uses static state),
 * feeds the menu answers on stdin and checks the printed output.
 *
 * Usage:  java SimulatorTest <dir with compiled simulator classes>
 */
public class SimulatorTest {

    static String classDir;
    static int passed = 0, failed = 0;
    static List<String> failures = new ArrayList<>();

    // ---------- running the simulator ----------

    static class Result {
        String out; int exit; boolean timedOut;
        List<int[]> gantt = new ArrayList<>();            // pid, before, after, start, end
        Map<Integer, int[]> table = new TreeMap<>();      // pid -> burst, start, term, wait, tat
        List<Integer> memoryUsed = new ArrayList<>();
        Map<Integer, int[]> starved = new TreeMap<>();    // pid -> original, final priority
    }

    static final Pattern GANTT = Pattern.compile("^P(\\d+) \\[(\\d+) -> (\\d+)\\]  time (\\d+) -> (\\d+)$");
    static final Pattern ROW = Pattern.compile("^(-?\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(-?\\d+)\\s+(\\d+)\\s*$");
    static final Pattern MEM = Pattern.compile("memory used: (-?\\d+)/2048 MB");
    static final Pattern STARVED = Pattern.compile("P(\\d+) suffered starvation  \\(original priority: (\\d+)  ->  final priority: (-?\\d+)\\)");

    static Result run(String jobs, String stdin, String... jvmArgs) throws Exception {
        Path f = Files.createTempFile("job", ".txt");
        Files.write(f, jobs.getBytes(StandardCharsets.US_ASCII));
        try {
            return runFile(f.toString(), stdin, jvmArgs);
        } finally {
            Files.deleteIfExists(f);
        }
    }

    static Result runFile(String path, String stdin, String... jvmArgs) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        cmd.addAll(Arrays.asList(jvmArgs));
        cmd.add("-cp"); cmd.add(classDir); cmd.add("Main"); cmd.add(path);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try (OutputStream os = p.getOutputStream()) {
            os.write(stdin.getBytes(StandardCharsets.US_ASCII));
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try { p.getInputStream().transferTo(buf); } catch (IOException ignored) { }
        });
        reader.start();
        Result r = new Result();
        if (!p.waitFor(20, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            r.timedOut = true;
        }
        reader.join(2000);
        r.out = buf.toString(StandardCharsets.UTF_8).replace("\r", "");
        r.exit = r.timedOut ? -1 : p.exitValue();
        boolean inTable = false;
        for (String line : r.out.split("\n")) {
            Matcher m;
            if (line.startsWith("========== PROCESS TABLE")) inTable = true;
            if (line.startsWith("========== PERFORMANCE")) inTable = false;
            if ((m = GANTT.matcher(line)).matches()) {
                r.gantt.add(new int[]{i(m, 1), i(m, 2), i(m, 3), i(m, 4), i(m, 5)});
            } else if (inTable && (m = ROW.matcher(line)).matches()) {
                r.table.put(i(m, 1), new int[]{i(m, 2), i(m, 3), i(m, 4), i(m, 5), i(m, 6)});
            }
            if ((m = MEM.matcher(line)).find()) r.memoryUsed.add(i(m, 1));
            if ((m = STARVED.matcher(line)).find()) r.starved.put(i(m, 1), new int[]{i(m, 2), i(m, 3)});
        }
        return r;
    }

    static int i(Matcher m, int g) { return Integer.parseInt(m.group(g)); }

    static String ganttString(Result r) {
        StringBuilder sb = new StringBuilder();
        for (int[] g : r.gantt) sb.append("P").append(g[0]).append("[").append(g[3]).append("-").append(g[4]).append("] ");
        return sb.toString().trim();
    }

    // ---------- assertions ----------

    static String current;

    static void check(boolean ok, String what) {
        if (ok) {
            passed++;
        } else {
            failed++;
            failures.add(current + " :: " + what);
            System.out.println("    FAIL: " + what);
        }
    }

    static void eq(Object expected, Object actual, String what) {
        check(Objects.equals(expected, actual), what + "  expected <" + expected + "> but was <" + actual + ">");
    }

    static void finishedCleanly(Result r) {
        check(!r.timedOut, "program finished (no deadlock / hang)");
        eq(0, r.exit, "exit code");
        check(!r.out.contains("Exception"), "no exception printed");
    }

    static void test(String name) {
        current = name;
        System.out.println("- " + name);
    }

    // ---------- hand-derived scenario tests ----------

    static final String TEAM =
            "1:12:5;600\n2:4:2;400\n3:20:8;700\n4:6:3;300\n5:15:10;350\n6:3:1;500\n7:9:4;200\n";

    static void reportScenarios() throws Exception {
        test("job_team SJF");
        Result r = run(TEAM, "1\nn\n");
        finishedCleanly(r);
        eq("P6[0-3] P2[3-7] P4[7-13] P7[13-22] P1[22-34] P5[34-49] P3[49-69]", ganttString(r), "gantt");
        check(r.out.contains("Average Waiting Time:     18.29 ms"), "avg waiting 18.29");
        check(r.out.contains("Average Turnaround Time:  28.14 ms"), "avg turnaround 28.14");

        test("job_team RR q=5");
        r = run(TEAM, "2\nn\n");
        finishedCleanly(r);
        eq("P1[0-5] P2[5-9] P3[9-14] P4[14-19] P1[19-24] P5[24-29] P3[29-34] P4[34-35] P1[35-37] "
                + "P5[37-42] P3[42-47] P6[47-50] P7[50-55] P5[55-60] P3[60-65] P7[65-69]", ganttString(r), "gantt");
        check(r.out.contains("Average Waiting Time:     36.57 ms"), "avg waiting 36.57");
        check(r.out.contains("Average Turnaround Time:  46.43 ms"), "avg turnaround 46.43");

        test("job_team Priority with aging");
        r = run(TEAM, "3\nn\n");
        finishedCleanly(r);
        eq("P6[0-3] P2[3-7] P4[7-13] P7[13-22] P1[22-34] P3[34-54] P5[54-69]", ganttString(r), "gantt");
        check(r.out.contains("Average Waiting Time:     19.00 ms"), "avg waiting 19.00");
        check(r.out.contains("Average Turnaround Time:  28.86 ms"), "avg turnaround 28.86");
        eq("[8, 7]", Arrays.toString(r.starved.get(3)), "P3 priority 8 -> 7");
        eq("[10, 5]", Arrays.toString(r.starved.get(5)), "P5 priority 10 -> 5");
        eq(2, r.starved.size(), "only P3 and P5 starved");

        test("job_team SJF + medium-term");
        // Loaded at t=0: P6 P2 P4 P7 P1 = 2000 MB (97.7%) -> P1, the longest loaded job, is swapped out.
        r = run(TEAM, "1\ny\n");
        finishedCleanly(r);
        check(r.out.contains("Memory utilization: 97.7%\nAction: Swap out Process 1"), "swap out P1 at 97.7%");
        check(r.out.contains("Memory utilization: 43.9%\nAction: Swap in Process 1"), "swap in P1 at 43.9%");
        check(r.out.contains("Average Waiting Time:     18.29 ms"), "averages unchanged by swapping");
    }

    static void admissionOrder() throws Exception {
        test("Long-term + Priority: big priority-1 job is loaded early, not last");
        Result r = run(TEAM + "8:10:1;1900\n", "3\nn\n");
        finishedCleanly(r);
        eq("P6[0-3] P8[3-13] P2[13-17] P4[17-23] P7[23-32] P1[32-44] P3[44-64] P5[64-79]", ganttString(r), "gantt");

        test("Long-term + Priority: a lower job never takes the memory a waiting higher job needs");
        // P2 (prio 2, 1500 MB) does not fit at t=0; P4 (prio 3) must not be loaded ahead of it,
        // otherwise P4 would grab the CPU at t=5 before P2.
        r = run("1:5:1;600\n2:5:2;1500\n3:5:9;100\n4:30:3;600\n", "3\nn\n");
        finishedCleanly(r);
        eq("P1[0-5] P2[5-10] P4[10-40] P3[40-45]", ganttString(r), "gantt");

        test("Long-term + SJF: shortest job is loaded first");
        r = run("1:20:1;1500\n2:2:1;1000\n", "1\nn\n");
        finishedCleanly(r);
        eq("P2[0-2] P1[2-22]", ganttString(r), "gantt");

        test("Long-term + RR: loading stays in arrival order");
        r = run("1:20:1;1500\n2:2:1;1000\n", "2\nn\n");
        finishedCleanly(r);
        check(r.out.contains("Not enough memory for P2"), "P2 waits behind P1");
        eq("P1[0-5] P1[5-10] P1[10-15] P1[15-20] P2[20-22]", ganttString(r), "gantt");
    }

    static void algorithmRules() throws Exception {
        String ties = "1:5:3;100\n2:5:1;100\n3:2:9;100\n";

        test("SJF: shortest first, equal bursts by arrival order");
        Result r = run(ties, "1\nn\n");
        finishedCleanly(r);
        eq("P3[0-2] P1[2-7] P2[7-12]", ganttString(r), "gantt");

        test("Priority: smaller number first, non-preemptive");
        r = run(ties, "3\nn\n");
        finishedCleanly(r);
        eq("P2[0-5] P1[5-10] P3[10-12]", ganttString(r), "gantt");

        test("RR: FIFO order, process shorter than quantum");
        r = run(ties, "2\nn\n");
        finishedCleanly(r);
        eq("P1[0-5] P2[5-10] P3[10-12]", ganttString(r), "gantt");
        eq("[5, 0, 5, 0, 5]", Arrays.toString(r.table.get(1)), "P1 row");
        eq("[5, 5, 10, 5, 10]", Arrays.toString(r.table.get(2)), "P2 row");
        eq("[2, 10, 12, 10, 12]", Arrays.toString(r.table.get(3)), "P3 row");

        test("RR: burst that is an exact multiple of the quantum");
        r = run("1:10:1;100\n", "2\nn\n");
        finishedCleanly(r);
        eq(2, r.gantt.size(), "two slices");
        eq("[1, 10, 5, 0, 5]", r.gantt.size() > 0 ? Arrays.toString(r.gantt.get(0)) : "none", "slice 1  10 -> 5");
        eq("[1, 5, 0, 5, 10]", r.gantt.size() > 1 ? Arrays.toString(r.gantt.get(1)) : "none", "slice 2  5 -> 0");

        test("RR: quantum boundary between two processes");
        r = run("1:7:1;100\n2:3:1;100\n", "2\nn\n");
        finishedCleanly(r);
        eq("P1[0-5] P2[5-8] P1[8-10]", ganttString(r), "gantt");
    }

    static void starvationRules() throws Exception {
        test("Starvation boundary: waiting exactly N*5 is NOT starvation");
        // 2 processes -> threshold 10. P2 waits exactly 10 ms.
        Result r = run("1:10:1;100\n2:1:5;100\n", "3\nn\n");
        finishedCleanly(r);
        check(r.out.contains("No processes suffered from starvation."), "no starvation reported");

        test("Starvation boundary: waiting N*5 + 1 IS starvation");
        r = run("1:11:1;100\n2:1:5;100\n", "3\nn\n");
        finishedCleanly(r);
        check(r.out.contains("[Starvation] P2 waited 11 ms (> 10 ms) at t=11"), "P2 detected at t=11");
        eq("[5, 5]", Arrays.toString(r.starved.get(2)), "detected but not aged yet (needs 4 ms)");

        test("Aging: priority drops by 1 every 4 ms after starvation");
        // threshold 10 -> starved at t=11, aged at 15,19,23 -> 5,4,3 ; P1 ends at 25
        r = run("1:25:1;100\n2:1:6;100\n", "3\nn\n");
        finishedCleanly(r);
        check(r.out.contains("[Aging] P2 priority boosted to 5 at t=15"), "aged at 15");
        check(r.out.contains("[Aging] P2 priority boosted to 3 at t=23"), "aged at 23");
        check(!r.out.contains("at t=24"), "no aging between steps");
        eq("[6, 3]", Arrays.toString(r.starved.get(2)), "final priority 3");

        test("Aging never goes below priority 1");
        r = run("1:200:1;100\n2:1:30;100\n", "3\nn\n");
        finishedCleanly(r);
        eq("[30, 1]", Arrays.toString(r.starved.get(2)), "final priority stops at 1");

        test("Starvation only reported for Priority, not SJF");
        r = run("1:200:1;100\n2:300:30;100\n", "1\nn\n");
        finishedCleanly(r);
        check(!r.out.contains("STARVATION REPORT") && !r.out.contains("[Starvation]"), "no starvation output in SJF");
    }

    static void memoryRules() throws Exception {
        test("Long-term: jobs that fill memory exactly (2048 MB) are both admitted");
        Result r = run("1:3:1;1024\n2:3:1;1024\n", "1\nn\n");
        finishedCleanly(r);
        check(r.out.contains("Admitted P2 into Ready Queue (memory used: 2048/2048 MB)"), "P2 admitted at 2048/2048");

        test("Long-term: job waits until memory is freed");
        r = run("1:4:1;1500\n2:2:1;1000\n", "2\nn\n");
        finishedCleanly(r);
        check(r.out.contains("[Time 0 ms] [Thread 2] Not enough memory for P2 (1000 MB needed, 548 MB free)"), "P2 blocked at t=0");
        check(r.out.contains("[Time 4 ms] [Thread 2] Admitted P2"), "P2 admitted at t=4");
        eq("[2, 4, 6, 4, 6]", Arrays.toString(r.table.get(2)), "P2 row");

        test("Memory returns to 0 and never exceeds 2048");
        r = run(TEAM, "2\ny\n");
        finishedCleanly(r);
        check(!r.memoryUsed.isEmpty() && r.memoryUsed.get(r.memoryUsed.size() - 1) == 0, "ends at 0/2048");
        check(r.memoryUsed.stream().allMatch(m -> m >= 0 && m <= 2048), "always within 0..2048");

        test("Medium-term: single job above 90% is not swapped out");
        r = run("1:5:1;2048\n", "3\ny\n");
        finishedCleanly(r);
        check(!r.out.contains("Swap out"), "no swap out");
        eq("P1[0-5]", ganttString(r), "gantt");

        test("Medium-term + Priority: high-priority big job is not swapped out (doctor's case)");
        r = run("1:8:5;500\n2:10:1;1300\n3:6:9;200\n", "3\ny\n");
        finishedCleanly(r);
        check(r.out.contains("Action: Swap out Process 3"), "lowest priority P3 is the victim");
        check(!r.out.contains("Action: Swap out Process 2"), "P2 (priority 1) never swapped out");
        eq("P2[0-10] P1[10-18] P3[18-24]", ganttString(r), "priority order kept");

        test("Medium-term + SJF: longest job is the victim, not the largest memory");
        // P1 longest (30 ms, 300 MB); P2 short but large (1500 MB)
        r = run("1:30:1;300\n2:2:1;1500\n3:5:1;100\n", "1\ny\n");
        finishedCleanly(r);
        check(r.out.contains("Action: Swap out Process 1"), "P1 (longest) swapped out");
        check(!r.out.contains("Action: Swap out Process 2"), "P2 (shortest, largest) kept");

        test("Medium-term + Priority: swap in picks highest priority, not FIFO");
        // B(9) swapped out at t=0, C(8) at t=1; at t=20 C must come back first.
        r = run("1:20:1;1880\n2:5:9;80\n3:5:8;80\n", "3\ny\n");
        finishedCleanly(r);
        eq("P1[0-20] P3[20-25] P2[25-30]", ganttString(r), "C runs before B");

        test("Medium-term + RR: finishes and keeps all processes");
        r = run(TEAM, "2\ny\n");
        finishedCleanly(r);
        eq(7, r.table.size(), "7 rows in table");
    }

    static void inputRules() throws Exception {
        test("Input: invalid lines are skipped, valid ones kept");
        String jobs = "1:5:1;100\n"
                + "2:5:1\n"            // wrong format
                + "3:x:1;100\n"        // not a number
                + "4:0:1;100\n"        // burst 0
                + "5:5:0;100\n"        // priority 0
                + "6:5:31;100\n"       // priority 31
                + "7:5:1;0\n"          // memory 0
                + "8:5:1;2049\n"       // memory > 2048
                + "9:2.5:1;100\n"      // decimal
                + "10:5:30;2048\n";    // boundaries -> valid
        Result r = run(jobs, "1\nn\n");
        finishedCleanly(r);
        eq("[1, 10]", r.table.keySet().toString(), "only P1 and P10 scheduled");

        test("Input: spaces, blank lines and Windows line endings");
        r = run(" 1 : 3 : 2 ; 100 \r\n\r\n\r\n2:1:1;100\r\n", "1\nn\n");
        finishedCleanly(r);
        eq("[1, 2]", r.table.keySet().toString(), "both parsed");

        test("Input: duplicate PID is rejected");
        r = run("1:5:1;100\n1:3:1;100\n2:4:1;100\n", "1\nn\n");
        finishedCleanly(r);
        check(r.out.contains("duplicate PID"), "duplicate reported");
        eq(2, r.table.size(), "only 2 processes scheduled");
        eq("[5, 4, 9, 4, 9]", Arrays.toString(r.table.get(1)), "first P1 kept (burst 5)");

        test("Input: PID must be positive");
        r = run("0:5:1;100\n-3:5:1;100\n2:4:1;100\n", "1\nn\n");
        finishedCleanly(r);
        eq("[2]", r.table.keySet().toString(), "only P2 scheduled");

        test("Input: missing file");
        r = runFile("no_such_file_12345.txt", "1\nn\n");
        finishedCleanly(r);
        check(r.out.contains("File not found") && r.out.contains("No valid jobs to schedule"), "clean message");

        test("Input: empty file");
        r = run("", "1\nn\n");
        finishedCleanly(r);
        check(r.out.contains("No valid jobs to schedule"), "clean message");

        test("Menu: invalid choice defaults to SJF");
        r = run("1:5:3;100\n2:5:1;100\n3:2:9;100\n", "9\nn\n");
        finishedCleanly(r);
        check(r.out.contains("Defaulting to SJF"), "message");
        eq("P3[0-2] P1[2-7] P2[7-12]", ganttString(r), "SJF order");

        test("Menu: no input at all (stdin closed)");
        r = run("1:5:3;100\n", "");
        finishedCleanly(r);
        eq("P1[0-5]", ganttString(r), "still runs with defaults");

        test("Locale: numbers stay readable on an Arabic system");
        r = run(TEAM, "1\nn\n", "-Duser.language=ar", "-Duser.country=SA");
        finishedCleanly(r);
        check(r.out.contains("Average Waiting Time:     18.29 ms"), "English digits in averages");
    }

    // ---------- random invariant tests ----------

    static void randomInvariants(int files) throws Exception {
        Random rnd = new Random(227);
        for (int f = 0; f < files; f++) {
            int n = 1 + rnd.nextInt(10);
            StringBuilder sb = new StringBuilder();
            int[] burst = new int[n + 1];
            for (int p = 1; p <= n; p++) {
                burst[p] = 1 + rnd.nextInt(30);
                sb.append(p).append(':').append(burst[p]).append(':').append(1 + rnd.nextInt(30))
                  .append(';').append(1 + rnd.nextInt(2048)).append('\n');
            }
            for (int choice = 1; choice <= 3; choice++) {
                for (String med : new String[]{"n", "y"}) {
                    test("random #" + f + " choice=" + choice + " medium=" + med + "  [" + sb.toString().replace("\n", " ").trim() + "]");
                    Result r = run(sb.toString(), choice + "\n" + med + "\n");
                    finishedCleanly(r);
                    eq(n, r.table.size(), "every process finished");

                    // Gantt: starts at 0, no gaps, no overlaps, slices add up to each burst
                    int t = 0, total = 0;
                    Map<Integer, Integer> ran = new HashMap<>();
                    Map<Integer, Integer> slices = new HashMap<>();
                    boolean contiguous = true, sliceOk = true;
                    for (int[] g : r.gantt) {
                        if (g[3] != t) contiguous = false;
                        if (g[4] - g[3] != g[1] - g[2]) sliceOk = false;
                        if (choice == 2 && g[4] - g[3] > 5) sliceOk = false;
                        t = g[4];
                        ran.merge(g[0], g[4] - g[3], Integer::sum);
                        slices.merge(g[0], 1, Integer::sum);
                    }
                    for (int p = 1; p <= n; p++) total += burst[p];
                    check(contiguous && t == total, "gantt contiguous from 0 to " + total);
                    check(sliceOk, "every slice consistent (and <= 5 ms in RR)");
                    boolean burstsOk = true, rowsOk = true, nonPreemptive = true;
                    for (int p = 1; p <= n; p++) {
                        if (!Integer.valueOf(burst[p]).equals(ran.get(p))) burstsOk = false;
                        int[] row = r.table.get(p);
                        if (row == null || row[0] != burst[p] || row[4] != row[2] || row[3] != row[4] - row[0]
                                || row[3] < 0 || row[1] > row[2] - row[0]) rowsOk = false;
                        if (choice != 2 && !Integer.valueOf(1).equals(slices.get(p))) nonPreemptive = false;
                    }
                    check(burstsOk, "each process got exactly its burst on the CPU");
                    check(rowsOk, "table: TAT = termination, WT = TAT - burst >= 0");
                    check(nonPreemptive, "SJF/Priority never split a process");
                    check(r.memoryUsed.stream().allMatch(m -> m >= 0 && m <= 2048), "memory within 0..2048");
                    check(!r.memoryUsed.isEmpty() && r.memoryUsed.get(r.memoryUsed.size() - 1) == 0, "memory ends at 0");
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        classDir = args[0];
        int randomFiles = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        reportScenarios();
        admissionOrder();
        algorithmRules();
        starvationRules();
        memoryRules();
        inputRules();
        if (randomFiles > 0) randomInvariants(randomFiles);

        System.out.println("\n==============================");
        System.out.println("PASSED checks: " + passed);
        System.out.println("FAILED checks: " + failed);
        for (String s : failures) System.out.println("  x " + s);
        System.exit(failed == 0 ? 0 : 1);
    }
}
