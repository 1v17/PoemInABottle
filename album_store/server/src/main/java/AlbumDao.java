import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.commons.dbcp2.BasicDataSource;

public class AlbumDao {
    private static BasicDataSource dataSource;

    public AlbumDao() {
        dataSource = DBCPDataSource.getDataSource();
        // create table if it doesn't exist
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "CREATE TABLE IF NOT EXISTS albums (album_id INT, title VARCHAR(255), artist VARCHAR(255), year INT, cover BYTEA)"
             )) {
            statement.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // create, update, and get operations for Album object
    public void createAlbum(Album album) throws SQLException {
        String query = "INSERT INTO albums (album_id, title, artist, year, cover) VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setInt(1, album.getAlbumId());
            statement.setString(2, album.getTitle());
            statement.setString(3, album.getArtist());
            statement.setInt(4, album.getYear());
            statement.setBytes(5, album.getCover());
            statement.executeUpdate();
        }
    }

    public void updateAlbum(Album album) throws SQLException {
        String query = "UPDATE albums SET title = ?, artist = ?, year = ?, cover = ? WHERE album_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, album.getTitle());
            statement.setString(2, album.getArtist());
            statement.setInt(3, album.getYear());
            statement.setInt(4, album.getAlbumId());
            statement.setBytes(5, album.getCover());
            statement.executeUpdate();
        }
    }

    public Album getAlbum(int id) throws SQLException {
        String query = "SELECT * FROM albums WHERE album_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setInt(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return new Album(
                        resultSet.getInt("album_id"),
                        resultSet.getString("title"),
                        resultSet.getString("artist"),
                        resultSet.getInt("year"),
                        resultSet.getBytes("cover")
                    );
                }
            }
        }
        return null;
    }
}
