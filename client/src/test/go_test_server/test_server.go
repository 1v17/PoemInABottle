package main

import (
	"log"
	"net/http"

	"github.com/gin-gonic/gin"
)

const (
	TIME_OUT_SEC = 5
	FIXED_POEM   = `Let me not to the marriage of true minds
Admit impediments, love is not love
Which alters when it alteration finds,
Or bends with the remover to remove.
O no, it is an ever-fixed mark
That looks on tempests and is never shaken;
It is the star to every wand'ring bark,
Whose worth's unknown, although his height be taken.
Love's not Time's fool, though rosy lips and cheeks
Within his bending sickle's compass come,
Love alters not with his brief hours and weeks,
But bears it out even to the edge of doom:
  If this be error and upon me proved,
  I never writ, nor no man ever loved.`
	WORKER_COUNT  = 500
	REQUEST_QUEUE = 1000
)

var (
	validThemes = map[string]struct{}{
		"Love":   {},
		"Death":  {},
		"Nature": {},
		"Beauty": {},
		"Random": {},
	}
	// sentenceQueue = make(chan Sentence, REQUEST_QUEUE)
	// wg            sync.WaitGroup
)

type Sentence struct {
	Author  int    `json:"author"`
	Content string `json:"content"`
	Theme   string `json:"theme"`
}

func main() {
	// Start worker pool
	// startWorkerPool()

	r := gin.Default()
	setupRouter(r)
	log.Println("Server started on :8080")
	r.Run(":8080")
}

func setupRouter(r *gin.Engine) {
	r.GET("/poem/:theme", getPoemByTheme)
	r.GET("/poem", getPoem)
	r.POST("/sentence", postSentence)
}

func getPoemByTheme(c *gin.Context) {
	theme := c.Param("theme")
	if !validateTheme(theme) {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid theme"})
		return
	}

	// Simulate processing time
	// time.Sleep(time.Second * 1)

	// Send the response directly (no goroutine)
	c.JSON(http.StatusOK, gin.H{"poem": FIXED_POEM})
}

func getPoem(c *gin.Context) {
	// Simulate processing time
	// time.Sleep(time.Second * 1)

	// Send the response directly (no goroutine)
	c.JSON(http.StatusOK, gin.H{"poem": FIXED_POEM})
}

func postSentence(c *gin.Context) {
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

	// Enqueue the sentence for background processing
	// select {
	// case sentenceQueue <- request:
	c.JSON(http.StatusOK, gin.H{"msg": "Sentence queued for theme: " + request.Theme})
	// default:
	// 	c.JSON(http.StatusServiceUnavailable, gin.H{"error": "Server too busy, try again later"})
	// }
}

// func startWorkerPool() {
// 	for range make([]struct{}, WORKER_COUNT) {
// 		wg.Add(1)
// 		go worker()
// 	}
// }

// func worker() {
// 	defer wg.Done()
// 	for sentence := range sentenceQueue {
// 		processSentence(sentence)
// 	}
// }

// func processSentence(sentence Sentence) {
// 	_, cancel := context.WithTimeout(context.Background(), TIME_OUT_SEC*time.Second)
// 	defer cancel()

// 	// Simulate processing time
// 	// time.Sleep(time.Second * 1)

// 	// log.Printf("Processed sentence from Author %d under theme: %s\n",
// 	//	 sentence.Author, sentence.Theme)
// }

func validateTheme(theme string) bool {
	_, exists := validThemes[theme]
	return exists
}
