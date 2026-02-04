import groovy.json.JsonOutput

pipeline {
    agent { label 'jenkins-node-10.10.192.18' }

    stages {
        stage('CODE_SYNC') {
            options {
                retry(20)
            }
            steps {
                checkout([
                    $class: 'GitSCM',
                    branches: [[name: '$GERRIT_REFSPEC']],
                    extensions: [],
                    userRemoteConfigs: [[
                        credentialsId: 'Jenkins',
                        name: 'origin',
                        refspec: 'refs/changes/*:refs/changes/*',
                        url: 'ssh://10.10.192.13:29418/ResearchKitSDK'
                    ]]
                ])
            }
        }

        stage('CODE_ANALYSIS') {
            steps {
                script {
                    def resetResult = sh script: 'git reset --hard', returnStatus: true
                    if (resetResult != 0) {
                        error("git reset --hard execution failed! Exit code: ${resetResult}")
                    } else {
                        echo "git reset --hard executed successfully: Workspace reset to latest commit state"
                    }
                    def cleanResult = sh script: 'git clean -fxd', returnStatus: true
                    if (cleanResult != 0) {
                        error("git clean -fxd execution failed! Exit code: ${cleanResult}")
                    } else {
                        echo "git clean -fxd executed successfully: All untracked files and directories removed"
                    }
                    
                    def propFilePath = 'local.properties'
                    if (fileExists(propFilePath)) {
                        def propContent = readFile(file: propFilePath)
                        propContent = propContent.replaceFirst(/sdk.dir=.+/, "sdk.dir=/home/jenkins/tools/android-sdk")
                        writeFile file: propFilePath, text: propContent
                        echo "Updated local.properties file:"
                        sh 'cat local.properties | grep sdk.dir'
                    } else {
                        echo "Warning: ${propFilePath} does not exist, creating it..."
                        writeFile file: propFilePath, text: "sdk.dir=/home/jenkins/tools/android-sdk\n"
                    }

                    withSonarQubeEnv('SonarQube_46') {
                        sh '''
                            set -e    // exit script execution caused by any error
                            export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
                            if [ ! -x ./gradlew ]; then
                                echo "gradlew script is not executable or does not exist."
                                exit 1
                            fi
                            ./gradlew clean :bledatatransfermodule:assembleRelease
                            if [ $? -ne 0 ]; then
                                echo "Gradle clean task failed. Check clean.log for details."
                                exit 1
                            fi
                            ./gradlew sonarqube \
                                -Dsonar.projectKey=ResearchKitSDK \
                                -Dsonar.branch.name=master  \
                                -Dsonar.projectName=ResearchKitSDK \
                                -Dsonar.host.url=http://10.10.192.46:9000/sonarqube \
                                -Dsonar.login=sqp_d76ae16ee264a50f232335f2e8b5fb996aa5f264
                        '''
                    }
                }
            }
        }
        
        stage("Quality Gate") {
            steps {
                script {
                    timeout(120) {
                        def qg = waitForQualityGate('SonarQube_46')
                        if (qg.status != 'OK') {
                            error "Code scan failed! failure: ${qg.status}"
                        }
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                def consoleLog = currentBuild.rawBuild.getLog(100)
                def gerritServer = "http://10.10.192.13:8082/gerrit"
                def changeId = env.GERRIT_CHANGE_NUMBER
                def revisionId = env.GERRIT_PATCHSET_NUMBER
                
                def formattedLog = "```\n" + consoleLog.join("\n") + "\n```"
                def jsonData = JsonOutput.toJson([message: "Jenkins Console Log:\n${formattedLog}"])
    
                try {
                    httpRequest (
                        url: "${gerritServer}/a/changes/${changeId}/revisions/${revisionId}/review",
                        httpMode: 'POST',
                        contentType: 'APPLICATION_JSON',
                        requestBody: jsonData,
                        // 使用有效的凭据ID，而不是用户名:密码字符串
                        authentication: 'jenkins_http_password'
                    )
                    echo "Successfully sent comment to Gerrit"
                } catch (Exception e) {
                    echo "Failed to send a comment to Gerrit: ${e.getMessage()}"
                }
            }
        }
    }
}