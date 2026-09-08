pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "NicoTVOverlay"
include(":core", ":comment-client", ":detection", ":overlay", ":app", ":tv-fixture")
