public class Sentence {
    final private int author;
    final private String content;
    private String theme;
    
    // Constructor with all fields
    public Sentence(int author, String content, String theme) {
        this.author = author;
        this.content = content;
        this.theme = theme;
    }

    // Getters for all and Setters for theme
    public int getAuthor() {
        return author;
    }

    public String getContent() {
        return content;
    }

    public String getTheme() {
        return theme;
    }
    public void setTheme(String theme) {
        this.theme = theme;
    }

    @Override
    public String toString() {
        return "Sentence{" +
                "author=" + author +
                ", content='" + content + '\'' +
                ", theme='" + theme + '\'' +
                '}';
    }
}