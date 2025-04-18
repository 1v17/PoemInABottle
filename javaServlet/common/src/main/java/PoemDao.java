import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.spec.GetItemSpec;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.KeyType;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.ResourceNotFoundException;

public class PoemDao {
    private static final String TABLE_NAME = "poems";
    private static final List<String> VALID_THEMES = Arrays.asList("Random", "Love", "Death", "Nature", "Beauty");
    
    private final AmazonDynamoDB client;
    private final DynamoDB dynamoDB;
    private final Table poemsTable;

    public PoemDao() {
        this.client = DBCPDataSource.getClient();
        this.dynamoDB = DBCPDataSource.getDynamoDB();
        
        // Create table if it doesn't exist
        ensureTableExists();
        this.poemsTable = dynamoDB.getTable(TABLE_NAME);
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

    // Create a new poem
    public void createPoem(Poem poem) {
        // Validate theme
        String theme = validateTheme(poem.getTheme());
        
        // Generate timestamp
        long timestamp = Instant.now().getEpochSecond();
        
        Item item = new Item()
            .withPrimaryKey("theme", theme, "timestamp", timestamp)
            .withList("authors", Arrays.stream(poem.getAuthors()).boxed().toArray())
            .withString("contents", poem.getContents())
            .withString("theme", theme);
        
        poemsTable.putItem(item);
    }

    // Get a poem by theme and timestamp
    public Poem getPoem(String theme, long timestamp) {
        theme = validateTheme(theme);
        
        GetItemSpec spec = new GetItemSpec()
            .withPrimaryKey("theme", theme, "timestamp", timestamp);
        
        Item item = poemsTable.getItem(spec);
        if (item == null) {
            return null;
        }
        
        return poemFromItem(item);
    }
    
    // Get a random poem by theme
    public Poem getRandomPoemByTheme(String theme) {
        theme = validateTheme(theme);
        
        QuerySpec querySpec = new QuerySpec()
            .withKeyConditionExpression("theme = :theme")
            .withValueMap(new ValueMap().withString(":theme", theme));
        
        List<Item> items = new ArrayList<>();
        poemsTable.query(querySpec).forEach(items::add);
        
        if (items.isEmpty()) {
            return null;
        }
        
        // Select a random item
        int randomIndex = (int) (Math.random() * items.size());
        Item item = items.get(randomIndex);
        
        return poemFromItem(item);
    }
    
    // Convert DynamoDB Item to Poem object
    private Poem poemFromItem(Item item) {
        List<Number> authorsList = item.getList("authors");
        int[] authors = new int[authorsList.size()];
        for (int i = 0; i < authorsList.size(); i++) {
            authors[i] = authorsList.get(i).intValue();
        }
        
        String contents = item.getString("contents");
        String theme = item.getString("theme");
        
        return new Poem(authors, contents, theme);
    }
    
    // Validate and normalize theme
    private String validateTheme(String theme) {
        if (theme == null || theme.trim().isEmpty()) {
            return "Random";
        }
        
        // Capitalize first letter for consistency
        theme = theme.substring(0, 1).toUpperCase() + theme.substring(1).toLowerCase();
        
        if (!VALID_THEMES.contains(theme)) {
            return "Random";
        }
        
        return theme;
    }
}
