# Architectural Trade-offs for Cloud-Native Applications: A Case Study of "Poem In A Bottle"

## Executive Summary

This report examines the architectural trade-offs between serverless and traditional server-based deployments for low-data-flow applications in AWS. Using the "Poem In A Bottle" collaborative poetry application as a case study, we analyze **cost efficiency**, **operational overhead**, **scalability characteristics**, and **performance implications** of two distinct implementation approaches. The findings demonstrate that while serverless architectures offer compelling advantages for low-traffic applications with variable workloads, traditional server-based architectures remain competitive for stable, predictable workloads.

## 1. Introduction
Cloud-native application development presents organizations with multiple architectural approaches, each with distinct advantages and limitations. This report focuses on a practical comparison between traditional server-based architecture and serverless architecture within the AWS ecosystem.

"Poem In A Bottle" is a collaborative poetry application where users contribute lines to community poems. This application represents a typical low-to-moderate data flow service with variable traffic patterns, making it an ideal candidate for comparing architectural approaches.

We assume this application will expect 400,000 requests per day, where `POST` request and `GET` requests contribute half and half. The peak hour should be 5 hours in the evening and at night, when people get back home from work. Within the 5 hours the 2/3 of total requests (266,666) are sending to the server.

The primary objectives of this report are to:

- Evaluate the cost implications of each architectural approach
- Assess operational complexity and maintenance requirements
- Analyze scalability characteristics under different load patterns
- Measure performance characteristics across both architectures
- Provide evidence-based recommendations for similar applications

## 2. Architectural Approaches

We offer two distinct architecture implementations, while the poem aggregation strategy remains the same. The service will group lines by themes and limit a random number (from 3 to 14) of lines per poem.

The two implementations are:

### 2.1 EC2 + RabbitMQ + RDS MySQL (Traditional)

   - **API Server**: Written in Go Gin, handles both `GET` and `POST` requests
     - `GET` requests directly query the MySQL database
     - `POST` requests are sent to RabbitMQ queue

   - **Consumer**: Aggregates sentences into poems and writes them to MySQL database
   - **Database**: RDS MySQL stores the completed poems
   - **Deployment**: API, RabbitMQ, and consumer can all be hosted on the same EC2 instance for cost efficiency, or separated for scalability

This architecture follows a more conventional pattern:

- **API Server**: Go-based Gin server handling both `GET` and `POST` requests
- **Message Queue**: RabbitMQ for asynchronous processing
- **Consumer Process**: Long-running application aggregating sentences into poems
- **Database**: RDS MySQL storing completed poems
- **Deployment**: All components can run on a single EC2 instance

### 2.2 Lambda + SQS + DynamoDB (Serverless)

   - **API Handler**: Java-based AWS Lambda, handles both `GET` and `POST` requests

      - `GET` requests directly query DynamoDB, aggregate sentences into poems, and delete used sentences
      - `POST` requests send sentences to SQS queue

   - **Consumer**: Java-based AWS Lambda, processes messages when triggered by SQS and writes sentences to DynamoDB
   - **Database**: DynamoDB stores individual sentences by theme and timestamps before they're used in poems

This architecture embraces a fully serverless approach:

- **API Handler**: Java-based Lambda function handling HTTP endpoints
- **Queue**: Amazon SQS for asynchronous message processing
- **Consumer**: Lambda function triggered by SQS events
- **Database**: DynamoDB storing sentences before aggregation
- **Deployment**: All components are managed services with no server maintenance

## 3. Cost Analysis

To understand the financial implications of each approach, we analyzed AWS cost estimates at three different traffic levels:

| Daily Requests | Lambda+SQS+DynamoDB (Monthly) | 1 EC2+RabbitMQ+RDS (Monthly) | 3 EC2+RabbitMQ+RDS (Monthly) |
| -------------- | ----------------------------- | ---------------------------- | ---------------------------- |
| 30,000         | $2.79                         | $32.67                       | $39.97                       |
| 300,000        | $27.70                        | $32.67                       | $39.97                       |
| 400,000        | $39.84                        | $32.67                       | $39.97                       |


|      |      |      |      |
| ---- | ---- | ---- | ---- |
|      |      |      |      |
|      |      |      |      |
|      |      |      |      |
|      |      |      |      |
|      |      |      |      |
|      |      |      |      |
|      |      |      |      |
|      |      |      |      |
|      |      |      |      |
|      |      |      |      |
|      |      |      |      |
|      |      |      |      |
|      |      |      |      |
|      |      |      |      |
|      |      |      |      |
|      |      |      |      |
|      |      |      |      |
|      |      |      |      |
|      |      |      |      |
|      |      |      |      |
|      |      |      |      |
|      |      |      |      |
|      |      |      |      |
|      |      |      |      |


> Note: The EC2-based solution would likely require scaling beyond a single t2.micro instance at higher traffic level.

## 4. Operational Complexity

## 5. Scalability Analysis

## 6. Performance Considerations

## 7. Conclusions and Recommendations

### 7.1 Conclusions


### 7.2 Recommendations

For "Poem In A Bottle", it's recommended to implement the serverless architecture due to its cost advantages and alignment with low traffic patterns. This approach is particularly well-suited for this specific use case and will minimize expenses during periods of low activity.

For similar applications, serverless is the optimal choice for new projects with unpredictable or growing workloads, while traditional architecture remains better suited for applications requiring consistent performance or specialized runtime environments. Complex applications with mixed workload characteristics often benefit from hybrid approaches that leverage the strengths of both serverless and traditional infrastructures, allowing you to optimize for both cost efficiency and performance requirements.

