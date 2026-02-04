import groovy.json.JsonOutput

pipeline {
    agent any
    environment {
        BUILD_WRAPPER="~/tools/build-wrapper-linux-x86"
        SONAR_SCANNER="~/tools/sonar-scanner/bin"
        PATH="$PATH:$BUILD_WRAPPER:$SONAR_SCANNER"
    }

    stages {
        stage('CODE_SYNC') {
            options {
                retry(20)
            }
            steps {
                checkout([$class: 'GitSCM', 
                    branches: [[name: '$GERRIT_REFSPEC']], 
                    extensions: [], 
                    userRemoteConfigs: [[credentialsId: 'Jenkins', 
                        refspec: 'refs/changes/*:refs/changes/*', 
                        url: 'ssh://10.10.192.13:29418/ResearchKit-Bootloader']]])
            }
        }
        
        stage('BUILD') {
            steps {
                dir ('target/band_bootloader') {
                    sh """
                        scons -c
                        git reset --hard
                        git clean -fxd
                        build-wrapper-linux-x86-64 --out-dir ../../bw-output scons -j\$(nproc)
                    """
                }
            }
        }
        
        stage('SCAN') {
            steps {
                withSonarQubeEnv('SonarQube_46') {
                    sh 'touch sonar-project.properties'
                    sh 'echo "sonar.projectKey=ResearchKit-Bootloader" >> sonar-project.properties'
                    sh 'echo "sonar.exclusions=third_party/**/*, \
                            target/**/common/*, \
                            target/**/rtconfig.h, \
                            platform/**/*, \
                            tools/**/*, \
                            framework/base/cm_backtrace/**/*, \
                            **/*.css, \
                            **/*.js, \
                            **/*.html, \
                            **/*.xml, \
                            **/*.py, \
                            **/*.php, \
                            **/*.yaml" >> sonar-project.properties'
                    sh 'sonar-scanner -Dsonar.projectKey=ResearchKit-Bootloader   \
                            -Dsonar.projectName="ResearchKit-Bootloader" \
                            -Dsonar.sources=.   \
                            -Dsonar.cfamily.build-wrapper-output=./bw-output   \
                            -Dsonar.host.url=http://10.10.192.46:9000/sonarqube   \
                            -Dsonar.login=sqp_630000be493fff473b5413f54f4d5b6662c01ac8'
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
