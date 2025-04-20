import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.ItemCollection;
import com.amazonaws.services.dynamodbv2.document.QueryOutcome;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.KeyType;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.ResourceNotFoundException;
import com.amazonaws.services.lambda.runtime.Context;

public class SentenceDao {
    public static final String TABLE_NAME = "sentences";
    public static final List<String> VALID_THEMES = Arrays.asList("random", "love", "death", "nature", "beauty");
    
    private final AmazonDynamoDB client;
    private final DynamoDB dynamoDB;
    private final Table sentencesTable;

    public SentenceDao() {
        this.client = DBCPDataSource.getClient();
        this.dynamoDB = DBCPDataSource.getDynamoDB();
        
        // Create table if it doesn't exist
        ensureTableExists();
        this.sentencesTable = dynamoDB.getTable(TABLE_NAME);
    }
    
    private void ensureTableExists() {
        try {
            client.describeTable(TABLE_NAME);
            // Table exists, no need to create
        } catch (ResourceNotFoundException e) {
            // Table doesn't exist, create it
            CreateTableRequest createTableRequest = new CreateTableRequest()
                .withTableName(TABLE_NAME)
                .withKeySchema(
                    new KeySchemaElement("theme", KeyType.HASH),  // Partition key
                    new KeySchemaElement("timestamp", KeyType.RANGE)  // Sort key
                )
                .withAttributeDefinitions(
                    new AttributeDefinition("theme", "S"),
                    new AttributeDefinition("timestamp", "N")
                )
                .withProvisionedThroughput(new ProvisionedThroughput(5L, 5L));
            
            client.createTable(createTableRequest);
            
            // Wait for table to be active
            try {
                System.out.println("Waiting for table to be created...");
                Table table = dynamoDB.getTable(TABLE_NAME);
                table.waitForActive();
                System.out.println("Table created successfully!");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                System.err.println("Table creation was interrupted: " + ex.getMessage());
            }
        }
    }
    
    /**
     * Store a sentence in DynamoDB
     * 
     * @param author The author ID
     * @param content The sentence content
     * @param theme The sentence theme
     * @return The timestamp when the sentence was stored
     */
    public long storeSentence(int author, String content, String theme, Context context) {
        theme = validateTheme(theme);
        long timestamp = Instant.now().getEpochSecond();
        
        Item item = new Item()
            .withPrimaryKey("theme", theme, "timestamp", timestamp)
            .withInt("author", author)
            .withString("content", content);
        
        sentencesTable.putItem(item);
        if (context != null) {
            context.getLogger().log("Successfully stored sentence with theme: " + theme);
        }
        
        return timestamp;
    }
    
    /**
     * Get the oldest n sentences for a specific theme
     * 
     * @param theme The theme to query
     * @param n The number of sentences to retrieve
     * @return List of DynamoDB Items representing sentences
     */
    public List<Item> getOldestSentencesByTheme(String theme, int n, Context context) {
        theme = validateTheme(theme);
        
        QuerySpec querySpec = new QuerySpec()
            .withKeyConditionExpression("theme = :theme")
            .withValueMap(new ValueMap().withString(":theme", theme))
            .withScanIndexForward(true) // Ascending order by timestamp (oldest first)
            .withMaxResultSize(n);
        
        List<Item> items = new ArrayList<>();
        try {
            ItemCollection<QueryOutcome> outcome = sentencesTable.query(querySpec);
            outcome.forEach(items::add);
            if (context != null) {
                context.getLogger().log("Retrieved " + items.size() + " sentences for theme: " + theme);
            }
        } catch (Exception e) {
            if (context != null) {
                context.getLogger().log("Error querying sentences: " + e.getMessage());
            }
        }
        
        return items;
    }
    
    /**
     * Delete a batch of sentences from DynamoDB
     * 
     * @param items List of Items to delete
     * @return true if all deletions succeeded, false otherwise
     */
    public boolean deleteSentences(List<Item> items, Context context) {
        boolean allSuccessful = true;
        
        for (Item item : items) {
            try {
                sentencesTable.deleteItem("theme", item.getString("theme"), "timestamp", item.getNumber("timestamp"));
                if (context != null) {
                    context.getLogger().log("Deleted sentence: theme=" + item.getString("theme") + 
                                          ", timestamp=" + item.getNumber("timestamp"));
                }
            } catch (Exception e) {
                if (context != null) {
                    context.getLogger().log("Failed to delete sentence: " + e.getMessage());
                }
                allSuccessful = false;
            }
        }
        
        return allSuccessful;
    }
    
    /**
     * Validate and normalize a theme string
     * 
     * @param theme The theme to validate
     * @return A validated and normalized theme string
     */
    public String validateTheme(String theme) {
        if (theme == null || theme.trim().isEmpty()) {
            return "random";
        }
        
        theme = theme.toLowerCase().trim();
        
        if (!VALID_THEMES.contains(theme)) {
            return "random";
        }
        
        return theme;
    }
}