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

        stage('BUILD_Rubis_secBoot') {
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
                        chmod +x ./tools/build_1700_vlog_glass_rubis.sh
                        build-wrapper-linux-x86-64 --out-dir bw-output_rubis_secboot ./tools/build_1700_vlog_glass_rubis.sh ota_build R1
                    '''
                }
            }
        }
        
        stage('SCAN_Rubis_secBoot') {
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
                            echo "sonar.projectKey=Rubis_secBootloader" >> sonar-project.properties
                            echo "sonar.inclusions=apps/lily_drivers/isp_a320/**/*,tests/ota_boot_watch/**/*,utils/ymodem/**/*" >> sonar-project.properties
                            echo -n "sonar.exclusions=" >> sonar-project.properties
                            cat sonar_exclusions.txt >> sonar-project.properties
                            echo -n "sonar.cpd.exclusions=" >> sonar-project.properties
                            cat sonar_cpd_exclusions.txt >> sonar-project.properties
                        '''
                        sh """
                            sonar-scanner \
                                -Dsonar.branch.name=${env.GERRIT_BRANCH} \
                                -Dsonar.sources=. \
                                -Dsonar.cfamily.build-wrapper-output=bw-output_rubis_secboot \
                                -Dsonar.host.url=http://10.10.192.46:9000/sonarqube \
                                -Dsonar.login=sqp_9884427202749d90f45b884d8a7e50e827536097
                        """
                    }
                }
            }
        }
        
        stage("Quality Gate_Rubis_secBoot") {
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
        
        stage('BUILD_Spinel') {
            steps {
                sh '''
                    chmod +x compile.sh
                    build-wrapper-linux-x86-64 --out-dir bw-output_spinel ./compile.sh spinelA
                '''
            }
        }
        
        stage('SCAN_Spinel') {
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
                            echo "sonar.projectKey=Spinel" >> sonar-project.properties
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
                                -Dsonar.cfamily.build-wrapper-output=bw-output_spinel \
                                -Dsonar.host.url=http://10.10.192.46:9000/sonarqube \
                                -Dsonar.login=sqp_a96942a094f2f01aa12ed6dec3602719f8d8b44f
                        """
                    }
                }
            }
        }
        
        stage("Quality Gate_Spinel") {
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

        stage('BUILD_Rubis') {
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
                        chmod +x compile.sh
                        build-wrapper-linux-x86-64 --out-dir bw-output_rubis ./compile.sh Rubis
                    '''
                }
            }
        }
        
        stage('SCAN_Rubis') {
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
                            echo "sonar.projectKey=Rubis" >> sonar-project.properties
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
                                -Dsonar.cfamily.build-wrapper-output=bw-output_rubis \
                                -Dsonar.host.url=http://10.10.192.46:9000/sonarqube \
                                -Dsonar.login=sqp_e19c613e74662f1fbc9c4afbd2c9c6a107b9b65f
                        """
                    }
                }
            }
        }
        
        stage("Quality Gate_Rubis") {
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

        stage('BUILD_Lily') {
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
                        chmod +x compile.sh
                        build-wrapper-linux-x86-64 --out-dir bw-output_lily ./compile.sh LiLy
                    '''
                }
            }
        }
        
        stage('SCAN_Lily') {
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
                            echo "sonar.projectKey=Lily" >> sonar-project.properties
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
                                -Dsonar.cfamily.build-wrapper-output=bw-output_lily \
                                -Dsonar.host.url=http://10.10.192.46:9000/sonarqube \
                                -Dsonar.login=sqp_99245546dd54d44a734f1f00ff02c044fc653f0c
                        """
                    }
                }
            }
        }
        
        stage("Quality Gate_Lily") {
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