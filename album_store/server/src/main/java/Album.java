public class Album {
    private int albumId;
    private String artist;
    private String title;
    private int year;
    private byte[] cover;
    
    // Constructor with all fields
    public Album(int albumId, String artist, String title, int year, byte[] cover) {
        this.albumId = albumId;
        this.artist = artist;
        this.title = title;
        this.year = year;
        this.cover = cover;
    }
    
    // Getters and setters
    public int getAlbumId() {
        return albumId;
    }
    
    public String getArtist() {
        return artist;
    }
    
    public void setArtist(String artist) {
        this.artist = artist;
    }
    
    public String getTitle() {
        return title;
    }
    
    public void setTitle(String title) {
        this.title = title;
    }
    
    public int getYear() {
        return year;
    }
    
    public void setYear(int year) {
        this.year = year;
    }

    public byte[] getCover() {
        return cover;
    }

    public void setCover(byte[] cover) {
        this.cover = cover;
    }
    
    @Override
    public String toString() {
        return "Album{" +
                "artist='" + artist + '\'' +
                ", title='" + title + '\'' +
                ", year=" + year +
                '}';
    }
}