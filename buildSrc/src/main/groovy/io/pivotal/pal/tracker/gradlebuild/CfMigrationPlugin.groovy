package io.pivotal.pal.tracker.gradlebuild

import groovy.json.JsonSlurper
import org.flywaydb.gradle.FlywayExtension
import org.flywaydb.gradle.task.FlywayMigrateTask
import org.flywaydb.gradle.task.FlywayRepairTask
import org.gradle.api.Plugin
import org.gradle.api.Project

class CfMigrationPlugin implements Plugin<Project> {
    private final static int TUNNEL_PORT = 63306

    @Override
    void apply(Project project) {
        Process tunnelProcess = null
        Map credentials = null

        project.with {
            afterEvaluate {
                def databases = project.extensions.findByType(DatabasesExtension)
                def appName = databases.cfApp
                def databaseInstanceName = databases.cfDatabase

                task( "acquireCredentials") {
                    doLast {
                        println "Acquiring database credentials"
                        credentials = acquireMysqlCredentials(appName, databaseInstanceName)
                    }
                }

                task("openTunnel") {
                    dependsOn "acquireCredentials"
                    doLast {
                        println "Opening Tunnel for $appName"
                        Thread.start {
                            tunnelProcess = "cf ssh -N -L ${TUNNEL_PORT}:${credentials["hostname"]}:${credentials["port"]} $appName".execute()
                        }

                        waitForTunnelConnectivity()
                    }
                }

                task("closeTunnel") {
                    doLast {
                        println "Closing Tunnel"
                        tunnelProcess?.destroyForcibly()
                    }
                }

                task("cfMigrate", type: FlywayMigrateTask, group: "Migration") {
                    dependsOn "openTunnel"
                    finalizedBy "closeTunnel"
                    doFirst { extension = buildFlywayExtension(project, appName, databaseInstanceName, credentials) }
                }

                task("cfRepair", type: FlywayRepairTask, group: "Migration") {
                    dependsOn "openTunnel"
                    finalizedBy "closeTunnel"
                    doFirst { extension = buildFlywayExtension(project, appName, databaseInstanceName, credentials) }
                }
            }
        }
    }

    private static void waitForTunnelConnectivity() {
        int remainingAttempts = 20
        while (remainingAttempts > 0) {
            remainingAttempts--
            try {
                new Socket('localhost', TUNNEL_PORT).close()
                remainingAttempts = 0
            } catch (ConnectException e) {
                println "Waiting for tunnel ($remainingAttempts attempts remaining)"
                sleep 1_000L
            }
        }
    }

    private static def buildFlywayExtension(Project project, String cfAppName, databaseInstanceName, credentials) {
        def extension = new FlywayExtension()

        extension.user = credentials["username"]
        extension.password = credentials["password"]
        extension.url = "jdbc:mysql://127.0.0.1:${TUNNEL_PORT}/${credentials["name"]}"

        extension.locations = ["filesystem:$project.projectDir/migrations"]
        return extension
    }

    // The shell/perl 'magic' here is needed because where a database
    // service uses CredHub, the credentials are not (for obvious reasons)
    // available via 'cf env' or direct calls to the API. However, the
    // expanded versions *are* presented to the running Java application
    // in its environment, which we can access via the /proc filesystem.
    private static Map acquireMysqlCredentials(cfAppName, databaseInstanceName) {
        def vcapServicesJson = execute(['cf', 'ssh', cfAppName, '-c',
                                        'perl -0 -ne "print if (s/^VCAP_SERVICES=//)" /proc/$(pgrep java)/environ'])
        def vcapServicesMap = new JsonSlurper().parseText(vcapServicesJson)

        def entryWithDbInstance = vcapServicesMap
                .find { key, value -> value.any { it["name"] == databaseInstanceName } }

        def dbInstance = entryWithDbInstance.value
                .find { it["name"] == databaseInstanceName }

        return dbInstance["credentials"]
    }

    private static String execute(List args) {
        def process = args.execute()
        def output = process.text
        process.waitFor()
        return output
    }
}
