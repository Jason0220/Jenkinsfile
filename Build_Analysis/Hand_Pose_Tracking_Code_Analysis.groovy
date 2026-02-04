import groovy.json.JsonOutput

pipeline {
    agent any
    environment {
        SONAR_SCANNER = "/home/data0/jenkins/tools/sonar-scanner/bin"
        
        PATH = "${PATH}:${SONAR_SCANNER}"
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
                        url: 'ssh://10.10.192.13:29418/Hand_Pose_Tracking']]])
            }
        }
        
        stage('Code_Analysis') {
            steps {
                withSonarQubeEnv('SonarQube_46') {
                    sh """
                        touch sonar-project.properties
                        echo "sonar.projectKey=Hand_Pose_Tracking" >> sonar-project.properties
                        sonar-scanner \
                            -Dsonar.projectKey=Hand_Pose_Tracking \
                            -Dsonar.branch.name=${env.GERRIT_BRANCH} \
                            -Dsonar.sources=. \
                            -Dsonar.host.url=http://10.10.192.46:9000/sonarqube \
                            -Dsonar.login=sqp_862da05a39d3ac5f6f7c4b2b94976d95d8eefcdf
                    """
                }
            }
        }
        
        stage("Quality Gate") {
            steps {
                script {
                    timeout(20) {
                        def qg = waitForQualityGate('SonarQube_46')
                        if (qg.status != 'OK') {
                            error "Code scan failed! failure: ${qg.status}"
                        }
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
