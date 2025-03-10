import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;

public class LoadTestClient {
  public static final String FILE_PATH = "sonnets.txt";
  public static final String RESULT_PATH = "../results";
  public static final int MAX_PER_ROUTE = 20;
  public static final int MAX_TOTAL_CONN = 500;
  private static final int INIT_EXECUTOR_TIMEOUT_MIN = 2;
  private static final int EXECUTOR_TIMEOUT_MIN = 30; // 30 minutes - Change time if necessary
  private static final int INIT_THREAD_COUNT = 10;
  private static final int INIT_REQUESTS_PER_THREAD = 100;
  private static final int REQUESTS_PER_THREAD = 1000;
  private static final int MAX_FAILURES = 100;
  private static final int LATENCY_THRESHOLD_MS = 5000;
  private static final int COOLDOWN_PERIOD_MS = 5000;
  private static final CopyOnWriteArrayList<String> sonnetLines = new CopyOnWriteArrayList<>();
  private static final Set<String> THEMES = Collections.synchronizedSet(
      new HashSet<>(Arrays.asList("", "Love", "Death", "Nature", "Beauty")));
  private static final ConcurrentLinkedQueue<String[]> responseTimes =
      new ConcurrentLinkedQueue<>();
  private static final AtomicInteger failedRequests = new AtomicInteger(0);
  private static final Logger logger = Logger.getLogger(LoadTestClient.class.getName());
  private static final ConcurrentHashMap<Long, AtomicInteger> throughput =
      new ConcurrentHashMap<>();
  private static final PoolingHttpClientConnectionManager connectionManager =
      new PoolingHttpClientConnectionManager();
  private static final CloseableHttpClient client = HttpClients.custom()
      .setConnectionManager(connectionManager)
      .build();
  private static final AtomicLong startTime = new AtomicLong();
  private static CircuitState circuitState = CircuitState.CLOSED;
  private static long lastFailureTime = 0;
  private static int failureCount = 0;
  private static boolean useCircuitBreaker = true;

  static {
    connectionManager.setMaxTotal(MAX_TOTAL_CONN);
    connectionManager.setDefaultMaxPerRoute(MAX_PER_ROUTE);
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 4) {
      System.err.println(
          "Usage: java -jar build/libs/client-1.0-all.jar" +
              " <threadGroupSize> <numThreadGroups> <delay> <IPAddr> [useCircuitBreaker]");
      return;
    }

    int threadGroupSize = Integer.parseInt(args[0]);
    int numThreadGroups = Integer.parseInt(args[1]);
    int delay = Integer.parseInt(args[2]) * 1000;
    String ipAddr = args[3];
    if (args.length > 4) {
      useCircuitBreaker = Boolean.parseBoolean(args[4]);
    }

    System.out.printf("Using circuit breaker: %b%n", useCircuitBreaker);

    readSonnets();

    ThreadPoolExecutor executor =
        (ThreadPoolExecutor) Executors.newFixedThreadPool(INIT_THREAD_COUNT);
    try {
      // Initialization phase
      List<Future<Integer>> initFutures = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        initFutures.add(executor.submit(() -> sendRequests(ipAddr, INIT_REQUESTS_PER_THREAD)));
      }
      AtomicInteger totalSuccessfulRequests = new AtomicInteger(0);
      for (Future<Integer> future : initFutures) {
        totalSuccessfulRequests.addAndGet(future.get());
      }
      executor.shutdown();
      boolean initTerminated = executor.awaitTermination(
          INIT_EXECUTOR_TIMEOUT_MIN, TimeUnit.MINUTES);
      if (!initTerminated) {
        System.out.println("Warning: Initialization phase not finished before timeout!");
        executor.shutdownNow(); // Force shutdown
      }

      if (totalSuccessfulRequests.get() < 10 * INIT_REQUESTS_PER_THREAD) {
        System.err.println(
            "Initialization phase did not receive enough successful responses. Aborting load test.");
        return;
      }

      System.out.println("Initialization phase complete. Starting load test...");

      // Reset counters for the main execution phase
      responseTimes.clear();
      failedRequests.set(0);

      startTime.set(System.currentTimeMillis()); // Initialize start time

      // Main execution phase
      ThreadPoolExecutor mainExecutor = (ThreadPoolExecutor) Executors.newCachedThreadPool();
      List<Future<Integer>> mainFutures = new ArrayList<>();
      ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
      for (int i = 0; i < numThreadGroups; i++) {
        final int groupIndex = i;
        scheduler.schedule(() -> {
          System.out.printf("Starting thread group %d at %d ms%n", groupIndex,
              System.currentTimeMillis() - startTime.get());
          for (int j = 0; j < threadGroupSize; j++) {
            mainFutures.add(mainExecutor.submit(() -> sendRequests(ipAddr, REQUESTS_PER_THREAD)));
          }
        }, (long) i * delay, TimeUnit.MILLISECONDS);
      }
      scheduler.shutdown();
      boolean schedulerTerminated = scheduler.awaitTermination(
          EXECUTOR_TIMEOUT_MIN, TimeUnit.MINUTES);
      if (!schedulerTerminated) {
        System.out.println("Warning: Thread group scheduler did not finish before timeout!");
        scheduler.shutdownNow(); // Force shutdown
      }

      // Wait for all tasks to complete
      for (Future<Integer> future : mainFutures) {
        try {
          future.get();
        } catch (Exception e) {
          System.err.println("Error: Task execution interrupted. " + e.getMessage());
        }
      }

      // close http client and shutdown executor
      try {
        client.close();
      } catch (IOException e) {
        System.err.println("Error closing HTTP client: " + e.getMessage());
      }

      boolean terminated = mainExecutor.awaitTermination(EXECUTOR_TIMEOUT_MIN, TimeUnit.MINUTES);
      if (!terminated) {
        System.out.println("Warning: Not all tasks finished before timeout!");
        mainExecutor.shutdownNow(); // Force shutdown
      }

      System.out.println("Load test complete. Generating report...");
      long endTime = System.currentTimeMillis();
      generateReport(startTime.get(), endTime, threadGroupSize, numThreadGroups);
    } catch (InterruptedException e) {
      System.err.println("Error: Load test interrupted. " + e.getMessage());
    }
  }

  private static void logThreadPoolStats(ThreadPoolExecutor executor) {
    logger.info("\nThreadPool Stats: " +
        "Pool Size: " + executor.getPoolSize() +
        ", Active Threads: " + executor.getActiveCount() +
        ", Completed Tasks: " + executor.getCompletedTaskCount() +
        ", Task Count: " + executor.getTaskCount());
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

  private static int sendRequests(String ipAddr, int numRequests) {
    int successfulRequests = 0;
    for (int i = 0; i < numRequests; i++) {
      if (useCircuitBreaker && circuitState == CircuitState.OPEN
          && System.currentTimeMillis() - lastFailureTime <= COOLDOWN_PERIOD_MS) {
        continue;
      }

      if (useCircuitBreaker && circuitState == CircuitState.OPEN) {
        circuitState = CircuitState.HALF_OPEN;
      }

      if (sendPostRequest(ipAddr)) {
        successfulRequests++;
      }
      if (sendGetRequest(ipAddr)) {
        successfulRequests++;
      }
    }
    return successfulRequests;
  }

  private static boolean sendPostRequest(String ipAddr) {
    String line = sonnetLines.get(ThreadLocalRandom.current().nextInt(sonnetLines.size()));
    String theme = new ArrayList<>(THEMES).get(ThreadLocalRandom.current().nextInt(THEMES.size()));
    JSONObject json = new JSONObject();
    json.put("author", Thread.currentThread().getId());
    json.put("content", line);
    json.put("theme", theme);

    String url = ipAddr + "/sentence";
    return sendHttpRequest(url, "POST", json.toString());
  }

  private static boolean sendGetRequest(String ipAddr) {
    String theme = new ArrayList<>(THEMES).get(ThreadLocalRandom.current().nextInt(THEMES.size()));
    String url = theme.isEmpty() ? ipAddr + "/poem" : ipAddr + "/poem/" + theme;
    return sendHttpRequest(url, "GET", null);
  }

  private static boolean sendHttpRequest(String url, String method, String payload) {
    long start = System.currentTimeMillis();
    CloseableHttpResponse response = null;
    try {
      if (method.equals("POST")) {
        HttpPost post = new HttpPost(url);
        post.setEntity(new StringEntity(payload));
        post.setHeader("Content-type", "application/json");
        response = client.execute(post);
      } else {
        HttpGet get = new HttpGet(url);
        response = client.execute(get);
      }

      long end = System.currentTimeMillis();
      long latency = end - start;
      int responseCode = response.getStatusLine().getStatusCode();

      if (responseCode == 200 || responseCode == 201) {
        responseTimes.add(new String[] {String.valueOf(start), method, String.valueOf(latency),
            String.valueOf(responseCode)});
        handleCircuitBreakerOnSuccess(latency);
        long completedSecond = (end - startTime.get()) / 1000;
        throughput.computeIfAbsent(completedSecond,
            k -> new AtomicInteger(0)).incrementAndGet();
        EntityUtils.consume(response.getEntity());
        return true;
      } else {
        failedRequests.incrementAndGet();
        EntityUtils.consume(response.getEntity());
        return false;
      }
    } catch (IOException e) {
      failedRequests.incrementAndGet();
      return false;
    } finally {
      if (response != null) {
        try {
          response.close();
        } catch (IOException ignored) {
        }
      }
    }
  }

  private static void handleCircuitBreakerOnSuccess(long latency) {
    if (useCircuitBreaker && latency > LATENCY_THRESHOLD_MS) {
      failureCount++;
      lastFailureTime = System.currentTimeMillis();
      if (failureCount >= MAX_FAILURES) {
        circuitState = CircuitState.OPEN;
        System.out.println("Circuit breaker tripped! Pausing requests.");
      }
    } else {
      failureCount = 0; // Reset failures on success
      if (useCircuitBreaker && circuitState == CircuitState.HALF_OPEN) {
        circuitState = CircuitState.CLOSED;
        System.out.println("Circuit breaker recovered!");
      }
    }
  }

  private static void generateReport(long startTime, long endTime, int threadGroupSize,
                                     int numThreadGroups) {
    long wallTime = (endTime - startTime) / 1000;
    long successfulRequests = responseTimes.size();
    long failedRequestsCount = failedRequests.get();
    double throughput = successfulRequests / (double) wallTime;

    System.out.println("Wall Time: " + wallTime + " seconds");
    System.out.printf("Throughput: %.2f requests/sec%n", throughput);
    System.out.println("Total Successful Requests: " + successfulRequests);
    System.out.println("Total Failed Requests: " + failedRequestsCount);

    writeResponseTimesCsv(threadGroupSize, numThreadGroups);
    writeThroughputCsv(threadGroupSize, numThreadGroups);
    System.out.println("Response times and throughput data written to CSV files.");

    calculateStats("Overall", new ArrayList<>(responseTimes));
    calculateStats("POST", filterResponseTimes("POST"));
    calculateStats("GET", filterResponseTimes("GET"));
  }

  private static void writeResponseTimesCsv(int threadGroupSize, int numThreadGroups) {
    String folderName = RESULT_PATH; // Go up one level to 'client' directory
    File folder = new File(folderName);

    if (!folder.exists() || !folder.isDirectory()) {
      folderName = "."; // Default to current directory
    }

    String filename = String.format("%s/response_time_size-%d_%d_groups.csv",
        folderName, threadGroupSize, numThreadGroups);

    try (PrintWriter writer = new PrintWriter(filename)) {
      writer.println("start_time,request_type,response_time,response_code");
      responseTimes.forEach(line -> writer.println(String.join(",", line)));
    } catch (FileNotFoundException e) {
      System.err.println("Error writing response times CSV file: " + e.getMessage());
    }
  }

  private static void writeThroughputCsv(int threadGroupSize, int numThreadGroups) {
    String folderName = RESULT_PATH; // Go up one level to 'client' directory
    File folder = new File(folderName);

    if (!folder.exists() || !folder.isDirectory()) {
      folderName = "."; // Default to current directory
    }

    String filename = String.format("%s/throughput_size-%d_%d_groups.csv",
        folderName, threadGroupSize, numThreadGroups);

    try (PrintWriter writer = new PrintWriter(filename)) {
      writer.println("second,requests_completed");
      throughput.forEach((second, count) -> writer.println(second + "," + count.get()));
    } catch (FileNotFoundException e) {
      System.err.println("Error writing throughput CSV file: " + e.getMessage());
    }
  }

  private static List<String[]> filterResponseTimes(String requestType) {
    return responseTimes.stream()
        .filter(line -> line[1].equals(requestType))
        .collect(Collectors.toList());
  }

  private static void calculateStats(String requestType, List<String[]> filteredResponseTimes) {
    List<Long> latencies = filteredResponseTimes.stream()
        .map(line -> Long.parseLong(line[2]))
        .sorted()
        .collect(Collectors.toList());

    if (latencies.isEmpty()) {
      System.out.println("No " + requestType + " requests were made.");
      return;
    }

    long min = latencies.get(0);
    long max = latencies.get(latencies.size() - 1);
    double mean = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
    long median = latencies.get(latencies.size() / 2);
    long p99 = latencies.get((int) (latencies.size() * 0.99));

    System.out.println("\n" + requestType + " Request Statistics:");
    System.out.printf("Min: %d ms%n", min);
    System.out.printf("Max: %d ms%n", max);
    System.out.printf("Mean: %.2f ms%n", mean);
    System.out.printf("Median: %d ms%n", median);
    System.out.printf("P99: %d ms%n", p99);
  }

  private enum CircuitState { CLOSED, OPEN, HALF_OPEN }
}