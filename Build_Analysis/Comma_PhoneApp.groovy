pipeline {
    agent any

    stages {
        stage('CODE_SYNC') {
            options {
                retry(20)
            }
            steps {
                checkout scmGit(branches: [[name: '$GERRIT_REFSPEC']], extensions: [], userRemoteConfigs: [[credentialsId: 'Jenkins', name: 'Comma_PhoneApp', refspec: 'refs/changes/*:refs/changes/*', url: 'ssh://10.10.192.13:29418/Comma_PhoneApp']])
            }
        }
        
        stage('Insert SonarQube Plugin') {
            steps {
                script {
                    // 要插入的 plugins 块
                    def pluginsBlock = """
plugins {
  id "org.sonarqube" version "3.4.0.2513"
}
"""
                    dir ('GTBluetoothTools') {
                        // 读取 build.gradle 文件内容
                        def buildGradleContent = readFile(file: 'build.gradle')
                        def lines = buildGradleContent.readLines()
                        def blockLevel = 0
                        def inBuildscriptBlock = false
                        def insertIndex = -1
            
                        // 查找 buildscript {} 块的结束位置
                        for (int i = 0; i < lines.size(); i++) {
                            def line = lines[i].trim()
                            // 忽略空行和注释行
                            if (line.isEmpty() || line.startsWith('//')) {
                                continue
                            }
            
                            if (line.contains('buildscript {')) {
                                inBuildscriptBlock = true
                                blockLevel = 1
                                continue
                            }
            
                            if (inBuildscriptBlock) {
                                for (char c : line) {
                                    if (c == '{') {
                                        blockLevel++
                                    } else if (c == '}') {
                                        blockLevel--
                                        if (blockLevel == 0) {
                                            insertIndex = i + 1
                                            break
                                        }
                                    }
                                }
                                if (insertIndex != -1) {
                                    break
                                }
                            }
                        }
            
                        if (insertIndex != -1) {
                            // 隔一个空行插入 plugins 块
                            lines.add(insertIndex, "") 
                            lines.add(insertIndex + 1, pluginsBlock)
                            def newBuildGradleContent = lines.join('\n')
                            // 覆盖原文件
                            writeFile file: 'build.gradle', text: newBuildGradleContent
                            echo "SonarQube plugin added to build.gradle"
                        } else {
                            echo "buildscript {} block not found in build.gradle"
                        }
                    }
                }
            }
        }
        
        stage('SCAN_GTB') {
            steps {
                script {    
                    withSonarQubeEnv('SonarQube_46') {
                        dir ('GTBluetoothTools') {
                            sh 'chmod a+x *'
                            sh 'echo "sdk.dir=/home/data0/jenkins/software/Android/Sdk" > local.properties'
                            sh """
                                export JAVA_HOME=/usr/lib/jvm/openjdk-11 && ./gradlew sonarqube \
                                -Dsonar.projectKey=Comma_PhoneApp \
                                -Dsonar.branch.name=${env.GERRIT_BRANCH}   \
                                -Dsonar.projectName=Comma_PhoneApp \
                                -Dsonar.host.url=http://10.10.192.46:9000/sonarqube \
                                -Dsonar.login=sqp_0f0abc891020644120fe6539bada159296a4a247
                            """
                        }    
                    }
                }
            }
        }
        
        stage("Quality Gate_GTB") {
            steps {
                script {
                    timeout(120) {
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
