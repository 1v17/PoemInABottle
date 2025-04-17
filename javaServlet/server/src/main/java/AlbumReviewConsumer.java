// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
import java.sql.SQLException;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class AlbumReviewConsumer implements RequestHandler<SQSEvent, Void> {
    private static final AlbumReviewDao reviewDao = new AlbumReviewDao();

    @Override
    public Void handleRequest(SQSEvent sqsEvent, Context context) {
        for (SQSMessage msg : sqsEvent.getRecords()) {
            processMessage(msg, context);
        }
        context.getLogger().log("done");
        return null;
    }

    private void processMessage(SQSMessage msg, Context context) {
        try {
            String messageBody = msg.getBody();
            context.getLogger().log("Received message: " + messageBody);

            // Parse the message body as JSON
            JsonObject jsonObject = JsonParser.parseString(messageBody).getAsJsonObject();
            int albumId = jsonObject.get("albumId").getAsInt();
            String action = jsonObject.get("action").getAsString();

            // Fetch the album review from the database
            AlbumReview review = reviewDao.getAlbumReview(albumId);
            if (review == null) {
                context.getLogger().log("Album review not found for albumId: " + albumId);
                return;
            }

            // Update the review based on the action
            if ("like".equals(action)) {
                review.setLikes(review.getLikes() + 1);
            } else if ("dislike".equals(action)) {
                review.setDislikes(review.getDislikes() + 1);
            }

            // Save the updated review back to the database
            reviewDao.updateAlbumReview(review);
        } catch (SQLException e) {
            context.getLogger().log("Database error: " + e.getMessage());
            // Don't rethrow SQL exceptions, but log them
        } catch (Exception e) {
            context.getLogger().log("Error processing message: " + e.getMessage());
            throw e; // Rethrow other exceptions to indicate failure
        }
    }
}
