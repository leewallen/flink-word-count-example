jib {

    from.image = "flink:${flinkVersion}-scala_${scalaBinaryVersion}-java11"
    to.image = project.name
    containerizingMode = 'packaged'
    container {
        creationTime = 'USE_CURRENT_TIMESTAMP'
        labels = [
                'version': version.toString(),
                'flink-version': flinkVersion.toString(),
        ]
        entrypoint = 'INHERIT'
        appRoot = '/opt/flink/usrlib'
    }

    extraDirectories {
        paths {
            path {
                from = file("${buildDir}/flink-libs")
                into = '/opt/flink/lib'
            }
            path {
                from = file("${buildDir}/flink-plugins")
                into = '/opt/flink/plugins'
            }
        }
    }
}

task copyFlinkLibs(type: Copy) {
    from configurations.flinkLib
    into "${buildDir}/flink-libs"
}

task copyFlinkPlugins(type: Copy) {
    from configurations.flinkPlugin
    into "${buildDir}/flink-plugins"
    exclude('**force-shading**')
    eachFile { file ->
        def pattern = /((?:flink-)?([\w-]+))(?:_\d+.\d+)?-\d+.\d+.\w+\.jar/

        file.name = file.name.replaceFirst(pattern) { _, fullName, shortName ->
            "${shortName}/${fullName}.jar"
        }
    }
}

tasks.jib.dependsOn copyFlinkLibs
tasks.jibDockerBuild.dependsOn copyFlinkLibs

tasks.jib.dependsOn copyFlinkPlugins
tasks.jibDockerBuild.dependsOn copyFlinkPlugins
