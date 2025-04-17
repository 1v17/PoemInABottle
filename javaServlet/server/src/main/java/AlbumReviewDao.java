import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.commons.dbcp2.BasicDataSource;

public class AlbumReviewDao {
    private static BasicDataSource dataSource;

    public AlbumReviewDao() {
        dataSource = DBCPDataSource.getDataSource();
        // create table if it doesn't exist
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "CREATE TABLE IF NOT EXISTS album_reviews (album_id INT, likes INT, dislikes INT)"
             )) {
            statement.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // create, update, and get operations for AlbumReview object
    public void createAlbumReview(AlbumReview albumReview) throws SQLException {
        String query = "INSERT INTO album_reviews (album_id, likes, dislikes) VALUES (?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setInt(1, albumReview.getAlbumId());
            statement.setInt(2, albumReview.getLikes());
            statement.setInt(3, albumReview.getDislikes());
            statement.executeUpdate();
        }
    }

    public void updateAlbumReview(AlbumReview albumReview) throws SQLException {
        String query = "UPDATE album_reviews SET likes = ?, dislikes = ? WHERE album_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setInt(1, albumReview.getLikes());
            statement.setInt(2, albumReview.getDislikes());
            statement.setInt(3, albumReview.getAlbumId());
            statement.executeUpdate();
        }
    }

    public AlbumReview getAlbumReview(int albumId) throws SQLException {
        String query = "SELECT * FROM album_reviews WHERE album_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setInt(1, albumId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return new AlbumReview(
                        resultSet.getInt("album_id"),
                        resultSet.getInt("likes"),
                        resultSet.getInt("dislikes")
                    );
                }
            }
        }
        return null;
    }
}
