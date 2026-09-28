@REM Maven Wrapper - delegates to local Maven
@ECHO OFF
SET MAVEN_HOME=C:\apache-maven-3.9.15
IF EXIST "%MAVEN_HOME%\bin\mvn.cmd" (
  "%MAVEN_HOME%\bin\mvn.cmd" %*
) ELSE (
  mvn %*
)
