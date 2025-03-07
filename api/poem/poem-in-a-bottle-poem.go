package poem

import (
    "context"
    "encoding/json"
    "strings"
    "poem-in-a-bottle-api/shared"

    "github.com/aws/aws-lambda-go/events"
    "github.com/aws/aws-lambda-go/lambda"
)

// poem represents our stored poem
type poem struct {
    Authors  []int    `json:"authors"`
    Contents []string `json:"content"`
    Theme    string   `json:"theme"`
}

// stringifyPoem joins poem lines with newlines
func (p *poem) stringifyPoem() string {
    return strings.Join(p.Contents, "\n")
}

func (d *DynamoDBStore) getpoem(ctx context.Context, theme string) (*poem, error) {
    out, err := d.client.GetItem(ctx, &dynamodb.GetItemInput{
        TableName: &d.tableName,
        Key: map[string]types.AttributeValue{
            "Theme": &types.AttributeValueMemberS{Value: theme},
        },
    })
    if err != nil {
        return nil, fmt.Errorf("failed to get poem: %w", err)
    }
    if out.Item == nil {
        return nil, nil // Means no poem found
    }

    var p poem
    if err := attributevalue.UnmarshalMap(out.Item, &p); err != nil {
        return nil, fmt.Errorf("failed to unmarshal poem: %w", err)
    }
    return &p, nil
}

// Handler for GET /poem (refactored to use shared DynamoDBStore)
func PoemHandler(ctx context.Context, req events.APIGatewayProxyRequest) (events.APIGatewayProxyResponse, error) {
    theme := req.PathParameters["theme"]
    if theme == "" {
        theme = "Random"
    }

    store := NewDynamoDBStore(ctx, "poem-in-a-bottle")

    // Retrieve the poem from DynamoDB
    dynapoem, err := store.getpoem(ctx, theme)
    if err != nil {
        return events.APIGatewayProxyResponse{
            StatusCode: 500,
            Body:       err.Error(),
        }, nil
    }
    if dynapoem == nil {
        return events.APIGatewayProxyResponse{
            StatusCode: 404,
            Body:       "No poem found for theme: " + theme,
        }, nil
    }

    // Construct JSON response
    poemText := dynapoem.stringifyPoem()
    responseBody, err := json.Marshal(poemText)
    if err != nil {
        return events.APIGatewayProxyResponse{
            StatusCode: 500,
            Body:       "Failed to encode poem",
        }, nil
    }

    return events.APIGatewayProxyResponse{
        StatusCode: 200,
        Body:       string(responseBody),
    }, nil
}

// Lambda entry point
func main() {
    lambda.Start(PoemHandler)
}
