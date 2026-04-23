import groovy.json.JsonOutput

pipeline {
    agent { label 'jenkins-node-10.10.192.18' }
    environment {
        SONAR_SCANNER = "/home/jenkins/tools/sonar-scanner-8.0.1/bin"
        PATH = "${SONAR_SCANNER}:${PATH}"
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
                        url: 'ssh://10.10.192.13:29418/Emotional%20Perception']]])
            }
        }
        
        stage('Code_Analysis') {
            steps {
                def SONAR_TOKEN = 'sqp_f66fca6fe0697522772dc061cfa295cc75a2e805'
                def branchName = env.GERRIT_BRANCH.replaceAll("refs/heads/", "")
                def venvPath = "/home/jenkins/virtual_environment/pysonar_common"
                withSonarQubeEnv('SonarQube_18') {
                    sh """
                        source ${venvPath}/bin/activate
                        set -e
                        echo "sonar.projectKey=Emotional_Perception" > sonar-project.properties
                        pysonar \
                            -Dsonar.projectKey=Emotional_Perception \
                            -Dsonar.branch.name=${branchName} \
                            -Dsonar.sources=. \
                            -Dsonar.python.version=3.12 \
                        
                        deactivate
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
                            "http://10.10.192.18:9000/api/qualitygates/project_status?projectKey=Emotional_Perception&branch=${branchName}"
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
                        Dashboard: http://10.10.192.18:9000/dashboard?id=Emotional_Perception&branch=${branchName}
                        """

                        currentBuild.result = 'FAILURE'
                    } else {
                        echo "INFO: QUALITY GATE PASSED"
                    }

                    env.SONAR_STATUS = qgStatus
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
        always {  // 无论构建成功或失败都执行
            script {
                def consoleLog = currentBuild.rawBuild.getLog(100) // 获取全部日志

                // Gerrit 相关信息
                def gerritServer = "http://10.10.192.13:8082/gerrit"  // Gerrit 服务器地址
                def changeId = env.GERRIT_CHANGE_NUMBER             // Gerrit Change ID
                def revisionId = env.GERRIT_PATCHSET_NUMBER         // Gerrit Patchset (Revision) ID
                def gerritUser = "jenkins"                    // Gerrit 用户名
                def gerritPassword = "L4Yjc6ypq0kLEhdJ9dl/1wUCgC2dWv9WI7Br2mIUdw"                // Gerrit 用户密码

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