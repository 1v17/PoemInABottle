import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;

public class DBCPDataSource {
    private static final AmazonDynamoDB client;
    private static final DynamoDB dynamoDB;

    // Get AWS region from environment variable or use a default
    private static final String AWS_REGION = System.getProperty("AWS_REGION", System.getenv("AWS_REGION") != null ? 
                                              System.getenv("AWS_REGION") : Regions.US_WEST_2.getName());

    static {
        // Build the AmazonDynamoDB client
        client = AmazonDynamoDBClientBuilder.standard()
                .withRegion(AWS_REGION)
                .withCredentials(new DefaultAWSCredentialsProviderChain())
                .build();
                
        // Create a DynamoDB object from the client
        dynamoDB = new DynamoDB(client);
    }

    /**
     * Returns the DynamoDB client.
     * 
     * @return An AmazonDynamoDB client
     */
    public static AmazonDynamoDB getClient() {
        return client;
    }
    
    /**
     * Returns the DynamoDB document interface.
     * 
     * @return A DynamoDB object for document operations
     */
    public static DynamoDB getDynamoDB() {
        return dynamoDB;
    }
}