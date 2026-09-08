import com.google.protobuf.gradle.*

plugins { kotlin("jvm"); `java-library`; id("com.google.protobuf") version "0.9.4" }
kotlin { jvmToolchain(17) }
dependencies {
 api(project(":core")); api("com.squareup.okhttp3:okhttp:4.12.0")
 implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
 implementation("com.google.protobuf:protobuf-javalite:4.31.1")
 testImplementation("junit:junit:4.13.2")
 testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
 testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
protobuf {
 protoc { artifact = "com.google.protobuf:protoc:4.31.1" }
 generateProtoTasks {
  all().configureEach { builtins { named("java") { option("lite") } } }
 }
}
