import org.apache.commons.dbcp2.BasicDataSource;

public class DBCPDataSource {
    private static BasicDataSource dataSource;

    // get values from environment variables
    private static final String HOST_NAME = System.getProperty("PostgreSQL_IP_ADDRESS", System.getenv("PostgreSQL_IP_ADDRESS"));
    private static final String PORT = System.getProperty("PostgreSQL_PORT", System.getenv("PostgreSQL_PORT"));
    private static final String DATABASE = "postgres";
    private static final String USERNAME = System.getProperty("DB_USERNAME", System.getenv("DB_USERNAME"));
    private static final String PASSWORD = System.getProperty("DB_PASSWORD", System.getenv("DB_PASSWORD"));

    static {
        dataSource = new BasicDataSource();
        try {
            // Use the PostgreSQL driver
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        // PostgreSQL JDBC URL format: jdbc:postgresql://host:port/database
        String url = String.format("jdbc:postgresql://%s:%s/%s", HOST_NAME, PORT, DATABASE);
        dataSource.setUrl(url);
        dataSource.setUsername(USERNAME);
        dataSource.setPassword(PASSWORD);
        dataSource.setInitialSize(10);
        dataSource.setMaxTotal(60);
    }

    public static BasicDataSource getDataSource() {
        return dataSource;
    }
}