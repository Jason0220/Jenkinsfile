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
                        url: 'ssh://10.10.192.13:29418/Echo-Bootloader']]])
            }
        }
        
        stage('BUILD') {
            steps {
                dir ('target/echo_bootloader') {
                sh 'scons -c'
                sh 'git clean -fxd'
                sh 'build-wrapper-linux-x86-64 --out-dir ../../bw-output scons -j64'
                }
            }
        }
        
        stage('SCAN') {
            steps {
                withSonarQubeEnv('SonarQube_46') {
                    sh '''
                        touch sonar-project.properties
                        echo "sonar.projectKey=Comma_PhoneService" >> sonar-project.properties
                        echo "sonar.exclusions=third_party/**/*, \
                        framework/ota/component/ota_zip/minizip/**/*, \
                        framework/ota/component/ota_zip/quickLZ/**/*, \
                        framework/ota/component/diff_algo/algo/**/*, \
                        target/**/common/*, \
                        target/**/rtconfig.h, \
                        platform/**/*, \
                        tools/**/*, \
                        framework/base/cm_backtrace/**/*, \
                        framework/base/ulog/backend/rtt/**/*, \
                        **/*.css, \
                        **/*.js, \
                        **/*.html, \
                        **/*.xml, \
                        **/*.py, \
                        **/*.php, \
                        **/*.yaml" >> sonar-project.properties
                        sonar-scanner -Dsonar.projectKey=Comma_PhoneService   \
                        -Dsonar.projectName=Echo-Bootloader   \
                        -Dsonar.sources=.   \
                        -Dsonar.cfamily.build-wrapper-output=./bw-output   \
                        -Dsonar.host.url=http://10.10.192.46:9000/sonarqube   \
                        -Dsonar.login=sqp_8b788d742bca8650613054833efb57b79897a2c0
                    '''
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
