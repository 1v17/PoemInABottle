# Define the base URL
$baseUrl = "http://localhost:8080"

# Test the /poem/:theme endpoint
$themes = @("Love", "Death", "Nature", "Beauty", "Random", "InvalidTheme")
foreach ($theme in $themes) {
    $response = Invoke-RestMethod -Uri "$baseUrl/poem/$theme" -Method Get
    Write-Output "GET /poem/$theme"
    Write-Output $response
    Write-Output ""
}

# Test the /poem endpoint
$response = Invoke-RestMethod -Uri "$baseUrl/poem" -Method Get
Write-Output "GET /poem"
Write-Output $response
Write-Output ""

# Test the /sentence endpoint with various payloads
$payloads = @(
    @{ author = 1; content = "This is a line of a poem."; theme = "Love" },
    @{ author = 2; content = "Another line of a poem."; theme = "InvalidTheme" },
    @{ author = 3; content = "Yet another line of a poem." },
    @{ author = 0; content = "Invalid author id."; theme = "Nature" },
    @{ author = 4; content = ""; theme = "Beauty" }
)

foreach ($payload in $payloads) {
    $jsonPayload = $payload | ConvertTo-Json
    $response = Invoke-RestMethod -Uri "$baseUrl/sentence" -Method Post -Body $jsonPayload -ContentType "application/json"
    Write-Output "POST /sentence"
    Write-Output $jsonPayload
    Write-Output $response
    Write-Output ""
}
