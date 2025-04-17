package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"math/rand"
	"net/http"
	"os"
	"strings"

	"github.com/gin-gonic/gin"
	_ "github.com/go-sql-driver/mysql"
	"github.com/joho/godotenv"
	"github.com/streadway/amqp"
)

type Publisher struct {
	db         *sql.DB
	rabbitConn *amqp.Connection
	rabbitCh   *amqp.Channel
}

const (
	QUEUE_PREFIX = "poem_"
	QUEUE_LOVE   = QUEUE_PREFIX + "love"
	QUEUE_DEATH  = QUEUE_PREFIX + "death"
	QUEUE_NATURE = QUEUE_PREFIX + "nature"
	QUEUE_BEAUTY = QUEUE_PREFIX + "beauty"
	QUEUE_RANDOM = QUEUE_PREFIX + "random"
)

var (
	queueNames = map[string]string{
		"love":   QUEUE_LOVE,
		"death":  QUEUE_DEATH,
		"nature": QUEUE_NATURE,
		"beauty": QUEUE_BEAUTY,
		"random": QUEUE_RANDOM,
	}
)

type Sentence struct {
	Author  int    `json:"author"`
	Content string `json:"content"`
	Theme   string `json:"theme"`
}

type Poem struct {
	Authors []int  `json:"authors"`
	Content string `json:"content"`
	Theme   string `json:"theme"`
}

func main() {
	publisher := &Publisher{}
	publisher.initDB()
	defer publisher.db.Close()

	publisher.initRabbitMQ()
	defer publisher.rabbitConn.Close()
	defer publisher.rabbitCh.Close()

	r := gin.Default()
	publisher.setupRouter(r)

	r.Run(":8080")
	log.Println("Server started on :8080")
}

func (p *Publisher) initDB() {
	loadEnvErr := godotenv.Load()
	if loadEnvErr != nil {
		log.Fatalf("Error loading .env file")
	}

	var err error
	dbUser := os.Getenv("DB_USER")
	dbPassword := os.Getenv("DB_PASSWORD")
	dbPort := os.Getenv("DB_PORT")
	dbHost := os.Getenv("DB_HOST")
	dbName := os.Getenv("DB_NAME")

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

func (p *Publisher) initRabbitMQ() {

	loadEnvErr := godotenv.Load()
	if loadEnvErr != nil {
		log.Fatalf("Error loading .env file")
	}
	username := os.Getenv("RABBITMQ_USER")
	password := os.Getenv("RABBITMQ_PASSWORD")
	host := os.Getenv("RABBITMQ_HOST")
	port := os.Getenv("RABBITMQ_PORT")

	log.Printf("Connecting to RabbitMQ at %s:%s with username %s", host, port, username)

	var connectionErr error
	p.rabbitConn, connectionErr = amqp.Dial(fmt.Sprintf("amqp://%s:%s@%s:%s/",
		username, password, host, port,
	))
	if connectionErr != nil {
		log.Fatalf("Failed to connect to RabbitMQ: %v", connectionErr)
	}

	p.rabbitCh, connectionErr = p.rabbitConn.Channel()
	if connectionErr != nil {
		log.Fatalf("Failed to create RabbitMQ channel: %v", connectionErr)
	}

	for _, queue := range queueNames {
		_, err := p.rabbitCh.QueueDeclare(
			queue,
			true,  // durable
			false, // auto-delete
			false, // exclusive
			false, // no-wait
			nil,   // arguments
		)
		if err != nil {
			log.Fatalf("Failed to declare RabbitMQ queue: %v", err)
		}
	}
}

func (p *Publisher) setupRouter(r *gin.Engine) {
	r.GET("/poem/:theme", p.getPoemByTheme)
	r.GET("/poem", p.getPoem)
	r.POST("/sentence", p.postSentence)
}

func (p *Publisher) getPoemByTheme(c *gin.Context) {
	theme := strings.ToLower(c.Param("theme"))
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
	themes := make([]string, 0, len(queueNames))
	for theme := range queueNames {
		themes = append(themes, theme)
	}
	randomTheme := themes[rand.Intn(len(themes))]

	poem, err := p.queryPoemByTheme(randomTheme)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to fetch poem with theme " + randomTheme})
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
		request.Theme = "random"
	}

	if !validateTheme(request.Theme) {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid theme"})
		return
	}

	err := p.publishToRabbitMQ(request)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to queue sentence"})
		return
	}

	c.JSON(http.StatusOK, gin.H{"msg": "Sentence queued for theme: " + request.Theme})
}

func validateTheme(theme string) bool {
	_, exists := queueNames[theme]
	return exists
}

func (p *Publisher) queryPoemByTheme(theme string) (*Poem, error) {
	query := fmt.Sprintf("SELECT Content, AuthorIDs FROM %s ORDER BY TimeStamp DESC LIMIT 1", theme)
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

	return &Poem{
		Authors: authors,
		Content: content,
		Theme:   theme,
	}, nil
}

func (p *Publisher) publishToRabbitMQ(sentence Sentence) error {
	queueName, exists := queueNames[sentence.Theme]
	if !exists {
		return fmt.Errorf("no queue found for theme: %s", sentence.Theme)
	}

	messageBody, err := json.Marshal(sentence)
	if err != nil {
		return err
	}

	err = p.rabbitCh.Publish(
		"",        // exchange
		queueName, // routing key
		false,     // mandatory
		false,     // immediate
		amqp.Publishing{
			ContentType: "application/json",
			Body:        messageBody,
		},
	)
	return err
}
