public class Poem {
    final private int[] authors;
    final private String contents;
    private String theme;

    public Poem(int[] authors, String content, String theme) {
        this.authors = authors;
        this.contents = content;
        this.theme = theme;
    }

    public int[] getAuthors() {
        return authors;
    }
    public String getContents() {
        return contents;
    }
    public String getTheme() {
        return theme;
    }
    public void setTheme(String theme) {
        this.theme = theme;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Poem{");
        sb.append("authors=[");
        for (int i = 0; i < authors.length; i++) {
            sb.append(authors[i]);
            if (i < authors.length - 1) {
                sb.append(", ");
            }
        }
        sb.append("], content='").append(contents).append('\'');
        sb.append(", theme='").append(theme).append('\'');
        sb.append('}');
        return sb.toString();
    }
}
