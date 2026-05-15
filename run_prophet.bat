@echo off
title NBA PROPHET - Autopilot
cd /d "%~dp0"

if not exist bin mkdir bin

set "JARS=json-20240303.jar;sqlite-jdbc-3.53.1.0-natives-windows.jar;sqlite-jdbc-3.53.1.0-without-natives.jar"

echo [0/6] KOMPILIERE CODE NEU...
javac -d bin -cp ".;%JARS%" *.java
if errorlevel 1 (
    echo FEHLER BEIM KOMPILIEREN!
    pause
    exit /b
)

set "CP=.;bin;%JARS%"

echo [1/6] ERGEBNISSE CHECKEN...
java -cp "%CP%" ResultPuller

echo [2/6] UPDATE POWER-MATRIX...
java -cp "%CP%" NBAMatrixUpdater

echo [3/6] QUOTEN LADEN...
java -cp "%CP%" NBADataPuller

echo [4/6] PLAYER PROPS ANALYSIEREN...
java -cp "%CP%" PlayerPropsPuller

echo [5/6] DASHBOARD JSON GENERIEREN...
java -cp "%CP%" DashboardUpdater

echo [6/6] CLOUD UPLOAD...
git add .
git commit -m "Prophet dashboard data update"
git push

echo SYSTEM KOMPLETT AKTUALISIERT!
pause