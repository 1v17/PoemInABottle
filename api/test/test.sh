curl "http://localhost:8080/poem"
curl -X POST "http://localhost:8080/sentence" -H "Content-Type: application/json" -d '{"content":"test sentence","author":1}' 