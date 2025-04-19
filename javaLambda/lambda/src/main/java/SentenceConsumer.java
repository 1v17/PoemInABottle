import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class SentenceConsumer implements RequestHandler<SQSEvent, Void> {
    private final SentenceDao sentenceDao;

    public SentenceConsumer() {
        this.sentenceDao = new SentenceDao();
    }

    @Override
    public Void handleRequest(SQSEvent sqsEvent, Context context) {
        try {
            for (SQSMessage msg : sqsEvent.getRecords()) {
                processSentence(msg, context);
            }
        } catch (Exception e) {
            context.getLogger().log("Error processing messages: " + e.getMessage());
        }
        return null;
    }

    private void processSentence(SQSMessage msg, Context context) {
        try {
            String messageBody = msg.getBody();
            context.getLogger().log("Processing message: " + messageBody);

            // Parse the message body as JSON
            JsonObject jsonObject = JsonParser.parseString(messageBody).getAsJsonObject();

            // Validate the required fields
            if (!jsonObject.has("author") || !jsonObject.has("content") || !jsonObject.has("theme")) {
                context.getLogger().log("Invalid message format: " + messageBody);
                return;
            }

            int author = jsonObject.get("author").getAsInt();
            String content = jsonObject.get("content").getAsString();
            String theme = jsonObject.get("theme").getAsString();

            // Store the sentence in DynamoDB using the DAO
            sentenceDao.storeSentence(author, content, theme, context);
            
        } catch (Exception e) {
            context.getLogger().log("Error processing message: " + e.getMessage());
        }
    }
}