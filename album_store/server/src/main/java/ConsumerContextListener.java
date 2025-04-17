import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;

@WebListener
public class ConsumerContextListener implements ServletContextListener {

    private Thread consumerThread;
    private Connection connection;
    private Channel channel;

    // Track total consumed messages and start time
    private static final AtomicLong messageCount = new AtomicLong(0);
    private static final long startTime = System.currentTimeMillis();

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost("localhost");
            connection = factory.newConnection();
            channel = connection.createChannel();
            channel.queueDeclare("albumQueue", false, false, false, null);

            consumerThread = new Thread(() -> {
                try {
                    // Basic consume with auto-ack = true
                    DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                        long totalSoFar = messageCount.incrementAndGet();
                        if (totalSoFar % 1000 == 0) {
                            long now = System.currentTimeMillis();
                            double elapsedSeconds = (now - startTime) / 1000.0;
                            double rate = totalSoFar / elapsedSeconds;
                            System.out.printf("[Consumer] Received %d messages so far, rate: %.2f msg/s%n",
                                              totalSoFar, rate);

                            // Write rate to file
                            try (FileWriter fw = new FileWriter("receive-rate-logs.txt", true);
                                 BufferedWriter bw = new BufferedWriter(fw)) {
                                bw.write(String.format("Received %d messages so far, rate: %.2f msg/s",
                                                       totalSoFar, rate));
                                bw.newLine();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }

                        String message = new String(delivery.getBody(), "UTF-8");
                        System.out.println("[Consumer] Received message: " + message);

                        // Parse message as JSON and update DB
                        try {
                            JsonObject obj = JsonParser.parseString(message).getAsJsonObject();
                            int albumId = obj.get("albumId").getAsInt();
                            String action = obj.get("action").getAsString();

                            AlbumReviewDao reviewDao = new AlbumReviewDao();
                            AlbumReview review = reviewDao.getAlbumReview(albumId);
                            if (review == null) {
                                review = new AlbumReview(albumId, 0, 0);
                            }

                            if ("like".equals(action)) {
                                review.setLikes(review.getLikes() + 1);
                            } else if ("dislike".equals(action)) {
                                review.setDislikes(review.getDislikes() + 1);
                            }

                            reviewDao.updateAlbumReview(review);
                            System.out.println("[Consumer] Processed " + action + " for album " + albumId);
                        } catch (Exception e) {
                            System.err.println("[!] Error processing message: " + e.getMessage());
                        }
                    };

                    channel.basicConsume("albumQueue", true, deliverCallback, consumerTag -> {
                        System.out.println("[Consumer] Cancelled: " + consumerTag);
                    });

                    // Keep this thread alive
                    while (!Thread.currentThread().isInterrupted()) {
                        Thread.sleep(1000);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            consumerThread.setDaemon(true);
            consumerThread.start();

            System.out.println("[ContextListener] Consumer thread started.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        try {
            if (consumerThread != null && consumerThread.isAlive()) {
                consumerThread.interrupt();
            }
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
            if (connection != null && connection.isOpen()) {
                connection.close();
            }
            System.out.println("[ContextListener] Consumer thread stopped and resources closed.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
