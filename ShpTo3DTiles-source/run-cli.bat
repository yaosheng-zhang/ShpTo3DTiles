@echo off
setlocal
rem Run the standalone CLI from the shaded jar built by Maven.
set "JAR=%~dp0target\shp-to-3dtiles-tool-1.1.0.jar"
if not exist "%JAR%" (
  echo JAR not found: %JAR%
  echo Build first with: mvn clean package
  exit /b 1
)
java -cp "%JAR%" com.pipeline.api.cli.ShpTo3dTilesCli %*
endlocal
