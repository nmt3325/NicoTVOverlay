plugins { kotlin("jvm"); `java-library` }
kotlin { jvmToolchain(17) }
dependencies { api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2"); testImplementation("junit:junit:4.13.2") }
