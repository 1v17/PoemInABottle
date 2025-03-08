import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class LoadTestClient {
  private static final int INIT_THREAD_COUNT = 10;
  private static final int INIT_REQUESTS_PER_THREAD = 100;
  private static final int REQUESTS_PER_THREAD = 1000;
  private static final int MAX_FAILURES = 50; // Max failures before tripping circuit breaker
  private static final int LATENCY_THRESHOLD_MS = 2000; // Max allowed response time in milliseconds
  private static final int COOLDOWN_PERIOD_MS = 5000; // Time before retrying requests in milliseconds
  private static final Set<String> THEMES_SET = Set.of("", "Love", "Death", "Nature", "Beauty");
  private static final Set<String> THEMES = ConcurrentHashMap.newKeySet();
  static {
    THEMES.addAll(THEMES_SET);
  }
  private static final String FILE_NAME = "sonnets.txt";
  private static final int EXECUTOR_TIMEOUT = 30;

  private enum CircuitState { CLOSED, OPEN, HALF_OPEN }
  private static CircuitState circuitState = CircuitState.CLOSED;
  private static long lastFailureTime = 0;
  private static int failureCount = 0;

  private static final Random RANDOM = new Random();
  private static final List<String> sonnetLines = new CopyOnWriteArrayList<>();

  public static void main(String[] args) throws IOException, InterruptedException {
    if (args.length < 4) {
      System.err.println(
          "Usage: java -jar build/libs/client-1.0-all.jar" +
              " <threadGroupSize> <numThreadGroups> <delay> <IPAddr>");
      return;
    }

    int threadGroupSize = Integer.parseInt(args[0]);
    int numThreadGroups = Integer.parseInt(args[1]);
    int delay = Integer.parseInt(args[2]) * 1000;
    String serverUrl = args[3];

    loadSonnetData();
    ExecutorService executor = Executors.newCachedThreadPool();

    runInitialLoadTest(executor, serverUrl);
    long startTime = System.currentTimeMillis();

    for (int i = 0; i < numThreadGroups; i++) {
      Thread.sleep(delay);
      for (int j = 0; j < threadGroupSize; j++) {
        executor.execute(() -> sendLoad(serverUrl, REQUESTS_PER_THREAD));
      }
    }

    executor.shutdown();
    boolean terminated = executor.awaitTermination(EXECUTOR_TIMEOUT, TimeUnit.MINUTES);
    if (!terminated) {
      System.out.println("Warning: Not all tasks finished before timeout!");
      executor.shutdownNow(); // Force shutdown
    }

    long endTime = System.currentTimeMillis();

    System.out.println("Wall Time: " + (endTime - startTime) / 1000.0 + " seconds");
  }

  private static void runInitialLoadTest(ExecutorService executor, String serverUrl) {
    for (int i = 0; i < INIT_THREAD_COUNT; i++) {
      executor.execute(() -> sendLoad(serverUrl, INIT_REQUESTS_PER_THREAD));
    }
  }

  private static void sendLoad(String serverUrl, int numRequests) {
    for (int i = 0; i < numRequests; i++) {
      if (circuitState == CircuitState.OPEN &&
          System.currentTimeMillis() - lastFailureTime <= COOLDOWN_PERIOD_MS) {
        // Skip requests while circuit is open and cooldown has not elapsed
        return;
      }

      if (circuitState == CircuitState.OPEN) {
        circuitState = CircuitState.HALF_OPEN;
      }

      long start = System.currentTimeMillis();
      int responseCode = sendPostRequest(serverUrl + "/sentence");
      long latency = System.currentTimeMillis() - start;

      if (responseCode >= 400 || latency > LATENCY_THRESHOLD_MS) {
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
    }
  }

  private static int sendPostRequest(String urlString) {
    try {
      URI uri = new URI(urlString);
      HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
      conn.setRequestMethod("POST");
      conn.setDoOutput(true);

      String jsonInputString = String.format(
          "{\"author\": \"%d\", \"content\": \"%s\", \"theme\": \"%s\"}",
          Thread.currentThread().threadId(),
          getRandomSonnetLine(),
          getRandomTheme());

      try (OutputStream os = conn.getOutputStream()) {
        byte[] input = jsonInputString.getBytes(StandardCharsets.UTF_8);
        os.write(input, 0, input.length);
      }

      return conn.getResponseCode();
    } catch (IOException e) {
      return 500; // Simulate server failure
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  private static void loadSonnetData() throws IOException {
    try (BufferedReader br = new BufferedReader(new InputStreamReader(
        Objects.requireNonNull(LoadTestClient.class.getClassLoader()
            .getResourceAsStream(FILE_NAME)), StandardCharsets.UTF_8))) {
      String line;
      while ((line = br.readLine()) != null) {
        sonnetLines.add(line);
      }
    }
  }

  private static String getRandomSonnetLine() {
    return sonnetLines.get(RANDOM.nextInt(sonnetLines.size()));
  }

  private static String getRandomTheme() {
    return THEMES.stream().skip(RANDOM.nextInt(THEMES.size())).findFirst().orElse("");
  }
}
