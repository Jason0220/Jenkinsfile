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
        stage('CODE_SYNC') {
            options {
                retry(20)
            }
            steps {
                checkout scmGit(branches: [[name: '$GERRIT_REFSPEC']], 
                    extensions: [], 
                    userRemoteConfigs: [[credentialsId: 'Jenkins', 
                        refspec: 'refs/changes/*:refs/changes/*', 
                        url: 'ssh://10.10.192.13:29418/BES2800BP_GLASS']])
            }
        }

        stage('BUILD_SpinelB') {
            steps {
                sh '''
                    chmod +x ./tools/env.sh ./tools/build_1700_vlog_glass.sh
                    . ./tools/env.sh
                    build-wrapper-linux-x86-64 --out-dir bw-output_spinel-B ./tools/build_1700_vlog_glass.sh use_lib R1
                '''
            }
        }
        
        stage('SCAN_SpinelB') {
            steps {
                withSonarQubeEnv('SonarQube_46') {
                    script {
                        def exclusionFile = "sonar_exclusions.txt"
                        def persistentPath = "/home/jenkins/sonar-files/Spinel/${exclusionFile}"
                        sh "if [ ! -f ${exclusionFile} ]; then cp ${persistentPath} ./; fi"    // if not exist then copy
                        sh 'cat sonar_exclusions.txt'
                        def exclusionCpdFile = "sonar_cpd_exclusions.txt"
                        def persistentCpdPath = "/home/jenkins/sonar-files/Spinel/${exclusionCpdFile}"
                        sh "if [ ! -f ${exclusionCpdFile} ]; then cp ${persistentCpdPath} ./; fi"

                        sh '''
                            > sonar-project.properties
                            echo "sonar.projectKey=Spinel-B" >> sonar-project.properties
                            echo "sonar.inclusions=apps/**/*,isp/**/*" >> sonar-project.properties
                            echo -n "sonar.exclusions=" >> sonar-project.properties
                            cat sonar_exclusions.txt >> sonar-project.properties
                            echo -n "sonar.cpd.exclusions=" >> sonar-project.properties
                            cat sonar_cpd_exclusions.txt >> sonar-project.properties
                        '''
                        sh """
                            sonar-scanner \
                                -Dsonar.branch.name=${env.GERRIT_BRANCH} \
                                -Dsonar.sources=. \
                                -Dsonar.cfamily.build-wrapper-output=bw-output_spinel-B \
                                -Dsonar.host.url=http://10.10.192.46:9000/sonarqube \
                                -Dsonar.login=sqp_8e63f35380f1e29d298b581615a80c02916b587f
                        """
                    }
                }
            }
        }
        
        stage("Quality Gate_SpinelB") {
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