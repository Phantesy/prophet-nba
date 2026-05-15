import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import org.json.JSONArray;
import org.json.JSONObject;

public class NBAMatrixUpdater {
    public static void main(String[] args) {
        String dbUrl = "jdbc:sqlite:nba_secret_weapon.db";
        // Die öffentliche API von ESPN für die NBA-Tabelle
        String espnUrl = "https://site.api.espn.com/apis/v2/sports/basketball/nba/standings";

        try {
            System.out.println("🧠 Verbinde mit ESPN Matrix...");
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(espnUrl)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JSONObject jsonResponse = new JSONObject(response.body());
                JSONArray leagues = jsonResponse.getJSONArray("children");

                try (Connection conn = DriverManager.getConnection(dbUrl)) {
                    // Wir leeren die alte Matrix, um Platz für die Live-Daten zu machen
                    conn.createStatement().execute("DELETE FROM team_power");

                    String insertSql = "INSERT INTO team_power (team_name, power_index, streak) VALUES (?, ?, ?)";
                    PreparedStatement pstmt = conn.prepareStatement(insertSql);

                    // ESPN teilt in Conferences auf (East / West)
                    for (int i = 0; i < leagues.length(); i++) {
                        JSONArray standings = leagues.getJSONObject(i).getJSONObject("standings").getJSONArray("entries");

                        for (int j = 0; j < standings.length(); j++) {
                            JSONObject teamData = standings.getJSONObject(j);
                            String teamName = teamData.getJSONObject("team").getString("displayName");

                            // Die echten Statistiken auslesen
                            JSONArray stats = teamData.getJSONArray("stats");
                            double winPercent = 0.5; // Standard
                            String streak = "N/A";

                            for (int k = 0; k < stats.length(); k++) {
                                JSONObject stat = stats.getJSONObject(k);
                                if (stat.getString("name").equals("winPercent")) {
                                    winPercent = stat.getDouble("value");
                                }
                                if (stat.getString("name").equals("streak")) {
                                    streak = stat.getString("displayValue");
                                }
                            }

                            // Unser Power Index ist die Win-Percentage * 100 (z.B. 0.75 * 100 = 75)
                            int powerIndex = (int) (winPercent * 100);

                            // Extra-Boost für Siegsträhnen (W = Win)
                            if (streak.contains("W") && streak.length() > 1) {
                                powerIndex += 5; // Wer einen Lauf hat, kriegt +5 Power
                            }

                            pstmt.setString(1, teamName);
                            pstmt.setInt(2, powerIndex);
                            pstmt.setString(3, streak);
                            pstmt.executeUpdate();
                        }
                    }
                    System.out.println("✅ Power-Matrix mit echten ESPN-Daten gefüttert!");
                }
            } else {
                System.out.println("❌ Fehler beim ESPN-Pull: " + response.statusCode());
            }
        } catch (Exception e) {
            System.out.println("⚠️ System-Warnung: Konnte ESPN nicht erreichen. Nutze alte Datenbank-Werte.");
            // e.printStackTrace(); // Optional für Debugging
        }
    }
}