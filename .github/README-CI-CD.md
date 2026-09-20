# GitHub CI/CD configuration

`gradle.yml` runs the normal Gradle checks. `build.yml` performs SonarQube pull-request
analysis and passes explicit PR key, source, and base metadata. `sonarqube-branch.yml`
performs branch analysis on `trunk` and `feat-*` pushes. SonarQube is the repository's
only analysis signal for the modernization demo; its token is supplied only through the
`SONAR_TOKEN` GitHub secret.
