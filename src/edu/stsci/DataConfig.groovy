package edu.stsci
import groovy.json.JsonOutput
import org.apache.commons.io.FileUtils

class DataConfig implements Serializable {
    String root = '.'
    String server_ident = ''
    String match_prefix = '(.*)'
    Boolean keep_data = false
    int keep_builds = 20
    int keep_days = 10
    def steps
    def server_conn
    String scan_path
    def data = [:]
    def buildInfo

    DataConfig(steps, connection) {
        this.steps = steps
        this.server_ident = server_ident
        this.server_conn = connection #steps.Artifactory.server(this.server_ident)
        this.buildInfo = this.server_conn.newBuildInfo()
        this.buildInfo.env.capture = true
        this.buildInfo.env.collect()
        // Construct absolute path to data
        this.scan_path = FilenameUtils.getFullPath(
                    "${this.steps.env.WORKSPACE}/${this.root}"
        )
    }

    def insert(String name, String block) {
        /* Store JSON directly as string */
        this.data[name] = block
    }

    def insert(String name, block=[:]) {
        /* Convert a Groovy Map to JSON and store it */
        this.data[name] = JsonOutput.toJson(block)
    }

    def populate() {
        // Record listing of all files starting at ${path}
        // (Native Java and Groovy approaches will not
        // work here)
        this.steps.sh(script: "find ${this.scan_path} -type f",
           returnStdout: true).trim().tokenize('\n').each {

            // Semi-wildcard matching of JSON input files
            // ex:
            //      it = "test_1234_result.json"
            //      artifact.match_prefix = "(.*)_result"
            //
            //      pattern becomes: (.*)_result(.*)\\.json
            if (it.matches(
                    this.match_prefix + '(.*)\\.json')) {
                def basename = FilenameUtils.getBaseName(it)
                def data = readFile(it)

                // Store JSON in a logical map
                // i.e. ["basename": [data]]
                this.insert(basename, data)
            }
        } // end find.each
    }

    def run() {
        this.populate()

        // Submit each request to the Artifactory server
        this.data.each { blob ->
            def spec = this.server_conn.upload(spec: blob.value)
            this.buildInfo.append(spec)
        }

        // Define retention scheme
        // Defaults: see DataConfig.groovy
        this.buildInfo.retention \
            maxBuilds: this.keep_builds, \
            maxDays: this.keep_days, \
            deleteBuildArtifacts: !this.keep_data

        this.server_conn.publishBuildInfo(this.buildInfo)

    } // end stage Artifactory
}
