pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral(); maven { url = uri("https://raw.githubusercontent.com/guardianproject/gpmaven/master") } } }
rootProject.name = "maillage"
include(":app")
