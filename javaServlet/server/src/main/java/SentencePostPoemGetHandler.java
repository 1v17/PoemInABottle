import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class SentencePostPoemGetHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
    private static final Gson gson = new Gson();
    private static final AmazonSQS sqsClient = AmazonSQSClientBuilder.defaultClient();
    private static final String AWS_REGION = System.getenv("AWS_REGION") != null ? System.getenv("AWS_REGION") : "us-west-2";
    private static final String SQS_QUEUE_URL = System.getenv("SQS_QUEUE_URL");
    private static final PoemDao dao = new PoemDao();
    private static final List<String> VALID_THEMES = Arrays.asList("random", "love", "death", "nature", "beauty");

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

        // Create message in JSON format
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("author", author);
        jsonObject.addProperty("content", content);
        jsonObject.addProperty("theme", theme);
        String message = gson.toJson(jsonObject);

        // Send message to FIFO SQS queue with MessageGroupId based on theme
        SendMessageRequest sendMessageRequest = new SendMessageRequest()
                .withQueueUrl(SQS_QUEUE_URL) // Ensure this is a FIFO queue URL
                .withMessageBody(message)
                .withMessageGroupId(theme) // Use theme as the MessageGroupId
                .withMessageDeduplicationId(author + "-" + System.currentTimeMillis()); // Unique deduplication ID
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
        
        // Validate theme
        if (!VALID_THEMES.contains(theme)) {
            theme = "random";
        }
        
        // Get random poem by theme
        context.getLogger().log("Getting random poem with theme: " + theme);
        Poem poem = dao.getRandomPoemByTheme(theme);
        
        if (poem == null) {
            return createErrorResponse(404, "No poems found for theme: " + theme);
        }

        APIGatewayV2HTTPResponse response = new APIGatewayV2HTTPResponse();
        response.setStatusCode(200);
        
        // Create poem response
        JsonObject poemJson = new JsonObject();
        poemJson.addProperty("theme", poem.getTheme());
        poemJson.addProperty("contents", poem.getContents());
        
        // Add authors array
        JsonObject responseJson = new JsonObject();
        responseJson.add("poem", poemJson);
        
        // Set response body
        response.setBody(gson.toJson(responseJson));
        return response;
    }

    private APIGatewayV2HTTPResponse createErrorResponse(int statusCode, String message) {
        APIGatewayV2HTTPResponse response = new APIGatewayV2HTTPResponse();
        response.setStatusCode(statusCode);
        response.setBody("{\"message\": \"" + message + "\"}");
        return response;
    }
}