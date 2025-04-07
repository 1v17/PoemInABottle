package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"math/rand"
	"net/http"
	"os"
	"time"

	"github.com/aws/aws-sdk-go/aws"
	"github.com/aws/aws-sdk-go/aws/session"
	"github.com/aws/aws-sdk-go/service/sqs"
	"github.com/gin-gonic/gin"
	_ "github.com/go-sql-driver/mysql"
)

type Publisher struct {
	db        *sql.DB
	sqsClient *sqs.SQS
	queueURL  string
}

// set cannot be constant
var (
	validThemes = map[string]struct{}{
		"Love":   {},
		"Death":  {},
		"Nature": {},
		"Beauty": {},
		"Random": {},
	}
)

type Sentence struct {
	Author  int    `json:"author"`
	Content string `json:"content"`
	Theme   string `json:"theme"`
}

type PoemResponse struct {
	Authors  []int  `json:"authors"`
	Contents string `json:"contents"`
	Theme    string `json:"theme"`
}

func main() {
	publisher := &Publisher{}
	publisher.initDB()
	defer publisher.db.Close()

	publisher.initSQS()

	r := gin.Default()
	publisher.setupRouter(r)
	log.Println("Server started on :8080")
	r.Run(":8080")
}

func (p *Publisher) initDB() {
	var err error
	dbUser := os.Getenv("DB_USER")
	dbPassword := os.Getenv("DB_PASSWORD")
	dbPort := os.Getenv("DB_PORT")
	dbHost := os.Getenv("DB_HOST")
	dbName := os.Getenv("DB_NAME")

	// Check for missing environment variables
	if dbUser == "" || dbPassword == "" || dbPort == "" || dbHost == "" || dbName == "" {
		log.Fatalf("One or more required environment variables are missing: DB_USER, DB_PASSWORD, DB_PORT, DB_HOST, DB_NAME")
	}

	dsn := fmt.Sprintf("%s:%s@tcp(%s:%s)/%s", dbUser, dbPassword, dbHost, dbPort, dbName)
	p.db, err = sql.Open("mysql", dsn)
	if err != nil {
		log.Fatalf("Failed to connect to database: %v", err)
	}

	if err = p.db.Ping(); err != nil {
		log.Fatalf("Failed to ping database: %v", err)
	}
}

func (p *Publisher) initSQS() {
	sess, err := session.NewSession(&aws.Config{
		Region: aws.String(os.Getenv("AWS_REGION")),
	})
	if err != nil {
		log.Fatalf("Failed to create AWS session: %v", err)
	}

	p.sqsClient = sqs.New(sess)
	p.queueURL = os.Getenv("SQS_QUEUE_URL")
	if p.queueURL == "" {
		log.Fatalf("SQS_QUEUE_URL environment variable is not set")
	}
}

func (p *Publisher) setupRouter(r *gin.Engine) {
	r.GET("/poem/:theme", p.getPoemByTheme)
	r.GET("/poem", p.getPoem)
	r.POST("/sentence", p.postSentence)
}

func (p *Publisher) getPoemByTheme(c *gin.Context) {
	theme := c.Param("theme")
	if !validateTheme(theme) {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid theme"})
		return
	}

	poem, err := p.queryPoemByTheme(theme)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to fetch poem"})
		return
	}

	c.JSON(http.StatusOK, poem)
}

func (p *Publisher) getPoem(c *gin.Context) {
	themes := []string{"Love", "Death", "Nature", "Beauty", "Random"}
	rand.Seed(time.Now().UnixNano())
	randomTheme := themes[rand.Intn(len(themes))]

	poem, err := p.queryPoemByTheme(randomTheme)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to fetch poem"})
		return
	}

	c.JSON(http.StatusOK, poem)
}

func (p *Publisher) postSentence(c *gin.Context) {
	var request Sentence
	if err := c.BindJSON(&request); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid request"})
		return
	}

	if request.Author <= 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid author"})
		return
	}

	if request.Content == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Content cannot be empty"})
		return
	}

	if request.Theme == "" {
		request.Theme = "Random"
	}

	if !validateTheme(request.Theme) {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid theme"})
		return
	}

	err := p.sendToSQS(request)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to queue sentence"})
		return
	}

	c.JSON(http.StatusOK, gin.H{"msg": "Sentence queued for theme: " + request.Theme})
}

func validateTheme(theme string) bool {
	_, exists := validThemes[theme]
	return exists
}

func (p *Publisher) queryPoemByTheme(theme string) (*PoemResponse, error) {
	query := "SELECT Content, AuthorIDs FROM " + theme + " ORDER BY TimeStamp DESC LIMIT 1"
	row := p.db.QueryRow(query)

	var content string
	var authorIDs string
	if err := row.Scan(&content, &authorIDs); err != nil {
		return nil, err
	}

	var authors []int
	if err := json.Unmarshal([]byte(authorIDs), &authors); err != nil {
		return nil, err
	}

	return &PoemResponse{
		Authors:  authors,
		Contents: content,
		Theme:    theme,
	}, nil
}

func (p *Publisher) sendToSQS(sentence Sentence) error {
	messageBody, err := json.Marshal(sentence)
	if err != nil {
		return err
	}

	_, err = p.sqsClient.SendMessage(&sqs.SendMessageInput{
		QueueUrl:    aws.String(p.queueURL),
		MessageBody: aws.String(string(messageBody)),
	})
	return err
}
