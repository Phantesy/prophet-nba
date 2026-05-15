import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.*;
import org.json.JSONArray;
import org.json.JSONObject;

public class ResultPuller {
    public static void main(String[] args) {
        String dbUrl = "jdbc:sqlite:nba_secret_weapon.db";
        String espnResultsUrl = "https://site.api.espn.com/apis/site/v2/sports/basketball/nba/scoreboard";

        try {
            System.out.println("🔍 Checke Ergebnisse der letzten Nacht...");
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(espnResultsUrl)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JSONObject json = new JSONObject(response.body());
                JSONArray events = json.getJSONArray("events");

                try (Connection conn = DriverManager.getConnection(dbUrl)) {
                    for (int i = 0; i < events.length(); i++) {
                        JSONObject event = events.getJSONObject(i);
                        JSONObject competition = event.getJSONArray("competitions").getJSONObject(0);

                        // Nur Spiele werten, die fertig sind ("STATUS_FULL")
                        if (!event.getJSONObject("status").getJSONObject("type").getString("name").equals("STATUS_FULL")) continue;

                        String homeTeam = competition.getJSONArray("competitors").getJSONObject(0).getJSONObject("team").getString("displayName");
                        String awayTeam = competition.getJSONArray("competitors").getJSONObject(1).getJSONObject("team").getString("displayName");

                        int homeScore = competition.getJSONArray("competitors").getJSONObject(0).getInt("score");
                        int awayScore = competition.getJSONArray("competitors").getJSONObject(1).getInt("score");
                        String actualWinner = (homeScore > awayScore) ? homeTeam : awayTeam;

                        // Checken, ob wir dieses Spiel in unserer History haben
                        String checkSql = "SELECT predicted_winner FROM prediction_history WHERE home_team = ? AND actual_winner IS NULL";
                        PreparedStatement pstmt = conn.prepareStatement(checkSql);
                        pstmt.setString(1, homeTeam);
                        ResultSet rs = pstmt.executeQuery();

                        if (rs.next()) {
                            String predicted = rs.getString("predicted_winner");
                            int isCorrect = predicted.equals(actualWinner) ? 1 : 0;

                            String updateSql = "UPDATE prediction_history SET actual_winner = ?, is_correct = ? WHERE home_team = ?";
                            PreparedStatement upstmt = conn.prepareStatement(updateSql);
                            upstmt.setString(1, actualWinner);
                            upstmt.setInt(2, isCorrect);
                            upstmt.setString(3, homeTeam);
                            upstmt.executeUpdate();
                            System.out.println("✅ Ergebnis eingetragen: " + homeTeam + " vs " + awayTeam + " -> Korrekt: " + (isCorrect == 1 ? "JA" : "NEIN"));
                        }
                    }
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }
}