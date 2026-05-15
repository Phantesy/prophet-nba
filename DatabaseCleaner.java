import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public class DatabaseCleaner {
    public static void main(String[] args) {
        String url = "jdbc:sqlite:nba_secret_weapon.db";
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            // Löscht alle alten Test-Einträge
            stmt.executeUpdate("DELETE FROM games WHERE game_id = 'GAME_001'");
            stmt.executeUpdate("DELETE FROM odds WHERE game_id = 'GAME_001'");

            System.out.println("✅ Alte Testdaten (Lakers) aus der Datenbank entfernt!");
        } catch (Exception e) {
            System.out.println("❌ Fehler beim Putzen: " + e.getMessage());
        }
    }
}