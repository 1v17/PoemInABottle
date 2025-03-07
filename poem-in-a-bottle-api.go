package main

import (
    "encoding/json"
    "net/http"
    "sync"
	"strings"

    "github.com/gorilla/mux"
)

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

type sentence struct {
    Author  int    `json:"author"`
    Content string `json:"content"`
    Theme   string `json:"theme"`
}

// poemStore holds poems for each theme
var (
    poemStore = map[string]*poem{}
    mu        sync.Mutex
)

func main() {
    r := mux.NewRouter()
    r.HandleFunc("/poem/{theme}", getPoem).Methods("GET")
    r.HandleFunc("/poem", getPoem).Methods("GET")
    r.HandleFunc("/sentence/{theme}", postSentence).Methods("POST")
    r.HandleFunc("/sentence", postSentence).Methods("POST")

    http.ListenAndServe(":8080", r)
}

func getPoem(w http.ResponseWriter, r *http.Request) {
    theme := mux.Vars(r)["theme"]
    if theme == "" {
        theme = "Random"
    }

    mu.Lock()
    p, ok := poemStore[theme]
    mu.Unlock()

    if !ok || len(p.Contents) == 0 {
        http.Error(w, "No poem found for theme: "+theme, http.StatusNotFound)
        return
    }

    w.Header().Set("Content-Type", "application/json")
    json.NewEncoder(w).Encode(p.stringifyPoem())
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

    // Create or get poem for this theme
    mu.Lock()
    p, exists := poemStore[theme]
    if !exists {
        p = &poem{Theme: theme}
        poemStore[theme] = p
    }
    mu.Unlock()

    // Use a goroutine to safely add the sentence
    go func(sent sentence) {
        mu.Lock()
        p.addSentence(sent)
        mu.Unlock()
    }(s)

    w.WriteHeader(http.StatusOK)
    w.Write([]byte("Sentence queued for theme: " + theme))
}
