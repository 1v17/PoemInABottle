public class AlbumReview {
    private int albumId;
    private int likes;
    private int dislikes;

    public AlbumReview(int albumId, int likes, int dislikes) {
        this.albumId = albumId;
        this.likes = likes;
        this.dislikes = dislikes;
    }

    public int getAlbumId() {
        return albumId;
    }

    public int getLikes() {
        return likes;
    }

    public int getDislikes() {
        return dislikes;
    }

    public void setLikes(int likes) {
        this.likes = likes;
    }

    public void setDislikes(int dislikes) {
        this.dislikes = dislikes;
    }

    public String toString() {
        return "AlbumReview{" +
            "likes=" + likes +
            ", dislikes=" + dislikes +
            '}';
    }
}
