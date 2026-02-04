pipeline {
    agent { label 'jenkins-node-10.10.192.18' }

    triggers {
        cron('H 0 * * *')
    }

    parameters {      // Manual trigger parameters
        string(
            name: 'MANUAL_BUILD_TRIGGER',
            defaultValue: 'false',
            description: 'Manually trigger the build? (true/false)'
        )
    }
    
    environment {
        GERRIT_BRANCH = 'spinel_app'
        DAILY_VERSION = sh(
            script: 'date +%Y.%m.%d',
            returnStdout: true,
            encoding: 'UTF-8'
        ).trim()
        TARGET_ZIP_NAME = "spinel_fw_dailybuild_${DAILY_VERSION}.zip"
    }

    stages {
        stage('Release_Notes') {
            steps {
                script {
                    env.GERRIT_URL = 'http://10.10.192.13:8082/gerrit'
                    env.TODAY_DATE = sh(
                        script: "date +%Y%m%d",
                        returnStdout: true
                    ).trim()
                    env.YESTERDAY_DATE = sh(
                        script: "date -d '1 day ago' +%Y%m%d",
                        returnStdout: true
                    ).trim()

                    env.NOTES_FILE = "release-notes_${env.TODAY_DATE}.md"

                    env.START_TIME = sh(
                        script: "date -d 'yesterday' +%Y-%m-%d",
                        returnStdout: true
                    ).trim()

                    env.END_TIME = sh(
                        script: "date +%Y-%m-%d",
                        returnStdout: true
                    ).trim()

                    /* ========= Gerrit Query ========= */
                    def query = [
                        "status:merged",
                        "mergedafter:${env.START_TIME}",
                        "mergedbefore:${env.END_TIME}",
                        "branch:${env.GERRIT_BRANCH}",
                        "limit:200"
                    ]

                    def queryUrl = "${env.GERRIT_URL}/a/changes/?q=${query.join('+')}" +
                                "&o=CURRENT_REVISION&o=ALL_FILES"

                    withCredentials([
                        usernamePassword(
                            credentialsId: 'jenkins_http_password',
                            usernameVariable: 'GERRIT_USER',
                            passwordVariable: 'GERRIT_PASS'
                        )
                    ]) {
                        sh """
                            set -e
                            mkdir -p out/release

                            curl -s -u "$GERRIT_USER:$GERRIT_PASS" "$queryUrl" \
                            | sed '1d' > gerrit.json
                        """
                    }

                    sh """
                        jq empty gerrit.json
                    """
                    def changeCount = sh(
                        script: "jq length gerrit.json",
                        returnStdout: true
                    ).trim()

                    if (changeCount == "0") {
                        sh """
                            echo "# Release Notes - ${env.YESTERDAY_DATE}" > ${env.NOTES_FILE}
                            echo "## No code changes merged in this period." >> ${env.NOTES_FILE}
                            cp ${env.NOTES_FILE} \$HOME/release_notes/spinel/${env.NOTES_FILE}
                        """
                        echo "No changes, TERMINATE this build!"
                        currentBuild.result = 'SUCCESS'
                        skipRemainingStages()
                    } else {
                        sh """
                            echo "# Release Notes - ${env.YESTERDAY_DATE}" > ${env.NOTES_FILE}
                            echo "## Total Merged Changes: ${changeCount}" >> ${env.NOTES_FILE}
                            echo >> ${env.NOTES_FILE}
                            echo "**Project:** BES2800BP_GLASS" >> ${env.NOTES_FILE}
                            echo "**Branch:** spinel_app" >> ${env.NOTES_FILE}
                            # 替换echo -e为printf，解决-e字符输出问题
                            # echo -e "\\n## Detailed Changes:\\n" >> ${env.NOTES_FILE}
                            printf '\\n## Detailed Changes:\\n\\n' >> ${env.NOTES_FILE}
                            
                            jq -r '
                                (if type=="array" then .
                                elif has("changes") then .changes
                                else [] end)[] |
                                # 兼容老版本jq的日期格式处理方式
                                .submitted |= (
                                    split(" ")[0] + "T" + split(" ")[1] |  # 替换空格为T
                                    split(".")[0] + "Z"                   # 截断纳秒并添加Z
                                ) |
                                "### Change-ID: \\(.id)\\n" +
                                "**Subject:** \\(.subject)\\n" +
                                "**Author:** \\(.owner._account_id)\\n" +
                                "**Merged Time:** \\(.submitted | fromdateiso8601 | strftime("%Y-%m-%d %H:%M:%S"))\\n" +
                                "**Modified Files:**\\n" +
                                (.revisions[.current_revision].files | keys[] | "  - " + .) +
                                "\\n"
                            ' gerrit.json >> gerrit.json.tmp1
                        """
                    }

                    withCredentials([
                        usernamePassword(
                            credentialsId: 'jenkins_http_password',
                            usernameVariable: 'GERRIT_USER',
                            passwordVariable: 'GERRIT_PASS'
                        )
                    ]) {
                        def finalContent = AuthorName(readFile('gerrit.json.tmp1'))
                        writeFile file: 'gerrit.json.tmp2', text: finalContent
                    }

                    sh """
                        awk '!seen[\$0]++' gerrit.json.tmp2 > ${env.NOTES_FILE}
                        cp ${env.NOTES_FILE} \$HOME/release_notes/spinel/${env.NOTES_FILE}
                    """
                }
            }
        }
        
        stage('Code_Checkout') {
            options {
                retry(3)
                timeout(time: 10, unit: 'MINUTES')
            }
            steps {
                cleanWs()
                checkout scmGit(
                    branches: [[name: "*/spinel_app"]], 
                    extensions: [[$class: 'CleanBeforeCheckout']], 
                    userRemoteConfigs: [[
                        credentialsId: 'Jenkins', 
                        url: 'ssh://10.10.192.13:29418/BES2800BP_GLASS'
                    ]]
                )
            }
        }

        stage('BUILD_USB_FW') {
            steps {
                script {
                    def configFile = "${env.WORKSPACE}/tools/spinel_config.sh"
                    echo "Spinel Version File: ${configFile}"
                    
                    def configFileContent = sh(script: "cat ${configFile}", returnStdout: true).trim()
                    def targetLinePattern = ~/LILY_VERSION_CFG="LILY_SOFTWARE_VERSION=\d+\.\d+\.\d+"/
                    def versionLine = configFileContent.split("\n").find { it =~ targetLinePattern }
                    echo "Original Version: ${versionLine}"

                    if (versionLine) {
                        def newVersionLine = versionLine.replaceAll(targetLinePattern) { match ->
                            "LILY_VERSION_CFG=\"LILY_SOFTWARE_VERSION=${env.DAILY_VERSION}\""
                        }
                        echo "New Version: ${newVersionLine}"
                        configFileContent = configFileContent.replace(versionLine, newVersionLine)
                        echo "File updated successfully."
                    } else {
                        error("No matching version line found in the config file.")
                    }

                    sh(script: "echo '${configFileContent}' > '${configFile}'", returnStdout: true)
                    echo "File updated successfully."

                    sh '''
                        rm -rf out/
                        ./compile.sh spinelA || { echo "Failed to execute ./compile.sh"; exit 1; }
                    ''' 
                } 
            } 
        }

        stage('Packaging_Firmware') {
            steps {
                script {
                    sh """
                        cp tools/dldtool/release/ota_boot_watch.bin out/release/
                        cp --parents -r vx100_fw/ out/release/
                        cp \$HOME/release_notes/spinel/${env.NOTES_FILE} out/release/${env.NOTES_FILE}
                    """
                    dir('out/release') {
                        zip( 
                            zipFile: env.TARGET_ZIP_NAME, 
                            archive: true, 
                            dir: '.'
                        )
                    }
                    echo 'Firmware packaging is completed!'
                }
            }
        }

        stage('BACKUP_to_FTP') {
            steps {
                script {
                    def url = "2025 Spinel_Rubis/dailyBuild/spinel"
                    
                    ftpPublisher(
                            alwaysPublishFromMaster: false,
                            continueOnError: false,
                            failOnError: false,
                            publishers: [
                                [
                                    configName: '10.10.192.11',
                                    transfers: [
                                        [
                                            asciiMode: false,
                                            cleanRemote: false,
                                            excludes: '',
                                            flatten: false,
                                            makeEmptyDirs: false,
                                            noDefaultExcludes: false,
                                            patternSeparator: '[, ]+',
                                            remoteDirectory: url,
                                            remoteDirectorySDF: false,
                                            removePrefix: "out/release",
                                            sourceFiles: "out/release/spinel_fw_dailybuild_${env.DAILY_VERSION}.zip"
                                        ]
                                    ],
                                    usePromotionTimestamp: false,
                                    useWorkspaceInPromotion: false,
                                    verbose: false
                                ]
                            ]
                    )
                }
            }
        }

        stage('Archiving') {
            steps {
                script {
                    echo "Start archiving the artifacts for ${env.TARGET_ZIP_NAME} ..."
                    def zipFilePath = "out/release/${env.TARGET_ZIP_NAME}"
                        if (!fileExists(zipFilePath)) {
                            error("Archiving failed! The file ${zipFilePath} does not exist!")
                        }
                
                    archiveArtifacts(
                        artifacts: "out/release/${env.TARGET_ZIP_NAME}",
                        fingerprint: true,
                        onlyIfSuccessful: true
                    )
                    echo "Artifacts archived successfully"

                    def buildUrl = env.BUILD_URL
                    def artifactUrl = "${buildUrl}artifact/${zipFilePath}"
                    def recipients = "jeremy.li@goertek.com, fisher.liuxk@goertek.com"
                    def subject = "[Spinel Daily Build] archived successfully - ${env.TARGET_ZIP_NAME}"
                    def body = """
                        <h3>Rubis daily build firmware archived successfully!</h3>
                        <p>Build Number: ${env.BUILD_NUMBER}</p>
                        <p>Artifact: ${env.TARGET_ZIP_NAME}</p>
                        <p>Download URL: <a href="${artifactUrl}">${artifactUrl}</a></p>
                        <p>Build Details: <a href="${buildUrl}">${buildUrl}</a></p>
                        <p>Remark: Please sign in Jenkins and then browse the link.</p>
                    """.stripIndent()

                    emailext(
                        subject: subject,
                        body: body,
                        to: recipients,
                        mimeType: 'text/html',
                        charset: 'UTF-8'
                    )
                    echo "Mail has been sent to: ${recipients}"
                }
            }
        }
    }
}

def AuthorName(String content) {
    def lines = content.readLines()
    def finalLines = []
    def accountIdCache = [:]  // 缓存已查询的ID-用户名映射，避免重复请求

    lines.each { line ->
        if (line.startsWith('**Author:**')) {
            // 提取account ID
            def accountId = line.replace('**Author:** ', '').trim()
            def userName = ''

            // 优先从缓存获取，避免重复调用API
            if (accountIdCache.containsKey(accountId)) {
                userName = accountIdCache[accountId]
            } else {
                // 调用Gerrit API获取用户名
                try {
                    userName = sh(
                        script: """
                            curl -s -u ${GERRIT_USER}:${GERRIT_PASS} "${env.GERRIT_URL}/accounts/${accountId}/name" | sed '1d'
                        """,
                        returnStdout: true
                    ).trim()

                    // 处理空返回的情况
                    if (userName.isEmpty()) {
                        userName = "Unknown (${accountId})"
                    }

                    // 存入缓存
                    accountIdCache[accountId] = userName
                } catch (Exception e) {
                    // 异常处理：API调用失败时保留ID并标注
                    userName = "Error (${accountId})"
                    echo "Failed to get the username of ${accountId}: ${e.getMessage()}"
                }
            }

            // 替换为用户名
            finalLines.add("**Author:** ${userName}")
        } else {
            // 非Author行直接保留
            finalLines.add(line)
        }
    }

    return finalLines.join('\n')
}