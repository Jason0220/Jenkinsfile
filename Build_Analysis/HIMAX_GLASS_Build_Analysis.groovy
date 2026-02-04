import groovy.json.JsonOutput

pipeline {
    agent { label 'jenkins-node-10.10.192.18' }

    environment {
        BUILD_WRAPPER="/home/jenkins/tools/build-wrapper-linux-x86"
        SONAR_SCANNER="/home/jenkins/tools/sonar-scanner/bin"
        GCC_ARM="/home/jenkins/tools/gcc-arm-none-eabi-10.3-2021.10/bin"
        PATH="$PATH:$BUILD_WRAPPER:$SONAR_SCANNER:$GCC_ARM"
    }

    stages {
        stage('Code_Checkout') {
            options {
                retry(20)
            }
            steps {
                checkout scmGit(branches: [[name: '$GERRIT_REFSPEC']], 
                    extensions: [], 
                    userRemoteConfigs: [[credentialsId: 'Jenkins', 
                        refspec: 'refs/changes/*:refs/changes/*', 
                        url: 'ssh://10.10.192.13:29418/HIMAX_GLASS']])
            }
        }

        stage('Build') {
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
                    
                    sh '''
                        make clean
                        build-wrapper-linux-x86-64 --out-dir bw-output make all
                    '''
                }
            }
        }
        
        stage('Analysis') {
            steps {
                withSonarQubeEnv('SonarQube_46') {
                    script {
                        sh '''
                            > sonar-project.properties
                            echo "sonar.projectKey=HIMAX_GLASS" >> sonar-project.properties
                            echo "sonar.inclusions=WE2_CM55M_APP_S/app/scenario_app/rubis_app/**/*" >> sonar-project.properties
                        '''
                        sh """
                            sonar-scanner \
                                -Dsonar.branch.name=${env.GERRIT_BRANCH} \
                                -Dsonar.sources=. \
                                -Dsonar.cfamily.build-wrapper-output=bw-output \
                                -Dsonar.host.url=http://10.10.192.46:9000/sonarqube \
                                -Dsonar.login=sqp_d577294a3506aab0676672ad1d6986613c3fcbb1
                        """
                    }
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
    }

    post {
        always {  // 无论构建成功或失败都执行
            script {
                deleteDir()

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