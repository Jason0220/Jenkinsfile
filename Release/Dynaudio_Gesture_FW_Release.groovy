pipeline {
    agent { label 'jenkins-node-10.10.192.18' }
    
    environment {
        TOOLCHAIN = "/home/jenkins/tools/arm-gnu-toolchain-14.2.rel1-x86_64-arm-none-eabi/bin"
        ARTIFACTORY = "http://10.10.192.15:8081/artifactory/Dynaudio_Gesture/Release"
        ARTIFACTORY_CREDS = credentials('artifactory-jenkins')
        FWMS = "https://alpha.goertek.com:8883/api/firmware"
    }
    
    options {
        buildDiscarder(logRotator(numToKeepStr: '100'))
        timeout(time: 2, unit: 'HOURS')
    }

    stages {
        stage('ENV_INIT') {
            steps {
                script {
                    env.VER_NAME = params.APP_VERSION
                    env.VER_CODE = params.APP_VERSION.replaceAll('\\.', '')
                    echo "VerName: ${env.VER_NAME}"
                    echo "VerCode: ${env.VER_CODE}"
                    
                    echo "Output: ${env.OUTPUT}"
                    env.PATH = "${env.TOOLCHAIN}:${env.PATH}"
                    sh 'which arm-none-eabi-gcc'
                }
            }
        }

        stage('CODE_SYNC') {
            options {
                retry(3)
                timeout(time: 10, unit: 'MINUTES')
            }
            steps {
                checkout scmGit(
                    branches: [[name: "*/master"]], 
                    extensions: [[$class: 'CleanBeforeCheckout']], 
                    userRemoteConfigs: [[
                        credentialsId: 'Jenkins', 
                        url: 'ssh://10.10.192.13:29418/Dynaudio_Gesture'
                    ]]
                )
            }
        }

        stage('BUILD') {
            options {
                timeout(time: 30, unit: 'MINUTES')
            }
            steps {
                script {  
                    ansiColor('xterm') {
                        dir('cmodel/infer') {
                            sh """
                                sed -i "s|^TOOLCHAIN_PATH = .*|TOOLCHAIN_PATH = ${TOOLCHAIN}|" Makefile
                                make clean
                                make all -j\$(nproc)
                            """
                        }
                    }
                    env.TIMESTAMP = new java.text.SimpleDateFormat("yyyyMMddHHmm").format(new Date())
                    echo "Timestamp: ${env.TIMESTAMP}"
                }
            }
        }
        
        stage('Firmware_Packaging') {
            steps {
                dir('cmodel/infer/export') {
                    script {
                        // 在 Groovy 中拼接完整文件名
                        def zipFileName = "Dynaudio_Gesture_${env.VER_NAME}.${env.TIMESTAMP}.zip"
                        zip zipFile: "${zipFileName}", dir: '', glob: 'include/**/*,lib/**/*'
                        sh """
                            # 直接使用 Groovy 生成的文件名
                            ZIP_FILE="${zipFileName}"
                            md5sum "\${ZIP_FILE}" > "\${ZIP_FILE}.md5"
                        """
                        
                        archiveArtifacts artifacts: "${zipFileName}", fingerprint: true
                        archiveArtifacts artifacts: "${zipFileName}.md5", fingerprint: true
                    }
                }
            }
        }

        stage('BACKUP_to_Artifactory') {
            steps {
                dir("cmodel/infer/export") {
                    sh """
                        ZIP_FILE=\$(ls Dynaudio_Gesture_*.zip | head -n 1)
                        
                        if [ -z "\${ZIP_FILE}" ]; then
                            echo "Error: No Dynaudio_Gesture_*.zip file found"
                            exit 1
                        fi
                                                
                        ARTIFACT_PATH="${ARTIFACTORY}/Dynaudio_Gesture_${env.VER_NAME}.${env.TIMESTAMP}"
                        echo "Uploading to: \${ARTIFACTORY}"
                        
                        # 上传ZIP文件和MD5文件
                        if [ -f "\${ZIP_FILE}" ] && [ -f "\${ZIP_FILE}.md5" ]; then
                            curl -u "${ARTIFACTORY_CREDS}" -T "\${ZIP_FILE}" "\${ARTIFACT_PATH}/"
                            curl -u "${ARTIFACTORY_CREDS}" -T "\${ZIP_FILE}.md5" "\${ARTIFACT_PATH}/"
                        else
                            echo "Error: Package file or MD5 file not found"
                            exit 1
                        fi
                    """
                }
            }
        }

        stage('BACKUP_to_FTP') {
            steps {
                script {
                    def zipFiles = sh(script: "ls cmodel/infer/export/Dynaudio_Gesture_*.zip || true", returnStdout: true).trim().split('\n')

                    if (zipFiles.length == 0) {
                        error "No Dynaudio_Gesture_*.zip files found under cmodel/infer/export"
                    }
                    
                    def ftpPath = "2026 Dynaudio_Gesture/Release/Dynaudio_Gesture_${env.VER_NAME}.${env.TIMESTAMP}/"
                    
                    ftpPublisher(
                        publishers: [
                            [
                                configName: '10.10.192.11',
                                transfers: [
                                    [
                                        sourceFiles: "cmodel/infer/export/Dynaudio_Gesture_${env.VER_NAME}.${env.TIMESTAMP}.zip",
                                        remoteDirectory: ftpPath,
                                        removePrefix: "cmodel/infer/export/",
                                        flatten: false
                                    ],
                                    [
                                        sourceFiles: "cmodel/infer/export/Dynaudio_Gesture_${env.VER_NAME}.${env.TIMESTAMP}.zip.md5",
                                        remoteDirectory: ftpPath,
                                        removePrefix: "cmodel/infer/export/",
                                        flatten: false
                                    ]
                                ],
                                verbose: true
                            ]
                        ]
                    )
                }
            }
        }

        stage('UPLOAD_to_FW-MS') {
            steps {
                script {
                    def zipFiles = sh(script: "ls cmodel/infer/export/Dynaudio_Gesture_*.zip || true", returnStdout: true).trim().split('\n')
                    
                    if (zipFiles.length == 0) {
                        error "No Dynaudio_Gesture_*.zip files found under cmodel/infer/export"
                    }
                    
                    def zipFilePath = zipFiles[0]
                    // Extract file name like: kk_dev_1.00.00.202505231230.zip
                    def zipFileName = zipFilePath.tokenize('/')[-1]
                    // Extract file name like: kk_dev_1.00.00.202505231230
                    def releaseName = zipFileName.substring(0, zipFileName.lastIndexOf('.'))
                    
                    def md5Value = readFile("${zipFilePath}.md5").trim()
                    
                    echo "Firmware MD5: ${md5Value}"
                    echo "Firmware Version: ${params.APP_VERSION}"
                    
                    def response = sh(
                        script: """
                            curl -s -w "\\n%{http_code}" -X POST -F "file=@${zipFilePath}" \\
                            -F "verCode=${env.VER_CODE}" \\
                            -F "verName=${params.APP_VERSION}" \\
                            -F "deviceTypeId=25" \\
                            -F "extType=.zip" \\
                            -F "md5=${md5Value}" \\
                            "${env.FWMS}/upload"
                        """,
                        returnStdout: true
                    ).trim()
                    
                    def responseParts = response.tokenize('\n')
                    def responseBody = responseParts[0]
                    def responseCode = responseParts[1]
                    
                    echo "API Response: ${responseBody}"
                    echo "HTTP Status Code: ${responseCode}"
                    
                    if (responseCode != "200") {
                        error "Upload failed: ${responseBody}"
                    }
                    
                    def firmwareUrl = "${env.FWMS}/download?md5=${md5Value}"
                    
                    emailext(
                        subject: "${releaseName} Firmware Release",
                        body: """
                            Dear all, <br><br>
                            We are pleased to announce the release of firmware ${releaseName}. <br>
                            Download Link: ${firmwareUrl} <br>
                            MD5: ${md5Value} <br>
                            Feedback to: jeremy.li@goertek.com
                        """,
                        to: "jeremy.li@goertek.com"
                    )
                    
                    def feishuGroups = [
                        [name: 'FEISHU_SWBJ', webhookUrl: env.FEISHU_SWBJ]
                    ]

                    feishuGroups.each { group ->
                        try {
                            def feishuMessage = """
                            {
                                "msg_type": "text",
                                "content": {
                                    "text": "Dynaudio_Gesture Firmware Release ${releaseName}. Download: ${firmwareUrl}\\nMD5: ${md5Value}"
                                }
                            }
                            """
                            
                            httpRequest(
                                url: group.webhookUrl,
                                httpMode: 'POST',
                                contentType: 'APPLICATION_JSON',
                                requestBody: feishuMessage,
                                validResponseCodes: '200'
                            )
                            
                            echo "Feishu notification sent to ${group.name} successfully"
                        } catch (Exception e) {
                            echo "Feishu notification failed to ${group.name}: ${e.message}"
                        }
                    }
                }
            }
        }
    }
}