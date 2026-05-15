import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public class PowerMatrixSetup {
    public static void main(String[] args) {
        String dbUrl = "jdbc:sqlite:nba_secret_weapon.db";

        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement()) {

            System.out.println("⏳ Baue die Power-Matrix auf...");

            // 1. Tabelle erstellen
            stmt.execute("CREATE TABLE IF NOT EXISTS team_power (team_name TEXT PRIMARY KEY, power_index INTEGER, streak TEXT)");

            // 2. Alte Daten löschen (für sauberen Neustart)
            stmt.execute("DELETE FROM team_power");

            // 3. Echte Form-Daten (Power Index 0-100) einspeisen
            // Cleveland ist aktuell stark, Detroit komplett am Boden
            stmt.execute("INSERT INTO team_power VALUES ('Cleveland Cavaliers', 85, 'W-W-W-L-W')");
            stmt.execute("INSERT INTO team_power VALUES ('Detroit Pistons', 20, 'L-L-L-L-L')");

            // Minnesota stark, Spurs im Neuaufbau
            stmt.execute("INSERT INTO team_power VALUES ('Minnesota Timberwolves', 75, 'W-L-W-W-W')");
            stmt.execute("INSERT INTO team_power VALUES ('San Antonio Spurs', 45, 'L-W-L-L-W')");

            // Ein paar Standardwerte für den Rest
            stmt.execute("INSERT INTO team_power VALUES ('Boston Celtics', 95, 'W-W-W-W-W')");
            stmt.execute("INSERT INTO team_power VALUES ('Los Angeles Lakers', 60, 'W-L-L-W-L')");

            System.out.println("✅ Power-Matrix erfolgreich geladen! Das Gehirn hat jetzt ein Gedächtnis.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}