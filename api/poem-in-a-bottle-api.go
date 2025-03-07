package main

import (
    "encoding/json"
    "net/http"
	"strings"

    "github.com/gorilla/mux"
    "github.com/aws/aws-sdk-go/aws"
    "github.com/aws/aws-sdk-go/aws/credentials"
    "github.com/aws/aws-sdk-go/aws/session"
    "github.com/aws/aws-sdk-go/service/dynamodb"
    "github.com/aws/aws-sdk-go/service/dynamodb/dynamodbattribute"

    "fmt"
    "log"
)

type sentence struct {
    Author  int    `json:"author"`
    Content string `json:"content"`
    Theme   string `json:"theme"`
}

type poem struct {
    Authors []int      `json:"authors"`
    Contents []string   `json:"content"`
    Theme   string     `json:"theme"`
}

func (p *poem) addSentence(s sentence) {
    p.Authors = append(p.Authors, s.Author)
    p.Contents = append(p.Contents, s.Content)
}

func (p *poem) stringifyPoem() string {
    return strings.Join(p.Contents, "\n")
}

var svc *dynamodb.DynamoDB

func main() {
    r := mux.NewRouter()
    r.HandleFunc("/poem/{theme}", getPoem).Methods("GET")
    r.HandleFunc("/poem", getPoem).Methods("GET")
    r.HandleFunc("/sentence", postSentence).Methods("POST")

    http.ListenAndServe(":8080", r)
}

func init() {
    sess, _ := session.NewSession(&aws.Config{
        Region:      aws.String("us-west-2"),
        Credentials: credentials.NewSharedCredentials("", "user1"),
    })
    // Create DynamoDB client
    svc = dynamodb.New(sess)
}

func getPoem(w http.ResponseWriter, r *http.Request) {
    theme := mux.Vars(r)["theme"]
    if theme == "" {
        theme = "Random"
    }

    tableName := "poem-in-a-bottle"

    result, err := svc.GetItem(&dynamodb.GetItemInput{
        TableName: aws.String(tableName),
        Key: map[string]*dynamodb.AttributeValue{
            "theme": {S: aws.String(theme)},
        },
    })

    if err != nil {
        log.Printf("Error calling GetItem: %s", err)
        http.Error(w, fmt.Sprintf("Error calling GetItem: %s", err), http.StatusInternalServerError)
        return
    }

    if result.Item == nil {
        http.Error(w, "No poem found for theme: "+theme, http.StatusNotFound)
        return
    }

    var p poem
    // Unmarshal the item into our poem struct
    err = dynamodbattribute.UnmarshalMap(result.Item, &p)
    if err != nil {
        http.Error(w, "Failed to unmarshal poem", http.StatusInternalServerError)
        return
    }

    // Return the poem (joined by newlines) as JSON
    w.Header().Set("Content-Type", "application/json")
    json.NewEncoder(w).Encode(map[string]string{
        "poem":  p.stringifyPoem(),
    })
}

func postSentence(w http.ResponseWriter, r *http.Request) {
    theme := mux.Vars(r)["theme"]
    if theme == "" {
        theme = "Random"
    }

    var s sentence
    if err := json.NewDecoder(r.Body).Decode(&s); err != nil {
        http.Error(w, "Invalid input", http.StatusBadRequest)
        return
    }
    s.Theme = theme

    av, err := dynamodbattribute.MarshalMap(s)
    if err != nil {
        http.Error(w, fmt.Sprintf("Failed to store sentence: %s", err), http.StatusInternalServerError)
        return
    }
    
    tableName := "sentence-in-a-bottle"

    input := &dynamodb.PutItemInput{
        Item:      av,
        TableName: aws.String(tableName),
    }
    
    _, err = svc.PutItem(input)
    if err != nil {
        log.Fatalf("Got error calling PutItem: %s", err)
    }
    
    fmt.Println("Successfully added '" + s.Content + " to table " + tableName)

    w.WriteHeader(http.StatusOK)
    w.Write([]byte("Sentence queued for theme: " + theme))
}
