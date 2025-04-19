import java.io.IOException;
import java.util.List;
import java.util.Random;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class PostSentenceGetPoemHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
    private static final Gson gson = new Gson();
    private static final AmazonSQS sqsClient = AmazonSQSClientBuilder.defaultClient();
    private static final String AWS_REGION = System.getenv("AWS_REGION") != null ? System.getenv("AWS_REGION") : "us-west-2";
    private static final String SQS_QUEUE_URL = System.getenv("SQS_QUEUE_URL");
    
    private final SentenceDao sentenceDao;
    
    public PostSentenceGetPoemHandler() {
        this.sentenceDao = new SentenceDao();
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent input, Context context) {
        String path = input.getRawPath();
        String httpMethod = input.getRequestContext().getHttp().getMethod();
        APIGatewayV2HTTPResponse response = new APIGatewayV2HTTPResponse();

        try {
            if ("POST".equalsIgnoreCase(httpMethod) && path.matches("^/sentence$")) {
                return storeSentence(input);
            } else if ("GET".equalsIgnoreCase(httpMethod) && path.matches("(?i)^/poem(/(random|love|death|nature|beauty))?$")) {
                return getPoem(path, context);
            }

            response.setStatusCode(400);
            response.setBody("{\"message\": \"Invalid input\"}");
        } catch (Exception e) {
            context.getLogger().log("Error handling request: " + e.getMessage());
            response.setStatusCode(500);
            response.setBody("{\"message\": \"Server error\"}");
        }

        return response;
    }

    private APIGatewayV2HTTPResponse storeSentence(APIGatewayV2HTTPEvent input) throws IOException {
        JsonObject bodyJson = JsonParser.parseString(input.getBody()).getAsJsonObject();
        APIGatewayV2HTTPResponse response = new APIGatewayV2HTTPResponse();

        // Validate required fields and types
        if (!bodyJson.has("author") || !bodyJson.get("author").isJsonPrimitive() || !bodyJson.get("author").getAsJsonPrimitive().isNumber()) {
            return createErrorResponse(400, "Missing or invalid 'author' field");
        }
        if (!bodyJson.has("content") || !bodyJson.get("content").isJsonPrimitive() || bodyJson.get("content").getAsString().isEmpty()) {
            return createErrorResponse(400, "Missing or invalid 'content' field");
        }

        // Extract and validate fields
        int author = bodyJson.get("author").getAsInt();
        String content = bodyJson.get("content").getAsString().trim();
        String theme = bodyJson.has("theme") && !bodyJson.get("theme").getAsString().isEmpty()
                ? bodyJson.get("theme").getAsString().trim().toLowerCase()
                : "random";

        // Validate theme
        theme = sentenceDao.validateTheme(theme);

        // Create message in JSON format
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("author", author);
        jsonObject.addProperty("content", content);
        jsonObject.addProperty("theme", theme);
        String message = gson.toJson(jsonObject);

        // Send message to FIFO SQS queue with MessageGroupId based on theme
        SendMessageRequest sendMessageRequest = new SendMessageRequest()
                .withQueueUrl(SQS_QUEUE_URL)
                .withMessageBody(message)
                .withMessageGroupId(theme)
                .withMessageDeduplicationId(author + "-" + System.currentTimeMillis());
        sqsClient.sendMessage(sendMessageRequest);

        // Create response
        response.setStatusCode(201);
        response.setBody("{\"msg\": \"Sentence queued for theme: " + theme + "\"}");
        return response;
    }

    private APIGatewayV2HTTPResponse getPoem(String path, Context context) throws Exception {
        // Extract theme from path
        String theme = "random"; // Default theme
        if (path.contains("/")) {
            String[] pathParts = path.split("/");
            if (pathParts.length > 2) {
                theme = pathParts[2].toLowerCase();
            }
        }
        
        // Validate theme using the DAO
        theme = sentenceDao.validateTheme(theme);
        
        // Generate random number n between 3 and 14 inclusive
        int n = 3 + new Random().nextInt(12);
        context.getLogger().log("Getting " + n + " oldest sentences with theme: " + theme);
        
        // Query DynamoDB for n oldest sentences by theme using the DAO
        List<Item> items = sentenceDao.getOldestSentencesByTheme(theme, n, context);
        
        if (items.isEmpty()) {
            return createErrorResponse(404, "No sentences found for theme: " + theme);
        }
        
        // Create poem from sentences
        int[] authors = new int[items.size()];
        StringBuilder contentBuilder = new StringBuilder();
        
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            authors[i] = item.getInt("author");
            contentBuilder.append(item.getString("content")).append("\n");
        }
        
        // Delete sentences from DynamoDB using the DAO
        boolean deletionSuccess = sentenceDao.deleteSentences(items, context);
        if (!deletionSuccess) {
            context.getLogger().log("Warning: Failed to delete some sentences");
        }
        
        // Create response
        APIGatewayV2HTTPResponse response = new APIGatewayV2HTTPResponse();
        response.setStatusCode(200);
        
        // Format poem response
        JsonObject poemJson = new JsonObject();
        poemJson.addProperty("theme", theme);
        poemJson.addProperty("contents", contentBuilder.toString().trim());
        
        // Add authors array
        JsonArray authorsArray = new JsonArray();
        for (int author : authors) {
            authorsArray.add(author);
        }
        poemJson.add("authors", authorsArray);

        // Set response body
        response.setBody(gson.toJson(poemJson));
        return response;
    }

    private APIGatewayV2HTTPResponse createErrorResponse(int statusCode, String message) {
        APIGatewayV2HTTPResponse response = new APIGatewayV2HTTPResponse();
        response.setStatusCode(statusCode);
        response.setBody("{\"message\": \"" + message + "\"}");
        return response;
    }
}