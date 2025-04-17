package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"math/rand"
	"os"
	"slices"
	"strings"

	_ "github.com/go-sql-driver/mysql"
	"github.com/joho/godotenv"
	"github.com/streadway/amqp"
)

const (
	PREFETCH_COUNT = 10
	MIN_LINE_COUNT = 3
	MAX_LINE_COUNT = 100
	QUEUE_PREFIX   = "poem_"
	QUEUE_LOVE     = QUEUE_PREFIX + "love"
	QUEUE_DEATH    = QUEUE_PREFIX + "death"
	QUEUE_NATURE   = QUEUE_PREFIX + "nature"
	QUEUE_BEAUTY   = QUEUE_PREFIX + "beauty"
	QUEUE_RANDOM   = QUEUE_PREFIX + "random"
)

var (
	queueNames = map[string]string{
		"Love":   QUEUE_LOVE,
		"Death":  QUEUE_DEATH,
		"Nature": QUEUE_NATURE,
		"Beauty": QUEUE_BEAUTY,
		"Random": QUEUE_RANDOM,
	}

	tableNames = map[string]string{
		"Love":   "Love",
		"Death":  "Death",
		"Nature": "Nature",
		"Beauty": "Beauty",
		"Random": "Random",
	}
)

// Consumer holds database and RabbitMQ connections
type Consumer struct {
	db     *sql.DB
	rabbit *amqp.Connection
}

// Sentence represents a single line of poetry from the queue
type Sentence struct {
	Author  int    `json:"author"`
	Content string `json:"content"`
	Theme   string `json:"theme"`
}

// PoemAggregator collects lines until reaching the target count
type PoemAggregator struct {
	theme      string
	lines      []string
	authorIDs  []int
	targetLine int
	lineCount  int
}

func main() {
	err := godotenv.Load()
	if err != nil {
		log.Printf("Warning: Error loading .env file: %v", err)
	}

	// Initialize the consumer
	consumer := &Consumer{}

	// Initialize database
	if err := consumer.initDB(); err != nil {
		log.Fatalf("Failed to initialize database: %v", err)
	}
	log.Println("Successfully connected to database")
	defer consumer.db.Close()

	// Initialize connection to RabbitMQ
	if err := consumer.initRabbitMQ(); err != nil {
		log.Fatalf("Failed to initialize RabbitMQ: %v", err)
	}
	log.Println("Successfully connected to RabbitMQ")
	defer consumer.rabbit.Close()

	// Create tables if they don't exist and start listening for messages
	consumer.createTablesIfNotExists()
	consumer.startListening()
	fmt.Println("Listening for poem lines...")

	// Block forever
	select {}
}

// initDB initializes the database connection
func (c *Consumer) initDB() error {
	// Load environment variables
	if err := godotenv.Load(); err != nil {
		log.Println("Warning: .env file not found, using environment variables")
	}

	dbUser := os.Getenv("DB_USER")
	dbPassword := os.Getenv("DB_PASSWORD")
	dbPort := os.Getenv("DB_PORT")
	dbHost := os.Getenv("DB_HOST")
	dbName := os.Getenv("DB_NAME")

	if dbUser == "" || dbPassword == "" || dbPort == "" || dbHost == "" || dbName == "" {
		return fmt.Errorf("one or more required environment variables are missing: DB_USER, DB_PASSWORD, DB_PORT, DB_HOST, DB_NAME")
	}

	dsn := fmt.Sprintf("%s:%s@tcp(%s:%s)/%s", dbUser, dbPassword, dbHost, dbPort, dbName)

	var err error
	c.db, err = sql.Open("mysql", dsn)
	if err != nil {
		return fmt.Errorf("failed to connect to database: %v", err)
	}

	if err = c.db.Ping(); err != nil {
		return fmt.Errorf("failed to ping database: %v", err)
	}
	return nil
}

func (c *Consumer) initRabbitMQ() error {
	username := os.Getenv("RABBITMQ_USER")
	password := os.Getenv("RABBITMQ_PASSWORD")
	host := os.Getenv("RABBITMQ_HOST")
	port := os.Getenv("RABBITMQ_PORT")

	if username == "" || password == "" || host == "" || port == "" {
		return fmt.Errorf("one or more required environment variables are missing: RABBITMQ_USER, RABBITMQ_PASSWORD, RABBITMQ_HOST, RABBITMQ_PORT")
	}

	log.Printf("Connecting to RabbitMQ at %s:%s with username %s", host, port, username)

	var err error
	c.rabbit, err = amqp.Dial(fmt.Sprintf("amqp://%s:%s@%s:%s/", username, password, host, port))
	if err != nil {
		return fmt.Errorf("failed to connect to RabbitMQ: %v", err)
	}
	return nil
}

// createTablesIfNotExists creates the required tables for each theme if they don't exist
func (c *Consumer) createTablesIfNotExists() {
	for _, tableName := range tableNames {
		createTable := fmt.Sprintf(`
			CREATE TABLE IF NOT EXISTS %s (
				PoemID INT AUTO_INCREMENT PRIMARY KEY,
				Content TEXT NOT NULL,
				TimeStamp DATETIME DEFAULT CURRENT_TIMESTAMP,
				AuthorIDs JSON NOT NULL
			)`, tableName)

		_, err := c.db.Exec(createTable)
		if err != nil {
			log.Fatalf("Failed to create %s table: %v", tableName, err)
		}
		log.Printf("Successfully created or verified %s table", tableName)
	}
}

// startListening begins consuming messages from RabbitMQ
func (c *Consumer) startListening() {
	channel, err := c.rabbit.Channel()
	if err != nil {
		log.Fatalf("Failed to open a channel: %v", err)
	}

	err = channel.Qos(PREFETCH_COUNT, 0, false)
	if err != nil {
		log.Fatalf("Failed to set QoS: %v", err)
	}

	// Create a separate queue for each theme
	for theme, queueName := range queueNames {
		// Declare queue for this theme
		queue, err := channel.QueueDeclare(
			queueName, // queue name
			true,      // durable
			false,     // auto-delete
			false,     // exclusive
			false,     // no-wait
			nil,       // arguments
		)
		if err != nil {
			log.Fatalf("Failed to declare queue %s: %v", queueName, err)
		}
		log.Printf("Declared queue: %s", queue.Name)

		// Set up consumer for this queue
		poemLines, err := channel.Consume(
			queue.Name, // queue name
			"",         // consumer tag
			false,      // auto-ack
			false,      // exclusive
			false,      // no-local
			false,      // no-wait
			nil,        // arguments
		)
		if err != nil {
			log.Fatalf("Failed to register consumer for queue %s: %v", queueName, err)
		}

		// Create an aggregator for this theme
		aggregator := &PoemAggregator{
			theme:      theme,
			lines:      make([]string, 0),
			authorIDs:  make([]int, 0),
			targetLine: rand.Intn(MAX_LINE_COUNT-MIN_LINE_COUNT+1) + MIN_LINE_COUNT,
			lineCount:  0,
		}

		// Start processing messages for this theme in a separate goroutine
		go c.processMessagesForTheme(poemLines, theme, aggregator)
		log.Printf("Started consumer for theme: %s with target of %d lines", theme, aggregator.targetLine)
	}
}

// processMessagesForTheme handles message processing for a specific theme
func (c *Consumer) processMessagesForTheme(messages <-chan amqp.Delivery, theme string, aggregator *PoemAggregator) {
	for msg := range messages {
		func() {
			defer func() {
				if r := recover(); r != nil {
					log.Printf("Recovered from panic in message processing for theme %s: %v", theme, r)
					msg.Nack(false, false)
				}
			}()

			var poemLine Sentence
			err := json.Unmarshal(msg.Body, &poemLine)
			if err != nil {
				log.Printf("Failed to unmarshal poem line for theme %s: %v", theme, err)
				msg.Nack(false, false)
				return
			}

			// Process the line and add to this theme's aggregator
			c.processLine(aggregator, poemLine)
			msg.Ack(false)
		}()
	}
}

// processLine adds a line to the aggregator and saves the poem if target is reached
func (c *Consumer) processLine(aggregator *PoemAggregator, poemLine Sentence) {
	// Add the line to the aggregator
	aggregator.lines = append(aggregator.lines, poemLine.Content)

	// Add the author ID if not already present
	if !contains(aggregator.authorIDs, poemLine.Author) {
		aggregator.authorIDs = append(aggregator.authorIDs, poemLine.Author)
	}

	aggregator.lineCount++

	log.Printf("Added line to theme %s (%d/%d): %s",
		aggregator.theme, aggregator.lineCount, aggregator.targetLine, poemLine.Content)

	// If we've reached the target, save the poem and reset the aggregator
	if aggregator.lineCount >= aggregator.targetLine {
		c.savePoem(aggregator)

		// Reset the aggregator with a new target line count
		aggregator.lines = make([]string, 0)
		aggregator.authorIDs = make([]int, 0)
		aggregator.targetLine = rand.Intn(MAX_LINE_COUNT-MIN_LINE_COUNT+1) + MIN_LINE_COUNT
		aggregator.lineCount = 0

		log.Printf("Reset aggregator for theme %s with new target: %d lines",
			aggregator.theme, aggregator.targetLine)
	}
}

// contains checks if an int slice contains a value
func contains(slice []int, val int) bool {
	return slices.Contains(slice, val)
}

// savePoem stores a completed poem in the database
func (c *Consumer) savePoem(aggregator *PoemAggregator) {
	// Join the lines with newlines to form a complete poem
	poemContent := strings.Join(aggregator.lines, "\n")

	// Marshal the author IDs to JSON
	authorIDsJSON, err := json.Marshal(aggregator.authorIDs)
	if err != nil {
		log.Printf("Failed to marshal author IDs: %v", err)
		return
	}

	query := fmt.Sprintf("INSERT INTO %s (Content, TimeStamp, AuthorIDs) VALUES (?, NOW(), ?)",
		aggregator.theme)

	result, err := c.db.Exec(query, poemContent, authorIDsJSON)
	if err != nil {
		log.Printf("Failed to save poem to database: %v", err)
		return
	}

	poemID, _ := result.LastInsertId()
	log.Printf("Saved %s poem #%d with %d lines from %d authors",
		aggregator.theme, poemID, aggregator.lineCount, len(aggregator.authorIDs))
}
