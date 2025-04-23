# Architectural Trade-offs for Cloud-Native Applications: A Case Study of "Poem In A Bottle"

## Executive Summary

This report examines the architectural trade-offs between serverless and traditional server-based deployments for low-data-flow applications in AWS. Using the "Poem In A Bottle" collaborative poetry application as a case study, we analyze **cost efficiency**, **operational overhead**, **scalability characteristics**, and **performance implications** of two distinct implementation approaches. The findings demonstrate that while serverless architectures offer compelling advantages for low-traffic applications with variable workloads, traditional server-based architectures remain competitive for stable, predictable workloads.

## 1. Introduction
Cloud-native application development presents organizations with multiple architectural approaches, each with distinct advantages and limitations. This report focuses on a practical comparison between traditional server-based architecture and serverless architecture within the AWS ecosystem.

"Poem In A Bottle" is a collaborative poetry application where users contribute lines to community poems. This application represents a typical low-to-moderate data flow service with variable traffic patterns, making it an ideal candidate for comparing architectural approaches.
The primary objectives of this report are to:

- Evaluate the cost implications of each architectural approach
- Assess operational complexity and maintenance requirements
- Analyze scalability characteristics under different load patterns
- Measure performance characteristics across both architectures
- Provide evidence-based recommendations for similar applications

## 2. Architectural Approaches

We offer two distinct architecture implementations, while the peom aggregation strategy remains the same. The service will group lines by themes and limit a random number (from 3 to 14) of lines per poem.

The two implementations are:

### 2.1 EC2 + RabbitMQ + RDS MySQL (Traditional)

   - **API Server**: Written in Go Gin, handles both GET and POST requests
     - GET requests directly query the MySQL database
     - POST requests are sent to RabbitMQ queue

   - **Consumer**: Aggregates sentences into poems and writes them to MySQL database
   - **Database**: RDS MySQL stores the completed poems
   - **Deployment**: API, RabbitMQ, and consumer can all be hosted on the same EC2 instance for cost efficiency, or separated for scalability

### 2.2 Lambda + SQS + DynamoDB (Serverless)

   - **API Server**: Java-based API hosted on AWS Lambda, handles both GET and POST requests

      - GET requests directly query DynamoDB, aggregate sentences into poems, and delete used sentences
      - POST requests send sentences to SQS queue

   - **Consumer**: Processes messages from SQS and writes sentences to DynamoDB
   - **Database**: DynamoDB stores individual sentences before they're used in poems


## 3. Cost Analysis

## 4. Operational Complexity

## 5. Scalability Analysis

## 6. Performance Considerations

## 7. Conclusions and Recommendations

### 7.1 Conclusions


### 7.2 Recommendations

For "Poem In A Bottle", it's recommanded to implement the serverless architecture due to its cost advantages and alignment with low traffic patterns. This approach is particularly well-suited for this specific use case and will minimize expenses during periods of low activity.

For similar applications, serverless is the optimal choice for new projects with unpredictable or growing workloads, while traditional architecture remains better suited for applications requiring consistent performance or specialized runtime environments. Complex applications with mixed workload characteristics often benefit from hybrid approaches that leverage the strengths of both serverless and traditional infrastructures, allowing you to optimize for both cost efficiency and performance requirements.

## 8. Future Considerations

1. Monitor evolving pricing models for both architectural approaches
2. Consider multi-cloud strategies for risk mitigation
3. Evaluate serverless offerings beyond AWS (Azure, Google Cloud)
4. Explore event-driven architecture patterns regardless of infrastructure choices
5. Balance vendor lock-in concerns against operational advantages
