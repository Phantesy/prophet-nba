import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public class SpursFixer {
    public static void main(String[] args) {
        // Pfad zu deiner bestehenden Datenbank
        String url = "jdbc:sqlite:nba_secret_weapon.db";

        String sql = "UPDATE players SET team_abbr = 'sas' WHERE team_abbr = 'sa'";

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            System.out.println("⏳ Starte Korrektur der Spurs-Kürzel...");

            int rowsAffected = stmt.executeUpdate(sql);

            if (rowsAffected > 0) {
                System.out.println("✅ Update erfolgreich! " + rowsAffected + " Spieler wurden von 'sa' auf 'sas' geändert.");
            } else {
                System.out.println("ℹ️ Keine Spieler mit dem Kürzel 'sa' gefunden. Eventuell sind sie bereits korrekt.");
            }

        } catch (Exception e) {
            System.err.println("❌ Fehler beim Datenbank-Update:");
            e.printStackTrace();
        }
    }
}