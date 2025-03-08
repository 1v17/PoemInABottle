import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.json.JSONObject;

public class LoadTestClient {
  public static final String FILE_PATH = "sonnets.txt";
  private static final int EXECUTOR_TIMEOUT_MIN = 30; // 30 minutes
  private static final int INIT_THREAD_COUNT = 10;
  private static final int INIT_REQUESTS_PER_THREAD = 100;
  private static final int REQUESTS_PER_THREAD = 1000;
  private static final int MAX_FAILURES = 10;
  private static final int LATENCY_THRESHOLD_MS = 2000;
  private static final int COOLDOWN_PERIOD_MS = 5000;
  private static final List<String> sonnetLines = Collections.synchronizedList(new ArrayList<>());
  private static final Set<String> THEMES = Collections.synchronizedSet(
      new HashSet<>(Arrays.asList("", "Love", "Death", "Nature", "Beauty")));
  private static final List<String[]> responseTimes =
      Collections.synchronizedList(new ArrayList<>());
  private static final AtomicInteger failedRequests = new AtomicInteger(0);
  private static CircuitState circuitState = CircuitState.CLOSED;
  private static long lastFailureTime = 0;
  private static int failureCount = 0;

  public static void main(String[] args) throws Exception {
    if (args.length < 4) {
      System.err.println(
          "Usage: java -jar build/libs/client-1.0-all.jar" +
              " <threadGroupSize> <numThreadGroups> <delay> <IPAddr>");
      return;
    }

    int threadGroupSize = Integer.parseInt(args[0]);
    int numThreadGroups = Integer.parseInt(args[1]);
    int delay = Integer.parseInt(args[2]) * 1000;
    String ipAddr = args[3];

    readSonnets();

    ExecutorService executor = Executors.newFixedThreadPool(INIT_THREAD_COUNT);
    try {
      // Initialization phase
      for (int i = 0; i < 10; i++) {
        executor.execute(() -> sendRequests(ipAddr, INIT_REQUESTS_PER_THREAD));
      }
      executor.shutdown();
      boolean initTerminated = executor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
      if (!initTerminated) {
        System.out.println("Warning: Initialization phase not finished before timeout!");
        executor.shutdownNow(); // Force shutdown
      }

      System.out.println("Initialization phase complete. Starting load test...");

      long startTime = System.currentTimeMillis();

      ExecutorService mainExecutor = Executors.newCachedThreadPool();
      try {
        for (int i = 0; i < numThreadGroups; i++) {
          for (int j = 0; j < threadGroupSize; j++) {
            mainExecutor.execute(() -> sendRequests(ipAddr, REQUESTS_PER_THREAD));
          }
          Thread.sleep(delay);
        }
        mainExecutor.shutdown();
        boolean terminated = mainExecutor.awaitTermination(EXECUTOR_TIMEOUT_MIN, TimeUnit.MINUTES);
        if (!terminated) {
          System.out.println("Warning: Not all tasks finished before timeout!");
          mainExecutor.shutdownNow(); // Force shutdown
        }
      } finally {
        if (!mainExecutor.isTerminated()) {
          mainExecutor.shutdownNow();
        }
      }

      long endTime = System.currentTimeMillis();
      generateReport(startTime, endTime, threadGroupSize, numThreadGroups);
    } catch (InterruptedException e) {
      System.err.println("Error: Load test interrupted. " + e.getMessage());
    }

  }

  private static void readSonnets() throws IOException {
    try (BufferedReader br = new BufferedReader(new InputStreamReader(
        Objects.requireNonNull(LoadTestClient.class.getClassLoader()
            .getResourceAsStream(FILE_PATH)), StandardCharsets.UTF_8))) {
      String line;
      while ((line = br.readLine()) != null) {
        sonnetLines.add(line);
      }
    }
  }

  private static void sendRequests(String ipAddr, int numRequests) {
    for (int i = 0; i < numRequests; i++) {
      if (circuitState == CircuitState.OPEN
          && System.currentTimeMillis() - lastFailureTime <= COOLDOWN_PERIOD_MS) {
        // Skip requests while circuit is open and cooldown has not elapsed
        return;
      }

      if (circuitState == CircuitState.OPEN) {
        circuitState = CircuitState.HALF_OPEN;
      }

      sendPostRequest(ipAddr);
      sendGetRequest(ipAddr);
    }
  }

  private static void sendPostRequest(String ipAddr) {
    String line = sonnetLines.get(ThreadLocalRandom.current().nextInt(sonnetLines.size()));
    String theme = new ArrayList<>(THEMES).get(ThreadLocalRandom.current().nextInt(THEMES.size()));
    JSONObject json = new JSONObject();
    json.put("author", Thread.currentThread().getId());
    json.put("content", line);
    json.put("theme", theme);

    String url = ipAddr + "/sentence";
    sendHttpRequest(url, "POST", json.toString());
  }

  private static void sendGetRequest(String ipAddr) {
    String theme = new ArrayList<>(THEMES).get(ThreadLocalRandom.current().nextInt(THEMES.size()));
    String url = theme.isEmpty() ? ipAddr + "/poem" : ipAddr + "/poem/" + theme;
    sendHttpRequest(url, "GET", null);
  }

  private static void sendHttpRequest(String url, String method, String payload) {
    for (int i = 0; i < 5; i++) { // Retry up to 5 times
      try {
        long start = System.currentTimeMillis();
        URI uri = new URI(url);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type", "application/json");
        if (method.equals("POST")) {
          conn.setDoOutput(true);
          try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.getBytes());
          }
        }
        int responseCode = conn.getResponseCode();
        long end = System.currentTimeMillis();
        long latency = end - start;

        responseTimes.add(new String[] {String.valueOf(start), method, String.valueOf(latency),
            String.valueOf(responseCode)});

        if (responseCode == 200 || responseCode == 201) {
          if (latency > LATENCY_THRESHOLD_MS) {
            failureCount++;
            lastFailureTime = System.currentTimeMillis();
            if (failureCount >= MAX_FAILURES) {
              circuitState = CircuitState.OPEN;
              System.out.println("Circuit breaker tripped! Pausing requests.");
            }
          } else {
            failureCount = 0; // Reset failures on success
            if (circuitState == CircuitState.HALF_OPEN) {
              circuitState = CircuitState.CLOSED;
              System.out.println("Circuit breaker recovered!");
            }
          }
          return;
        }
      } catch (Exception e) {
        failedRequests.incrementAndGet();
      }
    }
  }

  private static void generateReport(long startTime, long endTime, int threadGroupSize,
                                     int numThreadGroups) {
    long wallTime = (endTime - startTime) / 1000;
    long totalRequests = responseTimes.size() + failedRequests.get();
    double throughput = totalRequests / (double) wallTime;

    System.out.println("Wall Time: " + wallTime + " seconds");
    System.out.println("Throughput: " + throughput + " requests/sec");

    writeCsv(threadGroupSize, numThreadGroups);
    System.out.println("Response times written to CSV file.");
    calculateStats();
  }

  private static void writeCsv(int threadGroupSize, int numThreadGroups) {
    String filename = String.format("response time size-%d %d groups.csv",
        threadGroupSize, numThreadGroups);
    try (PrintWriter writer = new PrintWriter(filename)) {
      writer.println("start_time,request_type,response_time,response_code");
      responseTimes.forEach(line -> writer.println(String.join(",", line)));
    } catch (FileNotFoundException e) {
      System.err.println("Error writing CSV file: " + e.getMessage());
    }
  }

  private static void calculateStats() {
    List<Long> latencies = responseTimes.stream().map(line -> Long.parseLong(line[2]))
        .sorted().collect(Collectors.toList());
    if (latencies.isEmpty()) {
      return;
    }

    long min = latencies.get(0);
    long max = latencies.get(latencies.size() - 1);
    double mean = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
    long median = latencies.get(latencies.size() / 2);
    long p99 = latencies.get((int) (latencies.size() * 0.99));

    System.out.println("Min: " + min + " ms");
    System.out.println("Max: " + max + " ms");
    System.out.println("Mean: " + mean + " ms");
    System.out.println("Median: " + median + " ms");
    System.out.println("P99: " + p99 + " ms");
  }

  private enum CircuitState { CLOSED, OPEN, HALF_OPEN }
}