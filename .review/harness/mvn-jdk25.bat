@echo off
set "JAVA_HOME=C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot"
set "Path=%JAVA_HOME%\bin;%Path%"
mvn %*

