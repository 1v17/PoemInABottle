import java.io.IOException;
import java.sql.SQLException;
import java.util.Base64;

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

public class AlbumCreateHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
    private static final Gson gson = new Gson();
    private static final AmazonSQS sqsClient = AmazonSQSClientBuilder.defaultClient();
    private static final String AWS_REGION = System.getenv("AWS_REGION") != null ? System.getenv("AWS_REGION") : "us-east-1";
    private static final String SQS_QUEUE_URL = "https://sqs.us-west-2.amazonaws.com/243870365946/albumQueue";

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent input, Context context) {
        String path = input.getRawPath();
        String httpMethod = input.getRequestContext().getHttp().getMethod();
        APIGatewayV2HTTPResponse response = new APIGatewayV2HTTPResponse();

        try {
            if ("POST".equalsIgnoreCase(httpMethod)) {
                if (path.matches("^/album$")) {
                    return createAlbum(input);
                } else if (path.matches("^/review/(like|dislike)/\\d+$")) {
                    return likeOrDislikeWithSQS(input);
                }
            } else if ("GET".equalsIgnoreCase(httpMethod) && path.matches("^/album/\\d+$")) {
                return getAlbumInfo(path);
            }

            response.setStatusCode(400);
            response.setBody("{\"message\": \"Invalid input\"}");
        } catch (Exception e) {
            e.printStackTrace();
            response.setStatusCode(500);
            response.setBody("{\"message\": \"Server error\"}");
        }

        return response;
    }

    private APIGatewayV2HTTPResponse createAlbum(APIGatewayV2HTTPEvent input) throws IOException, SQLException {
        JsonObject bodyJson = JsonParser.parseString(input.getBody()).getAsJsonObject();
        APIGatewayV2HTTPResponse response = new APIGatewayV2HTTPResponse();

        // Validate required fields
        if (!bodyJson.has("artist") || bodyJson.get("artist").getAsString().isEmpty()) {
            return createErrorResponse(400, "Missing or invalid 'artist' field");
        }
        if (!bodyJson.has("title") || bodyJson.get("title").getAsString().isEmpty()) {
            return createErrorResponse(400, "Missing or invalid 'title' field");
        }
        if (!bodyJson.has("year") || bodyJson.get("year").getAsInt() <= 0) {
            return createErrorResponse(400, "Missing or invalid 'year' field");
        }
        if (!bodyJson.has("albumId") || bodyJson.get("albumId").getAsInt() <= 0) {
            return createErrorResponse(400, "Missing or invalid 'albumId' field");
        }

        String artist = bodyJson.get("artist").getAsString().trim();
        String title = bodyJson.get("title").getAsString().trim();
        int year = bodyJson.get("year").getAsInt();
        int albumId = bodyJson.get("albumId").getAsInt();
        byte[] imageBytes = bodyJson.has("image") ? Base64.getDecoder().decode(bodyJson.get("image").getAsString()) : new byte[0];

        // Save album to database
        AlbumDao dao = new AlbumDao();
        dao.createAlbum(new Album(albumId, artist, title, year, imageBytes));
        AlbumReviewDao reviewDao = new AlbumReviewDao();
        reviewDao.createAlbumReview(new AlbumReview(albumId, 0, 0));

        response.setStatusCode(201);
        response.setBody("{\"message\":\"Data created successfully\"}");
        return response;
    }

    private APIGatewayV2HTTPResponse likeOrDislikeWithSQS(APIGatewayV2HTTPEvent input) {
        String path = input.getRawPath();
        String[] pathParts = path.split("/");
        int albumId = Integer.parseInt(pathParts[3]);
        String action = pathParts[2]; // like or dislike

        // Create message in JSON format
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("albumId", albumId);
        jsonObject.addProperty("action", action);
        String message = gson.toJson(jsonObject);

        // Send message to SQS queue
        SendMessageRequest sendMessageRequest = new SendMessageRequest()
                .withQueueUrl(SQS_QUEUE_URL)
                .withMessageBody(message);
        sqsClient.sendMessage(sendMessageRequest);

        APIGatewayV2HTTPResponse response = new APIGatewayV2HTTPResponse();
        response.setStatusCode(201);
        response.setBody("{\"message\":\"Review submitted successfully\"}");
        return response;
    }

    private APIGatewayV2HTTPResponse getAlbumInfo(String path) throws Exception {
        String[] pathParts = path.split("/");
        int albumId = Integer.parseInt(pathParts[2]);

        AlbumDao dao = new AlbumDao();
        Album album = dao.getAlbum(albumId);

        if (album == null) {
            return createErrorResponse(404, "Data not found");
        }

        AlbumReviewDao reviewDao = new AlbumReviewDao();
        AlbumReview albumReview = reviewDao.getAlbumReview(albumId);
        if (albumReview == null) {
            albumReview = new AlbumReview(albumId, 0, 0);
        }

        APIGatewayV2HTTPResponse response = new APIGatewayV2HTTPResponse();
        response.setStatusCode(200);
        response.setBody("{\"albumId\": " + albumId + ", \"details\": " + album.toString() + ", \"reviews\": " + albumReview.toString() + "}");
        return response;
    }

    private APIGatewayV2HTTPResponse createErrorResponse(int statusCode, String message) {
        APIGatewayV2HTTPResponse response = new APIGatewayV2HTTPResponse();
        response.setStatusCode(statusCode);
        response.setBody("{\"message\": \"" + message + "\"}");
        return response;
    }
}