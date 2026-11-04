package com.pipeline.api.geom;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class GeometryDiagnostics {
   private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
   private static Path logFile;

   private GeometryDiagnostics() {
   }

   public static synchronized Path initialize(Path outputDirectory, Path shapefile) throws IOException {
      Files.createDirectories(outputDirectory);
      String timestamp = LocalDateTime.now().format(FILE_TIME);
      logFile = outputDirectory.resolve("geometry-diagnostics-" + timestamp + ".log");
      Files.writeString(
         logFile,
         "ShpTo3DTiles geometry diagnostics\nStarted: " + LocalDateTime.now() + "\nSHP: " + shapefile.toAbsolutePath() + "\n\n",
         StandardCharsets.UTF_8,
         StandardOpenOption.CREATE_NEW,
         StandardOpenOption.WRITE
      );
      return logFile;
   }

   public static synchronized void log(String message) {
      if (logFile == null) {
         System.err.println("[geometry-diagnostics] logger was not initialized: " + message);
         return;
      }

      try {
         Files.writeString(logFile, message + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
      } catch (IOException exception) {
         System.err.println("[geometry-diagnostics] could not write " + logFile + ": " + exception.getMessage());
      }
   }
}
