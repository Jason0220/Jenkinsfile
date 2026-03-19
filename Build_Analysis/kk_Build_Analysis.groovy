import groovy.json.JsonOutput

pipeline {
    agent any
    environment {
        BUILD_WRAPPER = "/home/data0/jenkins/tools/build-wrapper-linux-x86"
        SONAR_SCANNER = "/home/data0/jenkins/tools/sonar-scanner/bin"
        GCC_ARM_TOOLCHAIN = "/home/data0/jenkins/tools/gcc-arm-none-eabi-10.3-2021.10/bin"
        PATH = "${PATH}:${GCC_ARM_TOOLCHAIN}:${BUILD_WRAPPER}:${SONAR_SCANNER}"
        HIMA_BRANCH_PATTERN = '^HIMA$'      // strictly matching the branch HIMA
        SAIC_BRANCH_PATTERN = '^SAIC'
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
                        url: 'ssh://10.10.192.13:29418/kk']]])
            }
        }
        
        stage('Build & Analysis') {
            steps {
                script {
                    def currentBranch = env.GERRIT_BRANCH ?: 'unknown-branch'
                    echo "currentBranch: ${currentBranch}"
                    
                    def matchHimaPattern = (currentBranch =~ "${HIMA_BRANCH_PATTERN}") as boolean
                    def matchSaicPattern = (currentBranch =~ "${SAIC_BRANCH_PATTERN}") as boolean
                    def isDualBuildingBranch = matchHimaPattern || matchSaicPattern
                    echo "Is Dual Building Branch: ${isDualBuildingBranch}"

                    if (isDualBuildingBranch) {
                        echo 'HIMA branch build & analysis process...'
                        ansiColor('xterm') {
                            dir('platform/z20k144mc') {
                                sh """
                                    make clean
                                    build-wrapper-linux-x86-64 --out-dir ../../bw-output_k144 make all -j\$(nproc)
                                """
                            }
                        }

                        withSonarQubeEnv('SonarQube_46') {
                            sh """
                                echo "sonar.projectKey=kk" > sonar-project.properties
                                echo "sonar.inclusions=kkdetect/**/*" >> sonar-project.properties
                                sonar-scanner \
                                    -Dsonar.projectKey=kk \
                                    -Dsonar.branch.name=${env.GERRIT_BRANCH} \
                                    -Dsonar.sources=. \
                                    -Dsonar.cfamily.build-wrapper-output=bw-output_k144 \
                                    -Dsonar.host.url=http://10.10.192.46:9000/sonarqube \
                                    -Dsonar.login=sqp_50315c401fac3ffd0698b9336852fe8a2ea4756c
                            """
                        }

                        timeout(20) {
                            def qg = waitForQualityGate('SonarQube_46')
                            if (qg.status != 'OK') {
                                error "Code scan failed! failure: ${qg.status}"
                            }
                        }

                        ansiColor('xterm') {
                            dir('platform/z20k118mc') {
                                sh """
                                    make clean
                                    build-wrapper-linux-x86-64 --out-dir ../../bw-output_k118 make all -j\$(nproc)
                                """
                            }
                        }

                        withSonarQubeEnv('SonarQube_46') {
                            sh """
                                echo "sonar.projectKey=kk" > sonar-project.properties
                                echo "sonar.inclusions=kkdetect/**/*" >> sonar-project.properties
                                sonar-scanner \
                                    -Dsonar.projectKey=kk \
                                    -Dsonar.branch.name=${env.GERRIT_BRANCH} \
                                    -Dsonar.sources=. \
                                    -Dsonar.cfamily.build-wrapper-output=bw-output_k118 \
                                    -Dsonar.host.url=http://10.10.192.46:9000/sonarqube \
                                    -Dsonar.login=sqp_50315c401fac3ffd0698b9336852fe8a2ea4756c
                            """
                        }

                        timeout(20) {
                            def qg = waitForQualityGate('SonarQube_46')
                            if (qg.status != 'OK') {
                                error "Code scan failed! failure: ${qg.status}"
                            }
                        }
                    } 

                    else {
                        echo 'Non-HIMA branch build & analysis process...'
                        ansiColor('xterm') {
                            dir('platform/z20k144mc') {
                                sh """
                                    make clean
                                    build-wrapper-linux-x86-64 --out-dir ../../bw-output make all -j\$(nproc)
                                """
                            }
                        }

                        withSonarQubeEnv('SonarQube_46') {
                            sh """
                                echo "sonar.projectKey=kk" > sonar-project.properties
                                echo "sonar.inclusions=kkdetect/**/*" >> sonar-project.properties
                                sonar-scanner \
                                    -Dsonar.projectKey=kk \
                                    -Dsonar.branch.name=${env.GERRIT_BRANCH} \
                                    -Dsonar.sources=. \
                                    -Dsonar.cfamily.build-wrapper-output=bw-output \
                                    -Dsonar.host.url=http://10.10.192.46:9000/sonarqube \
                                    -Dsonar.login=sqp_50315c401fac3ffd0698b9336852fe8a2ea4756c
                            """
                        }

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
    }
    
    post {
        success {
            echo 'Build successful - performing cleanup'
            sh 'rm -rf *'
        }
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