plugins {
    id("qavlen.library")
}

dependencies {
    api(project(":core"))
    api(libs.hikari)
    // Applications add the JDBC driver they use, for example org.mariadb.jdbc:mariadb-java-client or org.postgresql:postgresql.
    testImplementation(libs.h2)
}
