import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public class HistorySetup {
    public static void main(String[] args) {
        String dbUrl = "jdbc:sqlite:nba_secret_weapon.db";
        try (Connection conn = DriverManager.getConnection(dbUrl); Statement stmt = conn.createStatement()) {
            // Tabelle für abgeschlossene Vorhersagen
            stmt.execute("CREATE TABLE IF NOT EXISTS prediction_history (" +
                    "game_id TEXT PRIMARY KEY, " +
                    "home_team TEXT, " +
                    "away_team TEXT, " +
                    "predicted_winner TEXT, " +
                    "actual_winner TEXT, " +
                    "is_correct INTEGER DEFAULT 0)");
            System.out.println("✅ Historie-Tabelle ist bereit!");
        } catch (Exception e) { e.printStackTrace(); }
    }
}