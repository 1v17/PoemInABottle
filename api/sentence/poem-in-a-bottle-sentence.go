package sentence

import (
    "context"
    "encoding/json"
    "poem-in-a-bottle-api/shared"

    "github.com/aws/aws-lambda-go/events"
    "github.com/aws/aws-lambda-go/lambda"
)

type sentence struct {
    Author  int    `json:"author"`
    Content string `json:"content"`
    Theme   string `json:"theme"`
}

// putsentence will store a sentence in DynamoDB
func (d *DynamoDBStore) putsentence(ctx context.Context, s sentence) error {
    item, err := attributevalue.MarshalMap(s)
    if err != nil {
        return fmt.Errorf("could not marshal sentence: %w", err)
    }

    _, err = d.client.PutItem(ctx, &dynamodb.PutItemInput{
        TableName: &d.tableName,
        Item:      item,
    })
    if err != nil {
        return fmt.Errorf("could not store sentence in DynamoDB: %w", err)
    }
    return nil
}

// SentenceHandler handles POST /sentence.
func SentenceHandler(ctx context.Context, req events.APIGatewayProxyRequest) (events.APIGatewayProxyResponse, error) {
    var s sentence
    if err := json.Unmarshal([]byte(req.Body), &s); err != nil {
        return events.APIGatewayProxyResponse{
            StatusCode: 400,
            Body:       "Invalid input",
        }, nil
    }
    if s.Theme == "" {
        s.Theme = "Random"
    }

    store := api.NewDynamoDBStore(ctx, "sentence-in-a-bottle")
    if err := store.putsentence(ctx, s); err != nil {
        return events.APIGatewayProxyResponse{
            StatusCode: 500,
            Body:       err.Error(),
        }, nil
    }

    return events.APIGatewayProxyResponse{
        StatusCode: 200,
        Body:       "Sentence queued for theme: " + s.Theme,
    }, nil
}

func main() {
    lambda.Start(SentenceHandler)
}