import groovy.json.JsonOutput

pipeline {
    agent { label 'jenkins-node-10.10.192.18' }
    environment {
        JAVA_HOME = "/usr/lib/jvm/java-17-openjdk-amd64"
        SONAR_SCANNER = "/home/jenkins/tools/sonar-scanner-8.0.1/bin"
        PATH = "${JAVA_HOME}/bin:${SONAR_SCANNER}:${PATH}"
    }

    stages {
        stage('Code_Sync') {
            options {
                retry(20)
            }
            steps {
                checkout([$class: 'GitSCM', 
                    branches: [[name: '$GERRIT_REFSPEC']], 
                    extensions: [], 
                    userRemoteConfigs: [[credentialsId: 'Jenkins', 
                        refspec: 'refs/changes/*:refs/changes/*', 
                        url: 'ssh://10.10.192.13:29418/Algo_Speech']]])
            }
        }
        
        stage('Code_Analysis') {
            steps {
                script {
                    def SONAR_TOKEN = 'sqp_2cfa2e1aa790e7214d5d1d3fa22f29aeda49902c'
                    def branchName = env.GERRIT_BRANCH.replaceAll("refs/heads/", "")
                    withSonarQubeEnv('SonarQube_18') {
                        def exclusionFile = "sonar_exclusions.txt"
                        def persistentPath = "/home/jenkins/sonar-files/Algo_Speech/${exclusionFile}"
                        sh "if [ ! -f ${exclusionFile} ]; then cp ${persistentPath} ./; fi"
                        
                        sh """
                            java -version
                            > sonar-project.properties
                                echo "sonar.projectKey=Algo_Speech" >> sonar-project.properties
                                echo "sonar.sources=." >> sonar-project.properties
                                echo "sonar.sourceEncoding=UTF-8" >> sonar-project.properties
                                echo -n "sonar.exclusions=" >> sonar-project.properties
                                cat sonar_exclusions.txt >> sonar-project.properties
                            
                            sonar-scanner \
                                -Dsonar.projectKey=Algo_Speech \
                                -Dsonar.branch.name=${branchName} \
                                -Dsonar.sources=. \
                                -Dsonar.host.url=http://10.10.192.18:9000
                        """
                    }

                    timeout(time: 20, unit: 'MINUTES') {

                        def qgStatus = ""
                        def maxRetry = 20

                        for (int i = 0; i < maxRetry; i++) {

                            echo "Checking SonarQube Quality Gate... attempt ${i+1}"

                            def response = sh(
                                script: """
                                curl -s -u ${SONAR_TOKEN}: \
                                "http://10.10.192.18:9000/api/qualitygates/project_status?projectKey=Algo_Speech&branch=${branchName}"
                                """,
                                returnStdout: true
                            ).trim()

                            qgStatus = sh(
                                script: """
                                echo '${response}' | python3 -c "import sys,json; print(json.load(sys.stdin)['projectStatus']['status'])"
                                """,
                                returnStdout: true
                            ).trim()

                            echo "Current Quality Gate status: ${qgStatus}"

                            // 只关心最终状态
                            if (qgStatus in ["OK", "ERROR", "WARN"]) {
                                break
                            }

                            sleep 30
                        }

                        echo "Final Quality Gate status: ${qgStatus}"

                        // 只做最终判断
                        if (qgStatus != "OK") {
                            echo """
                            INFO: QUALITY GATE FAILED or UNSTABLE
                            Dashboard: http://10.10.192.18:9000/dashboard?id=Algo_Speech&branch=${branchName}
                            """

                            currentBuild.result = 'FAILURE'
                        } else {
                            echo "INFO: QUALITY GATE PASSED"
                        }

                        env.SONAR_STATUS = qgStatus
                    }
                }
            }
        }
        
        stage('CLEAN') {
            steps {
                sh 'rm -rf *'
            }
        }
    }
    
    post {
        always {
            script {
                def consoleLog = currentBuild.rawBuild.getLog(100) // 获取全部日志

                // Gerrit 相关信息
                def gerritServer = "http://10.10.192.13:8082/gerrit"
                def changeId = env.GERRIT_CHANGE_NUMBER
                def revisionId = env.GERRIT_PATCHSET_NUMBER
                def gerritUser = "jenkins"
                def gerritPassword = "L4Yjc6ypq0kLEhdJ9dl/1wUCgC2dWv9WI7Br2mIUdw"

                // 格式化日志为代码块并加入换行符
                def formattedLog = "```\n" + consoleLog.join("\n") + "\n```"

                // 创建要发送的 JSON 数据
                def jsonData = JsonOutput.toJson([message: "Jenkins Console Log:\n${formattedLog}"])

                // 使用 curl 通过 Gerrit REST API 发送评论
                sh """
                curl -X POST --data '${jsonData}' \\
                     -H 'Content-Type: application/json' \\
                     --user '${gerritUser}:${gerritPassword}' \\
                     "${gerritServer}/a/changes/${changeId}/revisions/${revisionId}/review"
                """
            }
        }
    }
}