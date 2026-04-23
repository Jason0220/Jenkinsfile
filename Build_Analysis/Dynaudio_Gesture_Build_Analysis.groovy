import groovy.json.JsonOutput

pipeline {
    agent { label 'jenkins-node-10.10.192.18' }
    environment {
        BUILD_WRAPPER = "/home/jenkins/tools/build-wrapper-linux-x86-6.70.1"
        SONAR_SCANNER = "/home/jenkins/tools/sonar-scanner-8.0.1/bin"
        GCC_ARM_TOOLCHAIN = "/home/jenkins/tools/arm-gnu-toolchain-14.2.rel1-x86_64-arm-none-eabi/bin"
        PATH = "${GCC_ARM_TOOLCHAIN}:${BUILD_WRAPPER}:${SONAR_SCANNER}:${PATH}"
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
                        url: 'ssh://10.10.192.13:29418/Dynaudio_Gesture']]])
            }
        }
        
        stage('Build & Analysis') {
            steps {
                script {
                    def SONAR_TOKEN = 'sqa_e457f1860cda783f4ab2ce34f24b8ee39e5c33ea'
                    def branchName = env.GERRIT_BRANCH.replaceAll("refs/heads/", "")
                    ansiColor('xterm') {
                        dir('cmodel/infer') {
                            sh '''
                                set -e
                                sed -i "s|^TOOLCHAIN_PATH = .*|TOOLCHAIN_PATH = ${GCC_ARM_TOOLCHAIN}|" Makefile
                                make clean
                                build-wrapper-linux-x86-64 --out-dir ../../bw-output make all -j$(nproc)
                            '''
                        }
                    }

                    withSonarQubeEnv('SonarQube_18') {
                        sh """
                            set -e
                            echo "sonar.projectKey=Dynaudio_Gesture" > sonar-project.properties
                            echo "sonar.inclusions=cmodel/infer/**/*" >> sonar-project.properties
                            echo "sonar.exclusions=cmodel/infer/export/**/*,**/*.a,**/*.o" >> sonar-project.properties
                            sonar-scanner \
                                -Dsonar.projectKey=Dynaudio_Gesture \
                                -Dsonar.branch.name=${branchName} \
                                -Dsonar.sources=. \
                                -Dsonar.python.version=3.12 \
                                -Dsonar.cfamily.compile-commands=bw-output/compile_commands.json
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
                                "http://10.10.192.18:9000/api/qualitygates/project_status?projectKey=Dynaudio_Gesture&branch=${branchName}"
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
                            Dashboard: http://10.10.192.18:9000/dashboard?id=Dynaudio_Gesture&branch=${branchName}
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
    }
    
    post {
        success {
            echo "✅ Build successful - performing cleanup"
            cleanWs()
        }

        always {
            script {
                // 1. 判断 Sonar 状态
                if (env.SONAR_STATUS != 'OK') {
                    echo "❌ SonarQube Quality Gate Failed"
                    echo "🔗 http://10.10.192.18:9000/dashboard?id=Dynaudio_Gesture"
                    error("Quality Gate Failed")
                } else {
                    echo "✅ SonarQube Quality Gate Passed"
                }

                // 2. 获取日志
                def consoleLog = currentBuild.rawBuild.getLog(50)
                def logStr = consoleLog.join("\n").replace('"', '\\"').replace("'", "'\\''")

                // 3. Gerrit 信息
                def gerritServer = "http://10.10.192.13:8082/gerrit"
                def changeId = env.GERRIT_CHANGE_NUMBER
                def revisionId = env.GERRIT_PATCHSET_NUMBER
                def gerritUser = "jenkins"
                def gerritPassword = "L4Yjc6ypq0kLEhdJ9dl/1wUCgC2dWv9WI7Br2mIUdw"

                // 4. ✅ 【完全修复】shell 安全版 curl
                sh """
                    curl -X POST \\
                    -H "Content-Type: application/json" \\
                    -u "${gerritUser}:${gerritPassword}" \\
                    -d '{"message":"Jenkins 构建日志:\\n```\\n${logStr}\\n```"}' \\
                    "${gerritServer}/a/changes/${changeId}/revisions/${revisionId}/review"
                """
            }
        }
    }
}