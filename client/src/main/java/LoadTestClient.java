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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
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
  private static int executorTimeoutMin = 30; // 30 minutes - Change time if necessary

  static {
    connectionManager.setMaxTotal(MAX_TOTAL_CONN);
    connectionManager.setDefaultMaxPerRoute(MAX_PER_ROUTE);
  }

  public static void main(String[] args) throws Exception {
    CommandLine cmd = parseArguments(args);

    int threadGroupSize = Integer.parseInt(cmd.getArgs()[0]);
    int numThreadGroups = Integer.parseInt(cmd.getArgs()[1]);
    int delay = Integer.parseInt(cmd.getArgs()[2]) * 1000;
    String ipAddr = cmd.getArgs()[3];
    if (cmd.hasOption("c")) {
      useCircuitBreaker = Boolean.parseBoolean(cmd.getOptionValue("c"));
    }
    if (cmd.hasOption("e")) {
      executorTimeoutMin = Integer.parseInt(cmd.getOptionValue("e"));
    }

    logger.info(String.format("Using circuit breaker: %b%n", useCircuitBreaker));
    logger.info(String.format("Executor timeout: %d minutes%n", executorTimeoutMin));

    readSonnets();

    ThreadPoolExecutor executor =
        (ThreadPoolExecutor) Executors.newFixedThreadPool(INIT_THREAD_COUNT);
    initializePhase(executor, ipAddr);

    logger.info("Initialization phase complete. Starting load test...");

    // Reset counters for the main execution phase
    responseTimes.clear();
    failedRequests.set(0);

    startTime.set(System.currentTimeMillis()); // Initialize start time

    // Main execution phase
    ThreadPoolExecutor mainExecutor = (ThreadPoolExecutor) Executors.newCachedThreadPool();
    List<Future<Integer>> mainFutures = new ArrayList<>();
    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    scheduleThreadGroups(scheduler, mainExecutor, mainFutures, threadGroupSize, numThreadGroups,
        delay, ipAddr);

    // Wait for all tasks to complete
    waitForTasksToComplete(mainFutures);
    logger.info("All tasks completed.");
    
    // Close HTTP client and shutdown executor
    closeHttpClient();
    shutdownExecutor(mainExecutor);

    logger.info("Load test complete. Generating report...");
    long endTime = System.currentTimeMillis();
    generateReport(startTime.get(), endTime, threadGroupSize, numThreadGroups);
  }

  private static CommandLine parseArguments(String[] args) throws ParseException {
    Options options = new Options();

    options.addOption("c", "useCircuitBreaker", true,
        "Whether to use the circuit breaker feature (default is true)");
    options.addOption("e", "executorTimeoutMin", true,
        "Executor timeout in minutes (default is 30)");

    CommandLineParser parser = new DefaultParser();
    CommandLine cmd = parser.parse(options, args);

    if (cmd.getArgs().length < 4) {
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp("LoadTestClient <threadGroupSize> <numThreadGroups> <delay> <IPAddr>",
          options);
      System.exit(1);
    }

    return cmd;
  }

  private static void initializePhase(ThreadPoolExecutor executor, String ipAddr)
      throws InterruptedException, ExecutionException {
    List<Future<Integer>> initFutures = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      initFutures.add(executor.submit(() -> sendRequests(ipAddr, INIT_REQUESTS_PER_THREAD)));
    }
    AtomicInteger totalSuccessfulRequests = new AtomicInteger(0);
    for (Future<Integer> future : initFutures) {
      totalSuccessfulRequests.addAndGet(future.get());
    }
    shutdownExecutor(executor);

    if (totalSuccessfulRequests.get() < 10 * INIT_REQUESTS_PER_THREAD) {
      logger.warning(
          "Initialization phase did not receive enough successful responses. Aborting load test.");
      System.exit(1);
    }
  }

  private static void scheduleThreadGroups(ScheduledExecutorService scheduler,
                                           ThreadPoolExecutor mainExecutor,
                                           List<Future<Integer>> mainFutures, int threadGroupSize,
                                           int numThreadGroups, int delay, String ipAddr)
      throws InterruptedException {
    for (int i = 0; i < numThreadGroups; i++) {
      final int groupIndex = i;
      scheduler.schedule(() -> {
        logger.log(Level.INFO, "Starting thread group {0} at {1} ms", new Object[] {groupIndex,
            System.currentTimeMillis() - startTime.get()});
        for (int j = 0; j < threadGroupSize; j++) {
          mainFutures.add(mainExecutor.submit(() -> sendRequests(ipAddr, REQUESTS_PER_THREAD)));
        }
      }, (long) i * delay, TimeUnit.MILLISECONDS);
    }
    scheduler.shutdown();
    boolean schedulerTerminated = scheduler.awaitTermination(executorTimeoutMin, TimeUnit.MINUTES);
    if (!schedulerTerminated) {
      logger.warning("Warning: Thread group scheduler did not finish before timeout!");
      scheduler.shutdownNow(); // Force shutdown
    }
  }

  private static void waitForTasksToComplete(List<Future<Integer>> mainFutures) {
    for (Future<Integer> future : mainFutures) {
      try {
        future.get();
      } catch (Exception e) {
        logger.warning(String.format("Error: Task execution interrupted. ", e.getMessage()));
      }
    }
  }

  private static void closeHttpClient() {
    try {
      client.close();
    } catch (IOException e) {
      logger.warning(String.format("Error closing HTTP client: ", e.getMessage()));
    }
  }

  private static void shutdownExecutor(ThreadPoolExecutor mainExecutor)
      throws InterruptedException {
    mainExecutor.shutdown();
    boolean terminated = mainExecutor.awaitTermination(executorTimeoutMin, TimeUnit.MINUTES);
    if (!terminated) {
      logger.info("Warning: Not all tasks finished before timeout!");
      mainExecutor.shutdownNow(); // Force shutdown
    }
  }

  // private static void logThreadPoolStats(ThreadPoolExecutor executor) {
  //   logger.info("\nThreadPool Stats: " +
  //       "Pool Size: " + executor.getPoolSize() +
  //       ", Active Threads: " + executor.getActiveCount() +
  //       ", Completed Tasks: " + executor.getCompletedTaskCount() +
  //       ", Task Count: " + executor.getTaskCount());
  // }

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

      if (responseCode >= 200 && responseCode < 300) {
        responseTimes.add(new String[] {String.valueOf(start), method, String.valueOf(latency),
            String.valueOf(responseCode)});
        handleCircuitBreakerOnSuccess(latency);
        EntityUtils.consume(response.getEntity());
        return true;
      } else {
        failedRequests.incrementAndGet();
        EntityUtils.consume(response.getEntity());
        return false;
      }
    } catch (Exception e) {
      System.err.println("Error sending request: " + e.getMessage());
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
        logger.info("Circuit breaker tripped! Pausing requests.");
      }
    } else {
      failureCount = 0; // Reset failures on success
      if (useCircuitBreaker && circuitState == CircuitState.HALF_OPEN) {
        circuitState = CircuitState.CLOSED;
        logger.info("Circuit breaker recovered!");
      }
    }
  }

  private static void generateReport(long startTime, long endTime, int threadGroupSize,
                                     int numThreadGroups) {
    long wallTime = (endTime - startTime) / 1000;
    long successfulRequests = responseTimes.size();
    long failedRequestsCount = failedRequests.get();
    double throughput = successfulRequests / (double) wallTime;

    logger.log(Level.INFO, "Wall Time: {0} seconds", wallTime);
    logger.info(String.format("Throughput: %.2f requests/sec%n", throughput));
    logger.log(Level.INFO, "Total Successful Requests: {0}", successfulRequests);
    logger.log(Level.INFO, "Total Failed Requests: {0}", failedRequestsCount);

    writeResponseTimesCsv(threadGroupSize, numThreadGroups);
    logger.info("Response times and throughput data written to CSV files.");

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

  private static List<String[]> filterResponseTimes(String requestType) {
    return responseTimes.stream()
        .filter(line -> line[1].equals(requestType))
        .collect(Collectors.toList());
  }

  private static void calculateStats(String requestType, List<String[]> filteredResponseTimes) {
    List<Long> latencies = filteredResponseTimes.stream()
        .map(line -> Long.valueOf(line[2]))
        .sorted()
        .collect(Collectors.toList());

    if (latencies.isEmpty()) {
      logger.log(Level.INFO, "No {0} requests were made.", requestType);
      return;
    }

    long min = latencies.get(0);
    long max = latencies.get(latencies.size() - 1);
    double mean = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
    long median = latencies.get(latencies.size() / 2);
    long p99 = latencies.get((int) (latencies.size() * 0.99));

    logger.log(Level.INFO, "\n{0} Request Statistics:", requestType);
    logger.log(Level.INFO, "Min: {0} ms", min);
    logger.log(Level.INFO, "Max: {0} ms", max);
    logger.log(Level.INFO, "Mean: {0} ms", mean);
    logger.log(Level.INFO, "Median: {0} ms", median);
    logger.log(Level.INFO, "P99: {0} ms", p99);
  }

  private enum CircuitState { CLOSED, OPEN, HALF_OPEN }
}