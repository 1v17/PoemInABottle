package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"

	"github.com/aws/aws-sdk-go/aws"
	"github.com/aws/aws-sdk-go/aws/credentials"
	"github.com/aws/aws-sdk-go/aws/session"
	"github.com/aws/aws-sdk-go/service/dynamodb"
	"github.com/aws/aws-sdk-go/service/dynamodb/dynamodbattribute"
	"github.com/aws/aws-sdk-go/service/sqs"
	"github.com/gorilla/mux"
	"github.com/joho/godotenv"
)

type sentence struct {
	Author  int    `json:"author"`
	Content string `json:"content"`
	Theme   string `json:"theme"`
}

type poem struct {
	Authors  []int  `json:"authors"`
	Contents string `json:"content"`
	Theme    string `json:"theme"`
}

var svc *dynamodb.DynamoDB
var sqsClient *sqs.SQS
var queueURL string

func main() {
	r := mux.NewRouter()
	r.HandleFunc("/poem/{theme}", getPoem).Methods("GET")
	r.HandleFunc("/poem", getPoem).Methods("GET")
	r.HandleFunc("/sentence", postSentence).Methods("POST")

	http.ListenAndServe(":8080", r)
}

func init() {
	sess, err := session.NewSession(&aws.Config{
		Region:      aws.String("us-west-2"),
		Credentials: credentials.NewSharedCredentials("", "user1"),
	})
	if err != nil {
		log.Fatalf("Failed to create AWS session: %v", err)
	}

	// Create DynamoDB and SQS client
	svc = dynamodb.New(sess)
	sqsClient = sqs.New(sess)

	// Get the SQS queue URL from the environment
	if err := godotenv.Load(); err != nil {
		log.Fatalf("Error loading .env file: %v", err)
	}

	queueURL = os.Getenv("SQS_QUEUE_URL")

	// Start the poller in the background
	go pollQueueAndSend()
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
	json.NewEncoder(w).Encode(p)
}

func postSentence(w http.ResponseWriter, r *http.Request) {
	var s sentence
	if err := json.NewDecoder(r.Body).Decode(&s); err != nil {
		http.Error(w, "Invalid input", http.StatusBadRequest)
		return
	}

	if s.Theme == "" {
		s.Theme = "random"
	}

	// Convert the sentence to JSON so we can send it as an SQS message
	msgBody, err := json.Marshal(s)
	if err != nil {
		http.Error(w, "Failed to marshal sentence", http.StatusInternalServerError)
		return
	}

	var groupId string
	switch strings.ToLower(s.Theme) {
	case "love":
		groupId = "1"
	case "death":
		groupId = "2"
	case "nature":
		groupId = "3"
	case "beauty":
		groupId = "4"
	default:
		groupId = "0"
	}

	// Put the sentence into SQS
	input := &sqs.SendMessageInput{
		MessageBody: aws.String(string(msgBody)),
		QueueUrl:    &queueURL,
		// Assign the group ID to handle FIFO grouping
		MessageGroupId: aws.String(groupId),
	}

	if _, err := sqsClient.SendMessage(input); err != nil {
		log.Printf("Failed to send message to SQS: %s", err)
		http.Error(w, "Failed to queue sentence", http.StatusInternalServerError)
		return
	}

	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(map[string]string{
		"msg": "Sentence queued for theme: " + s.Theme,
	})
}

// Poll the queue in a separate goroutine or process, gathering messages
// until we reach 3, then print them out for now.
func pollQueueAndSend() {
	for {
		msgs, err := sqsClient.ReceiveMessage(&sqs.ReceiveMessageInput{
			QueueUrl:            &queueURL,
			MaxNumberOfMessages: aws.Int64(10), // Try getting up to 10
			WaitTimeSeconds:     aws.Int64(10),
			VisibilityTimeout:   aws.Int64(30),
			AttributeNames: []*string{
				aws.String(sqs.MessageSystemAttributeNameMessageGroupId),
			},
		})
		if err != nil {
			log.Printf("Error polling SQS: %v", err)
			continue
		}
		if len(msgs.Messages) == 0 {
			continue
		}

		// Group messages by MessageGroupId
		grouped := make(map[string][]*sqs.Message)
		for _, m := range msgs.Messages {
			groupId := *m.Attributes[sqs.MessageSystemAttributeNameMessageGroupId]
			grouped[groupId] = append(grouped[groupId], m)
		}

		// For each group that has at least 3 messages, process and delete exactly 3
		for groupId, msgGroup := range grouped {
			if len(msgGroup) < 3 {
				continue
			}

			toProcess := msgGroup[:3]

			// Convert SQS messages to sentence objects
			var sentences []sentence
			var entries []*sqs.DeleteMessageBatchRequestEntry
			for _, m := range toProcess {
				var s sentence
				if err := json.Unmarshal([]byte(*m.Body), &s); err != nil {
					log.Printf("Error unmarshaling SQS message: %v", err)
					continue
				}
				sentences = append(sentences, s)
				entries = append(entries, &sqs.DeleteMessageBatchRequestEntry{
					Id:            m.MessageId,
					ReceiptHandle: m.ReceiptHandle,
				})
			}

			fmt.Printf("Group: %s | Processing 3 messages:\n", groupId)
			for _, s := range sentences {
				fmt.Printf("Author=%d Theme=%s Content=%s\n", s.Author, s.Theme, s.Content)
			}

			// Aggregate the three sentences into one poem
			var p poem
			p.Authors = []int{
				sentences[0].Author,
				sentences[1].Author,
				sentences[2].Author,
			}
			p.Contents = sentences[0].Content + "\n" + sentences[1].Content + "\n" + sentences[2].Content
			p.Theme = sentences[0].Theme

			// Store the poem into DynamoDB
			av, err := dynamodbattribute.MarshalMap(p)
			if err != nil {
				log.Printf("Failed to marshal poem for DynamoDB: %v", err)
				continue
			}

			tableName := "poem-in-a-bottle"
			_, err = svc.PutItem(&dynamodb.PutItemInput{
				Item:      av,
				TableName: aws.String(tableName),
			})
			if err != nil {
				log.Printf("Got error calling PutItem: %v", err)
				continue
			}
			log.Printf("Successfully stored poem (theme=%s) in %s", p.Theme, tableName)

			// Delete the 3 processed messages
			_, err = sqsClient.DeleteMessageBatch(&sqs.DeleteMessageBatchInput{
				QueueUrl: &queueURL,
				Entries:  entries,
			})
			if err != nil {
				log.Printf("Error deleting messages: %v", err)
			}
		}
	}
}
