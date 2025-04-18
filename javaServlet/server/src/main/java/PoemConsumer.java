import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class PoemConsumer implements RequestHandler<SQSEvent, Void> {
    private static final PoemDao poemDao = new PoemDao();
    private static final Random random = new Random();

    @Override
    public Void handleRequest(SQSEvent sqsEvent, Context context) {
        try {
            while (true) {
                // Generate a random number n between 3 and 100
                int n = random.nextInt(98) + 3; // Random number between 3 and 100
                context.getLogger().log("Generated random number n: " + n);

                // Collect n sentences from the SQS event
                List<Sentence> sentences = collectSentences(sqsEvent, n, context);

                // If we have enough sentences, create and store the Poem
                if (sentences.size() == n) {
                    createAndStorePoem(sentences, context);
                } else {
                    context.getLogger().log("Not enough sentences to create a Poem. Required: " + n + ", Found: " + sentences.size());
                    break;
                }
            }
        } catch (Exception e) {
            context.getLogger().log("Error processing messages: " + e.getMessage());
        }
        return null;
    }

    private List<Sentence> collectSentences(SQSEvent sqsEvent, int n, Context context) {
        List<Sentence> sentences = new ArrayList<>();
        for (SQSMessage msg : sqsEvent.getRecords()) {
            try {
                String messageBody = msg.getBody();
                context.getLogger().log("Received message: " + messageBody);

                // Parse the message body as JSON
                JsonObject jsonObject = JsonParser.parseString(messageBody).getAsJsonObject();

                // Validate the required fields
                if (jsonObject.has("author") && jsonObject.has("content") && jsonObject.has("theme")) {
                    int author = jsonObject.get("author").getAsInt();
                    String content = jsonObject.get("content").getAsString();
                    String theme = jsonObject.get("theme").getAsString();
                    
                    sentences.add(new Sentence(author, content, theme));
                } else {
                    context.getLogger().log("Invalid message format: " + messageBody);
                }

                // Stop collecting if we have enough sentences
                if (sentences.size() == n) {
                    break;
                }
            } catch (Exception e) {
                context.getLogger().log("Error parsing message: " + e.getMessage());
            }
        }
        return sentences;
    }

    private void createAndStorePoem(List<Sentence> sentences, Context context) {
        try {
            // Extract authors and content from the collected sentences
            int[] authors = new int[sentences.size()];
            StringBuilder contentBuilder = new StringBuilder();
            String theme = sentences.get(0).getTheme(); // Assume all sentences share the same theme

            for (int i = 0; i < sentences.size(); i++) {
                Sentence sentence = sentences.get(i);
                authors[i] = sentence.getAuthor();
                contentBuilder.append(sentence.getContent()).append("\n");
            }

            // Create a new Poem object
            Poem poem = new Poem(authors, contentBuilder.toString().trim(), theme);

            // Store the Poem in the database
            poemDao.createPoem(poem);
            context.getLogger().log("Successfully created and stored Poem with theme: " + theme);
        } catch (AmazonServiceException e) {
            context.getLogger().log("DynamoDB error while storing Poem: " + e.getMessage());
        } catch (Exception e) {
            context.getLogger().log("Error while creating poem: " + e.getMessage());
        }
    }
}

