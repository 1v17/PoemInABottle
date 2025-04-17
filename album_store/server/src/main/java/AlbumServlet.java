import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.SQLException;
import java.util.stream.Collectors;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

@MultipartConfig
@WebServlet(name = "AlbumServlet", urlPatterns = { "/albumservlet/*" }, asyncSupported = true)
public class AlbumServlet extends HttpServlet {
  @Override
  public void init() throws ServletException {
    super.init();
    // Initialize RabbitMQ connection and channel
    try {
      ConnectionFactory factory = new ConnectionFactory();
      factory.setHost("localhost");
      Connection connection = factory.newConnection();
      Channel channel = connection.createChannel();
      channel.queueDeclare("albumQueue", false, false, false, null);
      getServletContext().setAttribute("rabbitmqConnection", connection);
      getServletContext().setAttribute("rabbitmqChannel", channel);
    } catch (Exception e) {
      throw new ServletException("Failed to initialize RabbitMQ connection", e);
    }
  }

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException {
    String pathInfo = request.getPathInfo();
    if (pathInfo.matches("^/album/\\d+$")) {
      try {
        getAlbumInfo(pathInfo, response);
        return;
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
    response.setContentType("application/json");
    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write("{\"message\": \"Invalid input\"}");
  }

  private void getAlbumInfo(String pathInfo, HttpServletResponse response)
      throws IOException, SQLException {
    // Extract albumId from the URL.
    String[] pathParts = pathInfo.split("/");
    int albumId = Integer.parseInt(pathParts[2]);

    // Create an instance of album DAO and retrieve the album info.
    AlbumDao dao = new AlbumDao();
    Album album = dao.getAlbum(albumId);

    if (album == null) {
      response.setContentType("application/json");
      response.setStatus(HttpServletResponse.SC_NOT_FOUND);
      response.setCharacterEncoding("UTF-8");
      response.getWriter().write("{\"message\": \"Data not found\"}");
      return;
    }

    AlbumReviewDao reviewDao = new AlbumReviewDao();
    AlbumReview albumReview = reviewDao.getAlbumReview(albumId);
    if (albumReview == null) {
      albumReview = new AlbumReview(albumId, 0, 0);
    }

    response.setContentType("application/json");
    response.setStatus(HttpServletResponse.SC_OK);
    response.setCharacterEncoding("UTF-8");
    // Return the retrieved album info in JSON format.
    response.getWriter().write("{\"albumId\": "+ albumId + ", \"details\": " + album.toString() + ", \"reviews\": " + albumReview.toString() + "}");
  }

  @Override
  protected void doPost(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException {
    final AsyncContext asyncContext = request.startAsync();
    asyncContext.setTimeout(30000); // 30 second timeout
    
    final String pathInfo = request.getPathInfo();
    
    asyncContext.start(() -> {
      try {
        HttpServletRequest asyncRequest = (HttpServletRequest) asyncContext.getRequest();
        HttpServletResponse asyncResponse = (HttpServletResponse) asyncContext.getResponse();
        
        /**
         * Two paths:
         * 1. POST /albums as multipart/form-data: create an album {artist, title, year}
         * 2. POST /review/like/{albumId} or POST /review/dislike/{albumId}: like or
         * dislike an album
         */
        if (pathInfo.matches("^/album$")) {
          try {
            createAlbum(asyncRequest, asyncResponse);
          } catch (SQLException | ServletException ex) {
            ex.printStackTrace();
            sendErrorResponse(asyncResponse, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Database error");
          }
        } else if (pathInfo.matches("^/review/(like|dislike)/\\d+$")) {
          likeOrDislikeWithRabbitMQ(asyncRequest, asyncResponse);
        } else {
          sendErrorResponse(asyncResponse, HttpServletResponse.SC_BAD_REQUEST, "Invalid input");
        }
      } catch (Exception e) {
        e.printStackTrace();
        try {
          HttpServletResponse asyncResponse = (HttpServletResponse) asyncContext.getResponse();
          sendErrorResponse(asyncResponse, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Server error");
        } catch (IOException ignored) { }
      } finally {
        asyncContext.complete();
      }
    });
  }
  
  // Helper method for error responses
  private void sendErrorResponse(HttpServletResponse response, int status, String message) throws IOException {
    response.setContentType("application/json");
    response.setStatus(status);
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write("{\"message\": \"" + message + "\"}");
  }

  private void createAlbum(HttpServletRequest request, HttpServletResponse response)
      throws IOException, SQLException, ServletException {
    // Get "image" part. Note: an empty file is allowed.
    Part imagePart = request.getPart("image");
    if (imagePart == null) {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      response.getWriter().write("{\"msg\": \"Missing 'image' field\"}");
      return;
    }

    byte[] imageBytes;
    try (InputStream inputStream = imagePart.getInputStream()) {
      imageBytes = inputStream.readAllBytes();
    } catch (IOException e) {
      response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      response.getWriter().write("{\"msg\": \"Error reading image data\"}");
      return;
    }

    // Get "profile" part (as a String, then parse to JSON).
    Part profilePart = request.getPart("profile");
    if (profilePart == null || profilePart.getSize() == 0) {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      response.getWriter().write("{\"msg\": \"Missing 'profile' field\"}");
      return;
    }

    String profileString;
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(profilePart.getInputStream()))) {
      profileString = reader.lines().collect(Collectors.joining("\n"));
    } catch (IOException e) {
      response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      response.getWriter().write("{\"msg\": \"Error reading profile data\"}");
      return;
    }

    JsonObject profileJson = JsonParser.parseString(profileString).getAsJsonObject();
    String artist = profileJson.get("artist").getAsString().trim();
    String title = profileJson.get("title").getAsString().trim();
    int year = profileJson.get("year").getAsInt();
    int albumId = profileJson.get("albumId").getAsInt();

    // Validate required "artist" field
    if (!profileJson.has("artist") || artist.isEmpty()) {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      response.getWriter().write("{\"msg\": \"Missing or invalid 'artist' field in profile\"}");
      return;
    }
    // Validate required "title" field
    if (!profileJson.has("title") || title.isEmpty()) {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      response.getWriter().write("{\"msg\": \"Missing or invalid 'title' field in profile\"}");
      return;
    }
    // Validate required "year" field
    if (!profileJson.has("year") || year <= 0) {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      response.getWriter().write("{\"msg\": \"Missing or invalid 'year' field in profile\"}");
      return;
    }

    // Validate required "albumId" field
    if (!profileJson.has("albumId") || albumId <= 0) {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      response.getWriter().write("{\"msg\": \"Missing or invalid 'albumId' field in profile\"}");
      return;
    }

    AlbumDao dao = new AlbumDao();
    // Save the album to the database
    dao.createAlbum(new Album(albumId, artist, title, year, imageBytes));
    AlbumReviewDao reviewDao = new AlbumReviewDao();
    reviewDao.createAlbumReview(new AlbumReview(albumId, 0, 0));

    // Return a success response.
    response.setContentType("application/json");
    response.setStatus(HttpServletResponse.SC_CREATED);
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write("{\"message\":\"Data created successfully\"}");
  }

  private void likeOrDislikeWithRabbitMQ(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    // Get channel from servlet context
    Channel channel = (Channel) getServletContext().getAttribute("rabbitmqChannel");

    // Extract albumId and action from the path
    String pathInfo = request.getPathInfo();
    String[] pathParts = pathInfo.split("/");
    int albumId = Integer.parseInt(pathParts[3]); // Adjust index based on the URL pattern
    String action = pathParts[2]; // like or dislike

    // Create message in JSON format
    String message = "{\"albumId\": " + albumId + ", \"action\": \"" + action + "\"}";

    // Publish message to RabbitMQ queue
    channel.basicPublish("", "albumQueue", null, message.getBytes());

    // Return a success response
    response.setContentType("application/json");
    response.setStatus(HttpServletResponse.SC_CREATED);
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write("{\"message\":\"Review submitted successfully\"}");
  }
}