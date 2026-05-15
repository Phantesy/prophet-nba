import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import org.json.JSONArray;
import org.json.JSONObject;

public class ESPNRosterSync {

    // Pfad zu deiner Datenbank (wird automatisch erstellt, falls sie nicht existiert)
    private static final String DB_URL = "jdbc:sqlite:nba_secret_weapon.db";

    public static void main(String[] args) {
        System.out.println("🤖 Initialisiere ESPN Roster Sync...");

        try {
            // 1. Datenbank & Tabelle vorbereiten
            setupDatabase();

            HttpClient client = HttpClient.newHttpClient();

            // 2. Liste aller 30 NBA Teams von ESPN holen
            System.out.println("📡 Hole Team-Datenbank von ESPN...");
            String teamsUrl = "https://site.api.espn.com/apis/site/v2/sports/basketball/nba/teams";
            String teamsJson = makeRequest(client, teamsUrl);

            // JSON navigieren (ESPN verschachtelt das ein bisschen)
            JSONArray teamsArray = new JSONObject(teamsJson)
                    .getJSONArray("sports").getJSONObject(0)
                    .getJSONArray("leagues").getJSONObject(0)
                    .getJSONArray("teams");

            // Verbindung zur Datenbank öffnen für die Massen-Eintragung
            try (Connection conn = DriverManager.getConnection(DB_URL)) {
                // PreparedStatement ist sicher und schnell
                String sql = "INSERT OR REPLACE INTO players (player_name, espn_id, team_abbr) VALUES (?, ?, ?)";
                PreparedStatement pstmt = conn.prepareStatement(sql);

                int totalPlayers = 0;

                // 3. Für jedes Team das Roster (Kader) abrufen
                for (int i = 0; i < teamsArray.length(); i++) {
                    JSONObject teamData = teamsArray.getJSONObject(i).getJSONObject("team");
                    String teamId = teamData.getString("id");
                    String teamAbbr = teamData.getString("abbreviation").toLowerCase();
                    String teamName = teamData.getString("displayName");

                    System.out.print("⏳ Lade Kader für " + teamName + " (" + teamAbbr.toUpperCase() + ")... ");

                    // Roster API Call für DIESES spezifische Team
                    String rosterUrl = "https://site.api.espn.com/apis/site/v2/sports/basketball/nba/teams/" + teamId + "/roster";
                    String rosterJson = makeRequest(client, rosterUrl);

                    JSONObject rootJson = new JSONObject(rosterJson);

                    // ESPN packt die Spieler hier direkt in das "athletes" Array ganz oben
                    JSONArray athletes = rootJson.getJSONArray("athletes");

                    int playersInTeam = 0;

                    for (int j = 0; j < athletes.length(); j++) {
                        JSONObject athlete = athletes.getJSONObject(j);

                        // Sicherheits-Check: Hat dieses Objekt überhaupt einen Namen und eine ID?
                        if (athlete.has("fullName") && athlete.has("id")) {
                            String playerId = athlete.getString("id");
                            String playerName = athlete.getString("fullName");

                            // In Datenbank eintragen
                            pstmt.setString(1, playerName);
                            pstmt.setString(2, playerId);
                            pstmt.setString(3, teamAbbr);
                            pstmt.executeUpdate();

                            playersInTeam++;
                            totalPlayers++;
                        }
                    }

                    System.out.println("✅ " + playersInTeam + " Spieler gespeichert.");

                    // Kurze Pause, damit ESPN uns nicht als Spam-Angriff blockiert (1 Sekunde)
                    Thread.sleep(1000);
                }

                System.out.println("\n🎯 SYNC ABGESCHLOSSEN! " + totalPlayers + " Spieler in der Secret Weapon DB aktualisiert.");
            }

        } catch (Exception e) {
            System.err.println("❌ System-Fehler beim Sync:");
            e.printStackTrace();
        }
    }

    // Erstellt die Tabelle, falls sie noch nicht existiert
    private static void setupDatabase() throws Exception {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {

            // 'player_name' ist der PRIMARY KEY. Wenn ESPN jemanden updatet, 
            // überschreibt 'INSERT OR REPLACE' einfach den alten Eintrag.
            String sql = "CREATE TABLE IF NOT EXISTS players (" +
                    "player_name TEXT PRIMARY KEY, " +
                    "espn_id TEXT NOT NULL, " +
                    "team_abbr TEXT NOT NULL" +
                    ");";
            stmt.execute(sql);
            System.out.println("🗄️ Datenbank-Struktur verifiziert.");
        }
    }

    private static String makeRequest(HttpClient client, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }
}