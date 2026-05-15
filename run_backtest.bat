@echo off
title NBA PROPHET - Backtest
cd /d "%~dp0"

:: Alle Bibliotheken einbinden (wichtig, falls Natives fehlen)
set CP=.;json-20240303.jar;sqlite-jdbc-3.53.1.0-natives-windows.jar;sqlite-jdbc-3.53.1.0-without-natives.jar

echo 🔨 [1/3] BACKTEST TOOLS KOMPILIEREN...
javac -cp "%CP%" PropBacktestSetup.java PropResultPuller.java BacktestStatsExporter.java
if errorlevel 1 (
    echo ❌ FEHLER BEIM KOMPILIEREN! Skript abgebrochen.
    pause
    exit /b
)

echo ⚙️ [2/3] DATENBANK STRUKTUR PRUEFEN...
java -cp "%CP%" PropBacktestSetup

echo 📈 [3/3] ERGEBNISSE AUSWERTEN...
java -cp "%CP%" PropResultPuller
java -cp "%CP%" BacktestStatsExporter

echo.
echo ✅ FERTIG! performance.json wurde aktualisiert.
pause